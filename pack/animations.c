/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_pack.h"
#include <string.h>

static uint32_t random_next(uint32_t *rng)
{
    *rng = *rng * 1664525U + 1013904223U;
    return *rng;
}

static void anim_init(pack_state *state)
{
    if (state->anim.rng != 0) return;
    state->anim.rng = 0x12345678U;
    for (size_t i = 0; i < PACK_STARS; ++i) {
        uint32_t r = random_next(&state->anim.rng);
        state->anim.stars[i].x = (uint8_t)(r % QIO_SCREEN_WIDTH);
        state->anim.stars[i].y = (uint8_t)((r >> 8) % QIO_SCREEN_HEIGHT);
        state->anim.stars[i].speed = (uint8_t)(1U + ((r >> 16) % 3U));
    }
    for (size_t i = 0; i < PACK_DROPS; ++i) {
        uint32_t r = random_next(&state->anim.rng);
        state->anim.drops[i] = (uint8_t)(r % (QIO_SCREEN_HEIGHT + 4U));
        state->anim.drop_speed[i] = (uint8_t)(1U + ((r >> 8) % 3U));
    }
}

static void ring_fill(pack_state *state, uint8_t r, uint8_t g, uint8_t b, unsigned lit)
{
    pack_ring_clear(state);
    if (lit > QIO_RING_WIDTH) lit = QIO_RING_WIDTH;
    for (unsigned i = 0; i < lit; ++i) {
        state->ring_pixels[i * 3] = pack_scale(r, state->options.brightness);
        state->ring_pixels[i * 3 + 1] = pack_scale(g, state->options.brightness);
        state->ring_pixels[i * 3 + 2] = pack_scale(b, state->options.brightness);
    }
}

static void plasma(pack_state *state, uint32_t t)
{
    for (int y = 0; y < QIO_SCREEN_HEIGHT; ++y) {
        for (int x = 0; x < QIO_SCREEN_WIDTH; ++x) {
            uint32_t v = (pack_tri((uint32_t)x * 16U + t * 3U) +
                          pack_tri((uint32_t)y * 20U + t * 5U) +
                          pack_tri((uint32_t)(x + y) * 12U + t * 2U)) / 3U;
            uint8_t rgb[3];
            pack_wheel((uint8_t)v, rgb);
            pack_pixel(state->screen_pixels, x, y,
                       pack_scale(rgb[0], state->options.brightness),
                       pack_scale(rgb[1], state->options.brightness),
                       pack_scale(rgb[2], state->options.brightness));
        }
    }
    for (unsigned i = 0; i < QIO_RING_WIDTH; ++i) {
        uint8_t rgb[3];
        pack_wheel((uint8_t)(i * 10U + t * 4U), rgb);
        for (unsigned c = 0; c < 3; ++c)
            state->ring_pixels[i * 3 + c] = pack_scale(rgb[c], state->options.brightness);
    }
}

static void rainbow(pack_state *state, uint32_t t)
{
    for (int x = 0; x < QIO_SCREEN_WIDTH; ++x) {
        uint8_t rgb[3];
        pack_wheel((uint8_t)((uint32_t)x * 10U + t * 4U), rgb);
        for (int y = 0; y < QIO_SCREEN_HEIGHT; ++y)
            pack_pixel(state->screen_pixels, x, y,
                       pack_scale(rgb[0], state->options.brightness),
                       pack_scale(rgb[1], state->options.brightness),
                       pack_scale(rgb[2], state->options.brightness));
    }
    for (unsigned i = 0; i < QIO_RING_WIDTH; ++i) {
        uint8_t rgb[3];
        pack_wheel((uint8_t)(i * 10U + t * 4U), rgb);
        for (unsigned c = 0; c < 3; ++c)
            state->ring_pixels[i * 3 + c] = pack_scale(rgb[c], state->options.brightness);
    }
}

