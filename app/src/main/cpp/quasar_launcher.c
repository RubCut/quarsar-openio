/*
 * Quasar OpenIO boot launcher.
 *
 * init runs this tiny PIE executable from /odm/quasar-openio.  It waits until
 * StationBreak has switched SELinux to Permissive, then asks Android to start
 * the app's foreground service.  No shell is executed from init itself.
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int selinux_is_permissive(void) {
    int fd = open("/sys/fs/selinux/enforce", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        /* If the file cannot be read, let am decide; on this device the
         * StationBreak launcher normally makes SELinux permissive first. */
        return -1;
    }
    char value = 0;
    ssize_t got = read(fd, &value, 1);
    close(fd);
    if (got != 1) return -1;
    return value == '0' ? 1 : 0;
}

int main(void) {
    setenv("PATH", "/sbin:/system/sbin:/system/bin:/system/xbin", 1);
    setenv("ANDROID_ROOT", "/system", 1);
    setenv("ANDROID_DATA", "/data", 1);
    setenv("ANDROID_RUNTIME_ROOT", "/system", 1);

    int permissive = 0;
    for (int i = 0; i < 120; ++i) {
        int state = selinux_is_permissive();
        if (state > 0) {
            permissive = 1;
            break;
        }
        if (state < 0 && i >= 10) {
            /* SELinux control file is not available; proceed after a short
             * grace period instead of blocking boot forever. */
            permissive = 1;
            break;
        }
        sleep(1);
    }
    if (!permissive) {
        fprintf(stderr, "quasar-openio: SELinux did not become permissive in 120s\n");
        return 1;
    }

    /* Give system_server/package manager a moment after boot_completed. */
    sleep(1);

    execl("/system/bin/am", "am", "start-foreground-service", "-n",
          "quasar.rubcut.openio/.service.QuasarService", (char*)NULL);
    fprintf(stderr, "quasar-openio: could not execute am: %s\n", strerror(errno));
    return 1;
}
