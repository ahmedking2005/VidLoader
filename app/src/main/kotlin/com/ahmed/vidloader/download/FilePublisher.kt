package com.ahmed.vidloader.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.ahmed.vidloader.data.DownloadSettings
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Publishes a finished local file into the user's download folder, using the
 * exact same destination logic as DownloadService.publishFile: the SAF tree
 * chosen in «إعدادات التنزيل» when set (falling back to the default folder on
 * any failure), else Downloads/VidLoader via MediaStore (API 29+) or the
 * public directory (older). Returns a content:// uri, or an absolute path on
 * the legacy path — the library record handles both. Used by [ConvertService];
 * DownloadService keeps its own private copy from before v1.9.
 */
object FilePublisher {

    fun publish(context: Context, src: File, displayName: String, mime: String): String {
        val tree = DownloadSettings.treeUri(context)
        if (tree != null) {
            try {
                return publishToTree(context, src, displayName, mime, tree)
            } catch (e: Exception) {
                // Permission revoked / storage gone: fall back to the default folder.
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(context, src, displayName, mime)
        } else {
            publishToLegacyDir(src, displayName)
        }
    }

    private fun publishToTree(context: Context, src: File, displayName: String, mime: String, treeUri: Uri): String {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val name = uniqueTreeName(context, treeUri, treeDocId, displayName)
        val docUri = DocumentsContract.createDocument(resolver, parentDocUri, mime, name)
            ?: throw IOException("createDocument returned null")
        try {
            val out = resolver.openOutputStream(docUri) ?: throw IOException("openOutputStream returned null")
            out.use { output ->
                FileInputStream(src).use { it.copyTo(output) }
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
            throw e
        }
        return docUri.toString()
    }

    private fun uniqueTreeName(context: Context, treeUri: Uri, treeDocId: String, displayName: String): String {
        val existing = mutableSetOf<String>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (idx >= 0) cursor.getString(idx)?.let { existing += it }
                }
            }
        } catch (e: Exception) {
            return displayName
        }
        if (displayName !in existing) return displayName
        val base = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', "")
        var attempt = 1
        while (attempt <= 100) {
            val candidate = if (ext.isEmpty()) "$base ($attempt)" else "$base ($attempt).$ext"
            if (candidate !in existing) return candidate
            attempt++
        }
        return displayName
    }

    private fun publishToMediaStore(context: Context, src: File, displayName: String, mime: String): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var name = displayName
        var attempt = 0
        while (true) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VidLoader")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(src).use { it.copyTo(out) }
                    }
                    val done = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                    return uri.toString()
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
            attempt++
            if (attempt > 20) throw IOException("mediastore insert failed")
            val base = displayName.substringBeforeLast('.')
            val ext = displayName.substringAfterLast('.', "")
            name = if (ext.isEmpty()) "$base ($attempt)" else "$base ($attempt).$ext"
        }
    }

    private fun publishToLegacyDir(src: File, displayName: String): String {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidLoader")
        dir.mkdirs()
        var dest = File(dir, displayName)
        var attempt = 1
        while (dest.exists()) {
            val base = displayName.substringBeforeLast('.')
            val ext = displayName.substringAfterLast('.', "")
            dest = File(dir, if (ext.isEmpty()) "$base ($attempt)" else "$base ($attempt).$ext")
            attempt++
        }
        src.copyTo(dest, overwrite = false)
        return dest.absolutePath
    }
}
