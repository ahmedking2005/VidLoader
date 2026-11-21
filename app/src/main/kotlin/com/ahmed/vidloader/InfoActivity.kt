package com.ahmed.vidloader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.ahmed.vidloader.data.PlaylistEntry
import com.ahmed.vidloader.data.PlaylistFetcher
import com.ahmed.vidloader.data.PlaylistInfo
import com.ahmed.vidloader.databinding.ActivityInfoBinding
import com.ahmed.vidloader.download.DownloadCenter
import com.ahmed.vidloader.download.DownloadKind
import com.ahmed.vidloader.download.DownloadService
import com.ahmed.vidloader.download.DownloadSpec
import com.ahmed.vidloader.ui.FormatRow
import com.ahmed.vidloader.ui.FormatsAdapter
import com.ahmed.vidloader.ui.PlaylistAdapter
import com.ahmed.vidloader.util.FormatUtils
import com.ahmed.vidloader.util.friendlyError
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInfoBinding
    private lateinit var adapter: FormatsAdapter

    private var videoUrl: String = ""
    private var videoTitle: String = ""
    private var pendingAction: (() -> Unit)? = null
    private var lastStatus: DownloadCenter.Status = DownloadCenter.Status.IDLE

    private var playlistInfo: PlaylistInfo? = null
    private var playlistAdapter: PlaylistAdapter? = null
    private var syncingSelectAll: Boolean = false

    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (action == null) return@registerForActivityResult
        if (granted) action()
        else Toast.makeText(this, R.string.error_storage_permission, Toast.LENGTH_LONG).show()
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* downloading proceeds regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.navigationIcon = ContextCompat.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)?.apply {
            setTint(ContextCompat.getColor(this@InfoActivity, android.R.color.white))
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        videoUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (videoUrl.isBlank()) {
            finish()
            return
        }

        adapter = FormatsAdapter { spec -> onOptionClicked(spec) }
        binding.formatsList.layoutManager = LinearLayoutManager(this)
        binding.formatsList.adapter = adapter

        binding.errorRetry.setOnClickListener { loadInfo() }
        binding.scheduleButton.setOnClickListener {
            // «جدولة التنزيل» (v1.10, single-video mode): pick a future
            // date+time; the URL downloads then at the quick preset.
            com.ahmed.vidloader.util.SchedulePicker.schedule(this, videoUrl, videoTitle)
        }
        binding.sectionToggle.setOnCheckedChangeListener { _, checked ->
            binding.sectionBox.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.pauseDownloadButton.setOnClickListener {
            // Pause the job this card is mirroring (with parallel downloads
            // that may not be the only one running); fall back to the global
            // pause when no job is mirrored.
            val jobId = DownloadCenter.state.value.jobId
            startService(
                if (jobId != null) DownloadService.pauseJobIntent(this, jobId)
                else DownloadService.pauseIntent(this)
            )
        }
        binding.cancelDownloadButton.setOnClickListener {
            val jobId = DownloadCenter.state.value.jobId
            startService(
                if (jobId != null) DownloadService.cancelJobIntent(this, jobId)
                else DownloadService.cancelIntent(this)
            )
        }

        loadInfo()
        observeDownloads()
    }

    private fun loadInfo() {
        binding.loading.visibility = View.VISIBLE
        binding.errorBox.visibility = View.GONE
        binding.infoScroll.visibility = View.GONE
        binding.playlistBox.visibility = View.GONE
        lifecycleScope.launch {
            try {
                // Probe first: a real playlist URL opens playlist mode; a single
                // video (even one carrying a list= parameter) stays on the
                // normal format-picker flow.
                val playlist = withContext(Dispatchers.IO) {
                    val app = VidLoaderApp.instance
                    app.initEngineBlocking()
                    if (!app.engineReady) throw IllegalStateException("engine: ${app.engineError}")
                    PlaylistFetcher.fetch(this@InfoActivity, videoUrl)
                }
                if (playlist != null) {
                    showPlaylist(playlist)
                } else {
                    val info = withContext(Dispatchers.IO) {
                        YoutubeDL.getInstance().getInfo(videoUrl)
                    }
                    showInfo(info)
                }
            } catch (e: Exception) {
                binding.loading.visibility = View.GONE
                binding.errorBox.visibility = View.VISIBLE
                binding.errorText.text = friendlyError(e)
            }
        }
    }

    private fun showInfo(info: VideoInfo) {
        binding.loading.visibility = View.GONE
        binding.infoScroll.visibility = View.VISIBLE

        videoTitle = info.title.orEmpty()
        // Link history (v1.9): the real title is known now — record/refresh it.
        com.ahmed.vidloader.data.LinkHistoryRepository(this)
            .record(videoUrl, videoTitle, com.ahmed.vidloader.data.LinkHistoryEntry.STATUS_STARTED)
        binding.videoTitle.text = info.title
        if (!info.thumbnail.isNullOrBlank()) {
            binding.thumbnail.load(info.thumbnail) {
                crossfade(true)
            }
        }
        val meta = mutableListOf<String>()
        if (info.duration > 0) meta += FormatUtils.duration(info.duration.toDouble())
        if (!info.extractor.isNullOrBlank()) meta += getString(R.string.from_site, info.extractor)
        binding.videoMeta.text = meta.joinToString(" • ")

        adapter.rows = buildRows(info)
    }

    private fun buildRows(info: VideoInfo): List<FormatRow> {
        val rows = mutableListOf<FormatRow>()
        val formats = info.formats ?: arrayListOf()

        fun sizeOf(f: VideoFormat): Long = maxOf(f.fileSize, f.fileSizeApproximate)
        fun isApprox(f: VideoFormat): Boolean = f.fileSize <= 0 && f.fileSizeApproximate > 0

        val audioFormats = formats.filter {
            (it.vcodec.isNullOrEmpty() || it.vcodec == "none") && !it.acodec.isNullOrEmpty() && it.acodec != "none"
        }
        val bestAudio = audioFormats.maxByOrNull { sizeOf(it) }
        val bestAudioSize = bestAudio?.let { sizeOf(it) } ?: 0L
        val bestAudioApprox = bestAudio?.let { isApprox(it) } ?: false

        val videoFormats = formats.filter {
            it.height > 0 && !it.vcodec.isNullOrEmpty() && it.vcodec != "none" &&
                !(it.formatNote?.contains("storyboard", ignoreCase = true) == true) &&
                !(it.formatId?.startsWith("sb") == true)
        }

        // Hard cap: the app never lists or downloads above 1080p.
        val heights = videoFormats.map { it.height }.distinct()
            .filter { it <= MAX_HEIGHT }.sortedDescending()

        rows += FormatRow.Header(getString(R.string.info_video_qualities))
        if (heights.isEmpty()) {
            rows += FormatRow.Option(
                label = getString(R.string.best_quality),
                sub = "",
                iconRes = R.drawable.ic_play,
                spec = DownloadSpec(DownloadKind.VIDEO, selector = BEST_SELECTOR, label = getString(R.string.best_quality))
            )
        } else {
            fun chosenFor(height: Int): VideoFormat =
                videoFormats.filter { it.height == height }
                    .maxWithOrNull(compareBy<VideoFormat>({ it.tbr }, { it.fps }, { sizeOf(it) }))!!

            fun videoSub(f: VideoFormat): String {
                val vSize = sizeOf(f)
                val needsAudio = f.acodec.isNullOrEmpty() || f.acodec == "none"
                val total = vSize + if (needsAudio) bestAudioSize else 0L
                val parts = mutableListOf<String>()
                val ext = f.ext.orEmpty()
                if (ext.isNotBlank()) parts += ext.uppercase()
                if (total > 0) parts += FormatUtils.sizeLabel(total, approx = isApprox(f) || (needsAudio && bestAudioApprox))
                return parts.joinToString(" • ")
            }

            val best = chosenFor(heights.first())
            rows += FormatRow.Option(
                label = "${getString(R.string.best_quality)} (${heights.first()}p)",
                sub = videoSub(best),
                iconRes = R.drawable.ic_play,
                spec = DownloadSpec(DownloadKind.VIDEO, selector = BEST_SELECTOR, label = "${getString(R.string.best_quality)} (${heights.first()}p)")
            )
            for (h in heights) {
                val f = chosenFor(h)
                rows += FormatRow.Option(
                    label = "${h}p",
                    sub = videoSub(f),
                    iconRes = R.drawable.ic_play,
                    spec = DownloadSpec(
                        DownloadKind.VIDEO,
                        selector = "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best",
                        label = "${h}p"
                    )
                )
            }
        }

        rows += FormatRow.Header(getString(R.string.info_audio_only))
        rows += FormatRow.Option(
            label = getString(R.string.audio_m4a),
            sub = if (bestAudioSize > 0) FormatUtils.sizeLabel(bestAudioSize, bestAudioApprox) else "",
            iconRes = R.drawable.ic_music,
            spec = DownloadSpec(DownloadKind.AUDIO_M4A, label = getString(R.string.audio_m4a))
        )
        rows += FormatRow.Option(
            label = getString(R.string.audio_mp3),
            sub = if (bestAudioSize > 0) FormatUtils.sizeLabel(bestAudioSize, bestAudioApprox) else "",
            iconRes = R.drawable.ic_music,
            spec = DownloadSpec(DownloadKind.AUDIO_MP3, label = getString(R.string.audio_mp3))
        )
        return rows
    }

    // region playlist mode

    private fun showPlaylist(playlist: PlaylistInfo) {
        playlistInfo = playlist
        binding.loading.visibility = View.GONE
        binding.infoScroll.visibility = View.GONE
        binding.playlistBox.visibility = View.VISIBLE

        videoTitle = playlist.title
        com.ahmed.vidloader.data.LinkHistoryRepository(this)
            .record(videoUrl, videoTitle, com.ahmed.vidloader.data.LinkHistoryEntry.STATUS_STARTED)
        binding.playlistTitle.text = playlist.title
        binding.playlistMeta.text = getString(R.string.playlist_meta_format, playlist.entries.size)

        val adapter = PlaylistAdapter(playlist.entries) { refreshPlaylistSelectionUi() }
        playlistAdapter = adapter
        binding.playlistList.layoutManager = LinearLayoutManager(this)
        binding.playlistList.adapter = adapter

        binding.qualityBest.isChecked = true
        binding.selectAll.setOnCheckedChangeListener { _, checked ->
            if (syncingSelectAll) return@setOnCheckedChangeListener
            playlist.entries.forEach { it.selected = checked }
            adapter.notifyDataSetChanged()
            refreshPlaylistSelectionUi()
        }
        syncingSelectAll = true
        binding.selectAll.isChecked = true
        syncingSelectAll = false

        binding.downloadSelected.setOnClickListener { onDownloadSelectedClicked() }
        refreshPlaylistSelectionUi()
    }

    private fun refreshPlaylistSelectionUi() {
        val playlist = playlistInfo ?: return
        val count = playlist.entries.count { it.selected }
        binding.downloadSelected.text = getString(R.string.download_selected, count)
        binding.downloadSelected.isEnabled = count > 0
        syncingSelectAll = true
        binding.selectAll.isChecked = count == playlist.entries.size
        syncingSelectAll = false
    }

    private fun onDownloadSelectedClicked() {
        val playlist = playlistInfo ?: return
        val selected = playlist.entries.filter { it.selected }
        if (selected.isEmpty()) return
        val spec = when (binding.playlistQuality.checkedRadioButtonId) {
            R.id.quality_720 -> DownloadSpec(
                DownloadKind.VIDEO,
                selector = "bestvideo[height<=720]+bestaudio/best[height<=720]/best",
                label = "720p"
            )
            R.id.quality_480 -> DownloadSpec(
                DownloadKind.VIDEO,
                selector = "bestvideo[height<=480]+bestaudio/best[height<=480]/best",
                label = "480p"
            )
            R.id.quality_mp3 -> DownloadSpec(DownloadKind.AUDIO_MP3, label = getString(R.string.audio_mp3))
            else -> DownloadSpec(DownloadKind.VIDEO, selector = BEST_SELECTOR, label = getString(R.string.best_quality))
        }
        val specWithSubs = spec.copy(subtitles = binding.subtitlesCheckPlaylist.isChecked)
        withStoragePermission { doStartPlaylist(specWithSubs, selected) }
    }

    private fun doStartPlaylist(spec: DownloadSpec, selected: List<PlaylistEntry>) {
        val intent = DownloadService.startBatchIntent(
            this,
            videoUrl,
            playlistInfo?.title ?: videoTitle,
            selected.map { it.index }.toIntArray(),
            selected.map { it.title }.toTypedArray(),
            spec
        )
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    // endregion

    /** Runs [action] now, or after the legacy storage permission is granted (API ≤ 28). */
    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAction = action
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        action()
    }

    private fun onOptionClicked(spec: DownloadSpec) {
        // No busy rejection: the service queues new jobs behind the running one.
        withStoragePermission { doStartDownload(spec) }
    }

    private fun doStartDownload(spec: DownloadSpec) {
        val section = readSection() ?: return
        val specWithSubs = spec.copy(
            subtitles = binding.subtitlesCheckSingle.isChecked,
            sectionStartSec = section.first,
            sectionEndSec = section.second
        )
        val intent = DownloadService.startIntent(this, videoUrl, videoTitle, specWithSubs)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    /**
     * Reads the «قصّ مقطع» fields (single downloads only — the section box
     * lives in the single-video layout and never shows in playlist mode).
     * Returns (startSec, endSec), both null when the cut is off or both
     * fields are empty; returns null when the input is invalid, after
     * explaining with a toast. An empty start means 0; an empty end means
     * "to the end of the video".
     */
    private fun readSection(): Pair<Long?, Long?>? {
        if (!binding.sectionToggle.isChecked) return Pair(null, null)
        val startText = binding.sectionStart.text?.toString()?.trim().orEmpty()
        val endText = binding.sectionEnd.text?.toString()?.trim().orEmpty()
        if (startText.isEmpty() && endText.isEmpty()) return Pair(null, null)
        val start = if (startText.isEmpty()) 0L else parseTimeToSeconds(startText)
        val end = if (endText.isEmpty()) null else parseTimeToSeconds(endText)
        if (start == null || (endText.isNotEmpty() && end == null) ||
            (end != null && start >= end)
        ) {
            Toast.makeText(this, R.string.section_invalid, Toast.LENGTH_LONG).show()
            return null
        }
        return Pair(start, end)
    }

    /**
     * Parses "90", "1:30" or "1:01:01" into seconds; null when malformed.
     * Arabic-Indic digits are normalized to ASCII first.
     */
    private fun parseTimeToSeconds(text: String): Long? {
        val normalized = text.map { ch ->
            if (ch in '٠'..'٩') '0' + (ch - '٠') else ch
        }.joinToString("")
        val parts = normalized.split(":")
        if (parts.size > 3) return null
        var total = 0L
        for (part in parts) {
            if (part.isEmpty() || !part.all { it.isDigit() }) return null
            total = total * 60 + part.toLong()
        }
        return total
    }

    private fun observeDownloads() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadCenter.state.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: DownloadCenter.State) {
        when (state.status) {
            DownloadCenter.Status.RUNNING, DownloadCenter.Status.PROCESSING -> {
                binding.downloadCard.visibility = View.VISIBLE
                binding.downloadTitle.text = state.title
                if (state.status == DownloadCenter.Status.PROCESSING) {
                    binding.downloadProgress.isIndeterminate = true
                    binding.downloadDetail.text = getString(R.string.processing)
                } else {
                    binding.downloadProgress.isIndeterminate = state.progress < 0
                    binding.downloadProgress.progress = state.progress.toInt().coerceIn(0, 100)
                    val detail = StringBuilder()
                    if (state.queueTotal > 1) {
                        detail.append(getString(R.string.playlist_progress, state.queuePosition, state.queueTotal))
                        if (state.failedCount > 0) {
                            detail.append(" • ").append(getString(R.string.playlist_failed_count, state.failedCount))
                        }
                        detail.append(" • ")
                    }
                    if (state.progress >= 0) detail.append(getString(R.string.download_progress, state.progress.toInt()))
                    if (state.etaSeconds > 0) detail.append(" • ").append(getString(R.string.eta_format, FormatUtils.eta(state.etaSeconds)))
                    if (state.speed.isNotBlank()) detail.append(" • ").append(state.speed)
                    binding.downloadDetail.text = detail.toString()
                }
            }
            DownloadCenter.Status.DONE -> {
                binding.downloadCard.visibility = View.GONE
                if (lastStatus != DownloadCenter.Status.DONE) {
                    Toast.makeText(this, R.string.download_done, Toast.LENGTH_LONG).show()
                }
            }
            DownloadCenter.Status.ERROR -> {
                binding.downloadCard.visibility = View.GONE
                if (lastStatus != DownloadCenter.Status.ERROR && !state.error.isNullOrBlank()) {
                    Toast.makeText(this, state.error, Toast.LENGTH_LONG).show()
                }
            }
            DownloadCenter.Status.CANCELLED -> {
                binding.downloadCard.visibility = View.GONE
                if (lastStatus != DownloadCenter.Status.CANCELLED) {
                    Toast.makeText(this, R.string.download_cancelled, Toast.LENGTH_SHORT).show()
                }
            }
            DownloadCenter.Status.PAUSED -> {
                binding.downloadCard.visibility = View.GONE
                if (lastStatus != DownloadCenter.Status.PAUSED) {
                    Toast.makeText(this, R.string.download_paused, Toast.LENGTH_LONG).show()
                }
            }
            DownloadCenter.Status.IDLE -> binding.downloadCard.visibility = View.GONE
        }
        lastStatus = state.status
    }

    companion object {
        private const val EXTRA_URL = "url"

        /** Hard cap: nothing above 1080p is ever listed or downloaded. */
        private const val MAX_HEIGHT = 1080
        private const val BEST_SELECTOR = "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"

        fun start(context: Context, url: String) {
            context.startActivity(Intent(context, InfoActivity::class.java).putExtra(EXTRA_URL, url))
        }
    }
}
