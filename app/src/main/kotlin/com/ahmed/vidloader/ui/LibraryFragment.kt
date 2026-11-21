package com.ahmed.vidloader.ui

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmed.vidloader.PlayerActivity
import com.ahmed.vidloader.R
import com.ahmed.vidloader.data.FailedJob
import com.ahmed.vidloader.data.FailedJobsRepository
import com.ahmed.vidloader.data.LibraryItem
import com.ahmed.vidloader.data.LibraryRepository
import com.ahmed.vidloader.data.PausedJob
import com.ahmed.vidloader.data.PausedJobsRepository
import com.ahmed.vidloader.data.Staging
import com.ahmed.vidloader.databinding.FragmentLibraryBinding
import com.ahmed.vidloader.download.DownloadCenter
import com.ahmed.vidloader.download.DownloadService
import com.ahmed.vidloader.util.FormatUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: LibraryRepository
    private lateinit var adapter: LibraryAdapter
    private lateinit var pausedRepo: PausedJobsRepository
    private lateinit var failedRepo: FailedJobsRepository
    private lateinit var activeAdapter: ActiveAdapter

    /** The initial tab is picked once, from whether anything is in progress when the screen opens. */
    private var initialTabChosen = false

    /** Completed items as loaded from the repository (repository order, stored sizes). */
    private var allItems: List<LibraryItem> = emptyList()

    /** Sizes resolved on demand for records whose stored size is missing (0). */
    private val sizeCache = mutableMapOf<String, Long>()
    private val sizeLookupsInFlight = mutableSetOf<String>()

    /** Completed-tab live search text (v1.9); empty = no text filter. */
    private var searchQuery: String = ""

    /** Completed-tab tag filter (v1.9); null = all tags. */
    private var selectedTag: String? = null

    private lateinit var sortPrefs: SharedPreferences

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = LibraryRepository(requireContext())
        sortPrefs = requireContext().getSharedPreferences(PREFS_LIBRARY, Context.MODE_PRIVATE)
        adapter = LibraryAdapter(
            onPlay = { playItem(it) },
            onOpen = { openItem(it) },
            onShare = { shareItem(it) },
            onDelete = { confirmDelete(it) },
            onSelectionChanged = { renderSelectionBar(it) },
            onTag = { showTagDialog(it) },
            onConvertMp3 = { startConvert(it, com.ahmed.vidloader.download.ConvertService.MODE_MP3) },
            onConvertCompress = { startConvert(it, com.ahmed.vidloader.download.ConvertService.MODE_COMPRESS) }
        )
        binding.libraryList.layoutManager = LinearLayoutManager(requireContext())
        binding.libraryList.adapter = adapter

        binding.librarySearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderCompleted()
            }
        })
        binding.historyButton.setOnClickListener {
            startActivity(Intent(requireContext(), com.ahmed.vidloader.HistoryActivity::class.java))
        }
        binding.watchButton.setOnClickListener {
            startActivity(Intent(requireContext(), com.ahmed.vidloader.WatchActivity::class.java))
        }

        binding.sortButton.setOnClickListener { showSortMenu() }
        binding.deleteSelectedButton.setOnClickListener { confirmDeleteSelected() }
        binding.cancelSelectionButton.setOnClickListener { adapter.exitSelection() }

        pausedRepo = PausedJobsRepository(requireContext())
        failedRepo = FailedJobsRepository(requireContext())
        activeAdapter = ActiveAdapter(
            onPause = { pauseActive(it) },
            onCancel = { cancelActive(it) },
            onResume = { resumePaused(it) },
            onDeletePaused = { confirmDeletePaused(it) },
            onRetryFailed = { retryFailed(it) },
            onDeleteFailed = { confirmDeleteFailed(it) }
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
                // persisted paused and failed jobs merged in as PAUSED /
                // FAILED rows.
                combine(
                    DownloadCenter.activeItems,
                    DownloadCenter.pausedJobs,
                    DownloadCenter.failedJobs
                ) { active, paused, failed ->
                    active + paused.map { it.toActiveItem() } + failed.map { it.toActiveItem() }
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
        // Selection belongs to the completed tab; leaving it resets the mode.
        if (position != 1 && ::adapter.isInitialized) adapter.exitSelection()
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

    private fun FailedJob.toActiveItem(): DownloadCenter.ActiveItem = DownloadCenter.ActiveItem(
        jobId = id,
        title = title,
        status = DownloadCenter.ActiveStatus.FAILED,
        isBatch = isBatch,
        failedCount = items.size,
        error = lastError,
        failedJob = this
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
        allItems = alive
        adapter.exitSelection()
        renderCompleted()
        resolveMissingSizes(alive)

        // Paused jobs: the repository is the source of truth (survives process
        // death); mirror it into the shared flow the in-progress tab renders.
        if (::pausedRepo.isInitialized) {
            DownloadCenter.pausedJobs.value = pausedRepo.load()
        }
        if (::failedRepo.isInitialized) {
            DownloadCenter.failedJobs.value = failedRepo.load()
        }
    }

    // region completed tab: sort, stats, on-demand sizes (v1.8)

    private enum class LibrarySort { NEWEST, OLDEST, LARGEST, NAME }

    private fun currentSort(): LibrarySort {
        val name = sortPrefs.getString(KEY_SORT, null)
        return LibrarySort.values().firstOrNull { it.name == name } ?: LibrarySort.NEWEST
    }

    /** The stored size when present, else the on-demand resolved size, else 0 (unknown). */
    private fun effectiveSize(item: LibraryItem): Long =
        if (item.size > 0) item.size else sizeCache[item.id] ?: 0L

    private fun sortItems(items: List<LibraryItem>): List<LibraryItem> = when (currentSort()) {
        LibrarySort.NEWEST -> items.sortedWith(
            compareByDescending<LibraryItem> { it.dateMillis }.thenBy { it.id }
        )
        LibrarySort.OLDEST -> items.sortedWith(
            compareBy<LibraryItem> { it.dateMillis }.thenBy { it.id }
        )
        LibrarySort.LARGEST -> items.sortedWith(
            compareByDescending<LibraryItem> { effectiveSize(it) }.thenByDescending { it.dateMillis }
        )
        LibrarySort.NAME -> items.sortedWith(
            compareBy<LibraryItem> { it.fileName.lowercase() }.thenByDescending { it.dateMillis }
        )
    }

    /** Renders the completed tab: sorted rows (with resolved sizes), the stats header, the empty state. */
    private fun renderCompleted() {
        if (_binding == null || !::adapter.isInitialized) return
        renderTagChips()
        val filtered = allItems.filter { item ->
            val matchesSearch = searchQuery.isEmpty() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                item.fileName.contains(searchQuery, ignoreCase = true)
            val matchesTag = selectedTag == null || item.tag == selectedTag
            matchesSearch && matchesTag
        }
        val sorted = sortItems(filtered).map { item ->
            val size = effectiveSize(item)
            if (size > 0 && size != item.size) item.copy(size = size) else item
        }
        adapter.items = sorted
        val total = sorted.sumOf { it.size }
        binding.completedStats.text =
            getString(R.string.library_stats, sorted.size, FormatUtils.humanSize(total))
        binding.emptyText.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        binding.libraryList.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
    }

    // region search, tags, convert (v1.9)

    /** Rebuilds the tag filter chips from the tags actually in use; keeps the current selection when it still exists. */
    private fun renderTagChips() {
        if (_binding == null) return
        val tags = allItems.map { it.tag }.filter { it.isNotBlank() }.distinct().sorted()
        if (selectedTag != null && selectedTag !in tags) selectedTag = null
        binding.tagChipsScroll.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
        binding.tagChips.removeAllViews()
        for (tag in tags) {
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = tag
                isCheckable = true
                isChecked = tag == selectedTag
            }
            chip.setOnClickListener {
                selectedTag = if (chip.isChecked) tag else null
                renderCompleted()
            }
            binding.tagChips.addView(chip)
        }
    }

    /** Tag picker: existing tags as a list, plus «وسم جديد» (type one) and «بدون وسم» (clear). */
    private fun showTagDialog(item: LibraryItem) {
        val tags = allItems.map { it.tag }.filter { it.isNotBlank() }.distinct().sorted()
        val options = mutableListOf<String>()
        options += tags
        options += getString(R.string.tag_new)
        options += getString(R.string.tag_none)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_tag)
            .setItems(Array<CharSequence>(options.size) { i -> options[i] }) { _, which ->
                when {
                    which < tags.size -> applyTag(item, tags[which])
                    which == tags.size -> showNewTagDialog(item)
                    else -> applyTag(item, "")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNewTagDialog(item: LibraryItem) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = item.tag
            setText(item.tag)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tag_new)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyTag(item, input.text.toString().trim())
            }
            .show()
    }

    private fun applyTag(item: LibraryItem, tag: String) {
        repo.setTag(item.id, tag)
        allItems = allItems.map { if (it.id == item.id) it.copy(tag = tag.trim()) else it }
        renderCompleted()
    }

    /** Starts a background conversion (ConvertService) for a completed item. */
    private fun startConvert(item: LibraryItem, mode: String) {
        if (!exists(item)) {
            Toast.makeText(requireContext(), R.string.file_missing, Toast.LENGTH_SHORT).show()
            repo.remove(item.id)
            reload()
            return
        }
        ContextCompat.startForegroundService(
            requireContext(),
            com.ahmed.vidloader.download.ConvertService.convertIntent(requireContext(), item.id, mode)
        )
        Toast.makeText(requireContext(), R.string.convert_started, Toast.LENGTH_SHORT).show()
    }

    // endregion

    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), binding.sortButton)
        popup.menuInflater.inflate(R.menu.library_sort, popup.menu)
        val checkedId = when (currentSort()) {
            LibrarySort.NEWEST -> R.id.sort_newest
            LibrarySort.OLDEST -> R.id.sort_oldest
            LibrarySort.LARGEST -> R.id.sort_largest
            LibrarySort.NAME -> R.id.sort_name
        }
        popup.menu.findItem(checkedId)?.isChecked = true
        popup.setOnMenuItemClickListener { menuItem: MenuItem ->
            val sort = when (menuItem.itemId) {
                R.id.sort_newest -> LibrarySort.NEWEST
                R.id.sort_oldest -> LibrarySort.OLDEST
                R.id.sort_largest -> LibrarySort.LARGEST
                R.id.sort_name -> LibrarySort.NAME
                else -> return@setOnMenuItemClickListener false
            }
            sortPrefs.edit().putString(KEY_SORT, sort.name).apply()
            adapter.exitSelection()
            renderCompleted()
            true
        }
        popup.show()
    }

    /**
     * Records whose stored size is missing (0 — written before sizes were
     * kept) get their real size from the content resolver / file system once,
     * off the main thread so the list never blocks on it. Resolved sizes are
     * cached, written back into library.json, and re-rendered. A failed
     * lookup leaves the size unknown: the row keeps showing «غير معروف» and
     * the item contributes 0 to the stats total.
     */
    private fun resolveMissingSizes(items: List<LibraryItem>) {
        val missing = items.filter {
            it.size <= 0 && sizeCache[it.id] == null && sizeLookupsInFlight.add(it.id)
        }
        if (missing.isEmpty()) return
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var resolvedAny = false
            for (item in missing) {
                val size = lookupSize(appContext, item)
                sizeLookupsInFlight.remove(item.id)
                if (size > 0) {
                    sizeCache[item.id] = size
                    resolvedAny = true
                }
            }
            if (resolvedAny) {
                withContext(Dispatchers.Main) {
                    val stored = repo.load().map { record ->
                        val size = sizeCache[record.id]
                        if (record.size <= 0 && size != null && size > 0) {
                            record.copy(size = size)
                        } else {
                            record
                        }
                    }
                    repo.replaceAll(stored)
                    allItems = allItems.map { item ->
                        val size = sizeCache[item.id]
                        if (item.size <= 0 && size != null && size > 0) {
                            item.copy(size = size)
                        } else {
                            item
                        }
                    }
                    renderCompleted()
                }
            }
        }
    }

    private fun lookupSize(context: Context, item: LibraryItem): Long {
        return try {
            if (item.uri.startsWith("content://")) {
                context.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r")
                    ?.use { it.statSize } ?: -1L
            } else {
                File(item.uri).length()
            }
        } catch (e: Exception) {
            -1L
        }
    }

    // endregion

    // region playback + bulk delete (v1.8)

    /**
     * Row tap: media files play in the built-in player; anything that is
     * neither audio nor video (no such items are produced today) falls back
     * to the external open. The popup menu keeps the external «فتح».
     */
    private fun playItem(item: LibraryItem) {
        if (!exists(item)) {
            Toast.makeText(requireContext(), R.string.file_missing, Toast.LENGTH_SHORT).show()
            repo.remove(item.id)
            reload()
            return
        }
        val isAudio = item.isAudio || item.mime.startsWith("audio/")
        val isVideo = item.mime.startsWith("video/")
        if (!isAudio && !isVideo) {
            openItem(item)
            return
        }
        PlayerActivity.start(
            requireContext(),
            resolveUri(item),
            item.title.ifBlank { item.fileName },
            isAudio
        )
    }

    private fun renderSelectionBar(count: Int) {
        if (_binding == null) return
        binding.selectionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) {
            binding.selectionCount.text = getString(R.string.delete_selected, count)
        }
    }

    private fun confirmDeleteSelected() {
        val targets = adapter.selectedItems()
        if (targets.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_selected_message, targets.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteSelected(targets)
            }
            .show()
    }

    /** Bulk delete: the same per-file semantics as the single delete, one confirmation, one summary toast. */
    private fun deleteSelected(targets: List<LibraryItem>) {
        for (item in targets) {
            deleteFile(item)
            repo.remove(item.id)
        }
        adapter.exitSelection()
        reload()
        Toast.makeText(
            requireContext(),
            getString(R.string.deleted_count, targets.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Deletes only the underlying file; record removal stays with the caller. */
    private fun deleteFile(item: LibraryItem): Boolean {
        return try {
            if (item.uri.startsWith("content://")) {
                requireContext().contentResolver.delete(Uri.parse(item.uri), null, null) > 0
            } else {
                File(item.uri).delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    // endregion

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

    private fun retryFailed(job: FailedJob) {
        // The service consumes the record and re-enqueues its items as a fresh job.
        ContextCompat.startForegroundService(requireContext(), DownloadService.retryFailedIntent(requireContext(), job.id))
        Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteFailed(job: FailedJob) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_failed_message, job.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteFailed(job)
            }
            .show()
    }

    private fun deleteFailed(job: FailedJob) {
        failedRepo.remove(job.id)
        DownloadCenter.failedJobs.value = failedRepo.load()
        Toast.makeText(requireContext(), R.string.failed_deleted, Toast.LENGTH_SHORT).show()
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
        val deleted = deleteFile(item)
        repo.remove(item.id)
        reload()
        Toast.makeText(
            requireContext(),
            if (deleted) R.string.deleted else R.string.file_missing,
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        private const val PREFS_LIBRARY = "library_prefs"
        private const val KEY_SORT = "sort_order"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
