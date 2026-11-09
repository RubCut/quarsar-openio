/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "quasar_openio.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

enum {
    QIO_SDK_OK = 0, QIO_SDK_NOT_FOUND, QIO_SDK_AMBIGUOUS,
    QIO_SDK_IDENTITY, QIO_SDK_SIGNAL, QIO_SDK_JOURNAL, QIO_SDK_ALREADY
};

typedef struct {
    pid_t pid;
    uint64_t start_ticks;
    char state;
} identity;

static int read_file(const char *path, char *buffer, size_t capacity, size_t *length)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    size_t used = 0;
    while (used < capacity - 1) {
        ssize_t count = read(fd, buffer + used, capacity - 1 - used);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) { close(fd); return -1; }
        if (count == 0) break;
        used += (size_t)count;
    }
    close(fd);
    buffer[used] = '\0';
    *length = used;
    return 0;
}

static int read_identity(pid_t pid, identity *out)
{
    char path[160];
    char buffer[2048];
    size_t length;
    int n = snprintf(path, sizeof(path), "%s/%ld/cmdline", QIO_PROC_ROOT, (long)pid);
    if (n < 0 || (size_t)n >= sizeof(path)) return QIO_SDK_IDENTITY;
    if (read_file(path, buffer, sizeof(buffer), &length) != 0)
        return QIO_SDK_NOT_FOUND;
    const char *nul = memchr(buffer, '\0', length);
    size_t first = nul == NULL ? length : (size_t)(nul - buffer);
    if (first != strlen(QIO_SDK_PACKAGE) || memcmp(buffer, QIO_SDK_PACKAGE, first) != 0)
        return QIO_SDK_IDENTITY;
    n = snprintf(path, sizeof(path), "%s/%ld/stat", QIO_PROC_ROOT, (long)pid);
    if (n < 0 || (size_t)n >= sizeof(path)) return QIO_SDK_IDENTITY;
    if (read_file(path, buffer, sizeof(buffer), &length) != 0)
        return QIO_SDK_NOT_FOUND;
    /* comm may contain spaces and parentheses; starttime is field 22. */
    char *cursor = strrchr(buffer, ')');
    if (cursor == NULL || length < (size_t)(cursor - buffer) + 3)
        return QIO_SDK_IDENTITY;
    cursor += 2;
    out->state = cursor[0];
    if (cursor[1] != ' ') return QIO_SDK_IDENTITY;
    for (int field = 3; field < 22; ++field) {
        cursor = strchr(cursor, ' ');
        if (cursor == NULL) return QIO_SDK_IDENTITY;
        ++cursor;
    }
    char *end;
    errno = 0;
    unsigned long long start = strtoull(cursor, &end, 10);
    if (errno != 0 || end == cursor || (*end != ' ' && *end != '\0') || start == 0)
        return QIO_SDK_IDENTITY;
    out->pid = pid;
    out->start_ticks = (uint64_t)start;
    return QIO_SDK_OK;
}

static int find_sdk(identity *out)
{
    DIR *dir = opendir(QIO_PROC_ROOT);
    if (dir == NULL) return QIO_SDK_NOT_FOUND;
    bool found = false;
    int result = QIO_SDK_NOT_FOUND;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        char *end;
        errno = 0;
        long pid = strtol(entry->d_name, &end, 10);
        if (errno != 0 || end == entry->d_name || *end != '\0' ||
            pid <= 0 || pid > INT_MAX)
            continue;
        identity candidate;
        if (read_identity((pid_t)pid, &candidate) != QIO_SDK_OK)
            continue;
        if (found) { result = QIO_SDK_AMBIGUOUS; break; }
        *out = candidate;
        found = true;
        result = QIO_SDK_OK;
    }
    closedir(dir);
    return result;
}

static int private_file(const char *path, bool missing_ok)
{
    struct stat st;
    if (lstat(path, &st) != 0)
        return missing_ok && errno == ENOENT ? 0 : -1;
    if (!S_ISREG(st.st_mode) || st.st_uid != geteuid() || st.st_nlink != 1 ||
        (st.st_mode & 0022) != 0)
        return -1;
    return 0;
}

static int remove_journal(void)
{
    if (private_file(QIO_SDK_JOURNAL_PATH, true) != 0) return -1;
    if (unlink(QIO_SDK_JOURNAL_PATH) != 0 && errno != ENOENT) return -1;
    return 0;
}

