/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_pack.h"
#include <string.h>

static int16_t read_s16le(const uint8_t *p)
{
    return (int16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

void pack_visualizer_feed(pack_state *state, const uint8_t *data, size_t length)
{
    pack_audio *audio = &state->audio;
    if (audio->carry_used >= sizeof(audio->carry)) return; /* fail closed on corrupted state */
    while (length != 0) {
        size_t need = 4U - audio->carry_used;
        size_t take = length < need ? length : need;
        memcpy(audio->carry + audio->carry_used, data, take);
        audio->carry_used += take;
        data += take;
        length -= take;
        if (audio->carry_used == 4U) {
            int32_t left = read_s16le(audio->carry);
            int32_t right = read_s16le(audio->carry + 2);
            audio->mono[audio->mono_pos] = (int16_t)((left + right) / 2);
            audio->mono_pos = (audio->mono_pos + 1U) % PACK_HISTORY;
            if (audio->mono_count < PACK_HISTORY)
                ++audio->mono_count;
            audio->carry_used = 0;
        }
    }
}

static int clamp_amplitude(int64_t value)
{
    if (value > 7) return 7;
    if (value < -7) return -7;
    return (int)value;
}

static void render_idle(pack_state *state)
{
    pack_screen_clear(state);
    uint8_t dim = pack_scale(48, state->options.brightness);
    for (int x = 0; x < QIO_SCREEN_WIDTH; ++x)
        pack_pixel(state->screen_pixels, x, QIO_SCREEN_HEIGHT / 2, dim, dim, dim);
    state->screen_dirty = true;
}

void pack_render_visualizer(pack_state *state)
{
    pack_audio *audio = &state->audio;
    if (audio->mono_count < 25) {
        render_idle(state);
        return;
    }
    int16_t window[PACK_VIS_WINDOW];
    size_t n = audio->mono_count < PACK_VIS_WINDOW ? audio->mono_count : PACK_VIS_WINDOW;
    size_t start = (audio->mono_pos + PACK_HISTORY - n) % PACK_HISTORY;
    int32_t peak = 0;
    for (size_t i = 0; i < n; ++i) {
        window[i] = audio->mono[(start + i) % PACK_HISTORY];
        int32_t value = window[i] < 0 ? -window[i] : window[i];
        if (value > peak) peak = value;
    }
    if (peak < 32) {
        render_idle(state);
        return;
    }
    size_t trigger = 0;
    for (size_t i = 0; i + 1 < n / 2; ++i) {
        if (window[i] <= 0 && window[i + 1] > 0) {
            trigger = i;
            break;
        }
    }
    size_t available = n - trigger;
    if (available < QIO_SCREEN_WIDTH) {
        trigger = 0;
        available = n;
    }
    int64_t scale = ((int64_t)7 * 32768) / peak;
    int sensitivity = state->options.sensitivity;
    uint8_t level = pack_scale(255, state->options.brightness);
    uint8_t dim = pack_scale(32, state->options.brightness);
    pack_screen_clear(state);
    for (int x = 0; x < QIO_SCREEN_WIDTH; ++x)
        pack_pixel(state->screen_pixels, x, QIO_SCREEN_HEIGHT / 2, dim, dim, dim);

    for (int x = 0; x < QIO_SCREEN_WIDTH; ++x) {
        size_t a = trigger + ((size_t)x * available) / QIO_SCREEN_WIDTH;
        size_t b = trigger + (((size_t)x + 1U) * available) / QIO_SCREEN_WIDTH;
        if (b <= a) b = a + 1;
        if (b > n) b = n;
        int32_t min = window[a];
        int32_t max = window[a];
        for (size_t i = a + 1; i < b; ++i) {
            if (window[i] < min) min = window[i];
            if (window[i] > max) max = window[i];
        }
        int top = QIO_SCREEN_HEIGHT / 2 -
                  clamp_amplitude(((int64_t)max * scale * sensitivity) / 32768);
        int bottom = QIO_SCREEN_HEIGHT / 2 -
                     clamp_amplitude(((int64_t)min * scale * sensitivity) / 32768);
        if (top > bottom) {
            int swap = top;
            top = bottom;
            bottom = swap;
        }
        for (int y = top; y <= bottom; ++y)
            pack_pixel(state->screen_pixels, x, y, level, level, level);
    }

    int64_t ring_level = ((int64_t)peak * sensitivity * QIO_RING_WIDTH) / 32768;
    if (ring_level > QIO_RING_WIDTH) ring_level = QIO_RING_WIDTH;
    if (ring_level < 0) ring_level = 0;
    (void)ring_level;
    state->screen_dirty = true;
}
