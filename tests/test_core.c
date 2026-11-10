/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_openio.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>

static void test_json(void)
{
    qio_request request;
    const char *valid = " { \"action\" : \"draw_ring\", \"data\":\"AAEC\" } ";
    assert(qio_parse_request((const uint8_t *)valid, strlen(valid), &request) == 0);
    assert(strcmp(request.action, "draw_ring") == 0 && request.has_data);
    assert(request.data_length == 4 && memcmp(request.data, "AAEC", 4) == 0);
    const char *escaped = "{\"\\u0061ction\":\"st\\u0061tus\"}";
    assert(qio_parse_request((const uint8_t *)escaped, strlen(escaped), &request) == 0);
    assert(strcmp(request.action, "status") == 0);
    const char *invalid[] = {
        "{}", "{\"action\":\"status\",\"action\":\"pause_sdk\"}",
        "{\"action\":\"status\",\"command\":\"reboot\"}",
        "{\"action\":\"status\"} trailing", "{\"action\":\"status\",}",
        "{\"action\":[\"status\"]}", "{\"action\":\"\\u0430dmin\"}",
        "{\"action\":\"status\\u0000ignored\"}",
        "{\"action\":\"status\"}\n{\"action\":\"pause_sdk\"}"
    };
    for (size_t i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i)
        assert(qio_parse_request((const uint8_t *)invalid[i], strlen(invalid[i]), &request) != 0);
    uint8_t output[16];
    size_t length;
    assert(qio_base64_decode("Zg==", 4, output, sizeof(output), &length) == 0 && length == 1 && output[0] == 'f');
    assert(qio_base64_decode("Zm8=", 4, output, sizeof(output), &length) == 0 && length == 2 && memcmp(output, "fo", 2) == 0);
    assert(qio_base64_decode("Zm9v", 4, output, sizeof(output), &length) == 0 && length == 3 && memcmp(output, "foo", 3) == 0);
    assert(qio_base64_decode("Zh==", 4, output, sizeof(output), &length) != 0);
    assert(qio_base64_decode("Zg== ", 5, output, sizeof(output), &length) != 0);
    assert(qio_base64_decode("====", 4, output, sizeof(output), &length) != 0);
}

static void geometry(struct fb_fix_screeninfo *fix, struct fb_var_screeninfo *var)
{
    memset(fix, 0, sizeof(*fix));
    memset(var, 0, sizeof(*var));
    fix->type = FB_TYPE_PACKED_PIXELS;
    fix->visual = FB_VISUAL_TRUECOLOR;
    fix->line_length = 96;
    fix->smem_len = 4096;
    var->xres = 25; var->yres = 16;
    var->xres_virtual = 32; var->yres_virtual = 24;
    var->xoffset = 1; var->yoffset = 1;
    var->bits_per_pixel = 24;
    var->red.length = var->green.length = var->blue.length = 8;
    var->green.offset = 8; var->blue.offset = 16;
}

static void test_framebuffer(void)
{
    struct fb_fix_screeninfo fix;
    struct fb_var_screeninfo var;
    qio_fb fb;
    qio_fb_init(&fb, "test-only", 25, 16);
    geometry(&fix, &var);
    assert(qio_fb_validate(&fix, &var, 25, 16, &fb) == 0);
    assert(fb.stride == 96 && fb.offset == 99);
    uint8_t *mapping = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(mapping != MAP_FAILED);
    memset(mapping, 0xa5, 4096);
    uint8_t frame[1200];
    for (size_t i = 0; i < sizeof(frame); ++i) frame[i] = (uint8_t)i;
    qio_fb_blit(mapping, &fb, frame);
    for (unsigned y = 0; y < 16; ++y) {
        for (unsigned x = 0; x < 75; ++x)
            assert(mapping[99 + y * 96 + x] == frame[y * 75 + x]);
        for (unsigned x = 75; x < 96; ++x)
            assert(mapping[99 + y * 96 + x] == 0xa5);
    }
    var.red.offset = 16; var.blue.offset = 0;
    assert(qio_fb_validate(&fix, &var, 25, 16, &fb) == 0);
    qio_fb_blit(mapping, &fb, frame);
    for (unsigned y = 0; y < 16; ++y) {
        for (unsigned x = 0; x < 25; ++x) {
            assert(mapping[99 + y * 96 + x * 3] == frame[y * 75 + x * 3 + 2]);
            assert(mapping[100 + y * 96 + x * 3] == frame[y * 75 + x * 3 + 1]);
            assert(mapping[101 + y * 96 + x * 3] == frame[y * 75 + x * 3]);
        }
    }
    assert(munmap(mapping, 4096) == 0);
    geometry(&fix, &var); var.bits_per_pixel = 32;
    assert(qio_fb_validate(&fix, &var, 25, 16, &fb) != 0);
    geometry(&fix, &var); fix.line_length = 74;
    assert(qio_fb_validate(&fix, &var, 25, 16, &fb) != 0);
    geometry(&fix, &var); fix.smem_len = 100;
    assert(qio_fb_validate(&fix, &var, 25, 16, &fb) != 0);
    geometry(&fix, &var); var.yoffset = 23;
    assert(qio_fb_validate(&fix, &var, 25, 16, &fb) != 0);
    geometry(&fix, &var); var.blue.offset = var.red.offset;
    assert(qio_fb_validate(&fix, &var, 25, 16, &fb) != 0);
    geometry(&fix, &var); var.grayscale = 1;
    assert(qio_fb_validate(&fix, &var, 25, 16, &fb) != 0);
    geometry(&fix, &var); fix.smem_len = 0;
    assert(qio_fb_validate(&fix, &var, 25, 16, &fb) != 0);
}

int main(void)
{
    test_json();
    test_framebuffer();
    puts("core: JSON/base64/framebuffer stride and layout OK");
    return 0;
}
