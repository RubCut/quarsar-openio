/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef QUASAR_PACK_H
#define QUASAR_PACK_H

#include "quasar_openio.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define PACK_VERSION "0.2.0"
#define PACK_IN_MAX 256
#define PACK_HISTORY 1024
#define PACK_VIS_WINDOW 512
#define PACK_STARS 24
#define PACK_DROPS QIO_SCREEN_WIDTH

enum pack_mode { PACK_CLOCK, PACK_VISUALIZER, PACK_ANIMATION };
enum pack_anim {
    PACK_ANIM_PLASMA, PACK_ANIM_RAINBOW, PACK_ANIM_STARFIELD,
    PACK_ANIM_PULSE, PACK_ANIM_RAIN,
    PACK_ANIM_MUSIC_PLAY, PACK_ANIM_MUSIC_PAUSE,
    PACK_ANIM_MIC_ON, PACK_ANIM_MIC_OFF,
    PACK_ANIM_VOLUME_UP, PACK_ANIM_VOLUME_DOWN, PACK_ANIM_VOLUME_RING,
    PACK_ANIM_COUNT
};

typedef struct {
    const char *screen_path;
    const char *ring_path;
    enum pack_mode mode;
    enum pack_anim animation;
    int fps;
    unsigned brightness;
    int sensitivity;
    int64_t duration_ms;
    int audio_fd;
    bool no_ring;
    bool clear_on_exit;
    unsigned level;
} pack_options;

typedef struct {
    int in_fd;
    uint8_t in[PACK_IN_MAX];
    size_t in_used;
    bool in_closed;
    uint64_t button_lines;
} pack_host;

typedef struct {
    int fd;
    bool eof;
    uint8_t carry[4];
    size_t carry_used;
    int16_t mono[PACK_HISTORY];
    size_t mono_pos;
    size_t mono_count;
} pack_audio;

typedef struct {
    uint32_t rng;
    struct { uint8_t x, y, speed; } stars[PACK_STARS];
    uint8_t drops[PACK_DROPS];
    uint8_t drop_speed[PACK_DROPS];
} pack_anim_state;

typedef struct {
    pack_options options;
    pack_host host;
    pack_audio audio;
    qio_fb screen;
    qio_fb ring;
    uint8_t screen_pixels[QIO_SCREEN_BYTES];
    uint8_t ring_pixels[QIO_RING_BYTES];
    bool screen_dirty;
    bool ring_dirty;
    int64_t start_ms;
    int64_t next_frame_ms;
    int64_t next_screen_draw_ms;
    int64_t next_ring_draw_ms;
    uint64_t frames;
    int last_second;
    pack_anim_state anim;
} pack_state;

/* render.c */
uint8_t pack_scale(uint8_t value, unsigned brightness);
void pack_pixel(uint8_t *screen, int x, int y, uint8_t r, uint8_t g, uint8_t b);
void pack_screen_clear(pack_state *state);
void pack_ring_clear(pack_state *state);
uint32_t pack_tri(uint32_t x);
void pack_wheel(uint8_t position, uint8_t rgb[3]);
void pack_render_clock(pack_state *state, int64_t now_ms);
void pack_render_visualizer(pack_state *state);
void pack_render_animation(pack_state *state, int64_t now_ms);
void pack_visualizer_feed(pack_state *state, const uint8_t *data, size_t length);

/* host.c: stdin button lines from the supervisor. No daemon draw API at all:
 * this binary maps fb4 and fb5 itself and blits both through the shared
 * stride/geometry validator. */
void pack_host_init(pack_state *state);
short pack_host_in_events(const pack_state *state);
void pack_host_event(pack_state *state, short revents);
void pack_host_close(pack_state *state);

/* audio.c: raw PCM from supervisor pipe fd. */
void pack_audio_init(pack_state *state);
short pack_audio_events(const pack_state *state);
void pack_audio_event(pack_state *state, short revents);
void pack_audio_close(pack_state *state);

#endif
