package com.ahmed.vidloader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One submitted link (v1.9): the URL the user sent to the downloader, the
 * title once it became known, when it was last submitted, and how the last
 * submission ended. Status values are the [STATUS] constants below.
 */
data class LinkHistoryEntry(
    val url: String,
    val title: String,
    val timestampMillis: Long,
    val status: String
) {
    companion object {
        const val STATUS_STARTED = "started"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * JSON store for the link history, next to failed_jobs.json / queue_jobs.json.
 * De-duplicated by URL: re-submitting a link updates its timestamp (and
 * title/status) and moves it to the top instead of adding a second row.
 */
class LinkHistoryRepository(context: Context) {

    private val file = File(context.filesDir, "link_history.json")
    private val lock = Any()

    fun load(): MutableList<LinkHistoryEntry> = synchronized(lock) {
        if (!file.exists()) return@synchronized mutableListOf()
        try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<LinkHistoryEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("url")
                if (url.isBlank()) continue
                list += LinkHistoryEntry(
                    url = url,
                    title = o.optString("title"),
                    timestampMillis = o.optLong("timestampMillis"),
                    status = o.optString("status", LinkHistoryEntry.STATUS_STARTED)
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /**
     * Records a submission. A blank incoming title never erases a title we
     * already learned for the same URL; a blank status keeps the old one.
     */
    fun record(url: String, title: String, status: String) {
        synchronized(lock) {
        if (url.isBlank()) return
        val list = load()
        val existing = list.firstOrNull { it.url == url }
        list.removeAll { it.url == url }
        list.add(
            0,
            LinkHistoryEntry(
                url = url,
                title = title.ifBlank { existing?.title.orEmpty() },
                timestampMillis = System.currentTimeMillis(),
                status = status.ifBlank { existing?.status ?: LinkHistoryEntry.STATUS_STARTED }
            )
        )
        save(list)
        }
    }

    /** Updates the status (and title, when a real one arrives) without touching the timestamp. */
    fun updateStatus(url: String, status: String, title: String? = null) {
        synchronized(lock) {
        val list = load()
        val index = list.indexOfFirst { it.url == url }
        if (index < 0) return
        val old = list[index]
        list[index] = old.copy(
            status = status,
            title = if (!title.isNullOrBlank()) title else old.title
        )
        save(list)
        }
    }

    fun remove(url: String) {
        synchronized(lock) {
            val list = load()
            list.removeAll { it.url == url }
            save(list)
        }
    }

    fun clear() {
        synchronized(lock) {
            save(mutableListOf())
        }
    }

    private fun save(list: List<LinkHistoryEntry>) {
        val arr = JSONArray()
        for (entry in list) {
            arr.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("timestampMillis", entry.timestampMillis)
                    .put("status", entry.status)
            )
        }
        file.writeText(arr.toString())
    }
}