static void starfield(pack_state *state, uint32_t t)
{
    anim_init(state);
    pack_screen_clear(state);
    for (size_t i = 0; i < PACK_STARS; ++i) {
        state->anim.stars[i].y = (uint8_t)(state->anim.stars[i].y + state->anim.stars[i].speed);
        if (state->anim.stars[i].y >= QIO_SCREEN_HEIGHT) {
            uint32_t r = random_next(&state->anim.rng);
            state->anim.stars[i].x = (uint8_t)(r % QIO_SCREEN_WIDTH);
            state->anim.stars[i].y = 0;
            state->anim.stars[i].speed = (uint8_t)(1U + ((r >> 8) % 3U));
        }
        uint8_t level = pack_scale((uint8_t)(120U + state->anim.stars[i].speed * 45U),
                                   state->options.brightness);
        pack_pixel(state->screen_pixels, state->anim.stars[i].x, state->anim.stars[i].y,
                   level, level, level);
        pack_pixel(state->screen_pixels, state->anim.stars[i].x, state->anim.stars[i].y - 1,
                   pack_scale(40, state->options.brightness),
                   pack_scale(40, state->options.brightness),
                   pack_scale(40, state->options.brightness));
    }
    for (unsigned i = 0; i < QIO_RING_WIDTH; ++i) {
        uint8_t level = ((t + i * 7U) % 24U) < 2U ?
                        pack_scale(160, state->options.brightness) : 0;
        state->ring_pixels[i * 3] = level;
        state->ring_pixels[i * 3 + 1] = level;
        state->ring_pixels[i * 3 + 2] = level;
    }
}

static void pulse(pack_state *state, uint32_t t)
{
    for (int y = 0; y < QIO_SCREEN_HEIGHT; ++y) {
        for (int x = 0; x < QIO_SCREEN_WIDTH; ++x) {
            int dx = x - QIO_SCREEN_WIDTH / 2;
            int dy = y - QIO_SCREEN_HEIGHT / 2;
            uint32_t distance = (uint32_t)(dx * dx + dy * dy);
            uint8_t v = (uint8_t)pack_tri(distance * 8U - t * 10U);
            uint8_t level = pack_scale(v, state->options.brightness);
            pack_pixel(state->screen_pixels, x, y, (uint8_t)(level / 2U), level, level);
        }
    }
    uint8_t breath = (uint8_t)pack_tri(t * 8U);
    uint8_t level = pack_scale(breath, state->options.brightness);
    for (unsigned i = 0; i < QIO_RING_WIDTH; ++i) {
        state->ring_pixels[i * 3] = (uint8_t)(level / 2U);
        state->ring_pixels[i * 3 + 1] = level;
        state->ring_pixels[i * 3 + 2] = level;
    }
}

static void rain(pack_state *state, uint32_t t)
{
    anim_init(state);
    pack_screen_clear(state);
    for (size_t x = 0; x < PACK_DROPS; ++x) {
        state->anim.drops[x] = (uint8_t)(state->anim.drops[x] + state->anim.drop_speed[x]);
        if (state->anim.drops[x] >= QIO_SCREEN_HEIGHT + 4U) {
            uint32_t r = random_next(&state->anim.rng);
            state->anim.drops[x] = 0;
            state->anim.drop_speed[x] = (uint8_t)(1U + ((r >> 8) % 3U));
        }
        for (unsigned trail = 0; trail < 4U; ++trail) {
            int y = (int)state->anim.drops[x] - (int)trail;
            uint8_t level = pack_scale((uint8_t)(255U - trail * 60U), state->options.brightness);
            pack_pixel(state->screen_pixels, (int)x, y, 0,
                       (uint8_t)(level / 2U), level);
        }
    }
    for (unsigned i = 0; i < QIO_RING_WIDTH; ++i) {
        uint8_t level = pack_scale((uint8_t)pack_tri((uint32_t)i * 20U + t * 6U),
                                   state->options.brightness);
        state->ring_pixels[i * 3] = 0;
        state->ring_pixels[i * 3 + 1] = (uint8_t)(level / 2U);
        state->ring_pixels[i * 3 + 2] = level;
    }
}

static void music_play(pack_state *state, uint32_t t)
{
    pack_screen_clear(state);
    uint8_t level = pack_scale(255, state->options.brightness);
    unsigned width = (t % 12U) + 2U;
    for (int y = 3; y < 13; ++y) {
        int half = (int)width - (y > 7 ? y - 7 : 7 - y);
        if (half < 0) continue;
        for (int x = 8; x <= 8 + half; ++x)
            pack_pixel(state->screen_pixels, x, y, 0, level, (uint8_t)(level / 2U));
    }
    ring_fill(state, 0, 255, 128, (t * 2U) % (QIO_RING_WIDTH + 1U));
}

