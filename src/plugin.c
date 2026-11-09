/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_openio.h"
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define PLUGIN_CONF_MAX 4096
#define PLUGIN_CHILD_GRACE_MS 250
#define PLUGIN_MUSIC_TIMEOUT_MS 1500
#define PLUGIN_MIN_LAUNCH_GAP_MS 120
#define PLUGIN_ENV_MAX 256

extern char **environ;

static const char *const required_animations[QIO_PLUGIN_REQUIRED_ANIMATIONS] = {
    "music-play", "music-pause", "mic-on", "mic-off",
    "volume-up", "volume-down", "volume-ring"
};

static bool mode_name_valid(const char *name)
{
    size_t length = strlen(name);
    if (length == 0 || length >= QIO_PLUGIN_MODE_MAX) return false;
    for (size_t i = 0; i < length; ++i) {
        unsigned char c = (unsigned char)name[i];
        if (!(islower(c) || isdigit(c) || c == '-')) return false;
    }
    return true;
}

static bool binary_name_valid(const char *name)
{
    size_t length = strlen(name);
    if (length == 0 || length >= 64) return false;
    for (size_t i = 0; i < length; ++i) {
        unsigned char c = (unsigned char)name[i];
        if (!(isalnum(c) || c == '_' || c == '-')) return false;
    }
    return true;
}

static int private_path(const char *path, bool executable)
{
    struct stat st;
    if (lstat(path, &st) != 0) return -1;
    if (S_ISLNK(st.st_mode)) return -1;
    if (st.st_uid != 0 && st.st_uid != geteuid()) return -1;
    if ((st.st_mode & 0022) != 0) return -1;
    if (executable) {
        if (!S_ISREG(st.st_mode) || st.st_nlink != 1 || (st.st_mode & 0111) == 0)
            return -1;
    } else if (!S_ISDIR(st.st_mode)) return -1;
    return 0;
}

static int read_conf(const char *path, char *buffer, size_t capacity)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_nlink != 1 ||
        (st.st_uid != 0 && st.st_uid != geteuid()) || (st.st_mode & 0022) != 0 ||
        st.st_size < 0 || (uint64_t)st.st_size >= capacity) {
        close(fd);
        return -1;
    }
    size_t used = 0;
    while (used < capacity - 1) {
        ssize_t count = read(fd, buffer + used, capacity - 1 - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        used += (size_t)count;
    }
    int saved = errno;
    close(fd);
    errno = saved;
    buffer[used] = '\0';
    return 0;
}

static char *trim(char *text)
{
    while (*text == ' ' || *text == '\t' || *text == '\r') ++text;
    size_t length = strlen(text);
    while (length > 0 && (text[length - 1] == ' ' || text[length - 1] == '\t' ||
                          text[length - 1] == '\r'))
        text[--length] = '\0';
    return text;
}

static bool animations_present(const char *list)
{
    for (size_t i = 0; i < QIO_PLUGIN_REQUIRED_ANIMATIONS; ++i) {
        const char *cursor = list;
        bool found = false;
        while (*cursor != '\0') {
            const char *comma = strchr(cursor, ',');
            size_t length = comma ? (size_t)(comma - cursor) : strlen(cursor);
            while (length > 0 && (*cursor == ' ' || *cursor == '\t')) { ++cursor; --length; }
            while (length > 0 && (cursor[length - 1] == ' ' || cursor[length - 1] == '\t')) --length;
            if (strlen(required_animations[i]) == length &&
                strncmp(cursor, required_animations[i], length) == 0) {
                found = true;
                break;
            }
            if (comma == NULL) break;
            cursor = comma + 1;
        }
        if (!found) return false;
    }
    return true;
}

