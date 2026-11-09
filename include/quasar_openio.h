/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef QUASAR_OPENIO_H
#define QUASAR_OPENIO_H

#define _GNU_SOURCE
#include <linux/fb.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/stat.h>
#include <sys/types.h>

#define QIO_VERSION "0.2.0"
#define QIO_MAX_CLIENTS 8
#define QIO_REQUEST_MAX 4096
#define QIO_RESPONSE_MAX 8192
#define QIO_SCREEN_WIDTH 25
#define QIO_SCREEN_HEIGHT 16
#define QIO_RING_WIDTH 24
#define QIO_RING_HEIGHT 1
#define QIO_SCREEN_BYTES (QIO_SCREEN_WIDTH * QIO_SCREEN_HEIGHT * 3)
#define QIO_RING_BYTES (QIO_RING_WIDTH * QIO_RING_HEIGHT * 3)
#define QIO_INPUT_COUNT 3
#define QIO_FRAME_INTERVAL_MS 17
#define QIO_RETRY_MS 5000
#define QIO_AUDIO_CAPACITY (256U * 1024U)
#define QIO_AUDIO_RATE 48000
#define QIO_AUDIO_CHANNELS 2
#define QIO_AUDIO_FRAME_BYTES 4
#define QIO_HTTP_CLIENTS 4
#define QIO_AUDIO_POLL_MAX (2 + 1 + QIO_HTTP_CLIENTS)
#define QIO_AUDIO_TOKEN_HEX 64
#define QIO_PLUGIN_PATH_MAX 256
#define QIO_PLUGIN_MODE_MAX 32
#define QIO_PLUGIN_REQUIRED_ANIMATIONS 7

#ifndef QIO_RUNTIME_DIR
#define QIO_RUNTIME_DIR "/data/local/tmp/quasar-openio"
#endif
#ifndef QIO_SOCKET_PATH
#define QIO_SOCKET_PATH QIO_RUNTIME_DIR "/quasar.sock"
#endif
#ifndef QIO_LOCK_PATH
#define QIO_LOCK_PATH QIO_RUNTIME_DIR "/daemon.lock"
#endif
#ifndef QIO_SCREEN_PATH
#define QIO_SCREEN_PATH "/dev/graphics/fb4"
#endif
#ifndef QIO_RING_PATH
#define QIO_RING_PATH "/dev/graphics/fb5"
#endif
#ifndef QIO_PANEL_PATH
#define QIO_PANEL_PATH "/dev/input/event0"
#endif
#ifndef QIO_ROTARY_PATH
#define QIO_ROTARY_PATH "/dev/input/event2"
#endif
#ifndef QIO_REMOTE_PATH
#define QIO_REMOTE_PATH "/dev/input/event4"
#endif
#ifndef QIO_PROC_ROOT
#define QIO_PROC_ROOT "/proc"
#endif
#ifndef QIO_SDK_PACKAGE
#define QIO_SDK_PACKAGE "com.yandex.io.sdk"
#endif
#ifndef QIO_SDK_JOURNAL_PATH
#define QIO_SDK_JOURNAL_PATH QIO_RUNTIME_DIR "/sdk.journal"
#endif
#ifndef QIO_AUDIO_TOKEN_PATH
#define QIO_AUDIO_TOKEN_PATH QIO_RUNTIME_DIR "/audio.token"
#endif
#ifndef QIO_PLUGIN_DIR
#define QIO_PLUGIN_DIR "/odm/lib/quasar/plugins/standard"
#endif

/* These are device nodes, never arbitrary write destinations in production. */
typedef struct {
    const char *path;
    int fd;
    uint8_t *mapping;
    size_t map_size;
    size_t offset;
    uint32_t stride;
    uint32_t width;
    uint32_t height;
    size_t frame_bytes;
    uint8_t channel_byte[3]; /* destination byte for input R, G, B */
    uint8_t pending[QIO_SCREEN_BYTES];
    bool has_pending;
    int64_t next_draw_ms;
    int64_t next_open_ms;
    uint64_t frames;
    const char *last_error;
} qio_fb;

