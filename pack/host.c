/* SPDX-License-Identifier: GPL-3.0-or-later */
/* stdin only: the supervisor forwards button events as text lines.
 * The plugin never asks the daemon to draw: screen and ring are both drawn by
 * this binary through validated framebuffer mmap. */
#include "quasar_pack.h"
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

void pack_host_init(pack_state *state)
{
    memset(&state->host, 0, sizeof(state->host));
    state->host.in_fd = STDIN_FILENO;
    int flags = fcntl(state->host.in_fd, F_GETFL);
    if (flags >= 0) (void)fcntl(state->host.in_fd, F_SETFL, flags | O_NONBLOCK);
}

short pack_host_in_events(const pack_state *state)
{
    return state->host.in_closed ? 0 : POLLIN;
}

static void drain_in(pack_state *state)
{
    pack_host *host = &state->host;
    uint8_t buffer[128];
    ssize_t count = read(host->in_fd, buffer, sizeof(buffer));
    if (count == 0) { host->in_closed = true; return; }
    if (count < 0) return;
    /* Mode transitions are owned by quasar_openio: it launches this binary with
     * the mode argument. Button lines are drained so the pipe never blocks. */
    size_t incoming = (size_t)count;
    if (incoming > PACK_IN_MAX - host->in_used) host->in_used = 0;
    memcpy(host->in + host->in_used, buffer, incoming);
    host->in_used += incoming;
    size_t consumed = 0;
    for (;;) {
        uint8_t *newline = memchr(host->in + consumed, '\n', host->in_used - consumed);
        if (newline == NULL) break;
        consumed = (size_t)(newline - host->in) + 1U;
        ++host->button_lines;
    }
    if (consumed != 0) {
        host->in_used -= consumed;
        memmove(host->in, host->in + consumed, host->in_used);
    }
    if (host->in_used == PACK_IN_MAX) host->in_used = 0;
}

void pack_host_event(pack_state *state, short revents)
{
    if ((revents & POLLNVAL) != 0) { state->host.in_closed = true; return; }
    if ((revents & POLLIN) != 0) drain_in(state);
    if ((revents & (POLLERR | POLLHUP)) != 0 && !state->host.in_closed) drain_in(state);
}

void pack_host_close(pack_state *state)
{
    state->host.in_used = 0;
    state->host.in_closed = true;
}
