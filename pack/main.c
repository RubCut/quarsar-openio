/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_pack.h"
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static pack_state state;

static void usage(FILE *out)
{
    fprintf(out,
        "quasar_pack %s - standard plugin binary for quasar_openio\n"
        "Usage: quasar_pack MODE [options]\n"
        "Modes: clock, visualizer,\n"
        "       music-play, music-pause, mic-on, mic-off,\n"
        "       volume-up, volume-down, volume-ring,\n"
        "       plasma, rainbow, starfield, pulse, rain\n"
        "This binary draws the screen AND the ring itself through validated\n"
        "framebuffer mmap. It never asks quasar_openio to draw anything.\n"
        "The ring is used only by animations and cleared when an animation ends.\n"
        "  --screen PATH         screen framebuffer (default %s)\n"
        "  --ring PATH           ring framebuffer (default %s)\n"
        "  --fps N               1..58 (default clock=1, others=30)\n"
        "  --brightness N        0..255 (default 64)\n"
        "  --sensitivity N       visualizer gain 1..10 (default 4)\n"
        "  --duration-ms N       exit after N ms, 0=forever (default 0)\n"
        "  --audio-fd N          raw PCM pipe fd for visualizer (default -1)\n"
        "  --level N             volume-ring level 0..100 (default 50)\n"
        "  --no-ring             never open the ring (forced for clock/visualizer)\n"
        "  --clear-on-exit       also blank the screen on clean shutdown\n"
        "  --help | --version\n", PACK_VERSION, QIO_SCREEN_PATH, QIO_RING_PATH);
}