typedef struct {
    pid_t pid;
    uint64_t start_ticks;
    bool owned;
    bool recovered;
    bool journal_blocked;
} qio_sdk;

typedef struct {
    const char *path;
    const char *name;
    const char *expected_name;
    int fd;
    int64_t next_open_ms;
    uint8_t partial[64];
    size_t partial_used;
} qio_input;

typedef struct {
    int fd;
    uint8_t in[QIO_REQUEST_MAX];
    size_t in_used;
    uint8_t out[QIO_RESPONSE_MAX];
    size_t out_used;
    bool eof;
    bool discard;
    int64_t last_activity_ms;
    int64_t partial_deadline_ms;
    int64_t slow_deadline_ms;
} qio_client;

typedef struct {
    int fd;
    uint8_t request[1024];
    size_t request_used;
    uint8_t header[768];
    size_t header_used;
    int64_t deadline_ms;
    bool streaming;
    bool close_after_send;
} qio_http;

typedef struct {
    int pcm_listen_fd;
    int http_listen_fd;
    int pcm_fd;
    int pcm_port;
    int http_port;
    int64_t pcm_deadline_ms;
    char token[QIO_AUDIO_TOKEN_HEX + 1];
    char token_path[256];
    struct stat token_identity;
    bool token_file_created;
    bool pcm_authed;
    uint8_t auth_line[QIO_AUDIO_TOKEN_HEX + 16];
    size_t auth_used;
    uint64_t auth_failures;
    uint64_t accepted_bytes;
    uint64_t dropped_bytes;
    uint64_t rejected_producers;
    bool producer_eof;
    int64_t last_sound_ms;
    int mirror_fd;
    uint8_t carry[QIO_AUDIO_FRAME_BYTES - 1];
    size_t carry_used;
    uint8_t ring[QIO_AUDIO_CAPACITY];
    size_t head;
    size_t used;
    qio_http http[QIO_HTTP_CLIENTS];
} qio_audio;

typedef struct {
    const char *socket_path;
    mode_t socket_mode;
    gid_t socket_gid;
    bool change_gid;
    int audio_port;
    int http_port;
    bool no_audio;
    const char *audio_token_file;
    bool dry_run;
    bool no_plugin;
    const char *plugin_dir;
    bool plugin_uid_set;
    uid_t plugin_uid;
    gid_t plugin_gid;
} qio_options;

enum qio_plugin_music_state { QIO_PLUGIN_MUSIC_STOPPED, QIO_PLUGIN_MUSIC_PLAYING, QIO_PLUGIN_MUSIC_PAUSED };

typedef struct {
    bool enabled;
    bool loaded;
    char dir[QIO_PLUGIN_PATH_MAX];
    char binary[QIO_PLUGIN_PATH_MAX];
    char idle[QIO_PLUGIN_MODE_MAX];
    char music[QIO_PLUGIN_MODE_MAX];
    char animations[512];
    pid_t child;
    int child_stdout;
    int child_stdin;
    char child_mode[QIO_PLUGIN_MODE_MAX];
    bool child_is_animation;
    int64_t child_deadline_ms;
    int64_t kill_deadline_ms;
    int64_t next_launch_ms;
    enum qio_plugin_music_state music_state;
    bool explicit_music;
    char overlay[QIO_PLUGIN_MODE_MAX];
    bool overlay_active;
    bool overlay_relaunch;
    int64_t overlay_deadline_ms;
    uint64_t launches;
    uint64_t stdout_bytes;
    uint64_t dropped_commands;
} qio_plugin;

typedef struct {
    qio_options options;
    int lock_fd;
    int signal_fd;
    int socket_fd;
    struct stat socket_identity;
    bool socket_created;
    qio_client clients[QIO_MAX_CLIENTS];
    qio_input inputs[QIO_INPUT_COUNT];
    qio_fb screen;
    qio_fb ring;
    qio_sdk sdk;
    qio_audio audio;
    qio_plugin plugin;
    uint64_t lost_events;
} qio_app;

enum qio_poll_role {
    QIO_POLL_SIGNAL, QIO_POLL_IPC, QIO_POLL_INPUT, QIO_POLL_CLIENT,
    QIO_POLL_AUDIO, QIO_POLL_PLUGIN
};

