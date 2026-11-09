/* SPDX-License-Identifier: GPL-3.0-or-later
 * Host-test interposer only. Never copy this library to the device.
 * It emulates read-only fb/input ioctls and captures kill() without signalling
 * any real process. Production code contains no test-mode hardware fallback.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fb.h>
#include <linux/input.h>
#include <stdarg.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

static int same_path(int fd, const char *variable)
{
    const char *expected = getenv(variable);
    if (expected == NULL) return 0;
    char link[64], path[512];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(link, path, sizeof(path) - 1);
    if (n <= 0) return 0;
    path[n] = '\0';
    return strcmp(path, expected) == 0;
}

int ioctl(int fd, unsigned long request, ...)
{
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    int screen = same_path(fd, "QIO_TEST_FB4");
    int ring = same_path(fd, "QIO_TEST_FB5");
    if (screen || ring) {
        struct stat st;
        if (fstat(fd, &st) != 0 || st.st_size <= 0) return -1;
        if (request == FBIOGET_FSCREENINFO) {
            struct fb_fix_screeninfo *fix = arg;
            memset(fix, 0, sizeof(*fix));
            fix->type = FB_TYPE_PACKED_PIXELS;
            fix->visual = FB_VISUAL_TRUECOLOR;
            const char *stride = getenv(screen ? "QIO_TEST_FB4_STRIDE" : "QIO_TEST_FB5_STRIDE");
            fix->line_length = stride ? (unsigned)strtoul(stride, NULL, 10) : (screen ? 75U : 72U);
            fix->smem_len = (unsigned)st.st_size;
            return 0;
        }
        if (request == FBIOGET_VSCREENINFO) {
            struct fb_var_screeninfo *var = arg;
            memset(var, 0, sizeof(*var));
            var->xres = var->xres_virtual = screen ? 25U : 24U;
            var->yres = var->yres_virtual = screen ? 16U : 1U;
            var->bits_per_pixel = 24;
            var->red.length = var->green.length = var->blue.length = 8;
            var->green.offset = 8;
            const char *order = getenv(screen ? "QIO_TEST_FB4_ORDER" : "QIO_TEST_FB5_ORDER");
            int bgr = order && strcmp(order, "bgr") == 0;
            var->red.offset = bgr ? 16 : 0;
            var->blue.offset = bgr ? 0 : 16;
            return 0;
        }
    }
    const char *names[3] = {"gpio_keypad", "rotary-volume", "Yandex Remote 2"};
    const char *variables[3] = {"QIO_TEST_PANEL", "QIO_TEST_ROTARY", "QIO_TEST_REMOTE"};
    for (size_t i = 0; i < 3; ++i) {
        if (same_path(fd, variables[i]) && request == EVIOCGNAME(127)) {
            snprintf(arg, 127, "%s", names[i]);
            return (int)strlen(names[i]) + 1;
        }
    }
    int (*real_ioctl)(int, unsigned long, ...) = dlsym(RTLD_NEXT, "ioctl");
    if (real_ioctl == NULL) { errno = ENOSYS; return -1; }
    return real_ioctl(fd, request, arg);
}

int kill(pid_t pid, int signal)
{
    if (pid != 424242 || (signal != SIGSTOP && signal != SIGCONT)) {
        int (*real_kill)(pid_t, int) = dlsym(RTLD_NEXT, "kill");
        if (real_kill == NULL) { errno = ENOSYS; return -1; }
        return real_kill(pid, signal);
    }
    const char *log = getenv("QIO_TEST_SIGNAL_LOG");
    if (log != NULL) {
        char line[64];
        int n = snprintf(line, sizeof(line), "%ld %d\n", (long)pid, signal);
        int fd = open(log, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
        if (fd >= 0) {
            ssize_t ignored = write(fd, line, (size_t)n);
            (void)ignored;
            close(fd);
        }
    }
    const char *stat_path = getenv("QIO_TEST_PROC_STAT");
    if (stat_path != NULL) {
        int fd = open(stat_path, O_RDWR | O_CLOEXEC);
        if (fd >= 0) {
            char text[2048];
            ssize_t n = read(fd, text, sizeof(text) - 1);
            if (n > 0) {
                text[n] = '\0';
                char *state = strrchr(text, ')');
                if (state != NULL && text + n - state > 3) {
                    state[2] = signal == SIGSTOP ? 'T' : 'S';
                    if (lseek(fd, 0, SEEK_SET) == 0) {
                        ssize_t ignored = write(fd, text, (size_t)n);
                        (void)ignored;
                    }
                }
            }
            close(fd);
        }
    }
    return 0;
}