static int load_manifest(qio_plugin *plugin)
{
    char path[QIO_PLUGIN_PATH_MAX];
    int n = snprintf(path, sizeof(path), "%s/plugin.conf", plugin->dir);
    if (n < 0 || (size_t)n >= sizeof(path)) return -1;
    char conf[PLUGIN_CONF_MAX];
    if (read_conf(path, conf, sizeof(conf)) != 0) return -1;
    char binary[64] = "", idle[QIO_PLUGIN_MODE_MAX] = "", music[QIO_PLUGIN_MODE_MAX] = "";
    char animations[512] = "";
    bool format = false, name = false;
    unsigned seen = 0;
    char *save = NULL;
    for (char *line = strtok_r(conf, "\n", &save); line != NULL;
         line = strtok_r(NULL, "\n", &save)) {
        char *comment = strchr(line, '#');
        if (comment != NULL) *comment = '\0';
        char *key = trim(line);
        if (*key == '\0') continue;
        char *equals = strchr(key, '=');
        if (equals == NULL) return -1;
        *equals = '\0';
        char *value = trim(equals + 1);
        key = trim(key);
        if (strcmp(key, "format") == 0) {
            if (format || strcmp(value, "quasar-plugin-v1") != 0) return -1;
            format = true;
        } else if (strcmp(key, "name") == 0) {
            if (name || strcmp(value, "standard") != 0) return -1;
            name = true;
        } else if (strcmp(key, "binary") == 0) {
            if (!binary_name_valid(value) || (seen & 1U) != 0) return -1;
            snprintf(binary, sizeof(binary), "%s", value);
            seen |= 1U;
        } else if (strcmp(key, "idle") == 0) {
            if (!mode_name_valid(value) || (seen & 2U) != 0) return -1;
            snprintf(idle, sizeof(idle), "%s", value);
            seen |= 2U;
        } else if (strcmp(key, "music") == 0) {
            if (!mode_name_valid(value) || (seen & 4U) != 0) return -1;
            snprintf(music, sizeof(music), "%s", value);
            seen |= 4U;
        } else if (strcmp(key, "animations") == 0) {
            if ((seen & 8U) != 0 || strlen(value) >= sizeof(animations)) return -1;
            snprintf(animations, sizeof(animations), "%s", value);
            seen |= 8U;
        } else return -1;
    }
    if (!format || !name || seen != 15U || !animations_present(animations)) return -1;
    n = snprintf(plugin->binary, sizeof(plugin->binary), "%s/%s", plugin->dir, binary);
    if (n < 0 || (size_t)n >= sizeof(plugin->binary)) return -1;
    snprintf(plugin->idle, sizeof(plugin->idle), "%s", idle);
    snprintf(plugin->music, sizeof(plugin->music), "%s", music);
    snprintf(plugin->animations, sizeof(plugin->animations), "%s", animations);
    return private_path(plugin->binary, true);
}

int qio_plugin_init(qio_app *app)
{
    qio_plugin *plugin = &app->plugin;
    memset(plugin, 0, sizeof(*plugin));
    plugin->child = -1;
    plugin->child_stdout = plugin->child_stdin = -1;
    plugin->enabled = !app->options.no_plugin && !app->options.dry_run;
    if (!plugin->enabled) return 0;
    const char *dir = app->options.plugin_dir ? app->options.plugin_dir : QIO_PLUGIN_DIR;
    if (strlen(dir) >= sizeof(plugin->dir) || private_path(dir, false) != 0) {
        qio_log("plugin: unsafe or missing directory %s", dir);
        return -1;
    }
    snprintf(plugin->dir, sizeof(plugin->dir), "%s", dir);
    if (load_manifest(plugin) != 0) {
        qio_log("plugin: invalid standard manifest in %s", plugin->dir);
        return -1;
    }
    plugin->loaded = true;
    plugin->music_state = QIO_PLUGIN_MUSIC_STOPPED;
    qio_log("plugin: loaded standard pack %s idle=%s music=%s",
            plugin->binary, plugin->idle, plugin->music);
    return 0;
}

static void close_child_pipes(qio_plugin *plugin)
{
    qio_close_fd(&plugin->child_stdout);
    qio_close_fd(&plugin->child_stdin);
}

static void reap_child(qio_app *app, int64_t now)
{
    qio_plugin *plugin = &app->plugin;
    if (plugin->child <= 0) return;
    int status = 0;
    pid_t result = waitpid(plugin->child, &status, WNOHANG);
    if (result == 0) return;
    if (result < 0 && errno != ECHILD) return;
    bool animation = plugin->child_is_animation;
    bool intentional_stop = plugin->kill_deadline_ms != 0;
    bool overlay_matches = animation && plugin->overlay_active &&
                           strcmp(plugin->overlay, plugin->child_mode) == 0;
    qio_log("plugin: child %ld exited status=%d", (long)plugin->child, status);
    /* Bounded non-blocking drain of child diagnostics before closing the pipe. */
    for (unsigned i = 0; i < 16 && plugin->child_stdout >= 0; ++i)
        qio_plugin_stdout_event(app, POLLIN, now);
    plugin->child = -1;
    plugin->child_mode[0] = '\0';
    plugin->child_is_animation = false;
    plugin->child_deadline_ms = plugin->kill_deadline_ms = 0;
    close_child_pipes(plugin);
    qio_close_fd(&app->audio.mirror_fd);
    /* The plugin owns fb5 and leaves the ring dark when an animation ends. */
    if (animation && overlay_matches) {
        plugin->overlay_active = false;
        plugin->overlay[0] = '\0';
    }
    /* Crash backoff only for an unexpected exit; mode transitions stay snappy. */
    plugin->next_launch_ms = now + ((animation || intentional_stop) ?
                                    PLUGIN_MIN_LAUNCH_GAP_MS : 1000);
}

