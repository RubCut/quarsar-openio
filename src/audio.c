/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_openio.h"
#include <arpa/inet.h>
#include <errno.h>
#include <inttypes.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define PCM_TIMEOUT_MS 30000
#define HTTP_TIMEOUT_MS 5000

static void close_http(qio_http *http)
{
    qio_close_fd(&http->fd);
    http->request_used = 0;
    http->header_used = 0;
    http->streaming = false;
}

static int listen_loopback(int port)
{
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;
    int yes = 1;
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons((uint16_t)port);
    if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes)) != 0 ||
        bind(fd, (struct sockaddr *)&address, sizeof(address)) != 0 ||
        listen(fd, 4) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

int qio_audio_init(qio_audio *audio, const qio_options *options, int64_t now)
{
    (void)now;
    audio->pcm_listen_fd = audio->http_listen_fd = audio->pcm_fd = -1;
    audio->mirror_fd = -1;
    audio->last_sound_ms = 0;
    for (size_t i = 0; i < QIO_HTTP_CLIENTS; ++i) audio->http[i].fd = -1;
    if (options->no_audio) return 0;
    audio->pcm_port = options->audio_port;
    audio->http_port = options->http_port;
    audio->pcm_listen_fd = listen_loopback(options->audio_port);
    if (audio->pcm_listen_fd < 0) {
        qio_log("PCM bind 127.0.0.1:%d: %s", options->audio_port, strerror(errno));
        return -1;
    }
    audio->http_listen_fd = listen_loopback(options->http_port);
    if (audio->http_listen_fd < 0) {
        qio_log("HTTP bind 127.0.0.1:%d: %s", options->http_port, strerror(errno));
        qio_audio_close(audio);
        return -1;
    }
    qio_log("audio: PCM 127.0.0.1:%d -> bounded %u-byte ring -> HTTP 127.0.0.1:%d; "
            "S16LE/48000/stereo", options->audio_port, QIO_AUDIO_CAPACITY, options->http_port);
    return 0;
}

static bool pcm_has_sound(const uint8_t *data, size_t length)
{
    for (size_t i = 0; i + 1 < length; i += 2) {
        int16_t sample = (int16_t)((uint16_t)data[i] | ((uint16_t)data[i + 1] << 8));
        if (sample > 64 || sample < -64) return true;
    }
    return false;
}

static void mirror_to_plugin(qio_audio *audio, const uint8_t *data, size_t length)
{
    if (audio->mirror_fd < 0 || length == 0) return;
    /* length <= 4096 == PIPE_BUF: nonblocking write is atomic or EAGAIN. */
    ssize_t count = write(audio->mirror_fd, data, length);
    if (count < 0 && errno == EPIPE) qio_close_fd(&audio->mirror_fd);
}

static void ring_discard(qio_audio *audio, size_t count)
{
    audio->head = (audio->head + count) % QIO_AUDIO_CAPACITY;
    audio->used -= count;
    audio->dropped_bytes += (uint64_t)count;
}

static void ring_push(qio_audio *audio, const uint8_t *data, size_t length)
{
    length -= length % QIO_AUDIO_FRAME_BYTES;
    if (length > QIO_AUDIO_CAPACITY) {
        size_t excess = length - QIO_AUDIO_CAPACITY;
        data += excess;
        length = QIO_AUDIO_CAPACITY;
        audio->dropped_bytes += (uint64_t)excess;
    }
    size_t free_bytes = QIO_AUDIO_CAPACITY - audio->used;
    if (length > free_bytes) {
        size_t drop = length - free_bytes;
        drop += (QIO_AUDIO_FRAME_BYTES - drop % QIO_AUDIO_FRAME_BYTES) % QIO_AUDIO_FRAME_BYTES;
        ring_discard(audio, drop);
    }
    size_t tail = (audio->head + audio->used) % QIO_AUDIO_CAPACITY;
    size_t first = length < QIO_AUDIO_CAPACITY - tail ? length : QIO_AUDIO_CAPACITY - tail;
    memcpy(audio->ring + tail, data, first);
    if (first < length) memcpy(audio->ring, data + first, length - first);
    audio->used += length;
}

static void http_error(qio_http *http, const char *status, const char *message)
{
    int n = snprintf((char *)http->header, sizeof(http->header),
                     "HTTP/1.1 %s\r\nContent-Type: text/plain\r\n"
                     "Content-Length: %zu\r\nConnection: close\r\n\r\n%s",
                     status, strlen(message), message);
    if (n < 0 || (size_t)n >= sizeof(http->header)) { close_http(http); return; }
    http->header_used = (size_t)n;
    http->streaming = false;
    http->close_after_send = true;
}

