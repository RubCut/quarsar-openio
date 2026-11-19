package quasar.rubcut.openio.data.net

import android.util.Log
import quasar.rubcut.openio.service.ControlApi
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Line-delimited JSON over TCP 127.0.0.1:8081 (never exposed on the LAN). */
class IpcServer(
    private val port: Int,
    private val api: ControlApi,
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "quasar-ipc-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    fun start(): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port))
            serverSocket = socket
            running = true
            thread = Thread({ acceptLoop(socket) }, "quasar-ipc-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "IPC listening on 127.0.0.1:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "cannot bind IPC port $port: ${e.message}")
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

    private fun acceptLoop(socket: ServerSocket) {
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
                    IpcSession(api, client.getInputStream(), client.getOutputStream(), client.remoteSocketAddress.toString())
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
        private const val TAG = "QuasarIpcTcp"
        private val counter = AtomicInteger()
    }
}