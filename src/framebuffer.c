/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_openio.h"
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

void qio_fb_init(qio_fb *fb, const char *path, uint32_t width, uint32_t height)
{
    memset(fb, 0, sizeof(*fb));
    fb->fd = -1;
    fb->path = path;
    fb->width = width;
    fb->height = height;
    fb->frame_bytes = (size_t)width * height * 3;
    fb->last_error = "not_opened";
}

int qio_fb_validate(const struct fb_fix_screeninfo *fixed,
                    const struct fb_var_screeninfo *variable,
                    uint32_t width, uint32_t height, qio_fb *layout)
{
    const uint32_t row_bytes = width * 3;
    if (fixed->type != FB_TYPE_PACKED_PIXELS ||
        fixed->visual != FB_VISUAL_TRUECOLOR || fixed->capabilities != 0 ||
        fixed->line_length < row_bytes || fixed->smem_len == 0 ||
        fixed->smem_len > 16U * 1024U * 1024U ||
        variable->xres != width || variable->yres != height ||
        variable->xres_virtual < width || variable->yres_virtual < height ||
        variable->bits_per_pixel != 24 ||
        /* The station's fb4 reports grayscale=1 even though it provides a
         * normal RGB888 layout. The distinct RGB byte offsets below are the
         * authoritative format check. */
        variable->rotate != 0 ||
        variable->red.length != 8 || variable->green.length != 8 ||
        variable->blue.length != 8 || variable->transp.length != 0 ||
        variable->red.msb_right != 0 || variable->green.msb_right != 0 ||
        variable->blue.msb_right != 0)
        return -1;
    const uint32_t offsets[3] = {
        variable->red.offset, variable->green.offset, variable->blue.offset
    };
    unsigned occupied = 0;
    for (size_t i = 0; i < 3; ++i) {
        if (offsets[i] > 16 || offsets[i] % 8 != 0)
            return -1;
        uint32_t byte = offsets[i] / 8;
        if ((occupied & (1U << byte)) != 0)
            return -1;
        occupied |= 1U << byte;
        layout->channel_byte[i] = (uint8_t)byte;
    }
    uint64_t offset = (uint64_t)variable->yoffset * fixed->line_length +
                      (uint64_t)variable->xoffset * 3;
    uint64_t required = offset + (uint64_t)height * fixed->line_length;
    if ((uint64_t)variable->xoffset + width > variable->xres_virtual ||
        (uint64_t)variable->yoffset + height > variable->yres_virtual ||
        required > fixed->smem_len || required > SIZE_MAX)
        return -1;
    layout->stride = fixed->line_length;
    layout->offset = (size_t)offset;
    layout->map_size = fixed->smem_len;
    return 0;
}

int qio_fb_open(qio_fb *fb, int64_t now)
{
    if (fb->mapping != NULL)
        return 0;
    if (now < fb->next_open_ms)
        return -1;
    fb->next_open_ms = now + QIO_RETRY_MS;
    int fd = open(fb->path, O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) {
        fb->last_error = "open_failed";
        qio_log("%s: open: %s", fb->path, strerror(errno));
        return -1;
    }
    struct fb_fix_screeninfo fixed;
    struct fb_var_screeninfo variable;
    memset(&fixed, 0, sizeof(fixed));
    memset(&variable, 0, sizeof(variable));
    if (ioctl(fd, FBIOGET_FSCREENINFO, &fixed) != 0 ||
        ioctl(fd, FBIOGET_VSCREENINFO, &variable) != 0) {
        fb->last_error = "geometry_ioctl_failed";
        qio_log("%s: cannot read framebuffer geometry: %s", fb->path, strerror(errno));
        close(fd);
        return -1;
    }
    if (qio_fb_validate(&fixed, &variable, fb->width, fb->height, fb) != 0) {
        fb->last_error = "unsupported_geometry";
        qio_log("%s: refusing geometry %ux%u/%ubpp stride=%u size=%u; "
                "expected %ux%u RGB888, no geometry changes attempted",
                fb->path, (unsigned)variable.xres, (unsigned)variable.yres,
                (unsigned)variable.bits_per_pixel, (unsigned)fixed.line_length,
                (unsigned)fixed.smem_len, (unsigned)fb->width, (unsigned)fb->height);
        close(fd);
        return -1;
    }
    void *mapping = mmap(NULL, fb->map_size, PROT_READ | PROT_WRITE,
                         MAP_SHARED, fd, 0);
    if (mapping == MAP_FAILED) {
        fb->last_error = "mmap_failed";
        qio_log("%s: mmap: %s", fb->path, strerror(errno));
        close(fd);
        return -1;
    }
    fb->fd = fd;
    fb->mapping = mapping;
    fb->last_error = NULL;
    qio_log("%s: mapped %zu bytes, stride=%u, RGB byte offsets=%u/%u/%u",
            fb->path, fb->map_size, (unsigned)fb->stride,
            (unsigned)fb->channel_byte[0], (unsigned)fb->channel_byte[1],
            (unsigned)fb->channel_byte[2]);
    return 0;
}

void qio_fb_blit(uint8_t *mapping, const qio_fb *layout, const uint8_t *rgb)
{
    for (uint32_t y = 0; y < layout->height; ++y) {
        uint8_t *row = mapping + layout->offset + (size_t)y * layout->stride;
        const uint8_t *source = rgb + (size_t)y * layout->width * 3;
        if (layout->channel_byte[0] == 0 && layout->channel_byte[1] == 1 &&
            layout->channel_byte[2] == 2) {
            memcpy(row, source, (size_t)layout->width * 3);
        } else {
            for (uint32_t x = 0; x < layout->width; ++x) {
                row[(size_t)x * 3 + layout->channel_byte[0]] = source[(size_t)x * 3];
                row[(size_t)x * 3 + layout->channel_byte[1]] = source[(size_t)x * 3 + 1];
                row[(size_t)x * 3 + layout->channel_byte[2]] = source[(size_t)x * 3 + 2];
            }
        }
        /* Row padding is never written. No write(), ioctl update, pan or msync. */
    }
}

int qio_fb_queue(qio_app *app, qio_fb *fb, const char *base64,
                 size_t length, int64_t now)
{
    uint8_t decoded[QIO_SCREEN_BYTES];
    size_t decoded_length;
    if (qio_base64_decode(base64, length, decoded, sizeof(decoded),
                          &decoded_length) != 0 || decoded_length != fb->frame_bytes)
        return -1;
    if (app->options.dry_run)
        return 0;
    if (qio_fb_open(fb, now) != 0)
        return -2;
    /* Constant memory, latest-frame-wins. A burst cannot allocate a frame queue. */
    memcpy(fb->pending, decoded, fb->frame_bytes);
    fb->has_pending = true;
    return 0;
}

void qio_fb_tick(qio_fb *fb, int64_t now)
{
    if (!fb->has_pending || fb->mapping == NULL || now < fb->next_draw_ms)
        return;
    qio_fb_blit(fb->mapping, fb, fb->pending);
    fb->has_pending = false;
    fb->next_draw_ms = now + QIO_FRAME_INTERVAL_MS;
    ++fb->frames;
}

void qio_fb_close(qio_fb *fb)
{
    if (fb->mapping != NULL) {
        (void)munmap(fb->mapping, fb->map_size);
        fb->mapping = NULL;
    }
    qio_close_fd(&fb->fd);
    fb->has_pending = false;
}
