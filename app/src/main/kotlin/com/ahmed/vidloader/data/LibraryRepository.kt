package com.ahmed.vidloader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibraryItem(
    val id: String,
    val title: String,
    val fileName: String,
    val size: Long,
    val dateMillis: Long,
    /** content:// uri string on API 29+, absolute file path on older versions. */
    val uri: String,
    val mime: String,
    val sourceUrl: String,
    val isAudio: Boolean
)

class LibraryRepository(context: Context) {

    private val file = File(context.filesDir, "library.json")
    private val lock = Any()

    fun load(): MutableList<LibraryItem> = synchronized(lock) {
        if (!file.exists()) return@synchronized mutableListOf()
        try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<LibraryItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    LibraryItem(
                        id = o.getString("id"),
                        title = o.optString("title"),
                        fileName = o.optString("fileName"),
                        size = o.optLong("size"),
                        dateMillis = o.optLong("dateMillis"),
                        uri = o.optString("uri"),
                        mime = o.optString("mime", "video/*"),
                        sourceUrl = o.optString("sourceUrl"),
                        isAudio = o.optBoolean("isAudio")
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun add(item: LibraryItem) = synchronized(lock) {
        val list = load()
        list.add(0, item)
        save(list)
    }

    fun remove(id: String) = synchronized(lock) {
        val list = load()
        list.removeAll { it.id == id }
        save(list)
    }

    private fun save(list: List<LibraryItem>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("fileName", item.fileName)
                    .put("size", item.size)
                    .put("dateMillis", item.dateMillis)
                    .put("uri", item.uri)
                    .put("mime", item.mime)
                    .put("sourceUrl", item.sourceUrl)
                    .put("isAudio", item.isAudio)
            )
        }
        file.writeText(arr.toString())
    }
}