/* util.c */
int64_t qio_now_ms(void);
void qio_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
int qio_signals_install(void);
bool qio_stopping(void);
int qio_signal_fd(void);
void qio_signals_drain(void);
void qio_signals_close(void);
void qio_close_fd(int *fd);
int qio_write_all(int fd, const void *data, size_t length);

/* json.c: bounded, allocation-free parser for the documented flat schema. */
typedef struct {
    char action[32];
    char data_storage[2048];
    const char *data;
    size_t data_length;
    bool has_data;
} qio_request;
int qio_parse_request(const uint8_t *line, size_t length, qio_request *request);
int qio_base64_decode(const char *input, size_t length, uint8_t *output,
                      size_t capacity, size_t *decoded);

/* framebuffer.c */
void qio_fb_init(qio_fb *fb, const char *path, uint32_t width, uint32_t height);
int qio_fb_open(qio_fb *fb, int64_t now);
int qio_fb_validate(const struct fb_fix_screeninfo *fixed,
                    const struct fb_var_screeninfo *variable,
                    uint32_t width, uint32_t height, qio_fb *layout);
void qio_fb_blit(uint8_t *mapping, const qio_fb *layout, const uint8_t *rgb);
int qio_fb_queue(qio_app *app, qio_fb *fb, const char *base64,
                 size_t length, int64_t now);
void qio_fb_tick(qio_fb *fb, int64_t now);
void qio_fb_close(qio_fb *fb);

/* sdk.c */
void qio_sdk_init(qio_sdk *sdk);
int qio_sdk_recover(qio_sdk *sdk);
int qio_sdk_pause(qio_sdk *sdk);
int qio_sdk_resume(qio_sdk *sdk);
void qio_sdk_cleanup(qio_sdk *sdk);
const char *qio_sdk_error(int result);
int qio_sdk_status(qio_sdk *sdk, char *buffer, size_t capacity);

/* input.c */
void qio_inputs_init(qio_app *app);
void qio_inputs_tick(qio_app *app, int64_t now);
void qio_inputs_read(qio_app *app, size_t index);
void qio_inputs_close(qio_app *app);

/* ipc.c */
int qio_runtime_prepare(void);
int qio_private_parent(const char *path);
int qio_runtime_prepare(void);
int qio_private_parent(const char *path);
int qio_lock_acquire(qio_app *app);
void qio_lock_release(qio_app *app);
int qio_ipc_init(qio_app *app);
void qio_ipc_accept(qio_app *app, int64_t now);
void qio_ipc_client_event(qio_app *app, size_t index, short revents, int64_t now);
void qio_ipc_tick(qio_app *app, int64_t now);
bool qio_ipc_has_pending(const qio_app *app);
void qio_ipc_close(qio_app *app);
void qio_broadcast(qio_app *app, const char *json);
void qio_broadcast_button(qio_app *app, const char *event,
                          const char *source, unsigned int code);

/* audio.c */
int qio_audio_init(qio_audio *audio, const qio_options *options, int64_t now);
size_t qio_audio_pollfds(qio_audio *audio, struct pollfd *fds, size_t capacity);
void qio_audio_handle(qio_audio *audio, int fd, short revents, int64_t now);
void qio_audio_tick(qio_audio *audio, int64_t now);
void qio_audio_close(qio_audio *audio);
int qio_audio_status(qio_audio *audio, char *buffer, size_t capacity);

/* plugin.c */
int qio_plugin_init(qio_app *app);
void qio_plugin_tick(qio_app *app, int64_t now);
short qio_plugin_events(const qio_app *app);
void qio_plugin_stdout_event(qio_app *app, short revents, int64_t now);
void qio_plugin_button(qio_app *app, const char *event);
bool qio_plugin_music_command(qio_app *app, const char *state, int64_t now);
bool qio_plugin_animation_command(qio_app *app, const char *name, int64_t now);
int qio_plugin_status(qio_plugin *plugin, char *buffer, size_t capacity);
void qio_plugin_close(qio_app *app);

#endif