static void stop_child(qio_app *app, int64_t now)
{
    qio_plugin *plugin = &app->plugin;
    if (plugin->child <= 0) return;
    if (plugin->kill_deadline_ms == 0) {
        (void)kill(-plugin->child, SIGTERM);
        (void)kill(plugin->child, SIGTERM);
        plugin->kill_deadline_ms = now + PLUGIN_CHILD_GRACE_MS;
    } else if (now >= plugin->kill_deadline_ms) {
        (void)kill(-plugin->child, SIGKILL);
        (void)kill(plugin->child, SIGKILL);
    }
}

static unsigned animation_duration(const char *name)
{
    if (strcmp(name, "music-play") == 0) return 1200;
    if (strcmp(name, "music-pause") == 0) return 1000;
    if (strcmp(name, "mic-on") == 0 || strcmp(name, "mic-off") == 0) return 800;
    if (strcmp(name, "volume-ring") == 0) return 1200;
    return 900;
}

static bool is_animation_mode(const qio_plugin *plugin, const char *mode)
{
    return strcmp(mode, plugin->idle) != 0 && strcmp(mode, plugin->music) != 0;
}

static bool mode_allowed(const qio_plugin *plugin, const char *mode)
{
    if (strcmp(mode, plugin->idle) == 0 || strcmp(mode, plugin->music) == 0) return true;
    const char *cursor = plugin->animations;
    while (*cursor != '\0') {
        const char *comma = strchr(cursor, ',');
        size_t length = comma ? (size_t)(comma - cursor) : strlen(cursor);
        while (length > 0 && (*cursor == ' ' || *cursor == '\t')) { ++cursor; --length; }
        while (length > 0 && (cursor[length - 1] == ' ' || cursor[length - 1] == '\t')) --length;
        if (strlen(mode) == length && strncmp(cursor, mode, length) == 0) return true;
        if (comma == NULL) break;
        cursor = comma + 1;
    }
    return false;
}

static int set_nonblock_cloexec(int fd)
{
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) return -1;
    flags = fcntl(fd, F_GETFD);
    if (flags < 0 || fcntl(fd, F_SETFD, flags | FD_CLOEXEC) != 0) return -1;
    return 0;
}