static void music_pause(pack_state *state, uint32_t t)
{
    pack_screen_clear(state);
    uint8_t level = pack_scale((uint8_t)(255U - (t % 16U) * 10U), state->options.brightness);
    for (int y = 3; y < 13; ++y) {
        for (int x = 8; x < 11; ++x) pack_pixel(state->screen_pixels, x, y, level, level, 0);
        for (int x = 14; x < 17; ++x) pack_pixel(state->screen_pixels, x, y, level, level, 0);
    }
    ring_fill(state, 255, 160, 0, QIO_RING_WIDTH - (t % (QIO_RING_WIDTH + 1U)));
}

static void mic(pack_state *state, uint32_t t, bool on)
{
    pack_screen_clear(state);
    uint8_t level = pack_scale(255, state->options.brightness);
    uint8_t r = on ? 0 : level;
    uint8_t g = on ? level : 0;
    for (int y = 3; y < 10; ++y)
        for (int x = 10; x < 15; ++x)
            pack_pixel(state->screen_pixels, x, y, r, g, 0);
    for (int x = 9; x < 16; ++x) pack_pixel(state->screen_pixels, x, 11, r, g, 0);
    for (int y = 11; y < 14; ++y) pack_pixel(state->screen_pixels, 12, y, r, g, 0);
    if (!on)
        for (int i = 0; i < 12; ++i)
            pack_pixel(state->screen_pixels, 6 + i, 2 + i, level, 0, 0);
    unsigned lit = on ? (t % (QIO_RING_WIDTH + 1U)) : QIO_RING_WIDTH - (t % (QIO_RING_WIDTH + 1U));
    ring_fill(state, r, g, 0, lit);
}

static void volume(pack_state *state, uint32_t t, bool up)
{
    pack_screen_clear(state);
    uint8_t level = pack_scale(255, state->options.brightness);
    int cy = up ? 4 : 11;
    int dy = up ? 1 : -1;
    for (int i = 0; i < 6; ++i) {
        int y = cy + dy * i;
        for (int x = 12 - i; x <= 12 + i; ++x)
            pack_pixel(state->screen_pixels, x, y, 0, level, level);
    }
    unsigned lit = up ? (t % (QIO_RING_WIDTH + 1U)) : QIO_RING_WIDTH - (t % (QIO_RING_WIDTH + 1U));
    ring_fill(state, 0, 180, 255, lit);
}

static void volume_ring(pack_state *state, uint32_t t)
{
    pack_screen_clear(state);
    unsigned lit = (state->options.level * QIO_RING_WIDTH) / 100U;
    uint8_t level = pack_scale(200, state->options.brightness);
    for (unsigned x = 0; x < QIO_SCREEN_WIDTH; ++x) {
        int y = QIO_SCREEN_HEIGHT / 2;
        pack_pixel(state->screen_pixels, (int)x, y,
                   (uint8_t)(level / 3U), (uint8_t)(level / 3U), (uint8_t)(level / 3U));
    }
    unsigned pulse_lit = (lit + t) % (QIO_RING_WIDTH + 1U);
    ring_fill(state, 0, 180, 255, pulse_lit > lit ? lit : pulse_lit);
}

void pack_render_animation(pack_state *state, int64_t now_ms)
{
    (void)now_ms;
    uint32_t t = (uint32_t)state->frames;
    pack_screen_clear(state);
    pack_ring_clear(state);
    switch (state->options.animation) {
    case PACK_ANIM_PLASMA: plasma(state, t); break;
    case PACK_ANIM_RAINBOW: rainbow(state, t); break;
    case PACK_ANIM_STARFIELD: starfield(state, t); break;
    case PACK_ANIM_PULSE: pulse(state, t); break;
    case PACK_ANIM_RAIN: rain(state, t); break;
    case PACK_ANIM_MUSIC_PLAY: music_play(state, t); break;
    case PACK_ANIM_MUSIC_PAUSE: music_pause(state, t); break;
    case PACK_ANIM_MIC_ON: mic(state, t, true); break;
    case PACK_ANIM_MIC_OFF: mic(state, t, false); break;
    case PACK_ANIM_VOLUME_UP: volume(state, t, true); break;
    case PACK_ANIM_VOLUME_DOWN: volume(state, t, false); break;
    case PACK_ANIM_VOLUME_RING: volume_ring(state, t); break;
    default: plasma(state, t); break;
    }
    state->screen_dirty = true;
    state->ring_dirty = true;
}
