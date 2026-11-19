package quasar.rubcut.openio.data.net

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import quasar.rubcut.openio.service.ControlApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Same protocol as [IpcServer], available as an Android abstract unix socket.
 * Android 9's public LocalServerSocket API only exposes abstract namespace
 * binding, hence the externally visible address is @quasar-openio rather than
 * a filesystem node. HTTP :8080 and TCP IPC :8081 remain the primary APIs.
 */
class LocalIpcServer(
    private val name: String,
    private val api: ControlApi,
) {
    @Volatile
    private var running = false
    private var serverSocket: LocalServerSocket? = null
    private var thread: Thread? = null
    private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "quasar-ipc-unix-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    fun start(): Boolean {
        if (running) return true
        return try {
            val socket = LocalServerSocket(name)
            serverSocket = socket
            running = true
            thread = Thread({ acceptLoop(socket) }, "quasar-ipc-unix-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "IPC listening on unix:@$name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "cannot bind unix socket @$name: ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        executor.shutdownNow()
    }

    private fun acceptLoop(socket: LocalServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break
            }
            clients.add(client)
            executor.execute {
                val session = try {
                    IpcSession(api, client.inputStream, client.outputStream, "unix:@$name")
                } catch (e: Exception) {
                    runCatching { client.close() }
                    return@execute
                }
                try {
                    session.run()
                } finally {
                    session.close()
                    clients.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    companion object {
        private const val TAG = "QuasarIpcUnix"
        private val counter = AtomicInteger()
        const val DEFAULT_NAME = "quasar-openio"
        const val DISPLAY_ADDRESS = "@quasar-openio"
    }
}
