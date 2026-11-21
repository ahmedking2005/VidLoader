package com.ahmed.vidloader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One monitored channel / playlist (v1.10): new entries appearing after the
 * watch was added are auto-downloaded at the quick preset. [seenEntryIds]
 * holds the entry ids already known (newest first, bounded) — anything NOT
 * in it on a later check is new. [seeded] is false until the initial fetch
 * has recorded the existing back catalogue, so a watch whose seeding fetch
 * failed never downloads the whole history on its first successful check.
 */
data class WatchEntry(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val label: String,
    val seenEntryIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long = 0L,
    val enabled: Boolean = true,
    val seeded: Boolean = false
) {
    companion object {
        const val MAX_SEEN = 200
    }
}

/** JSON store for watches, next to link_history.json (same repository style). */
class WatchRepository(context: Context) {

    private val file = File(context.filesDir, "watches.json")
    private val lock = Any()

    fun load(): MutableList<WatchEntry> = synchronized(lock) {
        if (!file.exists()) return@synchronized mutableListOf()
        try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<WatchEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("url")
                if (url.isBlank()) continue
                val seenJson = o.optJSONArray("seenEntryIds") ?: JSONArray()
                val seen = mutableListOf<String>()
                for (j in 0 until seenJson.length()) {
                    val id = seenJson.optString(j)
                    if (id.isNotBlank()) seen += id
                }
                list += WatchEntry(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    url = url,
                    label = o.optString("label"),
                    seenEntryIds = seen,
                    createdAt = o.optLong("createdAt"),
                    lastCheckedAt = o.optLong("lastCheckedAt"),
                    enabled = o.optBoolean("enabled", true),
                    seeded = o.optBoolean("seeded", seen.isNotEmpty())
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun add(watch: WatchEntry) {
        synchronized(lock) {
            val list = load()
            list.add(0, watch)
            save(list)
        }
    }

    /** Replaces the stored copy of the watch with the same id (no-op when gone). */
    fun update(watch: WatchEntry) {
        synchronized(lock) {
            val list = load()
            val index = list.indexOfFirst { it.id == watch.id }
            if (index < 0) return
            list[index] = watch
            save(list)
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            val list = load()
            list.removeAll { it.id == id }
            save(list)
        }
    }

    private fun save(list: List<WatchEntry>) {
        val arr = JSONArray()
        for (watch in list) {
            val seenJson = JSONArray()
            for (id in watch.seenEntryIds.take(WatchEntry.MAX_SEEN)) seenJson.put(id)
            arr.put(
                JSONObject()
                    .put("id", watch.id)
                    .put("url", watch.url)
                    .put("label", watch.label)
                    .put("seenEntryIds", seenJson)
                    .put("createdAt", watch.createdAt)
                    .put("lastCheckedAt", watch.lastCheckedAt)
                    .put("enabled", watch.enabled)
                    .put("seeded", watch.seeded)
            )
        }
        file.writeText(arr.toString())
    }
}
