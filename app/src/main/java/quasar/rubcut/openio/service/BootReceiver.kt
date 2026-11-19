package quasar.rubcut.openio.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Secondary boot path. The primary one is the /odm init rc + PIE launcher,
 * which does not depend on the package manager allowlist.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        try {
            val service = Intent(context, QuasarService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
            Log.i(TAG, "foreground service requested after $action")
        } catch (e: Exception) {
            Log.w(TAG, "cannot start service after boot: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "QuasarBoot"
    }
}