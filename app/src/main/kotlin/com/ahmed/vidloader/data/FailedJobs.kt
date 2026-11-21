package com.ahmed.vidloader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A job that finished with failures: [items] are exactly the items that
 * failed (after the service's automatic retries), kept so the user can
 * re-run just those from the library's «قيد التنزيل» tab. Items that
 * eventually succeeded — on any attempt — never appear here.
 */
data class FailedJob(
    /** Record id (NOT a job id: retrying mints a fresh job). */
    val id: String,
    val label: String,
    val isBatch: Boolean,
    val items: List<PausedJobItem>,
    /** Friendly error of the last failed item, when known. */
    val lastError: String?,
    val failedAtMillis: Long
) {
    /** Display title: the job label, falling back to the first failed item. */
    val title: String
        get() = label.ifBlank { items.firstOrNull()?.title.orEmpty() }
}

/** JSON store for failed jobs, next to paused_jobs.json. Survives process death. */
class FailedJobsRepository(context: Context) {

    private val file = File(context.filesDir, "failed_jobs.json")
    private val lock = Any()

    fun load(): MutableList<FailedJob> = synchronized(lock) {
        if (!file.exists()) return@synchronized mutableListOf()
        try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<FailedJob>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val itemsJson = o.optJSONArray("items") ?: JSONArray()
                val items = mutableListOf<PausedJobItem>()
                for (j in 0 until itemsJson.length()) {
                    items += PersistedItemJson.fromJson(itemsJson.getJSONObject(j), j)
                }
                if (items.isEmpty()) continue
                list += FailedJob(
                    id = o.getString("id"),
                    label = o.optString("label"),
                    isBatch = o.optBoolean("isBatch"),
                    items = items,
                    lastError = if (o.isNull("lastError")) null else o.optString("lastError"),
                    failedAtMillis = o.optLong("failedAtMillis")
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun getById(id: String): FailedJob? = load().firstOrNull { it.id == id }

    /** Inserts the record at the top (newest failure first). */
    fun add(job: FailedJob) = synchronized(lock) {
        val list = load()
        list.removeAll { it.id == job.id }
        list.add(0, job)
        save(list)
    }

    fun remove(id: String) = synchronized(lock) {
        val list = load()
        list.removeAll { it.id == id }
        save(list)
    }

    private fun save(list: List<FailedJob>) {
        val arr = JSONArray()
        for (job in list) {
            val itemsJson = JSONArray()
            for (item in job.items) {
                itemsJson.put(PersistedItemJson.toJson(item))
            }
            arr.put(
                JSONObject()
                    .put("id", job.id)
                    .put("label", job.label)
                    .put("isBatch", job.isBatch)
                    .put("items", itemsJson)
                    .put("lastError", job.lastError ?: JSONObject.NULL)
                    .put("failedAtMillis", job.failedAtMillis)
            )
        }
        file.writeText(arr.toString())
    }
}
