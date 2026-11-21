package com.ahmed.vidloader.ui

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmed.vidloader.R
import com.ahmed.vidloader.data.LibraryItem
import com.ahmed.vidloader.data.LibraryRepository
import com.ahmed.vidloader.data.PausedJob
import com.ahmed.vidloader.data.PausedJobsRepository
import com.ahmed.vidloader.data.Staging
import com.ahmed.vidloader.databinding.FragmentLibraryBinding
import com.ahmed.vidloader.download.DownloadCenter
import com.ahmed.vidloader.download.DownloadService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: LibraryRepository
    private lateinit var adapter: LibraryAdapter
    private lateinit var pausedRepo: PausedJobsRepository
    private lateinit var activeAdapter: ActiveAdapter

    /** The initial tab is picked once, from whether anything is in progress when the screen opens. */
    private var initialTabChosen = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = LibraryRepository(requireContext())
        adapter = LibraryAdapter(
            onOpen = { openItem(it) },
            onShare = { shareItem(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.libraryList.layoutManager = LinearLayoutManager(requireContext())
        binding.libraryList.adapter = adapter

        pausedRepo = PausedJobsRepository(requireContext())
        activeAdapter = ActiveAdapter(
            onPause = { pauseActive(it) },
            onCancel = { cancelActive(it) },
            onResume = { resumePaused(it) },
            onDeletePaused = { confirmDeletePaused(it) }
        )
        binding.activeList.layoutManager = LinearLayoutManager(requireContext())
        binding.activeList.adapter = activeAdapter

        binding.libraryTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                showTab(tab?.position ?: 0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // In-progress rows: the service's live queue state, with the
                // persisted paused jobs merged in as PAUSED rows.
                combine(DownloadCenter.activeItems, DownloadCenter.pausedJobs) { active, paused ->
                    active + paused.map { it.toActiveItem() }
                }.collect { renderActive(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun showTab(position: Int) {
        if (_binding == null) return
        binding.activeContainer.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.completedContainer.visibility = if (position == 1) View.VISIBLE else View.GONE
    }

    private fun PausedJob.toActiveItem(): DownloadCenter.ActiveItem = DownloadCenter.ActiveItem(
        jobId = id,
        title = title,
        status = DownloadCenter.ActiveStatus.PAUSED,
        progress = progressPercent.toFloat(),
        isBatch = isBatch,
        queuePosition = queuePosition,
        queueTotal = queueTotal,
        failedCount = failedCount,
        pausedJob = this
    )

    private fun renderActive(rows: List<DownloadCenter.ActiveItem>) {
        if (_binding == null || !::activeAdapter.isInitialized) return
        activeAdapter.items = rows
        binding.activeEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.activeList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        if (!initialTabChosen) {
            initialTabChosen = true
            val tab = binding.libraryTabs.getTabAt(if (rows.isEmpty()) 1 else 0)
            if (tab != null && binding.libraryTabs.selectedTabPosition != tab.position) {
                tab.select()
            } else {
                showTab(binding.libraryTabs.selectedTabPosition)
            }
        }
    }

    private fun pauseActive(item: DownloadCenter.ActiveItem) {
        // Plain startService (the InfoActivity pattern): the rows only exist
        // while the service runs, and if it just stopped the intent no-ops.
        requireContext().startService(DownloadService.pauseJobIntent(requireContext(), item.jobId))
    }

    private fun cancelActive(item: DownloadCenter.ActiveItem) {
        requireContext().startService(DownloadService.cancelJobIntent(requireContext(), item.jobId))
    }

    private fun reload() {
        if (!::repo.isInitialized) return
        val all = repo.load()
        val alive = all.filter { exists(it) }
        if (alive.size != all.size) {
            repo.replaceAll(alive)
        }
        adapter.items = alive
        binding.emptyText.visibility = if (alive.isEmpty()) View.VISIBLE else View.GONE
        binding.libraryList.visibility = if (alive.isEmpty()) View.GONE else View.VISIBLE

        // Paused jobs: the repository is the source of truth (survives process
        // death); mirror it into the shared flow the in-progress tab renders.
        if (::pausedRepo.isInitialized) {
            DownloadCenter.pausedJobs.value = pausedRepo.load()
        }
    }

    private fun resumePaused(job: PausedJob) {
        ContextCompat.startForegroundService(requireContext(), DownloadService.resumeIntent(requireContext(), job.id))
        Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeletePaused(job: PausedJob) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_paused_message, job.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deletePaused(job)
            }
            .show()
    }

    private fun deletePaused(job: PausedJob) {
        pausedRepo.remove(job.id)
        Staging.deleteJob(requireContext(), job.id)
        val manager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(DownloadService.pausedNotificationId(job.id))
        DownloadCenter.pausedJobs.value = pausedRepo.load()
        Toast.makeText(requireContext(), R.string.paused_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun exists(item: LibraryItem): Boolean {
        return try {
            if (item.uri.startsWith("content://")) {
                requireContext().contentResolver.openInputStream(Uri.parse(item.uri))?.use { true } ?: false
            } else {
                File(item.uri).exists()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveUri(item: LibraryItem): Uri {
        return if (item.uri.startsWith("content://")) {
            Uri.parse(item.uri)
        } else {
            FileProvider.getUriForFile(requireContext(), "com.ahmed.vidloader.fileprovider", File(item.uri))
        }
    }

    private fun openItem(item: LibraryItem) {
        if (!exists(item)) {
            Toast.makeText(requireContext(), R.string.file_missing, Toast.LENGTH_SHORT).show()
            repo.remove(item.id)
            reload()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(resolveUri(item), item.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.error_no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareItem(item: LibraryItem) {
        if (!exists(item)) {
            Toast.makeText(requireContext(), R.string.file_missing, Toast.LENGTH_SHORT).show()
            repo.remove(item.id)
            reload()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mime
            putExtra(Intent.EXTRA_STREAM, resolveUri(item))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, item.fileName))
    }

    private fun confirmDelete(item: LibraryItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, item.fileName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteItem(item)
            }
            .show()
    }

    private fun deleteItem(item: LibraryItem) {
        var deleted = false
        try {
            deleted = if (item.uri.startsWith("content://")) {
                requireContext().contentResolver.delete(Uri.parse(item.uri), null, null) > 0
            } else {
                File(item.uri).delete()
            }
        } catch (e: Exception) {
            deleted = false
        }
        repo.remove(item.id)
        reload()
        Toast.makeText(
            requireContext(),
            if (deleted) R.string.deleted else R.string.file_missing,
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
