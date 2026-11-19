package quasar.rubcut.openio.data.net

import android.util.Log
import quasar.rubcut.openio.domain.audio.Pcm
import quasar.rubcut.openio.service.MicHub
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Raw microphone stream over TCP 127.0.0.1:8082.
 *
 * The stream has no header: signed 16-bit little-endian mono samples at
 * 16 kHz, starting from the moment the client connects. Each client gets a
 * small bounded queue so a stalled reader is dropped, never the capture thread.
 */
class PcmStreamServer(
    private val mic: MicHub,
    private val port: Int = 8082,
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "quasar-pcm-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    fun start(): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port))
            serverSocket = socket
            running = true
            thread = Thread({ acceptLoop(socket) }, "quasar-pcm-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "raw PCM listening on 127.0.0.1:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "cannot bind PCM port $port: ${e.message}")
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
            executor.execute { serve(client) }
        }
    }

    private fun serve(socket: Socket) {
        val queue = ArrayBlockingQueue<ShortArray>(16)
        val subscription = mic.subscribe { samples ->
            if (!queue.offer(samples)) {
                queue.poll()
                queue.offer(samples)
            }
        }
        try {
            socket.tcpNoDelay = true
            val output = socket.getOutputStream().buffered()
            while (running && !socket.isClosed) {
                val chunk = queue.poll(1, TimeUnit.SECONDS) ?: continue
                output.write(Pcm.toLittleEndian(chunk))
                output.flush()
            }
        } catch (_: Exception) {
            // Client closed; normal.
        } finally {
            subscription.close()
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "QuasarPcmStream"
        private val counter = AtomicInteger()
        const val DEFAULT_PORT = 8082
    }
}