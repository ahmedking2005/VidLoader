package com.ahmed.vidloader

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmed.vidloader.data.PlaylistFetcher
import com.ahmed.vidloader.data.ScheduledJob
import com.ahmed.vidloader.data.ScheduledJobsRepository
import com.ahmed.vidloader.data.WatchEntry
import com.ahmed.vidloader.data.WatchRepository
import com.ahmed.vidloader.databinding.ActivityWatchBinding
import com.ahmed.vidloader.ui.ScheduledAdapter
import com.ahmed.vidloader.ui.WatchAdapter
import com.ahmed.vidloader.util.FormatUtils
import com.ahmed.vidloader.work.WorkScheduler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «المراقبة» (v1.10): channels / playlists to watch for NEW videos (auto
 * quick-downloaded at the preset, checked about every 6 hours), plus the
 * «التنزيلات المجدولة» section — scheduled downloads with their planned
 * time and a cancel action.
 *
 * Adding a watch seeds it with the entries that already exist, so only
 * videos published AFTER the add are ever downloaded.
 */
class WatchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWatchBinding
    private lateinit var watchRepo: WatchRepository
    private lateinit var scheduledRepo: ScheduledJobsRepository
    private lateinit var watchAdapter: WatchAdapter
    private lateinit var scheduledAdapter: ScheduledAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWatchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        watchRepo = WatchRepository(this)
        scheduledRepo = ScheduledJobsRepository(this)

        watchAdapter = WatchAdapter(
            onToggle = { watch, enabled ->
                watchRepo.update(watch.copy(enabled = enabled))
            },
            onDelete = { watch -> confirmDeleteWatch(watch) }
        )
        binding.watchList.layoutManager = LinearLayoutManager(this)
        binding.watchList.adapter = watchAdapter

        scheduledAdapter = ScheduledAdapter(onCancel = { job -> cancelScheduled(job) })
        binding.scheduledList.layoutManager = LinearLayoutManager(this)
        binding.scheduledList.adapter = scheduledAdapter

        binding.watchAddButton.setOnClickListener { onAddWatchClicked() }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val watches = watchRepo.load()
        watchAdapter.items = watches
        binding.watchEmpty.visibility = if (watches.isEmpty()) View.VISIBLE else View.GONE
        binding.watchList.visibility = if (watches.isEmpty()) View.GONE else View.VISIBLE

        val jobs = scheduledRepo.load()
        scheduledAdapter.items = jobs
        binding.scheduledEmpty.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
        binding.scheduledList.visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun onAddWatchClicked() {
        val rawUrl = binding.watchUrlInput.text?.toString()?.trim().orEmpty()
        val url = FormatUtils.extractUrl(rawUrl)
        if (url == null || !url.startsWith("http")) {
            Toast.makeText(this, R.string.watch_invalid_url, Toast.LENGTH_LONG).show()
            return
        }
        val label = binding.watchLabelInput.text?.toString()?.trim().orEmpty()
        binding.watchAddButton.isEnabled = false
        binding.watchStatus.visibility = View.VISIBLE
        binding.watchStatus.setText(R.string.watch_seeding)

        lifecycleScope.launch {
            // Seed with what already exists: monitoring starts from the
            // NEXT new video, the back catalogue is never downloaded.
            val seedIds = withContext(Dispatchers.IO) {
                try {
                    val app = VidLoaderApp.instance
                    app.initEngineBlocking()
                    if (!app.engineReady) {
                        null
                    } else {
                        PlaylistFetcher.fetchFlatEntries(this@WatchActivity, url)?.map { it.id }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            val watch = WatchEntry(
                url = url,
                label = label,
                seenEntryIds = seedIds?.take(WatchEntry.MAX_SEEN) ?: emptyList(),
                lastCheckedAt = if (seedIds != null) System.currentTimeMillis() else 0L,
                seeded = seedIds != null
            )
            watchRepo.add(watch)
            WorkScheduler.ensureWatchPeriodic(this@WatchActivity)
            binding.watchUrlInput.text?.clear()
            binding.watchLabelInput.text?.clear()
            binding.watchAddButton.isEnabled = true
            binding.watchStatus.visibility = View.GONE
            Toast.makeText(
                this@WatchActivity,
                if (seedIds != null) R.string.watch_seeded else R.string.watch_added_unseeded,
                Toast.LENGTH_LONG
            ).show()
            reload()
        }
    }

    private fun confirmDeleteWatch(watch: WatchEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.watch_delete_message, watch.label.ifBlank { watch.url }))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                watchRepo.remove(watch.id)
                reload()
            }
            .show()
    }

    private fun cancelScheduled(job: ScheduledJob) {
        WorkScheduler.cancelScheduled(this, job.id)
        scheduledRepo.remove(job.id)
        reload()
    }
}