static int write_journal(const identity *id)
{
    if (private_file(QIO_SDK_JOURNAL_PATH, true) != 0) return -1;
    char boot_path[160];
    int n = snprintf(boot_path, sizeof(boot_path), "%s/sys/kernel/random/boot_id",
                     QIO_PROC_ROOT);
    if (n < 0 || (size_t)n >= sizeof(boot_path)) return -1;
    char boot[64];
    size_t boot_length;
    if (read_file(boot_path, boot, sizeof(boot), &boot_length) != 0) return -1;
    boot[strcspn(boot, "\r\n")] = '\0';
    if (strlen(boot) != 36) return -1;
    char text[256];
    n = snprintf(text, sizeof(text), "%ld %" PRIu64 " %s %s\n",
                 (long)id->pid, id->start_ticks, boot, QIO_SDK_PACKAGE);
    if (n < 0 || (size_t)n >= sizeof(text)) return -1;
    size_t text_length = (size_t)n;
    char temporary[sizeof(QIO_SDK_JOURNAL_PATH) + 5];
    n = snprintf(temporary, sizeof(temporary), "%s.tmp", QIO_SDK_JOURNAL_PATH);
    if (n < 0 || (size_t)n >= sizeof(temporary)) return -1;
    if (private_file(temporary, true) != 0) return -1;
    int fd = open(temporary, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return -1;
    int failed = private_file(temporary, false) != 0 ||
                 qio_write_all(fd, text, text_length) != 0;
    if (!failed) {
        while (fsync(fd) != 0) {
            if (errno == EINTR) continue;
            failed = 1;
            break;
        }
    }
    if (close(fd) != 0) failed = 1;
    if (!failed && rename(temporary, QIO_SDK_JOURNAL_PATH) != 0) failed = 1;
    if (failed) {
        int saved = errno;
        (void)unlink(temporary);
        errno = saved;
        return -1;
    }
    return 0;
}

void qio_sdk_init(qio_sdk *sdk) { memset(sdk, 0, sizeof(*sdk)); }

int qio_sdk_recover(qio_sdk *sdk)
{
    if (private_file(QIO_SDK_JOURNAL_PATH, true) != 0) {
        sdk->journal_blocked = true;
        qio_log("SDK journal is missing or unsafe; automatic recovery refused");
        return -1;
    }
    char text[256];
    size_t length;
    if (read_file(QIO_SDK_JOURNAL_PATH, text, sizeof(text), &length) != 0) {
        if (errno == ENOENT) return 0;
        sdk->journal_blocked = true;
        return -1;
    }
    char *save = NULL;
    char *pid_text = strtok_r(text, " \t\r\n", &save);
    char *start_text = strtok_r(NULL, " \t\r\n", &save);
    char *boot = strtok_r(NULL, " \t\r\n", &save);
    char *package = strtok_r(NULL, " \t\r\n", &save);
    char *extra = strtok_r(NULL, " \t\r\n", &save);
    if (pid_text == NULL || start_text == NULL || boot == NULL || package == NULL ||
        extra != NULL || strlen(boot) != 36 || strcmp(package, QIO_SDK_PACKAGE) != 0) {
        sdk->journal_blocked = true;
        qio_log("invalid SDK journal; no signal sent");
        return -1;
    }
    char *end;
    errno = 0;
    long pid = strtol(pid_text, &end, 10);
    if (errno != 0 || end == pid_text || *end != '\0' || pid <= 0 || pid > INT_MAX) {
        sdk->journal_blocked = true;
        qio_log("invalid SDK journal PID; no signal sent");
        return -1;
    }
    errno = 0;
    unsigned long long start = strtoull(start_text, &end, 10);
    if (errno != 0 || end == start_text || *end != '\0' || start == 0) {
        sdk->journal_blocked = true;
        qio_log("invalid SDK journal starttime; no signal sent");
        return -1;
    }
    char boot_path[160], current_boot[64];
    int n = snprintf(boot_path, sizeof(boot_path), "%s/sys/kernel/random/boot_id",
                     QIO_PROC_ROOT);
    if (n < 0 || (size_t)n >= sizeof(boot_path)) return -1;
    size_t used;
    if (read_file(boot_path, current_boot, sizeof(current_boot), &used) != 0)
        return -1;
    current_boot[strcspn(current_boot, "\r\n")] = '\0';
    identity id;
    if (strcmp(boot, current_boot) != 0 || read_identity((pid_t)pid, &id) != QIO_SDK_OK ||
        id.start_ticks != (uint64_t)start) {
        if (remove_journal() != 0) return -1;
        qio_log("stale SDK journal removed; no signal sent");
        return 0;
    }
    if (kill(id.pid, SIGCONT) != 0) return -1;
    if (remove_journal() != 0) return -1;
    sdk->recovered = true;
    qio_log("resumed SDK pid=%ld after validating the previous daemon's journal",
            (long)id.pid);
    return 0;
}

int qio_sdk_pause(qio_sdk *sdk)
{
    if (sdk->journal_blocked) return QIO_SDK_JOURNAL;
    identity id;
    if (sdk->owned) {
        int result = read_identity(sdk->pid, &id);
        if (result != QIO_SDK_OK || id.start_ticks != sdk->start_ticks)
            return QIO_SDK_IDENTITY;
        if (id.state == 'T') return QIO_SDK_ALREADY;
        if (id.state == 'Z' || id.state == 'X') return QIO_SDK_NOT_FOUND;
    } else {
        int result = find_sdk(&id);
        if (result != QIO_SDK_OK) return result;
        /* Do not adopt an externally stopped process and later resume it. */
        if (id.state == 'T') return QIO_SDK_ALREADY;
        if (id.state == 'Z' || id.state == 'X') return QIO_SDK_NOT_FOUND;
    }
    /* Intent journal precedes STOP: a crash in between is safe to recover. */
    if (write_journal(&id) != 0) {
        sdk->journal_blocked = true;
        return QIO_SDK_JOURNAL;
    }
    if (kill(id.pid, SIGSTOP) != 0) {
        int saved = errno;
        (void)remove_journal();
        errno = saved;
        return QIO_SDK_SIGNAL;
    }
    sdk->pid = id.pid;
    sdk->start_ticks = id.start_ticks;
    sdk->owned = true;
    qio_log("SDK pid=%ld: SIGSTOP sent (fds remain open; other SDK services freeze)",
            (long)id.pid);
    return QIO_SDK_OK;
}

int qio_sdk_resume(qio_sdk *sdk)
{
    if (!sdk->owned) return QIO_SDK_OK; /* never resume somebody else's STOP */
    identity id;
    int result = read_identity(sdk->pid, &id);
    if (result == QIO_SDK_NOT_FOUND) {
        if (remove_journal() != 0) return QIO_SDK_JOURNAL;
        sdk->owned = false;
        return QIO_SDK_OK;
    }
    if (result != QIO_SDK_OK || id.start_ticks != sdk->start_ticks)
        return QIO_SDK_IDENTITY;
    if (kill(id.pid, SIGCONT) != 0) return QIO_SDK_SIGNAL;
    sdk->owned = false;
    qio_log("SDK pid=%ld: SIGCONT sent", (long)id.pid);
    if (remove_journal() != 0) {
        sdk->journal_blocked = true;
        return QIO_SDK_JOURNAL;
    }
    return QIO_SDK_OK;
}

void qio_sdk_cleanup(qio_sdk *sdk)
{
    int result = qio_sdk_resume(sdk);
    if (result != QIO_SDK_OK)
        qio_log("SDK cleanup: %s; inspect the root-only journal before manual recovery",
                qio_sdk_error(result));
}

const char *qio_sdk_error(int result)
{
    switch (result) {
    case QIO_SDK_OK: return NULL;
    case QIO_SDK_NOT_FOUND: return "sdk_not_found";
    case QIO_SDK_AMBIGUOUS: return "ambiguous_sdk_process";
    case QIO_SDK_IDENTITY: return "sdk_identity_changed";
    case QIO_SDK_SIGNAL: return "signal_failed";
    case QIO_SDK_JOURNAL: return "journal_failed";
    case QIO_SDK_ALREADY: return "sdk_already_stopped";
    default: return "sdk_error";
    }
}

int qio_sdk_status(qio_sdk *sdk, char *buffer, size_t capacity)
{
    identity id;
    const char *state = "not_found";
    long pid = 0;
    int result;
    if (sdk->owned) {
        result = read_identity(sdk->pid, &id);
        if (result != QIO_SDK_OK || id.start_ticks != sdk->start_ticks) {
            state = "identity_changed";
            pid = (long)sdk->pid;
        } else pid = (long)id.pid;
    } else {
        result = find_sdk(&id);
        if (result == QIO_SDK_OK) pid = (long)id.pid;
    }
    if (pid != 0 && strcmp(state, "identity_changed") != 0) {
        switch (id.state) {
        case 'T': state = "stopped"; break;
        case 'Z': case 'X': state = "dead"; break;
        default: state = "running_or_sleeping"; break;
        }
    }
    int n = snprintf(buffer, capacity,
                     "{\"package\":\"%s\",\"pid\":%ld,\"state\":\"%s\","
                     "\"owned_stop\":%s,\"recovered\":%s}",
                     QIO_SDK_PACKAGE, pid, state, sdk->owned ? "true" : "false",
                     sdk->recovered ? "true" : "false");
    return n < 0 || (size_t)n >= capacity ? -1 : n;
}
