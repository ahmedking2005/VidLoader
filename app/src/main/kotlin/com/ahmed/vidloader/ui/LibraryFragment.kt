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
import kotlinx.coroutines.launch
import java.io.File

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: LibraryRepository
    private lateinit var adapter: LibraryAdapter
    private lateinit var pausedRepo: PausedJobsRepository
    private lateinit var pausedAdapter: PausedAdapter

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
        pausedAdapter = PausedAdapter(
            onResume = { resumePaused(it) },
            onDelete = { confirmDeletePaused(it) }
        )
        binding.pausedList.layoutManager = LinearLayoutManager(requireContext())
        binding.pausedList.adapter = pausedAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadCenter.pausedJobs.collect { renderPaused(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
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
        // death); mirror it into the shared flow the observer renders from.
        if (::pausedRepo.isInitialized) {
            val paused = pausedRepo.load()
            DownloadCenter.pausedJobs.value = paused
            renderPaused(paused)
        }
    }

    private fun renderPaused(paused: List<PausedJob>) {
        if (_binding == null || !::pausedAdapter.isInitialized) return
        pausedAdapter.items = paused
        val visibility = if (paused.isEmpty()) View.GONE else View.VISIBLE
        binding.pausedHeader.visibility = visibility
        binding.pausedList.visibility = visibility
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
