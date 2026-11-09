/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_openio.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int qio_lock_acquire(qio_app *app)
{
    int fd = open(QIO_LOCK_PATH, O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) { qio_log("lock open: %s", strerror(errno)); return -1; }
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != geteuid() ||
        st.st_nlink != 1 || (st.st_mode & 0022) != 0) {
        qio_log("unsafe lock file %s; refusing to run", QIO_LOCK_PATH);
        close(fd);
        return -1;
    }
    if (flock(fd, LOCK_EX | LOCK_NB) != 0) {
        qio_log("instance lock busy or unavailable: %s", strerror(errno));
        close(fd);
        return -1;
    }
    char pid[32];
    int n = snprintf(pid, sizeof(pid), "%ld\n", (long)getpid());
    if (n < 0 || (size_t)n >= sizeof(pid) || ftruncate(fd, 0) != 0 ||
        qio_write_all(fd, pid, (size_t)n) != 0) {
        close(fd);
        return -1;
    }
    app->lock_fd = fd;
    return 0; /* Never unlink the lock: that would break mutual exclusion. */
}

void qio_lock_release(qio_app *app) { qio_close_fd(&app->lock_fd); }

static void close_client(qio_app *app, size_t index)
{
    qio_close_fd(&app->clients[index].fd);
    app->clients[index].in_used = 0;
    app->clients[index].out_used = 0;
}

int qio_ipc_init(qio_app *app)
{
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    if (strlen(app->options.socket_path) >= sizeof(address.sun_path)) {
        qio_log("Unix socket path is too long");
        return -1;
    }
    address.sun_family = AF_UNIX;
    strcpy(address.sun_path, app->options.socket_path);
    for (size_t i = 0; i < QIO_MAX_CLIENTS; ++i) app->clients[i].fd = -1;
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) { qio_log("IPC socket: %s", strerror(errno)); return -1; }
    struct stat st;
    if (lstat(address.sun_path, &st) == 0) {
        if (!S_ISSOCK(st.st_mode) || st.st_uid != geteuid() || (st.st_mode & 0002) != 0) {
            qio_log("refusing to replace non-socket, foreign or unsafe %s", address.sun_path);
            close(fd);
            return -1;
        }
        int probe = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        if (probe < 0) { close(fd); return -1; }
        int result = connect(probe, (struct sockaddr *)&address, sizeof(address));
        int error = errno;
        close(probe);
        if (result == 0 || error == EINPROGRESS || error == EAGAIN) {
            qio_log("another service is listening on %s", address.sun_path);
            close(fd);
            return -1;
        }
        if (error != ECONNREFUSED) {
            qio_log("cannot probe stale socket: %s", strerror(error));
            close(fd);
            return -1;
        }
        if (unlink(address.sun_path) != 0) { close(fd); return -1; }
    } else if (errno != ENOENT) {
        qio_log("socket lstat: %s", strerror(errno));
        close(fd);
        return -1;
    }
    mode_t previous = umask(0077);
    int result = bind(fd, (struct sockaddr *)&address, sizeof(address));
    int saved = errno;
    umask(previous);
    if (result != 0) { qio_log("IPC bind: %s", strerror(saved)); close(fd); return -1; }
    app->socket_fd = fd;
    /* fstat(socket_fd) reports the socket inode, not the bound filesystem node. */
    app->socket_created = lstat(address.sun_path, &app->socket_identity) == 0;
    if (listen(fd, QIO_MAX_CLIENTS) != 0 ||
        (app->options.change_gid &&
         chown(address.sun_path, (uid_t)-1, app->options.socket_gid) != 0) ||
        chmod(address.sun_path, app->options.socket_mode) != 0) {
        qio_log("IPC listen/permissions: %s", strerror(errno));
        qio_ipc_close(app);
        return -1;
    }
    qio_log("IPC listening on %s mode=%04o", address.sun_path,
            (unsigned)app->options.socket_mode);
    return 0;
}