static void launch_child(qio_app *app, const char *mode, int64_t now)
{
    qio_plugin *plugin = &app->plugin;
    if (!plugin->loaded || plugin->child > 0 || now < plugin->next_launch_ms) return;
    bool animation = is_animation_mode(plugin, mode);
    bool visualizer = strcmp(mode, plugin->music) == 0;
    int in_pipe[2] = {-1, -1}, out_pipe[2] = {-1, -1}, audio_pipe[2] = {-1, -1};
    if (pipe2(in_pipe, O_CLOEXEC) != 0 || pipe2(out_pipe, O_CLOEXEC) != 0 ||
        (visualizer && pipe2(audio_pipe, O_CLOEXEC) != 0)) {
        qio_log("plugin: pipe: %s", strerror(errno));
        goto fail;
    }
    char fps[16], brightness[16], duration[24];
    snprintf(fps, sizeof(fps), "%u", animation ? 30U : (visualizer ? 30U : 1U));
    snprintf(brightness, sizeof(brightness), "64");
    snprintf(duration, sizeof(duration), "%u", animation ? animation_duration(mode) : 0U);
    char *argv[20];
    size_t used = 0;
    argv[used++] = plugin->binary;
    argv[used++] = (char *)mode;
    argv[used++] = "--screen"; argv[used++] = (char *)app->screen.path;
    argv[used++] = "--ring"; argv[used++] = (char *)app->ring.path;
    argv[used++] = "--fps"; argv[used++] = fps;
    argv[used++] = "--brightness"; argv[used++] = brightness;
    argv[used++] = "--duration-ms"; argv[used++] = duration;
    if (visualizer) { argv[used++] = "--audio-fd"; argv[used++] = "3"; }
    if (!animation) argv[used++] = "--no-ring";
    argv[used] = NULL;
    char *envp[PLUGIN_ENV_MAX];
    size_t env_used = 0;
    for (char **e = environ; *e != NULL; ++e) {
        if (strncmp(*e, "LD_PRELOAD=", 11) == 0 || strncmp(*e, "LD_LIBRARY_PATH=", 16) == 0)
            continue;
        if (env_used + 1 >= PLUGIN_ENV_MAX) {
            qio_log("plugin: environment too large; refusing launch");
            goto fail;
        }
        envp[env_used++] = *e;
    }
    envp[env_used] = NULL;
    pid_t parent = getpid();
    pid_t child = fork();
    if (child < 0) {
        qio_log("plugin: fork: %s", strerror(errno));
        goto fail;
    }
    if (child == 0) {
        (void)setpgid(0, 0);
        (void)prctl(PR_SET_PDEATHSIG, SIGTERM);
        if (getppid() != parent) _exit(1);
        struct rlimit limit = {0, 0};
        (void)setrlimit(RLIMIT_CORE, &limit);
        limit.rlim_cur = limit.rlim_max = 32U * 1024U * 1024U;
        (void)setrlimit(RLIMIT_AS, &limit);
        limit.rlim_cur = limit.rlim_max = 32;
        (void)setrlimit(RLIMIT_NOFILE, &limit);
        if (dup2(in_pipe[0], STDIN_FILENO) < 0 || dup2(out_pipe[1], STDOUT_FILENO) < 0 ||
            (visualizer && dup2(audio_pipe[0], 3) < 0))
            _exit(126);
        (void)fcntl(STDIN_FILENO, F_SETFD, 0);
        (void)fcntl(STDOUT_FILENO, F_SETFD, 0);
        if (visualizer) (void)fcntl(3, F_SETFD, 0);
        /* Close every inherited descriptor except stdio and the visualizer PCM fd. */
        for (int fd = 3; fd < 128; ++fd) {
            if (visualizer && fd == 3) continue;
            close(fd);
        }
        execve(plugin->binary, argv, envp);
        _exit(127);
    }
    (void)setpgid(child, 0);
    close(in_pipe[0]); in_pipe[0] = -1;
    close(out_pipe[1]); out_pipe[1] = -1;
    if (visualizer) { close(audio_pipe[0]); audio_pipe[0] = -1; }
    if (set_nonblock_cloexec(in_pipe[1]) != 0 || set_nonblock_cloexec(out_pipe[0]) != 0 ||
        (visualizer && set_nonblock_cloexec(audio_pipe[1]) != 0)) {
        close(in_pipe[1]); close(out_pipe[0]);
        if (visualizer) close(audio_pipe[1]);
        (void)kill(child, SIGKILL);
        (void)waitpid(child, NULL, 0);
        plugin->next_launch_ms = now + 1000;
        return;
    }
    plugin->child = child;
    plugin->child_stdin = in_pipe[1];
    plugin->child_stdout = out_pipe[0];
    snprintf(plugin->child_mode, sizeof(plugin->child_mode), "%s", mode);
    plugin->child_is_animation = animation;
    plugin->kill_deadline_ms = 0;
    plugin->child_deadline_ms = animation ? now + animation_duration(mode) + 500 : 0;
    if (visualizer) app->audio.mirror_fd = audio_pipe[1];
    else app->audio.mirror_fd = -1;
    ++plugin->launches;
    plugin->next_launch_ms = now + PLUGIN_MIN_LAUNCH_GAP_MS;
    qio_log("plugin: launched %s pid=%ld animation=%d", mode, (long)child, animation);
    return;
fail:
    if (in_pipe[0] >= 0) close(in_pipe[0]);
    if (in_pipe[1] >= 0) close(in_pipe[1]);
    if (out_pipe[0] >= 0) close(out_pipe[0]);
    if (out_pipe[1] >= 0) close(out_pipe[1]);
    if (audio_pipe[0] >= 0) close(audio_pipe[0]);
    if (audio_pipe[1] >= 0) close(audio_pipe[1]);
    plugin->next_launch_ms = now + 1000;
}

static void send_child_line(qio_app *app, const char *line)
{
    qio_plugin *plugin = &app->plugin;
    if (plugin->child_stdin < 0) return;
    size_t length = strlen(line);
    ssize_t count = write(plugin->child_stdin, line, length);
    if (count < 0 && errno != EAGAIN && errno != EINTR) ++plugin->dropped_commands;
    if (count >= 0 && (size_t)count < length) ++plugin->dropped_commands;
}

