package quasar.rubcut.openio.data.root

object AutostartConfig {
    const val RC_PATH = "/odm/etc/init/quasar-openio.rc"
    const val LAUNCHER_DIR = "/odm/quasar-openio"
    const val LAUNCHER_PATH = "$LAUNCHER_DIR/quasar-openio-launcher"
    const val LAUNCHER_ASSET = "launcher/quasar-openio-launcher"

    val RC_CONTENT: String = """
        # Quasar OpenIO boot entrypoint.
        # init executes only this ELF launcher. The launcher waits for
        # StationBreak to switch SELinux to Permissive and then starts the
        # foreground service through am. No shell is executed by init itself.
        service quasar_openio_boot /odm/quasar-openio/quasar-openio-launcher
            class main
            user root
            group root shell system
            seclabel u:r:shell:s0
            oneshot
            disabled

        on property:sys.boot_completed=1
            start quasar_openio_boot
    """.trimIndent() + "\n"
}