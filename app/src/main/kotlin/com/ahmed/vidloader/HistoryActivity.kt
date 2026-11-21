package com.ahmed.vidloader

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmed.vidloader.data.LinkHistoryEntry
import com.ahmed.vidloader.data.LinkHistoryRepository
import com.ahmed.vidloader.databinding.ActivityHistoryBinding
import com.ahmed.vidloader.ui.HistoryAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * «سجل الروابط» (v1.9): every link ever submitted to the downloader,
 * newest first. Tap re-opens the link in the normal InfoActivity fetch
 * flow; long-press deletes one entry; the header button clears all.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var repo: LinkHistoryRepository
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = LinkHistoryRepository(this)
        adapter = HistoryAdapter(
            onOpen = { entry -> InfoActivity.start(this, entry.url) },
            onDelete = { entry -> confirmDelete(entry) }
        )
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        binding.clearHistoryButton.setOnClickListener { confirmClear() }
        reload()
    }

    private fun reload() {
        val entries = repo.load()
        adapter.items = entries
        binding.historyEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.historyList.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        binding.clearHistoryButton.isEnabled = entries.isNotEmpty()
    }

    private fun confirmDelete(entry: LinkHistoryEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.history_delete_message, entry.title.ifBlank { entry.url }))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.remove(entry.url)
                reload()
            }
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_history)
            .setMessage(R.string.history_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_history) { _, _ ->
                repo.clear()
                reload()
            }
            .show()
    }
}