static int parse_int(const char *text, long min, long max, long *out)
{
    char *end;
    errno = 0;
    long value = strtol(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' || value < min || value > max)
        return -1;
    *out = value;
    return 0;
}

static int animation_from_name(const char *name, enum pack_anim *animation)
{
    if (strcmp(name, "plasma") == 0) *animation = PACK_ANIM_PLASMA;
    else if (strcmp(name, "rainbow") == 0) *animation = PACK_ANIM_RAINBOW;
    else if (strcmp(name, "starfield") == 0) *animation = PACK_ANIM_STARFIELD;
    else if (strcmp(name, "pulse") == 0) *animation = PACK_ANIM_PULSE;
    else if (strcmp(name, "rain") == 0) *animation = PACK_ANIM_RAIN;
    else if (strcmp(name, "music-play") == 0) *animation = PACK_ANIM_MUSIC_PLAY;
    else if (strcmp(name, "music-pause") == 0) *animation = PACK_ANIM_MUSIC_PAUSE;
    else if (strcmp(name, "mic-on") == 0) *animation = PACK_ANIM_MIC_ON;
    else if (strcmp(name, "mic-off") == 0) *animation = PACK_ANIM_MIC_OFF;
    else if (strcmp(name, "volume-up") == 0) *animation = PACK_ANIM_VOLUME_UP;
    else if (strcmp(name, "volume-down") == 0) *animation = PACK_ANIM_VOLUME_DOWN;
    else if (strcmp(name, "volume-ring") == 0) *animation = PACK_ANIM_VOLUME_RING;
    else return -1;
    return 0;
}

static int parse_options(int argc, char **argv)
{
    pack_options *options = &state.options;
    memset(options, 0, sizeof(*options));
    options->screen_path = QIO_SCREEN_PATH;
    options->ring_path = QIO_RING_PATH;
    options->brightness = 64;
    options->sensitivity = 4;
    options->audio_fd = -1;
    options->level = 50;
    options->mode = PACK_CLOCK;
    if (argc < 2) { usage(stderr); return -1; }
    const char *mode = argv[1];
    if (strcmp(mode, "clock") == 0) options->mode = PACK_CLOCK;
    else if (strcmp(mode, "visualizer") == 0) options->mode = PACK_VISUALIZER;
    else if (animation_from_name(mode, &options->animation) == 0) options->mode = PACK_ANIMATION;
    else if (strcmp(mode, "--help") == 0 || strcmp(mode, "-h") == 0) { usage(stdout); return 1; }
    else if (strcmp(mode, "--version") == 0) { puts(PACK_VERSION); return 1; }
    else { usage(stderr); return -1; }
    for (int i = 2; i < argc; ++i) {
        const char *arg = argv[i];
        long value;
        if (strcmp(arg, "--help") == 0 || strcmp(arg, "-h") == 0) { usage(stdout); return 1; }
        if (strcmp(arg, "--version") == 0) { puts(PACK_VERSION); return 1; }
        if (strcmp(arg, "--no-ring") == 0) { options->no_ring = true; continue; }
        if (strcmp(arg, "--clear-on-exit") == 0) { options->clear_on_exit = true; continue; }
        if (i + 1 >= argc) { usage(stderr); return -1; }
        const char *text = argv[++i];
        if (strcmp(arg, "--screen") == 0) options->screen_path = text;
        else if (strcmp(arg, "--ring") == 0) options->ring_path = text;
        else if (strcmp(arg, "--fps") == 0) {
            if (parse_int(text, 1, 58, &value) != 0) return -1;
            options->fps = (int)value;
        } else if (strcmp(arg, "--brightness") == 0) {
            if (parse_int(text, 0, 255, &value) != 0) return -1;
            options->brightness = (unsigned)value;
        } else if (strcmp(arg, "--sensitivity") == 0) {
            if (parse_int(text, 1, 10, &value) != 0) return -1;
            options->sensitivity = (int)value;
        } else if (strcmp(arg, "--duration-ms") == 0) {
            if (parse_int(text, 0, 86400000, &value) != 0) return -1;
            options->duration_ms = value;
        } else if (strcmp(arg, "--audio-fd") == 0) {
            if (parse_int(text, -1, 64, &value) != 0) return -1;
            options->audio_fd = (int)value;
        } else if (strcmp(arg, "--level") == 0) {
            if (parse_int(text, 0, 100, &value) != 0) return -1;
            options->level = (unsigned)value;
        } else { usage(stderr); return -1; }
    }
    if (options->fps == 0) options->fps = options->mode == PACK_CLOCK ? 1 : 30;
    /* Idle content and the visualizer must never touch the ring. */
    if (options->mode != PACK_ANIMATION) options->no_ring = true;
    return 0;
}

static void consider_timeout(int64_t *timeout, int64_t deadline, int64_t now)
{
    if (deadline <= 0) return;
    int64_t remaining = deadline > now ? deadline - now : 0;
    if (*timeout < 0 || remaining < *timeout) *timeout = remaining;
}

static bool ring_enabled(void)
{
    return state.options.mode == PACK_ANIMATION && !state.options.no_ring;
}

static void blit_screen(int64_t now)
{
    if (!state.screen_dirty || now < state.next_screen_draw_ms) return;
    if (qio_fb_open(&state.screen, now) != 0) return;
    qio_fb_blit(state.screen.mapping, &state.screen, state.screen_pixels);
    state.screen_dirty = false;
    state.next_screen_draw_ms = now + QIO_FRAME_INTERVAL_MS;
    ++state.screen.frames;
}

static void blit_ring(int64_t now)
{
    if (!ring_enabled() || !state.ring_dirty || now < state.next_ring_draw_ms) return;
    if (qio_fb_open(&state.ring, now) != 0) return;
    qio_fb_blit(state.ring.mapping, &state.ring, state.ring_pixels);
    state.ring_dirty = false;
    state.next_ring_draw_ms = now + QIO_FRAME_INTERVAL_MS;
    ++state.ring.frames;
}

static void render_frame(int64_t now)
{
    switch (state.options.mode) {
    case PACK_CLOCK: pack_render_clock(&state, now); break;
    case PACK_VISUALIZER: pack_render_visualizer(&state); break;
    case PACK_ANIMATION: pack_render_animation(&state, now); break;
    }
    blit_screen(now);
    blit_ring(now);
    state.ring_dirty = false;
}

static int run(void)
{
    int interval = 1000 / state.options.fps;
    if (interval < 1) interval = 1;
    state.start_ms = qio_now_ms();
    state.next_frame_ms = state.start_ms;
    state.last_second = -1;
    while (!qio_stopping()) {
        int64_t now = qio_now_ms();
        if (now < 0) return 1;
        if (state.options.duration_ms > 0 && now - state.start_ms >= state.options.duration_ms)
            break;
        if (now >= state.next_frame_ms) {
            render_frame(now);
            ++state.frames;
            state.next_frame_ms += interval;
            if (state.next_frame_ms <= now) state.next_frame_ms = now + interval;
        }
        struct pollfd fds[3];
        size_t used = 0;
        fds[used].fd = qio_signal_fd(); fds[used].events = POLLIN; ++used;
        size_t host_in_slot = (size_t)-1;
        short in_events = pack_host_in_events(&state);
        if (in_events != 0) {
            host_in_slot = used;
            fds[used].fd = state.host.in_fd; fds[used].events = in_events; ++used;
        }
        size_t audio_slot = (size_t)-1;
        if (state.options.mode == PACK_VISUALIZER && state.audio.fd >= 0 && !state.audio.eof) {
            audio_slot = used;
            fds[used].fd = state.audio.fd; fds[used].events = POLLIN; ++used;
        }
        int64_t timeout = state.next_frame_ms - now;
        if (state.screen_dirty)
            consider_timeout(&timeout, state.next_screen_draw_ms, now);
        if (ring_enabled() && state.ring_dirty)
            consider_timeout(&timeout, state.next_ring_draw_ms, now);
        if (state.options.duration_ms > 0)
            consider_timeout(&timeout, state.start_ms + state.options.duration_ms, now);
        int wait = timeout < 0 ? 0 : (timeout > 1000 ? 1000 : (int)timeout);
        int ready = poll(fds, (nfds_t)used, wait);
        if (ready < 0) {
            if (errno == EINTR) continue;
            qio_log("pack: poll: %s", strerror(errno));
            return 1;
        }
        now = qio_now_ms();
        if (now < 0) return 1;
        if ((fds[0].revents & POLLIN) != 0) qio_signals_drain();
        if (host_in_slot != (size_t)-1) pack_host_event(&state, fds[host_in_slot].revents);
        if (audio_slot != (size_t)-1) pack_audio_event(&state, fds[audio_slot].revents);
    }
    return 0;
}

int main(int argc, char **argv)
{
    int parsed = parse_options(argc, argv);
    if (parsed != 0) return parsed > 0 ? 0 : 2;
    qio_fb_init(&state.screen, state.options.screen_path, QIO_SCREEN_WIDTH, QIO_SCREEN_HEIGHT);
    qio_fb_init(&state.ring, state.options.ring_path, QIO_RING_WIDTH, QIO_RING_HEIGHT);
    if (qio_signals_install() != 0) {
        qio_log("pack: signal setup: %s", strerror(errno));
        return 1;
    }
    pack_host_init(&state);
    pack_audio_init(&state);
    qio_log("pack: started mode=%d fps=%d brightness=%u screen=%s ring=%s",
            (int)state.options.mode, state.options.fps, state.options.brightness,
            state.options.screen_path, ring_enabled() ? state.options.ring_path : "off");
    int result = run();
    /* An animation owns the ring only while it runs: always leave it dark. */
    if (ring_enabled() && state.ring.mapping != NULL) {
        pack_ring_clear(&state);
        qio_fb_blit(state.ring.mapping, &state.ring, state.ring_pixels);
    }
    if (state.options.clear_on_exit && state.screen.mapping != NULL) {
        pack_screen_clear(&state);
        qio_fb_blit(state.screen.mapping, &state.screen, state.screen_pixels);
    }
    pack_host_close(&state);
    pack_audio_close(&state);
    qio_fb_close(&state.screen);
    qio_fb_close(&state.ring);
    qio_signals_close();
    qio_log("pack: stopped");
    return result;
}
