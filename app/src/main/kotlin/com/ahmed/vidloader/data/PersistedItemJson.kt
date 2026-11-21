package com.ahmed.vidloader.data

import org.json.JSONObject

/**
 * JSON round-trip for [PausedJobItem], the persisted form of a work item.
 * Shared by the queue store and the failed-jobs store (the paused store
 * predates this helper and writes the same keys inline).
 */
object PersistedItemJson {

    fun toJson(item: PausedJobItem): JSONObject = JSONObject()
        .put("url", item.url)
        .put("title", item.title)
        .put("kind", item.kind)
        .put("selector", item.selector ?: JSONObject.NULL)
        .put("subtitles", item.subtitles)
        .put("playlistIndex", item.playlistIndex ?: JSONObject.NULL)
        .put("slot", item.slot)
        .put("quick", item.quick)
        .put("sectionStart", item.sectionStartSec ?: JSONObject.NULL)
        .put("sectionEnd", item.sectionEndSec ?: JSONObject.NULL)

    fun fromJson(o: JSONObject, fallbackSlot: Int): PausedJobItem = PausedJobItem(
        url = o.getString("url"),
        title = o.optString("title"),
        kind = o.optString("kind", "VIDEO"),
        selector = if (o.isNull("selector")) null else o.optString("selector"),
        subtitles = o.optBoolean("subtitles"),
        playlistIndex = if (o.isNull("playlistIndex")) null else o.optInt("playlistIndex"),
        slot = o.optInt("slot", fallbackSlot),
        quick = o.optBoolean("quick"),
        sectionStartSec = if (o.isNull("sectionStart")) null else o.optLong("sectionStart"),
        sectionEndSec = if (o.isNull("sectionEnd")) null else o.optLong("sectionEnd")
    )
}
