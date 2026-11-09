/* SPDX-License-Identifier: GPL-3.0-or-later
 * Read-only framebuffer geometry probe. No mmap, no write, no mode change.
 * Build: cc -std=c11 -O2 -Wall -Wextra -o fb_info tools/fb_info.c
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/fb.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    if (argc != 2) {
        fprintf(stderr, "usage: %s /dev/graphics/fbN\n", argv[0]);
        return 2;
    }
    int fd = open(argv[1], O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "%s: open: %s\n", argv[1], strerror(errno));
        return 1;
    }
    struct fb_fix_screeninfo fixed;
    struct fb_var_screeninfo variable;
    memset(&fixed, 0, sizeof(fixed));
    memset(&variable, 0, sizeof(variable));
    if (ioctl(fd, FBIOGET_FSCREENINFO, &fixed) != 0 ||
        ioctl(fd, FBIOGET_VSCREENINFO, &variable) != 0) {
        fprintf(stderr, "%s: geometry ioctl: %s\n", argv[1], strerror(errno));
        close(fd);
        return 1;
    }
    printf("path=%s\n", argv[1]);
    printf("xres=%u yres=%u virtual=%ux%u offset=%u,%u bpp=%u\n",
           (unsigned)variable.xres, (unsigned)variable.yres,
           (unsigned)variable.xres_virtual, (unsigned)variable.yres_virtual,
           (unsigned)variable.xoffset, (unsigned)variable.yoffset,
           (unsigned)variable.bits_per_pixel);
    printf("stride=%u smem_len=%u type=%u visual=%u capabilities=0x%x\n",
           (unsigned)fixed.line_length, (unsigned)fixed.smem_len,
           (unsigned)fixed.type, (unsigned)fixed.visual, (unsigned)fixed.capabilities);
    printf("red=%u/%u green=%u/%u blue=%u/%u transp=%u/%u grayscale=%u rotate=%u\n",
           (unsigned)variable.red.offset, (unsigned)variable.red.length,
           (unsigned)variable.green.offset, (unsigned)variable.green.length,
           (unsigned)variable.blue.offset, (unsigned)variable.blue.length,
           (unsigned)variable.transp.offset, (unsigned)variable.transp.length,
           (unsigned)variable.grayscale, (unsigned)variable.rotate);
    close(fd);
    return 0;
}
