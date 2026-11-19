package quasar.rubcut.openio

import android.app.Application
import android.util.Log

class QuasarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Quasar OpenIO ${BuildConfig.VERSION_NAME} starting")
    }

    companion object {
        private const val TAG = "QuasarApp"
    }
}