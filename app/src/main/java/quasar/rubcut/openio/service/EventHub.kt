package quasar.rubcut.openio.service

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out of JSON event lines. Every listener is isolated: a broken socket
 * must never take down the audio or input producer thread.
 */
class EventHub(private val historyLimit: Int = 200) {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val history = ArrayDeque<String>()

    fun emit(json: String) {
        synchronized(history) {
            history.addLast(json)
            while (history.size > historyLimit) history.removeFirst()
        }
        for (listener in listeners) {
            runCatching { listener(json) }
        }
    }

    fun add(listener: (String) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    fun recent(): List<String> = synchronized(history) { history.toList() }

    fun listenerCount(): Int = listeners.size
}