/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_openio.h"
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t stop_requested;
static int signal_pipe[2] = {-1, -1};

int64_t qio_now_ms(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return -1;
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

void qio_log(const char *format, ...)
{
    char line[512];
    va_list ap;
    int prefix = snprintf(line, sizeof(line), "quasar_openio: ");
    if (prefix < 0 || (size_t)prefix >= sizeof(line))
        return;
    va_start(ap, format);
    int length = vsnprintf(line + prefix, sizeof(line) - (size_t)prefix, format, ap);
    va_end(ap);
    if (length < 0)
        return;
    size_t used = (size_t)prefix + (size_t)length;
    if (used > sizeof(line) - 2)
        used = sizeof(line) - 2;
    line[used++] = '\n';
    /* No timestamp/localtime allocations, no log files, no fork. */
    (void)qio_write_all(STDERR_FILENO, line, used);
}

static void on_signal(int number)
{
    int saved = errno;
    if (number == SIGCHLD) {
        if (signal_pipe[1] >= 0) {
            const uint8_t byte = 1;
            ssize_t written = write(signal_pipe[1], &byte, 1);
            (void)written;
        }
        errno = saved;
        return;
    }
    stop_requested = 1;
    if (signal_pipe[1] >= 0) {
        const uint8_t byte = 1;
        ssize_t written = write(signal_pipe[1], &byte, 1);
        (void)written; /* A full pipe already means a wakeup is pending. */
    }
    errno = saved;
}

int qio_signals_install(void)
{
    if (pipe2(signal_pipe, O_CLOEXEC | O_NONBLOCK) != 0)
        return -1;
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGINT, &action, NULL) != 0 ||
        sigaction(SIGTERM, &action, NULL) != 0 ||
        sigaction(SIGCHLD, &action, NULL) != 0) {
        qio_signals_close();
        return -1;
    }
    action.sa_handler = SIG_IGN;
    if (sigaction(SIGPIPE, &action, NULL) != 0) {
        qio_signals_close();
        return -1;
    }
    return 0;
}

bool qio_stopping(void) { return stop_requested != 0; }
int qio_signal_fd(void) { return signal_pipe[0]; }

void qio_signals_drain(void)
{
    uint8_t bytes[64];
    for (;;) {
        ssize_t count = read(signal_pipe[0], bytes, sizeof(bytes));
        if (count < 0 && errno == EINTR)
            continue;
        if (count > 0)
            continue;
        break;
    }
}

void qio_signals_close(void)
{
    qio_close_fd(&signal_pipe[0]);
    qio_close_fd(&signal_pipe[1]);
}

void qio_close_fd(int *fd)
{
    if (*fd >= 0) {
        (void)close(*fd);
        *fd = -1;
    }
}

int qio_write_all(int fd, const void *data, size_t length)
{
    const uint8_t *cursor = data;
    while (length != 0) {
        ssize_t count = write(fd, cursor, length);
        if (count < 0 && errno == EINTR)
            continue;
        if (count <= 0)
            return -1;
        cursor += (size_t)count;
        length -= (size_t)count;
    }
    return 0;
}
