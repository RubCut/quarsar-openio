/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_openio.h"
#include <errno.h>
#include <inttypes.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static qio_app app; /* Fixed BSS storage; no per-frame/per-event heap allocation. */

static void usage(FILE *out)
{
    fprintf(out,
        "quasar_openio %s\n"
        "Usage: quasar_openio [options]\n"
        "  --socket PATH       Unix NDJSON socket (default %s)\n"
        "  --socket-mode MODE  0600 (default) or 0660\n"
        "  --socket-gid GID    numeric group for the socket\n"
        "  --audio-port PORT   loopback PCM input (default 19080)\n"
        "  --http-port PORT    loopback HTTP output (default 19081)\n"
        "  --no-audio          disable both audio listeners\n"
        "  --audio-token-file PATH  bearer token file, 0600 (default %s)\n"
        "  --plugin-dir PATH   standard plugin directory (default %s)\n"
        "  --no-plugin         do not launch plugin binaries\n"
        "  --plugin-uid UID    drop the plugin to this unprivileged uid (needs --plugin-gid)\n"
        "  --plugin-gid GID    primary group for the dropped plugin\n"
        "  --dry-run           no input/framebuffer opens, SDK signals, journal writes or plugin\n"
        "  --version | --help\n", QIO_VERSION, QIO_SOCKET_PATH, QIO_AUDIO_TOKEN_PATH, QIO_PLUGIN_DIR);
}