static bool request_overlay(qio_app *app, const char *name, int64_t now)
{
    qio_plugin *plugin = &app->plugin;
    if (!plugin->loaded || !mode_name_valid(name) || !mode_allowed(plugin, name) ||
        !is_animation_mode(plugin, name)) return false;
    if (plugin->overlay_active && strcmp(plugin->overlay, "music-play") == 0 &&
        strcmp(name, "music-play") != 0 && strcmp(name, "music-pause") != 0)
        return false; /* the play intro must finish before the visualizer */
    if (plugin->overlay_active && strcmp(plugin->overlay, name) == 0) {
        plugin->overlay_deadline_ms = now + animation_duration(name);
        return true;
    }
    snprintf(plugin->overlay, sizeof(plugin->overlay), "%s", name);
    plugin->overlay_active = true;
    plugin->overlay_deadline_ms = now + animation_duration(name);
    if (plugin->child > 0) stop_child(app, now);
    return true;
}

void qio_plugin_button(qio_app *app, const char *event)
{
    qio_plugin *plugin = &app->plugin;
    if (!plugin->loaded) return;
    int64_t now = qio_now_ms();
    if (now < 0) return;
    char line[128];
    int n = snprintf(line, sizeof(line), "button %s\n", event);
    if (n > 0 && (size_t)n < sizeof(line)) send_child_line(app, line);
    if (strcmp(event, "volume_up") == 0) request_overlay(app, "volume-up", now);
    else if (strcmp(event, "volume_down") == 0) request_overlay(app, "volume-down", now);
    else if (strcmp(event, "microphone_mute_off") == 0) request_overlay(app, "mic-on", now);
    else if (strcmp(event, "microphone_mute_on") == 0) request_overlay(app, "mic-off", now);
}

bool qio_plugin_music_command(qio_app *app, const char *state, int64_t now)
{
    qio_plugin *plugin = &app->plugin;
    if (!plugin->loaded) return false;
    if (strcmp(state, "auto") == 0) {
        plugin->explicit_music = false;
        return true;
    }
    enum qio_plugin_music_state next;
    if (strcmp(state, "playing") == 0) next = QIO_PLUGIN_MUSIC_PLAYING;
    else if (strcmp(state, "paused") == 0) next = QIO_PLUGIN_MUSIC_PAUSED;
    else if (strcmp(state, "stopped") == 0 || strcmp(state, "idle") == 0)
        next = QIO_PLUGIN_MUSIC_STOPPED;
    else return false;
    plugin->explicit_music = true;
    if (next == plugin->music_state) return true;
    plugin->music_state = next;
    if (next == QIO_PLUGIN_MUSIC_PLAYING) request_overlay(app, "music-play", now);
    else if (next == QIO_PLUGIN_MUSIC_PAUSED) request_overlay(app, "music-pause", now);
    else {
        plugin->overlay_active = false;
        plugin->overlay[0] = '\0';
        if (plugin->child > 0) stop_child(app, now);
    }
    return true;
}

bool qio_plugin_animation_command(qio_app *app, const char *name, int64_t now)
{
    return request_overlay(app, name, now);
}

static void update_auto_music(qio_app *app, int64_t now)
{
    qio_plugin *plugin = &app->plugin;
    if (plugin->explicit_music || app->options.no_audio) return;
    bool playing = app->audio.pcm_fd >= 0 && app->audio.last_sound_ms != 0 &&
                   now - app->audio.last_sound_ms < PLUGIN_MUSIC_TIMEOUT_MS;
    enum qio_plugin_music_state next = playing ? QIO_PLUGIN_MUSIC_PLAYING : QIO_PLUGIN_MUSIC_STOPPED;
    if (next == plugin->music_state) return;
    plugin->music_state = next;
    if (next == QIO_PLUGIN_MUSIC_PLAYING) request_overlay(app, "music-play", now);
    else {
        if (plugin->overlay_active && strcmp(plugin->overlay, "music-play") == 0) {
            plugin->overlay_active = false;
            plugin->overlay[0] = '\0';
        }
        if (plugin->child > 0) stop_child(app, now);
    }
}

static const char *desired_mode(qio_app *app, int64_t now)
{
    qio_plugin *plugin = &app->plugin;
    if (plugin->overlay_active) {
        if (now >= plugin->overlay_deadline_ms) {
            plugin->overlay_active = false;
            plugin->overlay[0] = '\0';
        } else return plugin->overlay;
    }
    return plugin->music_state == QIO_PLUGIN_MUSIC_PLAYING ? plugin->music : plugin->idle;
}

