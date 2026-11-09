/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_openio.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

/* glibc uses unsigned long ioctl requests; musl/bionic use int. */
#if defined(__GLIBC__)
#define QIO_IOCTL_REQUEST(value) ((unsigned long)(value))
#else
#define QIO_IOCTL_REQUEST(value) ((int)(value))
#endif

void qio_inputs_init(qio_app *app)
{
    const char *paths[QIO_INPUT_COUNT] = {
        QIO_PANEL_PATH, QIO_ROTARY_PATH, QIO_REMOTE_PATH
    };
    const char *names[QIO_INPUT_COUNT] = {"panel", "rotary", "remote"};
    const char *expected[QIO_INPUT_COUNT] = {
        "gpio_keypad", "rotary-volume", "Yandex Remote 2"
    };
    for (size_t i = 0; i < QIO_INPUT_COUNT; ++i) {
        app->inputs[i].path = paths[i];
        app->inputs[i].name = names[i];
        app->inputs[i].expected_name = expected[i];
        app->inputs[i].fd = -1;
    }
}

void qio_inputs_tick(qio_app *app, int64_t now)
{
    if (app->options.dry_run) return;
    for (size_t i = 0; i < QIO_INPUT_COUNT; ++i) {
        qio_input *input = &app->inputs[i];
        if (input->fd >= 0 || now < input->next_open_ms) continue;
        input->next_open_ms = now + QIO_RETRY_MS;
        int fd = open(input->path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) {
            qio_log("%s (%s): open: %s; retry in %d ms", input->path,
                    input->name, strerror(errno), QIO_RETRY_MS);
            continue;
        }
        char name[128] = {0};
        if (ioctl(fd, QIO_IOCTL_REQUEST(EVIOCGNAME(sizeof(name) - 1)), name) < 0 ||
            strcmp(name, input->expected_name) != 0) {
            qio_log("%s: expected input name '%s', got '%s'; refusing wrong device",
                    input->path, input->expected_name, name);
            close(fd);
            continue;
        }
        /* Observe only. No EVIOCGRAB: Android and io.sdk still receive input. */
        input->fd = fd;
        qio_log("%s: observing %s", input->path, name);
    }
}

static void emit_key(qio_app *app, size_t index, const struct input_event *event)
{
    const char *name = NULL;
    bool edge = event->value == 1;
    if (index == 0) {
        if (event->type == EV_SW && event->code == 14 &&
            (event->value == 0 || event->value == 1)) {
            qio_broadcast_button(app, event->value ? "microphone_mute_on" :
                                   "microphone_mute_off", "panel", event->code);
            return;
        }
        if (event->type == EV_KEY && event->code == 582) name = "button_alice";
        if (event->type == EV_KEY && event->code == KEY_MUTE) name = "button_mute";
    } else if (index == 1 && event->type == EV_KEY) {
        /* Rotary detents are press-only; releases must not double the volume. */
        if (event->code == 104) name = "volume_up";
        if (event->code == 109) name = "volume_down";
    } else if (index == 2 && event->type == EV_KEY) {
        switch (event->code) {
        case KEY_VOLUMEUP: name = "volume_up"; break;
        case KEY_VOLUMEDOWN: name = "volume_down"; break;
        case KEY_POWER: name = "button_power"; break;
        case KEY_MUTE: name = "button_mute"; break;
        case KEY_BACK: name = "button_back"; break;
        case KEY_HOMEPAGE: name = "button_home"; break;
        case KEY_VOICECOMMAND: name = "button_alice"; break;
        case KEY_PLAYPAUSE: name = "button_play_pause"; break;
        case KEY_PLAY: name = "button_play_pause"; break;
        case KEY_PAUSE: name = "button_play_pause"; break;
        default: break;
        }
    }
    if (name == NULL || (!edge && (index == 1 || event->value != 0))) return;
    if (edge && strncmp(name, "volume_", 7) == 0) {
        qio_broadcast_button(app, name, app->inputs[index].name, event->code);
        return;
    }
    char event_name[96];
    int n = snprintf(event_name, sizeof(event_name), "%s_%s", name,
                     edge ? "pressed" : "released");
    if (n < 0 || (size_t)n >= sizeof(event_name)) return;
    qio_broadcast_button(app, event_name, app->inputs[index].name, event->code);
}

void qio_inputs_read(qio_app *app, size_t index)
{
    qio_input *input = &app->inputs[index];
    _Static_assert(sizeof(struct input_event) <= 64, "input_event exceeds carry buffer");
    struct input_event events[64];
    uint8_t buffer[sizeof(events) + sizeof(input->partial)];
    memcpy(buffer, input->partial, input->partial_used);
    /* Bounded work quantum; remaining kernel events stay queued for poll(). */
    ssize_t bytes = read(input->fd, buffer + input->partial_used, sizeof(events));
    if (bytes < 0 && (errno == EAGAIN || errno == EINTR)) return;
    if (bytes > 0) {
        size_t total = input->partial_used + (size_t)bytes;
        size_t count = total / sizeof(events[0]);
        memcpy(events, buffer, count * sizeof(events[0]));
        for (size_t i = 0; i < count; ++i) emit_key(app, index, &events[i]);
        input->partial_used = total % sizeof(events[0]);
        memcpy(input->partial, buffer + count * sizeof(events[0]), input->partial_used);
        return;
    }
    if (bytes < 0)
        qio_log("%s: read: %s", input->path, strerror(errno));
    else
        qio_log("%s: EOF; reopening", input->path);
    qio_close_fd(&input->fd);
    input->partial_used = 0;
    input->next_open_ms = qio_now_ms() + QIO_RETRY_MS;
}

void qio_inputs_close(qio_app *app)
{
    for (size_t i = 0; i < QIO_INPUT_COUNT; ++i)
        qio_close_fd(&app->inputs[i].fd);
}
