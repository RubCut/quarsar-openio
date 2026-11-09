/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_pack.h"
#include <string.h>
#include <time.h>

uint8_t pack_scale(uint8_t value, unsigned brightness)
{
    return (uint8_t)(((unsigned)value * brightness) / 255U);
}

void pack_pixel(uint8_t *screen, int x, int y, uint8_t r, uint8_t g, uint8_t b)
{
    if (x < 0 || x >= QIO_SCREEN_WIDTH || y < 0 || y >= QIO_SCREEN_HEIGHT)
        return;
    size_t offset = ((size_t)y * QIO_SCREEN_WIDTH + (size_t)x) * 3;
    screen[offset] = r;
    screen[offset + 1] = g;
    screen[offset + 2] = b;
}

void pack_screen_clear(pack_state *state)
{
    memset(state->screen_pixels, 0, sizeof(state->screen_pixels));
}

void pack_ring_clear(pack_state *state)
{
    memset(state->ring_pixels, 0, sizeof(state->ring_pixels));
}

uint32_t pack_tri(uint32_t x)
{
    x &= 511U;
    return x < 256U ? x : 511U - x;
}

void pack_wheel(uint8_t position, uint8_t rgb[3])
{
    unsigned p = position;
    if (p < 85U) {
        rgb[0] = (uint8_t)(255U - p * 3U);
        rgb[1] = (uint8_t)(p * 3U);
        rgb[2] = 0;
    } else if (p < 170U) {
        p -= 85U;
        rgb[0] = 0;
        rgb[1] = (uint8_t)(255U - p * 3U);
        rgb[2] = (uint8_t)(p * 3U);
    } else {
        p -= 170U;
        rgb[0] = (uint8_t)(p * 3U);
        rgb[1] = 0;
        rgb[2] = (uint8_t)(255U - p * 3U);
    }
}

static const uint8_t digits[10][7] = {
    {0x0e, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0e},
    {0x04, 0x0c, 0x04, 0x04, 0x04, 0x04, 0x0e},
    {0x0e, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1f},
    {0x1f, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0e},
    {0x02, 0x06, 0x0a, 0x12, 0x1f, 0x02, 0x02},
    {0x1f, 0x10, 0x1e, 0x01, 0x01, 0x11, 0x0e},
    {0x06, 0x08, 0x10, 0x1e, 0x11, 0x11, 0x0e},
    {0x1f, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08},
    {0x0e, 0x11, 0x11, 0x0e, 0x11, 0x11, 0x0e},
    {0x0e, 0x11, 0x11, 0x0f, 0x01, 0x02, 0x0c}
};

static void draw_digit(pack_state *state, int x, int y, int digit, uint8_t level)
{
    for (int row = 0; row < 7; ++row) {
        uint8_t bits = digits[digit][row];
        for (int col = 0; col < 5; ++col) {
            if ((bits & (uint8_t)(1U << (4 - col))) != 0)
                pack_pixel(state->screen_pixels, x + col, y + row, level, level, level);
        }
    }
}

void pack_render_clock(pack_state *state, int64_t now_ms)
{
    (void)now_ms;
    time_t wall = time(NULL);
    struct tm local;
    if (localtime_r(&wall, &local) == NULL)
        return;
    if (local.tm_sec == state->last_second)
        return;
    state->last_second = local.tm_sec;

    pack_screen_clear(state);
    uint8_t level = pack_scale(255, state->options.brightness);
    int hour_tens = local.tm_hour / 10;
    int hour_ones = local.tm_hour % 10;
    int minute_tens = local.tm_min / 10;
    int minute_ones = local.tm_min % 10;
    draw_digit(state, 0, 4, hour_tens, level);
    draw_digit(state, 6, 4, hour_ones, level);
    draw_digit(state, 14, 4, minute_tens, level);
    draw_digit(state, 20, 4, minute_ones, level);
    if ((local.tm_sec % 2) == 0) {
        pack_pixel(state->screen_pixels, 12, 6, level, level, level);
        pack_pixel(state->screen_pixels, 12, 8, level, level, level);
    }
    state->screen_dirty = true;
}