void qio_plugin_tick(qio_app *app, int64_t now)
{
    qio_plugin *plugin = &app->plugin;
    if (!plugin->loaded) return;
    reap_child(app, now);
    update_auto_music(app, now);
    if (plugin->child > 0 && plugin->child_deadline_ms != 0 && now >= plugin->child_deadline_ms)
        stop_child(app, now);
    if (plugin->kill_deadline_ms != 0 && now >= plugin->kill_deadline_ms)
        stop_child(app, now);
    const char *mode = desired_mode(app, now);
    if (plugin->child > 0) {
        if (strcmp(plugin->child_mode, mode) != 0) stop_child(app, now);
        return;
    }
    launch_child(app, mode, now);
}

short qio_plugin_events(const qio_app *app)
{
    return app->plugin.child_stdout >= 0 ? POLLIN : 0;
}

/* The plugin draws fb4 and fb5 itself. Its stdout is only diagnostics: drain a
 * bounded amount so a chatty child can never block or grow daemon memory. */
void qio_plugin_stdout_event(qio_app *app, short revents, int64_t now)
{
    (void)now;
    qio_plugin *plugin = &app->plugin;
    if (plugin->child_stdout < 0) return;
    if ((revents & POLLNVAL) != 0) {
        close_child_pipes(plugin);
        return;
    }
    if ((revents & POLLIN) != 0) {
        uint8_t buffer[256];
        ssize_t count = read(plugin->child_stdout, buffer, sizeof(buffer));
        if (count < 0 && (errno == EAGAIN || errno == EINTR)) return;
        if (count <= 0) {
            close_child_pipes(plugin);
            return;
        }
        plugin->stdout_bytes += (uint64_t)count;
    }
    if ((revents & (POLLERR | POLLHUP)) != 0)
        close_child_pipes(plugin);
}

int qio_plugin_status(qio_plugin *plugin, char *buffer, size_t capacity)
{
    const char *music = plugin->music_state == QIO_PLUGIN_MUSIC_PLAYING ? "playing" :
                        plugin->music_state == QIO_PLUGIN_MUSIC_PAUSED ? "paused" : "stopped";
    int n = snprintf(buffer, capacity,
        "{\"enabled\":%s,\"loaded\":%s,\"child_pid\":%ld,\"child_mode\":\"%s\","
        "\"music_state\":\"%s\",\"explicit_music\":%s,\"overlay\":\"%s\","
        "\"launches\":%" PRIu64 ",\"stdout_bytes\":%" PRIu64 ","
        "\"dropped_commands\":%" PRIu64 "}",
        plugin->enabled ? "true" : "false", plugin->loaded ? "true" : "false",
        (long)(plugin->child > 0 ? plugin->child : 0), plugin->child_mode,
        music, plugin->explicit_music ? "true" : "false",
        plugin->overlay_active ? plugin->overlay : "",
        plugin->launches, plugin->stdout_bytes, plugin->dropped_commands);
    return n < 0 || (size_t)n >= capacity ? -1 : n;
}

void qio_plugin_close(qio_app *app)
{
    qio_plugin *plugin = &app->plugin;
    qio_close_fd(&app->audio.mirror_fd);
    if (plugin->child > 0) {
        (void)kill(-plugin->child, SIGTERM);
        (void)kill(plugin->child, SIGTERM);
        int64_t deadline = qio_now_ms() + PLUGIN_CHILD_GRACE_MS;
        for (;;) {
            int status = 0;
            pid_t result = waitpid(plugin->child, &status, WNOHANG);
            if (result == plugin->child || result < 0) break;
            int64_t now = qio_now_ms();
            if (now >= deadline) {
                (void)kill(-plugin->child, SIGKILL);
                (void)kill(plugin->child, SIGKILL);
                (void)waitpid(plugin->child, &status, 0);
                break;
            }
            struct pollfd fd = {.fd = plugin->child_stdout, .events = POLLIN, .revents = 0};
            if (fd.fd >= 0) (void)poll(&fd, 1, 20);
            else {
                struct timespec ts = {.tv_sec = 0, .tv_nsec = 20 * 1000000};
                (void)nanosleep(&ts, NULL);
            }
        }
        plugin->child = -1;
    }
    close_child_pipes(plugin);
    plugin->loaded = false;
}