static void prepare_wav(uint8_t *header)
{
    static const uint8_t prefix[12] = {'R','I','F','F',0xff,0xff,0xff,0xff,'W','A','V','E'};
    static const uint8_t fmt[24] = {
        'f','m','t',' ',16,0,0,0,1,0,2,0,
        0x80,0xbb,0,0,0x00,0xee,0x02,0,4,0,16,0
    };
    memcpy(header, prefix, sizeof(prefix));
    memcpy(header + 12, fmt, sizeof(fmt));
    memcpy(header + 36, "data", 4);
    memset(header + 40, 0xff, 4);
}

static void http_request(qio_audio *audio, qio_http *http)
{
    uint8_t *end = memmem(http->request, http->request_used, "\r\n\r\n", 4);
    if (end == NULL) {
        if (http->request_used == sizeof(http->request))
            http_error(http, "431 Request Header Fields Too Large", "headers too large\n");
        return;
    }
    /* This local endpoint accepts exactly one request, with no body/pipelining. */
    if ((size_t)(end + 4 - http->request) != http->request_used) {
        http_error(http, "400 Bad Request", "unexpected request body\n");
        return;
    }
    char line[256];
    uint8_t *line_end = memchr(http->request, '\n', (size_t)(end - http->request));
    if (line_end == NULL) { http_error(http, "400 Bad Request", "bad request line\n"); return; }
    size_t line_length = (size_t)(line_end - http->request);
    if (line_length > 0 && http->request[line_length - 1] == '\r') --line_length;
    if (line_length >= sizeof(line)) {
        http_error(http, "400 Bad Request", "bad request line\n");
        return;
    }
    memcpy(line, http->request, line_length);
    line[line_length] = '\0';
    char *save = NULL;
    char *method = strtok_r(line, " ", &save);
    char *path = strtok_r(NULL, " ", &save);
    char *version = strtok_r(NULL, " ", &save);
    if (method == NULL || path == NULL || version == NULL || strtok_r(NULL, " ", &save) != NULL ||
        (strcmp(version, "HTTP/1.1") != 0 && strcmp(version, "HTTP/1.0") != 0)) {
        http_error(http, "400 Bad Request", "bad request line\n");
        return;
    }
    if (strcmp(method, "GET") != 0) {
        http_error(http, "405 Method Not Allowed", "GET only\n");
        return;
    }
    http->request_used = 0;
    http->deadline_ms = 0;
    int n;
    if (strcmp(path, "/health") == 0) {
        char body[512];
        if (qio_audio_status(audio, body, sizeof(body)) < 0) {
            http_error(http, "500 Internal Server Error", "status error\n");
            return;
        }
        n = snprintf((char *)http->header, sizeof(http->header),
                     "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                     "Content-Length: %zu\r\nConnection: close\r\n\r\n%s", strlen(body), body);
        http->close_after_send = true;
    } else if (strcmp(path, "/audio.wav") == 0 || strcmp(path, "/audio.pcm") == 0) {
        for (size_t i = 0; i < QIO_HTTP_CLIENTS; ++i) {
            if (&audio->http[i] != http && audio->http[i].streaming) {
                http_error(http, "503 Service Unavailable", "one audio consumer allowed\n");
                return;
            }
        }
        bool wav = strcmp(path, "/audio.wav") == 0;
        n = snprintf((char *)http->header, sizeof(http->header) - 44,
                     "HTTP/1.1 200 OK\r\nContent-Type: %s\r\nConnection: close\r\n"
                     "Cache-Control: no-store\r\nX-Quasar-PCM: S16LE,48000,2\r\n\r\n",
                     wav ? "audio/wav" : "application/octet-stream");
        if (n > 0 && (size_t)n < sizeof(http->header) - 44) {
            if (wav) { prepare_wav(http->header + n); n += 44; }
            http->streaming = true;
            http->close_after_send = false;
            http->deadline_ms = qio_now_ms() + HTTP_TIMEOUT_MS;
        } else n = -1;
    } else {
        http_error(http, "404 Not Found", "use /audio.wav, /audio.pcm or /health\n");
        return;
    }
    if (n < 0 || (size_t)n >= sizeof(http->header)) {
        http_error(http, "500 Internal Server Error", "response too large\n");
        return;
    }
    http->header_used = (size_t)n;
}

static void http_send(qio_audio *audio, qio_http *http)
{
    if (http->fd < 0) return;
    while (http->header_used != 0) {
        ssize_t count = send(http->fd, http->header, http->header_used, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) {
            if (errno != EAGAIN) close_http(http);
            return;
        }
        if (count == 0) { close_http(http); return; }
        size_t sent = (size_t)count;
        http->header_used -= sent;
        memmove(http->header, http->header + sent, http->header_used);
    }
    if (!http->streaming) {
        if (http->close_after_send) close_http(http);
        return;
    }
    http->deadline_ms = 0;
    while (audio->used != 0) {
        size_t contiguous = QIO_AUDIO_CAPACITY - audio->head;
        size_t amount = audio->used < contiguous ? audio->used : contiguous;
        if (amount > 4096) amount = 4096;
        ssize_t count = send(http->fd, audio->ring + audio->head, amount, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) {
            if (errno != EAGAIN) close_http(http);
            return;
        }
        if (count == 0) { close_http(http); return; }
        size_t sent = (size_t)count;
        audio->head = (audio->head + sent) % QIO_AUDIO_CAPACITY;
        audio->used -= sent;
    }
    if (audio->producer_eof) close_http(http);
}

static void pump(qio_audio *audio)
{
    for (size_t i = 0; i < QIO_HTTP_CLIENTS; ++i)
        if (audio->http[i].fd >= 0) http_send(audio, &audio->http[i]);
}

static void accept_http(qio_audio *audio, int64_t now)
{
    for (size_t attempt = 0; attempt < QIO_HTTP_CLIENTS; ++attempt) {
        int fd = accept4(audio->http_listen_fd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
        if (fd < 0 && errno == EINTR) continue;
        if (fd < 0) return;
        size_t slot;
        for (slot = 0; slot < QIO_HTTP_CLIENTS && audio->http[slot].fd >= 0; ++slot) {}
        if (slot == QIO_HTTP_CLIENTS) { close(fd); return; }
        qio_http *http = &audio->http[slot];
        memset(http, 0, sizeof(*http));
        http->fd = fd;
        http->deadline_ms = now + HTTP_TIMEOUT_MS;
    }
}

static void close_producer(qio_audio *audio)
{
    qio_close_fd(&audio->pcm_fd);
    audio->carry_used = 0;
    audio->producer_eof = true;
    audio->pcm_deadline_ms = 0;
}

static void accept_pcm(qio_audio *audio, int64_t now)
{
    int fd = accept4(audio->pcm_listen_fd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (fd < 0) return;
    if (audio->pcm_fd >= 0) {
        ++audio->rejected_producers;
        close(fd);
        return;
    }
    audio->pcm_fd = fd;
    audio->producer_eof = false;
    audio->carry_used = 0;
    audio->pcm_deadline_ms = now + PCM_TIMEOUT_MS;
    qio_log("PCM producer connected");
}

static void read_pcm(qio_audio *audio, int64_t now)
{
    uint8_t bytes[4096];
    ssize_t count = recv(audio->pcm_fd, bytes, sizeof(bytes), 0);
    if (count < 0 && (errno == EAGAIN || errno == EINTR)) return;
    if (count <= 0) {
        if (count < 0) qio_log("PCM read: %s", strerror(errno));
        close_producer(audio);
        pump(audio);
        return;
    }
    audio->accepted_bytes += (uint64_t)count;
    audio->pcm_deadline_ms = now + PCM_TIMEOUT_MS;
    uint8_t framed[sizeof(bytes) + QIO_AUDIO_FRAME_BYTES - 1];
    memcpy(framed, audio->carry, audio->carry_used);
    memcpy(framed + audio->carry_used, bytes, (size_t)count);
    size_t total = audio->carry_used + (size_t)count;
    size_t complete = total - total % QIO_AUDIO_FRAME_BYTES;
    ring_push(audio, framed, complete);
    if (pcm_has_sound(framed, complete)) audio->last_sound_ms = now;
    mirror_to_plugin(audio, framed, complete);
    audio->carry_used = total - complete;
    memcpy(audio->carry, framed + complete, audio->carry_used);
    pump(audio);
}

static void http_event(qio_audio *audio, qio_http *http, short revents, int64_t now)
{
    if ((revents & POLLNVAL) != 0) { close_http(http); return; }
    if ((revents & POLLIN) != 0) {
        if (http->streaming && http->request_used == 0) {
            uint8_t extra[64];
            ssize_t count = recv(http->fd, extra, sizeof(extra), 0);
            if (count == 0 || (count < 0 && errno != EAGAIN && errno != EINTR)) {
                close_http(http);
                return;
            }
            if (count > 0) { close_http(http); return; } /* no pipelining/upload */
        } else {
            ssize_t count = recv(http->fd, http->request + http->request_used,
                                 sizeof(http->request) - http->request_used, 0);
            if (count == 0 || (count < 0 && errno != EAGAIN && errno != EINTR)) {
                close_http(http);
                return;
            }
            if (count > 0) {
                http->request_used += (size_t)count;
                http->deadline_ms = now + HTTP_TIMEOUT_MS;
                http_request(audio, http);
            }
        }
    }
    if (http->fd < 0) return;
    if ((revents & (POLLERR | POLLHUP)) != 0 && http->header_used == 0) {
        close_http(http);
        return;
    }
    if (http->header_used != 0) http_send(audio, http);
}

size_t qio_audio_pollfds(qio_audio *audio, struct pollfd *fds, size_t capacity)
{
    size_t used = 0;
    int listeners[2] = {audio->pcm_listen_fd, audio->http_listen_fd};
    for (size_t i = 0; i < 2; ++i) {
        if (listeners[i] < 0 || used >= capacity) continue;
        fds[used].fd = listeners[i];
        fds[used++].events = POLLIN;
    }
    if (audio->pcm_fd >= 0 && used < capacity) {
        fds[used].fd = audio->pcm_fd;
        fds[used++].events = POLLIN;
    }
    for (size_t i = 0; i < QIO_HTTP_CLIENTS && used < capacity; ++i) {
        qio_http *http = &audio->http[i];
        if (http->fd < 0) continue;
        fds[used].fd = http->fd;
        fds[used].events = POLLIN;
        if (http->header_used != 0 || (http->streaming && audio->used != 0))
            fds[used].events |= POLLOUT;
        ++used;
    }
    return used;
}

void qio_audio_handle(qio_audio *audio, int fd, short revents, int64_t now)
{
    if (fd == audio->pcm_listen_fd && fd >= 0) { accept_pcm(audio, now); return; }
    if (fd == audio->http_listen_fd && fd >= 0) { accept_http(audio, now); return; }
    if (fd == audio->pcm_fd && fd >= 0) { read_pcm(audio, now); return; }
    for (size_t i = 0; i < QIO_HTTP_CLIENTS; ++i) {
        if (audio->http[i].fd == fd && fd >= 0) {
            http_event(audio, &audio->http[i], revents, now);
            return;
        }
    }
}

void qio_audio_tick(qio_audio *audio, int64_t now)
{
    if (audio->pcm_fd >= 0 && now >= audio->pcm_deadline_ms) {
        qio_log("PCM producer idle timeout");
        close_producer(audio);
    }
    for (size_t i = 0; i < QIO_HTTP_CLIENTS; ++i) {
        qio_http *http = &audio->http[i];
        if (http->fd < 0) continue;
        if (http->deadline_ms != 0 && now >= http->deadline_ms) {
            close_http(http);
            continue;
        }
        if (http->streaming && http->header_used == 0 && audio->used == 0 && audio->producer_eof)
            close_http(http);
    }
}

void qio_audio_close(qio_audio *audio)
{
    qio_close_fd(&audio->pcm_fd);
    qio_close_fd(&audio->pcm_listen_fd);
    qio_close_fd(&audio->http_listen_fd);
    for (size_t i = 0; i < QIO_HTTP_CLIENTS; ++i) close_http(&audio->http[i]);
    audio->used = 0;
}

int qio_audio_status(qio_audio *audio, char *buffer, size_t capacity)
{
    size_t consumers = 0;
    for (size_t i = 0; i < QIO_HTTP_CLIENTS; ++i)
        if (audio->http[i].streaming) ++consumers;
    int n = snprintf(buffer, capacity,
                     "{\"enabled\":%s,\"pcm_port\":%d,\"http_port\":%d,"
                     "\"format\":\"S16LE\",\"rate\":%d,\"channels\":%d,"
                     "\"producer_connected\":%s,\"consumers\":%zu,"
                     "\"buffered_bytes\":%zu,\"capacity_bytes\":%u,"
                     "\"accepted_bytes\":%" PRIu64 ",\"dropped_bytes\":%" PRIu64 ","
                     "\"rejected_producers\":%" PRIu64 "}",
                     audio->pcm_listen_fd >= 0 ? "true" : "false", audio->pcm_port,
                     audio->http_port, QIO_AUDIO_RATE, QIO_AUDIO_CHANNELS,
                     audio->pcm_fd >= 0 ? "true" : "false", consumers, audio->used,
                     QIO_AUDIO_CAPACITY, audio->accepted_bytes, audio->dropped_bytes,
                     audio->rejected_producers);
    return n < 0 || (size_t)n >= capacity ? -1 : n;
}