static int parse_port(const char *text, int *port)
{
    char *end;
    errno = 0;
    long value = strtol(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' || value < 1 || value > 65535)
        return -1;
    *port = (int)value;
    return 0;
}

static int parse_options(int argc, char **argv, qio_options *options)
{
    memset(options, 0, sizeof(*options));
    options->socket_path = QIO_SOCKET_PATH;
    options->socket_mode = 0600;
    options->audio_port = 19080;
    options->http_port = 19081;
    options->plugin_dir = QIO_PLUGIN_DIR;
    options->audio_token_file = QIO_AUDIO_TOKEN_PATH;
    for (int i = 1; i < argc; ++i) {
        const char *arg = argv[i];
        if (strcmp(arg, "--help") == 0) { usage(stdout); return 1; }
        if (strcmp(arg, "--version") == 0) { puts(QIO_VERSION); return 1; }
        if (strcmp(arg, "--no-audio") == 0) { options->no_audio = true; continue; }
        if (strcmp(arg, "--no-plugin") == 0) { options->no_plugin = true; continue; }
        if (strcmp(arg, "--dry-run") == 0) { options->dry_run = true; continue; }
        if (i + 1 >= argc) { usage(stderr); return -1; }
        const char *value = argv[++i];
        if (strcmp(arg, "--socket") == 0) {
            options->socket_path = value;
        } else if (strcmp(arg, "--audio-token-file") == 0) {
            options->audio_token_file = value;
        } else if (strcmp(arg, "--plugin-dir") == 0) {
            options->plugin_dir = value;
        } else if (strcmp(arg, "--plugin-uid") == 0 || strcmp(arg, "--plugin-gid") == 0) {
            char *end;
            errno = 0;
            unsigned long id = strtoul(value, &end, 10);
            if (errno != 0 || end == value || *end != '\0' || id == 0 || id > 4294967294UL)
                return -1;
            if (strcmp(arg, "--plugin-uid") == 0) {
                options->plugin_uid = (uid_t)id;
                options->plugin_uid_set = true;
            } else {
                options->plugin_gid = (gid_t)id;
            }
        } else if (strcmp(arg, "--socket-mode") == 0) {
            char *end;
            errno = 0;
            unsigned long mode = strtoul(value, &end, 8);
            if (errno != 0 || end == value || *end != '\0' || (mode != 0600 && mode != 0660))
                return -1;
            options->socket_mode = (mode_t)mode;
        } else if (strcmp(arg, "--socket-gid") == 0) {
            char *end;
            errno = 0;
            unsigned long gid = strtoul(value, &end, 10);
            if (errno != 0 || end == value || *end != '\0' || gid > 4294967294UL)
                return -1;
            options->socket_gid = (gid_t)gid;
            options->change_gid = true;
        } else if (strcmp(arg, "--audio-port") == 0) {
            if (parse_port(value, &options->audio_port) != 0) return -1;
        } else if (strcmp(arg, "--http-port") == 0) {
            if (parse_port(value, &options->http_port) != 0) return -1;
        } else {
            usage(stderr);
            return -1;
        }
    }
    if (!options->no_audio && options->audio_port == options->http_port) return -1;
    if (options->change_gid && options->socket_mode != 0660) return -1;
    if (options->plugin_uid_set && options->plugin_gid == 0) return -1;
    if (!options->plugin_uid_set && options->plugin_gid != 0) return -1;
    return 0;
}

static void consider_timeout(int64_t *timeout, int64_t deadline, int64_t now)
{
    if (deadline <= 0) return;
    int64_t remaining = deadline > now ? deadline - now : 0;
    if (*timeout < 0 || remaining < *timeout) *timeout = remaining;
}

static int run(void)
{
    const size_t capacity = 2 + QIO_INPUT_COUNT + QIO_MAX_CLIENTS + QIO_AUDIO_POLL_MAX + 1;
    struct pollfd fds[2 + QIO_INPUT_COUNT + QIO_MAX_CLIENTS + QIO_AUDIO_POLL_MAX + 1];
    enum qio_poll_role roles[2 + QIO_INPUT_COUNT + QIO_MAX_CLIENTS + QIO_AUDIO_POLL_MAX + 1];
    size_t indices[2 + QIO_INPUT_COUNT + QIO_MAX_CLIENTS + QIO_AUDIO_POLL_MAX + 1];
    while (!qio_stopping()) {
        int64_t now = qio_now_ms();
        if (now < 0) return 1;
        qio_inputs_tick(&app, now);
        qio_fb_tick(&app.screen, now);
        qio_fb_tick(&app.ring, now);
        qio_ipc_tick(&app, now);
        qio_audio_tick(&app.audio, now);
        qio_plugin_tick(&app, now);
        int64_t timeout = -1;
        for (size_t i = 0; i < QIO_INPUT_COUNT; ++i)
            if (app.inputs[i].fd < 0 && !app.options.dry_run)
                consider_timeout(&timeout, app.inputs[i].next_open_ms, now);
        if (app.screen.has_pending)
            consider_timeout(&timeout, app.screen.next_draw_ms > 0 ? app.screen.next_draw_ms : now, now);
        if (app.ring.has_pending)
            consider_timeout(&timeout, app.ring.next_draw_ms > 0 ? app.ring.next_draw_ms : now, now);
        for (size_t i = 0; i < QIO_MAX_CLIENTS; ++i) {
            qio_client *client = &app.clients[i];
            if (client->fd < 0) continue;
            consider_timeout(&timeout, client->last_activity_ms + 300000, now);
            consider_timeout(&timeout, client->partial_deadline_ms, now);
            consider_timeout(&timeout, client->slow_deadline_ms, now);
        }
        consider_timeout(&timeout, app.audio.pcm_deadline_ms, now);
        consider_timeout(&timeout, app.plugin.overlay_deadline_ms, now);
        consider_timeout(&timeout, app.plugin.child_deadline_ms, now);
        consider_timeout(&timeout, app.plugin.kill_deadline_ms, now);
        if (app.plugin.child <= 0) consider_timeout(&timeout, app.plugin.next_launch_ms, now);
        if (!app.plugin.explicit_music && app.plugin.music_state == QIO_PLUGIN_MUSIC_PLAYING)
            consider_timeout(&timeout, app.audio.last_sound_ms + 1500, now);
        for (size_t i = 0; i < QIO_HTTP_CLIENTS; ++i)
            consider_timeout(&timeout, app.audio.http[i].deadline_ms, now);
        /* Buffered complete commands are pending work: never sleep on them. */
        if (qio_ipc_has_pending(&app)) timeout = 0;
        size_t used = 0;
        fds[used].fd = qio_signal_fd(); fds[used].events = POLLIN;
        roles[used] = QIO_POLL_SIGNAL; indices[used++] = 0;
        fds[used].fd = app.socket_fd; fds[used].events = POLLIN;
        roles[used] = QIO_POLL_IPC; indices[used++] = 0;
        for (size_t i = 0; i < QIO_INPUT_COUNT; ++i) {
            if (app.inputs[i].fd < 0) continue;
            fds[used].fd = app.inputs[i].fd; fds[used].events = POLLIN;
            roles[used] = QIO_POLL_INPUT; indices[used++] = i;
        }
        for (size_t i = 0; i < QIO_MAX_CLIENTS; ++i) {
            if (app.clients[i].fd < 0) continue;
            fds[used].fd = app.clients[i].fd;
            fds[used].events = POLLIN | (app.clients[i].out_used ? POLLOUT : 0);
            roles[used] = QIO_POLL_CLIENT; indices[used++] = i;
        }
        if (app.plugin.child_stdout >= 0 && used < capacity) {
            fds[used].fd = app.plugin.child_stdout;
            fds[used].events = qio_plugin_events(&app);
            roles[used] = QIO_POLL_PLUGIN; indices[used++] = 0;
        }
        size_t audio_start = used;
        used += qio_audio_pollfds(&app.audio, fds + used, capacity - used);
        for (size_t i = audio_start; i < used; ++i) {
            roles[i] = QIO_POLL_AUDIO;
            indices[i] = 0;
        }
        int wait = timeout < 0 ? -1 : (timeout > INT_MAX ? INT_MAX : (int)timeout);
        int count = poll(fds, (nfds_t)used, wait);
        if (count < 0) {
            if (errno == EINTR) continue;
            qio_log("poll: %s", strerror(errno));
            return 1;
        }
        now = qio_now_ms();
        if (now < 0) return 1;
        for (size_t i = 0; i < used && count > 0; ++i) {
            if (fds[i].revents == 0) continue;
            --count;
            switch (roles[i]) {
            case QIO_POLL_SIGNAL: qio_signals_drain(); break;
            case QIO_POLL_IPC: qio_ipc_accept(&app, now); break;
            case QIO_POLL_INPUT:
                if (app.inputs[indices[i]].fd == fds[i].fd) qio_inputs_read(&app, indices[i]);
                break;
            case QIO_POLL_CLIENT:
                if (app.clients[indices[i]].fd == fds[i].fd)
                    qio_ipc_client_event(&app, indices[i], fds[i].revents, now);
                break;
            case QIO_POLL_AUDIO: qio_audio_handle(&app.audio, fds[i].fd, fds[i].revents, now); break;
            case QIO_POLL_PLUGIN: qio_plugin_stdout_event(&app, fds[i].revents, now); break;
            }
            if (qio_stopping()) break;
        }
    }
    return 0;
}

int main(int argc, char **argv)
{
    int parsed = parse_options(argc, argv, &app.options);
    if (parsed != 0) return parsed > 0 ? 0 : 2;
    /* Device access and CAP_KILL are checked by the kernel. The supplied init
     * service runs as root; a separately confined UID can also be used. */
    memset(&app.clients, 0, sizeof(app.clients));
    app.lock_fd = app.socket_fd = -1;
    qio_fb_init(&app.screen, QIO_SCREEN_PATH, QIO_SCREEN_WIDTH, QIO_SCREEN_HEIGHT);
    qio_fb_init(&app.ring, QIO_RING_PATH, QIO_RING_WIDTH, QIO_RING_HEIGHT);
    qio_inputs_init(&app);
    qio_sdk_init(&app.sdk);
    app.audio.pcm_listen_fd = app.audio.http_listen_fd = app.audio.pcm_fd = -1;
    app.audio.mirror_fd = -1;
    for (size_t i = 0; i < QIO_HTTP_CLIENTS; ++i) app.audio.http[i].fd = -1;
    int result = 1;
    if (qio_signals_install() != 0) {
        qio_log("signal setup: %s", strerror(errno));
        goto cleanup;
    }
    if (qio_runtime_prepare() != 0) goto cleanup;
    if (qio_lock_acquire(&app) != 0) goto cleanup;
    if (!app.options.dry_run && qio_sdk_recover(&app.sdk) != 0) {
        qio_log("SDK recovery failed; refusing to start with an unsafe journal");
        goto cleanup;
    }
    if (qio_ipc_init(&app) != 0) goto cleanup;
    if (qio_audio_init(&app.audio, &app.options, qio_now_ms()) != 0) goto cleanup;
    if (qio_plugin_init(&app) != 0) goto cleanup;
    qio_log("started pid=%ld dry_run=%d; no shell commands, no PackageManager changes",
            (long)getpid(), app.options.dry_run);
    result = run();
cleanup:
    qio_plugin_close(&app);
    qio_audio_close(&app.audio);
    qio_ipc_close(&app);
    qio_inputs_close(&app);
    qio_fb_close(&app.screen);
    qio_fb_close(&app.ring);
    if (!app.options.dry_run) qio_sdk_cleanup(&app.sdk);
    qio_lock_release(&app);
    qio_signals_close();
    qio_log("stopped");
    return result;
}
