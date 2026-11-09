/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_pack.h"
#include <errno.h>
#include <string.h>
#include <unistd.h>

void pack_audio_init(pack_state *state)
{
    memset(&state->audio, 0, sizeof(state->audio));
    state->audio.fd = state->options.audio_fd;
}

short pack_audio_events(const pack_state *state)
{
    return state->audio.fd >= 0 && !state->audio.eof ? POLLIN : 0;
}

void pack_audio_event(pack_state *state, short revents)
{
    pack_audio *audio = &state->audio;
    if (audio->fd < 0 || audio->eof) return;
    if ((revents & POLLNVAL) != 0) { audio->eof = true; return; }
    bool hung_up = (revents & (POLLERR | POLLHUP)) != 0;
    if ((revents & POLLIN) == 0 && !hung_up) return;
    /* Drain a bounded quantum. On HUP the writer is gone, but buffered PCM may
     * still be readable, so read before declaring EOF. */
    uint8_t buffer[4096];
    for (unsigned i = 0; i < 8; ++i) {
        ssize_t count = read(audio->fd, buffer, sizeof(buffer));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0 && errno == EAGAIN) break;
        if (count <= 0) { audio->eof = true; return; }
        pack_visualizer_feed(state, buffer, (size_t)count);
    }
    /* HUP/ERR must set EOF even when POLLIN is absent: otherwise poll() keeps
     * returning immediately on a dead fd and the loop burns 100% CPU. */
    if (hung_up) audio->eof = true;
}

void pack_audio_close(pack_state *state)
{
    state->audio.fd = -1;
    state->audio.eof = true;
}
