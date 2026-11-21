package com.ahmed.vidloader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One remaining item of a paused job (mirrors the service's work item, minus runtime state). */
data class PausedJobItem(
    val url: String,
    val title: String,
    /** DownloadKind name. */
    val kind: String,
    val selector: String?,
    val subtitles: Boolean,
    /** 1-based playlist entry index, or null for a standalone download. */
    val playlistIndex: Int?,
    /** Stable position of this item inside its job; keys the item's staging directory. */
    val slot: Int
)

/**
 * A job stopped with ⏸ "pause". Only the items that had not finished yet are
 * stored; the first item's partial files still sit in its staging directory
 * (see [Staging]), so resuming re-runs yt-dlp against the same output path
 * and it continues from the .part data instead of starting over.
 */
data class PausedJob(
    /** Stable job id; also the name of the job's staging directory. */
    val id: String,
    val label: String,
    val isBatch: Boolean,
    val items: List<PausedJobItem>,
    /** Last known progress % of the first remaining item (0 when unknown). */
    val progressPercent: Int,
    /** Items of this job that had already failed (skipped) before the pause. */
    val failedCount: Int,
    /** 1-based position of the first remaining item inside the original job. */
    val queuePosition: Int,
    /** Total items the job started with. */
    val queueTotal: Int,
    val pausedAtMillis: Long
) {
    /** Display title: the current item's title, falling back to the job label. */
    val title: String
        get() = items.firstOrNull()?.title?.takeIf { it.isNotBlank() } ?: label
}

/** JSON store for paused jobs, next to library.json. Survives process death / app restart. */
class PausedJobsRepository(context: Context) {

    private val file = File(context.filesDir, "paused_jobs.json")
    private val lock = Any()

    fun load(): MutableList<PausedJob> = synchronized(lock) {
        if (!file.exists()) return@synchronized mutableListOf()
        try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<PausedJob>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val itemsJson = o.optJSONArray("items") ?: JSONArray()
                val items = mutableListOf<PausedJobItem>()
                for (j in 0 until itemsJson.length()) {
                    val io = itemsJson.getJSONObject(j)
                    items += PausedJobItem(
                        url = io.getString("url"),
                        title = io.optString("title"),
                        kind = io.optString("kind", "VIDEO"),
                        selector = if (io.isNull("selector")) null else io.optString("selector"),
                        subtitles = io.optBoolean("subtitles"),
                        playlistIndex = if (io.isNull("playlistIndex")) null else io.optInt("playlistIndex"),
                        slot = io.optInt("slot", j)
                    )
                }
                if (items.isEmpty()) continue
                list += PausedJob(
                    id = o.getString("id"),
                    label = o.optString("label"),
                    isBatch = o.optBoolean("isBatch"),
                    items = items,
                    progressPercent = o.optInt("progressPercent"),
                    failedCount = o.optInt("failedCount"),
                    queuePosition = o.optInt("queuePosition", 1),
                    queueTotal = o.optInt("queueTotal", items.size),
                    pausedAtMillis = o.optLong("pausedAtMillis")
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun getById(id: String): PausedJob? = load().firstOrNull { it.id == id }

    /** Inserts the record, or replaces the existing one with the same job id (re-pause). */
    fun addOrUpdate(job: PausedJob) = synchronized(lock) {
        val list = load()
        val index = list.indexOfFirst { it.id == job.id }
        if (index >= 0) list[index] = job else list.add(0, job)
        save(list)
    }

    fun remove(id: String) = synchronized(lock) {
        val list = load()
        list.removeAll { it.id == id }
        save(list)
    }

    private fun save(list: List<PausedJob>) {
        val arr = JSONArray()
        for (job in list) {
            val itemsJson = JSONArray()
            for (item in job.items) {
                itemsJson.put(
                    JSONObject()
                        .put("url", item.url)
                        .put("title", item.title)
                        .put("kind", item.kind)
                        .put("selector", item.selector ?: JSONObject.NULL)
                        .put("subtitles", item.subtitles)
                        .put("playlistIndex", item.playlistIndex ?: JSONObject.NULL)
                        .put("slot", item.slot)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", job.id)
                    .put("label", job.label)
                    .put("isBatch", job.isBatch)
                    .put("items", itemsJson)
                    .put("progressPercent", job.progressPercent)
                    .put("failedCount", job.failedCount)
                    .put("queuePosition", job.queuePosition)
                    .put("queueTotal", job.queueTotal)
                    .put("pausedAtMillis", job.pausedAtMillis)
            )
        }
        file.writeText(arr.toString())
    }
}

/**
 * On-disk staging layout. Paths are a pure function of (jobId, slot) — never
 * of the attempt — which is exactly what makes yt-dlp resume work: re-running
 * with the same -o template finds the previous attempt's .part files.
 */
object Staging {

    fun root(context: Context): File = File(context.getExternalFilesDir(null), "staging")

    fun jobDir(context: Context, jobId: String): File = File(root(context), jobId)

    fun itemDir(context: Context, jobId: String, slot: Int): File =
        File(jobDir(context, jobId), slot.toString())

    /** Removes every partial kept for [jobId] (cancel / delete-paused). */
    fun deleteJob(context: Context, jobId: String) {
        jobDir(context, jobId).deleteRecursively()
    }
}
