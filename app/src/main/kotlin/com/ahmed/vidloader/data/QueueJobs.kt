package com.ahmed.vidloader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A job whose work is not finished yet: waiting in the queue (never started,
 * possibly blocked by the mobile-data policy) or interrupted mid-run by
 * process death. [items] always holds the NOT-YET-FINISHED tail — the
 * service rewrites the record at every item transition, exactly like the
 * pause-persist path — so a restored job resumes where it stopped instead
 * of starting over. An unexpanded quick playlist has no items yet and is
 * carried by [expandUrl] / [expandKind] / [expandSelector].
 */
data class QueueJob(
    /** Stable job id; also the name of the job's staging directory. */
    val id: String,
    val label: String,
    val isBatch: Boolean,
    val items: List<PausedJobItem>,
    /** Items of the original job that sit before [items] (display positions). */
    val positionOffset: Int = 0,
    /** Unexpanded quick playlist: the URL to expand when the job starts. */
    val expandUrl: String? = null,
    /** DownloadKind name for the expansion preset. */
    val expandKind: String? = null,
    val expandSelector: String? = null
)

/**
 * JSON store for the live queue, next to paused_jobs.json. The service
 * rewrites the whole file on every queue mutation (submit / start / item
 * transition / finish / pause / cancel), so what is on disk is always the
 * set of jobs that still owe work. Survives process death.
 */
class QueueJobsRepository(context: Context) {

    private val file = File(context.filesDir, "queue_jobs.json")
    private val lock = Any()

    fun load(): MutableList<QueueJob> = synchronized(lock) {
        if (!file.exists()) return@synchronized mutableListOf()
        try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<QueueJob>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val itemsJson = o.optJSONArray("items") ?: JSONArray()
                val items = mutableListOf<PausedJobItem>()
                for (j in 0 until itemsJson.length()) {
                    items += PersistedItemJson.fromJson(itemsJson.getJSONObject(j), j)
                }
                val expandUrl = if (o.isNull("expandUrl")) null else o.optString("expandUrl")
                // A record with neither items nor an expansion target has no work left.
                if (items.isEmpty() && expandUrl == null) continue
                list += QueueJob(
                    id = o.getString("id"),
                    label = o.optString("label"),
                    isBatch = o.optBoolean("isBatch"),
                    items = items,
                    positionOffset = o.optInt("positionOffset"),
                    expandUrl = expandUrl,
                    expandKind = if (o.isNull("expandKind")) null else o.optString("expandKind"),
                    expandSelector = if (o.isNull("expandSelector")) null else o.optString("expandSelector")
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** Replaces the whole store with [jobs] (the service's full queue snapshot). */
    fun replaceAll(jobs: List<QueueJob>) = synchronized(lock) {
        val arr = JSONArray()
        for (job in jobs) {
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
                    .put("positionOffset", job.positionOffset)
                    .put("expandUrl", job.expandUrl ?: JSONObject.NULL)
                    .put("expandKind", job.expandKind ?: JSONObject.NULL)
                    .put("expandSelector", job.expandSelector ?: JSONObject.NULL)
            )
        }
        file.writeText(arr.toString())
    }
}
