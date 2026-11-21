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
import com.ahmed.vidloader.databinding.ActivityInfoBinding
import com.ahmed.vidloader.download.DownloadCenter
import com.ahmed.vidloader.download.DownloadKind
import com.ahmed.vidloader.download.DownloadService
import com.ahmed.vidloader.download.DownloadSpec
import com.ahmed.vidloader.ui.FormatRow
import com.ahmed.vidloader.ui.FormatsAdapter
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
    private var pendingSpec: DownloadSpec? = null
    private var lastStatus: DownloadCenter.Status = DownloadCenter.Status.IDLE

    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val spec = pendingSpec
        pendingSpec = null
        if (spec == null) return@registerForActivityResult
        if (granted) doStartDownload(spec)
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
        binding.cancelDownloadButton.setOnClickListener {
            startService(DownloadService.cancelIntent(this))
        }

        loadInfo()
        observeDownloads()
    }

    private fun loadInfo() {
        binding.loading.visibility = View.VISIBLE
        binding.errorBox.visibility = View.GONE
        binding.infoScroll.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    val app = VidLoaderApp.instance
                    app.initEngineBlocking()
                    if (!app.engineReady) throw IllegalStateException("engine: ${app.engineError}")
                    YoutubeDL.getInstance().getInfo(videoUrl)
                }
                showInfo(info)
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

        rows += FormatRow.Header(getString(R.string.info_video_qualities))
        if (videoFormats.isEmpty()) {
            rows += FormatRow.Option(
                label = getString(R.string.best_quality),
                sub = "",
                iconRes = R.drawable.ic_play,
                spec = DownloadSpec(DownloadKind.VIDEO, selector = "best", label = getString(R.string.best_quality))
            )
        } else {
            val heights = videoFormats.map { it.height }.distinct().sortedDescending()
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
                spec = DownloadSpec(DownloadKind.VIDEO, selector = "bestvideo+bestaudio/best", label = "${getString(R.string.best_quality)} (${heights.first()}p)")
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

    private fun onOptionClicked(spec: DownloadSpec) {
        if (DownloadCenter.isBusy()) {
            Toast.makeText(this, R.string.download_already_running, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSpec = spec
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        doStartDownload(spec)
    }

    private fun doStartDownload(spec: DownloadSpec) {
        val intent = DownloadService.startIntent(this, videoUrl, videoTitle, spec)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
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
            DownloadCenter.Status.IDLE -> binding.downloadCard.visibility = View.GONE
        }
        lastStatus = state.status
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun start(context: Context, url: String) {
            context.startActivity(Intent(context, InfoActivity::class.java).putExtra(EXTRA_URL, url))
        }
    }
}
