package quasar.rubcut.openio.data.anim

import android.content.Context
import android.util.Log
import quasar.rubcut.openio.domain.render.GifAnimation
import quasar.rubcut.openio.domain.render.GifDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Stock animations shipped in `assets/anim` plus user packs.
 *
 * Asset ids are relative paths without the leading folder, e.g.
 * `media/25x16_Pause.gif`. Decoding is lazy and the LRU keeps only a handful of
 * clips in memory, because some stock scenarios are long.
 */
class AnimationStore(
    private val context: Context,
    private val cacheSize: Int = 12,
) {
    private val cache = object : LinkedHashMap<String, GifAnimation?>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GifAnimation?>?): Boolean =
            size > cacheSize
    }

    private val lock = Any()

    private val failures = ConcurrentHashMap.newKeySet<String>()

    /** Sorted list of every bundled animation id. */
    val ids: List<String> by lazy { collect("anim") }

    fun load(id: String?): GifAnimation? {
        val key = normalize(id) ?: return null
        if (failures.contains(key)) return null
        synchronized(lock) { cache[key]?.let { return it } }
        val animation = runCatching {
            context.assets.open("anim/$key").use { stream ->
                GifDecoder.decode(stream.readBytes())
            }
        }.onFailure { Log.w(TAG, "cannot decode animation $key", it) }.getOrNull()
        if (animation == null) failures.add(key)
        synchronized(lock) { cache[key] = animation }
        return animation
    }

    /** Decodes a GIF that lives inside an installed plugin pack. */
    fun loadBytes(key: String, bytes: ByteArray): GifAnimation? {
        val cacheKey = "bytes:$key"
        if (failures.contains(cacheKey)) return null
        synchronized(lock) { cache[cacheKey]?.let { return it } }
        val animation = runCatching { GifDecoder.decode(bytes) }
            .onFailure { Log.w(TAG, "cannot decode plugin animation $key", it) }
            .getOrNull()
        if (animation == null) failures.add(cacheKey)
        synchronized(lock) { cache[cacheKey] = animation }
        return animation
    }

    /** Groups (`media`, `oknix`, ...) with their clip ids, for the web UI. */
    fun catalog(): Map<String, List<String>> =
        ids.groupBy { if ('/' in it) it.substringBefore('/') else ROOT_GROUP }.toSortedMap()

    private fun collect(dir: String): List<String> {
        val result = ArrayList<String>()
        fun walk(path: String, prefix: String) {
            val entries = runCatching { context.assets.list(path) }.getOrNull() ?: return
            for (entry in entries.sorted()) {
                val childPath = "$path/$entry"
                val childPrefix = if (prefix.isEmpty()) entry else "$prefix/$entry"
                if (entry.endsWith(".gif", ignoreCase = true)) {
                    result.add(childPrefix)
                } else {
                    walk(childPath, childPrefix)
                }
            }
        }
        walk(dir, "")
        return result
    }

    private fun normalize(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val trimmed = id.trim().removePrefix("/").removePrefix("anim/")
        return if (trimmed.endsWith(".gif", ignoreCase = true)) trimmed else "$trimmed.gif"
    }

    companion object {
        private const val TAG = "QuasarAnim"
        private const val ROOT_GROUP = "root"
    }
}