void qio_ipc_accept(qio_app *app, int64_t now)
{
    for (size_t attempt = 0; attempt < QIO_MAX_CLIENTS; ++attempt) {
        int fd = accept4(app->socket_fd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
        if (fd < 0 && errno == EINTR) continue;
        if (fd < 0) {
            if (errno != EAGAIN && errno != ECONNABORTED)
                qio_log("IPC accept: %s", strerror(errno));
            return;
        }
        size_t slot;
        for (slot = 0; slot < QIO_MAX_CLIENTS && app->clients[slot].fd >= 0; ++slot) {}
        if (slot == QIO_MAX_CLIENTS) {
            const char reply[] = "{\"ok\":false,\"error\":\"too_many_clients\"}\n";
            (void)qio_write_all(fd, reply, sizeof(reply) - 1);
            close(fd);
            return;
        }
        qio_client *client = &app->clients[slot];
        memset(client, 0, sizeof(*client));
        client->fd = fd;
        client->last_activity_ms = now;
    }
}

static void append_response(qio_app *app, size_t index, const char *text, size_t length)
{
    qio_client *client = &app->clients[index];
    if (client->fd < 0) return;
    if (length > QIO_RESPONSE_MAX || client->out_used > QIO_RESPONSE_MAX - length) {
        ++app->lost_events;
        close_client(app, index);
        return;
    }
    memcpy(client->out + client->out_used, text, length);
    client->out_used += length;
}

static void error_response(qio_app *app, size_t index, const char *code)
{
    char response[160];
    int n = snprintf(response, sizeof(response), "{\"ok\":false,\"error\":\"%s\"}\n", code);
    if (n > 0 && (size_t)n < sizeof(response))
        append_response(app, index, response, (size_t)n);
}

static void dispatch(qio_app *app, size_t index, const uint8_t *line,
                     size_t length, int64_t now)
{
    qio_request request;
    if (qio_parse_request(line, length, &request) != 0) {
        error_response(app, index, "invalid_request");
        return;
    }
    const char *action = request.action;
    bool draw = strcmp(action, "draw_screen") == 0 || strcmp(action, "draw_ring") == 0;
    bool data_action = draw || strcmp(action, "music_state") == 0 ||
                       strcmp(action, "plugin_animation") == 0;
    if (data_action && !request.has_data) { error_response(app, index, "missing_data"); return; }
    if (!data_action && request.has_data) { error_response(app, index, "unexpected_data"); return; }
    if (draw) {
        if (app->plugin.loaded) { error_response(app, index, "plugin_owns_output"); return; }
        qio_fb *fb = strcmp(action, "draw_screen") == 0 ? &app->screen : &app->ring;
        int result = qio_fb_queue(app, fb, request.data, request.data_length, now);
        if (result == -1) error_response(app, index, "invalid_frame_data");
        else if (result == -2) error_response(app, index, "hardware_unavailable");
        else {
            const char reply[] = "{\"ok\":true,\"queued\":true}\n";
            append_response(app, index, reply, sizeof(reply) - 1);
        }
    } else if (strcmp(action, "pause_sdk") == 0 || strcmp(action, "resume_sdk") == 0) {
        bool pause = strcmp(action, "pause_sdk") == 0;
        if (app->options.dry_run) {
            const char reply[] = "{\"ok\":true,\"dry_run\":true}\n";
            append_response(app, index, reply, sizeof(reply) - 1);
            return;
        }
        int result = pause ? qio_sdk_pause(&app->sdk) : qio_sdk_resume(&app->sdk);
        if (result != 0) error_response(app, index, qio_sdk_error(result));
        else {
            const char reply[] = "{\"ok\":true,\"signal_requested\":true}\n";
            append_response(app, index, reply, sizeof(reply) - 1);
        }
    } else if (strcmp(action, "music_state") == 0) {
        if (!request.has_data) { error_response(app, index, "missing_data"); return; }
        if (!app->plugin.loaded) { error_response(app, index, "plugin_disabled"); return; }
        if (!qio_plugin_music_command(app, request.data, now)) {
            error_response(app, index, "invalid_music_state");
            return;
        }
        const char reply[] = "{\"ok\":true}\n";
        append_response(app, index, reply, sizeof(reply) - 1);
    } else if (strcmp(action, "plugin_animation") == 0) {
        if (!request.has_data) { error_response(app, index, "missing_data"); return; }
        if (!app->plugin.loaded) { error_response(app, index, "plugin_disabled"); return; }
        if (!qio_plugin_animation_command(app, request.data, now)) {
            error_response(app, index, "invalid_animation");
            return;
        }
        const char reply[] = "{\"ok\":true}\n";
        append_response(app, index, reply, sizeof(reply) - 1);
    } else if (strcmp(action, "status") == 0) {
        char sdk[384], audio[512], plugin[512], response[3072];
        if (qio_sdk_status(&app->sdk, sdk, sizeof(sdk)) < 0 ||
            qio_audio_status(&app->audio, audio, sizeof(audio)) < 0 ||
            qio_plugin_status(&app->plugin, plugin, sizeof(plugin)) < 0) {
            error_response(app, index, "status_too_large");
            return;
        }
        int n = snprintf(response, sizeof(response),
            "{\"ok\":true,\"version\":\"%s\",\"dry_run\":%s,\"sdk\":%s,"
            "\"screen\":{\"mapped\":%s,\"pending\":%s,\"frames\":%" PRIu64 "},"
            "\"ring\":{\"mapped\":%s,\"pending\":%s,\"frames\":%" PRIu64 "},"
            "\"audio\":%s,\"plugin\":%s,\"lost_events\":%" PRIu64 "}\n",
            QIO_VERSION, app->options.dry_run ? "true" : "false", sdk,
            app->screen.mapping ? "true" : "false", app->screen.has_pending ? "true" : "false",
            app->screen.frames, app->ring.mapping ? "true" : "false",
            app->ring.has_pending ? "true" : "false", app->ring.frames, audio, plugin, app->lost_events);
        if (n < 0 || (size_t)n >= sizeof(response)) error_response(app, index, "status_too_large");
        else append_response(app, index, response, (size_t)n);
    } else {
        error_response(app, index, "unknown_action");
    }
}

static void flush_client(qio_app *app, size_t index, int64_t now)
{
    qio_client *client = &app->clients[index];
    if (client->fd < 0) return;
    while (client->out_used != 0) {
        ssize_t count = send(client->fd, client->out, client->out_used, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) {
            if (errno == EAGAIN) {
                if (client->slow_deadline_ms == 0) client->slow_deadline_ms = now + 5000;
                return;
            }
            close_client(app, index);
            return;
        }
        if (count == 0) { close_client(app, index); return; }
        size_t sent = (size_t)count;
        client->out_used -= sent;
        memmove(client->out, client->out + sent, client->out_used);
        client->slow_deadline_ms = 0;
    }
    if (client->eof || client->discard) close_client(app, index);
}

void qio_ipc_client_event(qio_app *app, size_t index, short revents, int64_t now)
{
    qio_client *client = &app->clients[index];
    if (client->fd < 0) return;
    if ((revents & POLLNVAL) != 0) { close_client(app, index); return; }
    if ((revents & POLLIN) != 0 && !client->eof && !client->discard) {
        ssize_t count = recv(client->fd, client->in + client->in_used,
                             QIO_REQUEST_MAX - client->in_used, 0);
        if (count < 0 && errno != EAGAIN && errno != EINTR) {
            close_client(app, index);
            return;
        }
        if (count == 0) client->eof = true;
        if (count > 0) {
            client->in_used += (size_t)count;
            client->last_activity_ms = now;
        }
        size_t consumed = 0;
        for (size_t command = 0; command < 8; ++command) {
            uint8_t *newline = memchr(client->in + consumed, '\n', client->in_used - consumed);
            if (newline == NULL) break;
            size_t length = (size_t)(newline - (client->in + consumed));
            dispatch(app, index, client->in + consumed, length, now);
            consumed += length + 1;
            if (client->fd < 0) return;
        }
        if (client->eof && consumed < client->in_used) {
            dispatch(app, index, client->in + consumed, client->in_used - consumed, now);
            consumed = client->in_used;
        }
        if (client->fd < 0) return;
        client->in_used -= consumed;
        memmove(client->in, client->in + consumed, client->in_used);
        if (client->in_used == QIO_REQUEST_MAX) {
            error_response(app, index, "request_too_long");
            client->discard = true;
        }
        client->partial_deadline_ms = client->in_used == 0 ? 0 : now + 5000;
    }
    if ((revents & (POLLERR | POLLHUP)) != 0 && client->out_used == 0) {
        close_client(app, index);
        return;
    }
    if ((revents & POLLOUT) != 0 || client->out_used != 0)
        flush_client(app, index, now);
}

void qio_ipc_tick(qio_app *app, int64_t now)
{
    for (size_t i = 0; i < QIO_MAX_CLIENTS; ++i) {
        qio_client *client = &app->clients[i];
        if (client->fd < 0) continue;
        if (now - client->last_activity_ms >= 300000 ||
            (client->partial_deadline_ms != 0 && now >= client->partial_deadline_ms) ||
            (client->slow_deadline_ms != 0 && now >= client->slow_deadline_ms)) {
            close_client(app, i);
            ++app->lost_events;
            continue;
        }
        if (client->out_used != 0) flush_client(app, i, now);
    }
}

void qio_broadcast(qio_app *app, const char *json)
{
    size_t length = strlen(json);
    if (length + 1 > QIO_RESPONSE_MAX) return;
    for (size_t i = 0; i < QIO_MAX_CLIENTS; ++i) {
        if (app->clients[i].fd < 0 || app->clients[i].discard) continue;
        append_response(app, i, json, length);
        if (app->clients[i].fd >= 0) append_response(app, i, "\n", 1);
    }
}

void qio_broadcast_button(qio_app *app, const char *event,
                          const char *source, unsigned int code)
{
    char json[256];
    int n = snprintf(json, sizeof(json),
                     "{\"event\":\"%s\",\"source\":\"%s\",\"code\":%u}", event, source, code);
    if (n > 0 && (size_t)n < sizeof(json)) qio_broadcast(app, json);
    qio_plugin_button(app, event);
}

void qio_ipc_close(qio_app *app)
{
    for (size_t i = 0; i < QIO_MAX_CLIENTS; ++i) close_client(app, i);
    qio_close_fd(&app->socket_fd);
    if (app->socket_created) {
        struct stat current;
        if (lstat(app->options.socket_path, &current) == 0 &&
            S_ISSOCK(current.st_mode) && current.st_dev == app->socket_identity.st_dev &&
            current.st_ino == app->socket_identity.st_ino)
            (void)unlink(app->options.socket_path);
        app->socket_created = false;
    }
}
