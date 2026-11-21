package com.ahmed.vidloader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One scheduled download (v1.10): a URL to quick-download at
 * [triggerAtMillis]. The record is removed once its WorkManager request has
 * fired and submitted the download (or when the user cancels it).
 */
data class ScheduledJob(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String = "",
    val triggerAtMillis: Long,
    val createdAt: Long = System.currentTimeMillis()
)

/** JSON store for scheduled jobs, next to watches.json (same repository style). */
class ScheduledJobsRepository(context: Context) {

    private val file = File(context.filesDir, "scheduled_jobs.json")
    private val lock = Any()

    fun load(): MutableList<ScheduledJob> = synchronized(lock) {
        if (!file.exists()) return@synchronized mutableListOf()
        try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<ScheduledJob>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("url")
                if (url.isBlank()) continue
                list += ScheduledJob(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    url = url,
                    title = o.optString("title"),
                    triggerAtMillis = o.optLong("triggerAtMillis"),
                    createdAt = o.optLong("createdAt")
                )
            }
            list.sortBy { it.triggerAtMillis }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun add(job: ScheduledJob) {
        synchronized(lock) {
            val list = load()
            list += job
            save(list)
        }
    }

    fun get(id: String): ScheduledJob? = load().firstOrNull { it.id == id }

    fun remove(id: String) {
        synchronized(lock) {
            val list = load()
            list.removeAll { it.id == id }
            save(list)
        }
    }

    private fun save(list: List<ScheduledJob>) {
        val arr = JSONArray()
        for (job in list) {
            arr.put(
                JSONObject()
                    .put("id", job.id)
                    .put("url", job.url)
                    .put("title", job.title)
                    .put("triggerAtMillis", job.triggerAtMillis)
                    .put("createdAt", job.createdAt)
            )
        }
        file.writeText(arr.toString())
    }
}
