package com.ahmed.vidloader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmed.vidloader.InfoActivity
import com.ahmed.vidloader.R
import com.ahmed.vidloader.databinding.FragmentBrowserBinding
import com.ahmed.vidloader.download.DownloadKind
import com.ahmed.vidloader.download.DownloadService
import com.ahmed.vidloader.download.DownloadSpec
import com.ahmed.vidloader.util.PageVideo
import com.ahmed.vidloader.util.PageVideoMatch
import java.net.URLDecoder
import java.net.URLEncoder

class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    /** Sniffed direct-media URLs on the current page (de-duplicated, insertion-ordered). */
    private val detected = LinkedHashSet<String>()

    /** The current page recognised as a video page (e.g. a YouTube watch page), if any. */
    private var pageVideo: PageVideoMatch? = null
    private lateinit var detectedAdapter: DetectedAdapter
    private var pendingDirect: DetectedMedia? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (_binding != null && binding.browserWeb.canGoBack()) {
                binding.browserWeb.goBack()
            }
        }
    }

    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val item = pendingDirect
        pendingDirect = null
        if (item == null) return@registerForActivityResult
        if (granted) doDirectDownload(item)
        else Toast.makeText(requireContext(), R.string.error_storage_permission, Toast.LENGTH_LONG).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        detectedAdapter = DetectedAdapter(
            onDirectDownload = { item -> onDirectClicked(item) },
            onOpenInDownloader = { item -> InfoActivity.start(requireContext(), item.url) }
        )
        binding.detectedList.layoutManager = LinearLayoutManager(requireContext())
        binding.detectedList.adapter = detectedAdapter

        setupWebView()

        binding.browserGo.setOnClickListener { submitAddress() }
        binding.browserUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitAddress()
                true
            } else {
                false
            }
        }
        binding.browserBack.setOnClickListener {
            if (binding.browserWeb.canGoBack()) binding.browserWeb.goBack()
        }
        binding.browserForward.setOnClickListener {
            if (binding.browserWeb.canGoForward()) binding.browserWeb.goForward()
        }
        binding.browserReload.setOnClickListener { binding.browserWeb.reload() }
        binding.browserDownloadPage.setOnClickListener {
            val current = binding.browserWeb.url
            if (current.isNullOrBlank()) {
                Toast.makeText(requireContext(), R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
            } else {
                InfoActivity.start(requireContext(), current)
            }
        }
        binding.browserDetectedButton.setOnClickListener {
            // Always open the panel: when nothing was detected it shows a hint
            // explaining the «تنزيل الصفحة» fallback instead of a blank list.
            binding.detectedPanel.visibility =
                if (binding.detectedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.detectedClose.setOnClickListener { binding.detectedPanel.visibility = View.GONE }

        refreshDetectedUi()
        if (binding.browserWeb.url == null) {
            binding.browserWeb.loadUrl(HOME_URL)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        updateBackCallback()
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        val web = binding.browserWeb
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            userAgentString = DESKTOP_UA
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (_binding == null) return
                binding.browserProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                binding.browserProgress.progress = newProgress
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // A new page means a fresh detection list.
                detected.clear()
                pageVideo = null
                if (_binding != null) {
                    refreshDetectedUi()
                    binding.detectedPanel.visibility = View.GONE
                    binding.browserProgress.visibility = View.VISIBLE
                    if (url != null && !binding.browserUrl.hasFocus()) binding.browserUrl.setText(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (_binding == null) return
                binding.browserProgress.visibility = View.GONE
                if (url != null && !binding.browserUrl.hasFocus()) binding.browserUrl.setText(url)
                updatePageVideo(url)
                binding.browserBack.isEnabled = web.canGoBack()
                binding.browserForward.isEnabled = web.canGoForward()
                updateBackCallback()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // SPA navigations (YouTube, Instagram…) change the URL without a
                // full page load, so re-check the page-video patterns here too.
                if (_binding != null) updatePageVideo(url)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()
                if (url != null && isMediaUrl(url)) {
                    val added = synchronized(detected) { detected.add(url) }
                    if (added) {
                        view?.post { if (_binding != null) refreshDetectedUi() }
                    }
                }
                return null // never actually intercept; just observe
            }
        }
    }

    /**
     * Direct-media test for sniffed requests. A URL counts when a known media
     * extension ends the path (…/clip.mp4) or a query value (…/stream?file=clip.mp4).
     * YouTube's googlevideo playback URLs are deliberately excluded: they are
     * session/IP-bound split streams that cannot be downloaded directly —
     * YouTube pages are covered by the page-video patterns instead.
     */
    private fun isMediaUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        if (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) return false
        val path = uri.path?.lowercase().orEmpty()
        if (MEDIA_EXTENSIONS.any { path.endsWith(it) }) return true
        val query = uri.query?.lowercase().orEmpty()
        if (query.isBlank()) return false
        return query.split('&').any { part ->
            val value = part.substringAfter('=', "")
            MEDIA_EXTENSIONS.any { value.endsWith(it) }
        }
    }

    /** Re-evaluates whether the current page is a known video page. */
    private fun updatePageVideo(url: String?) {
        val match = PageVideo.detect(url)
        if (match != pageVideo) {
            pageVideo = match
            if (_binding != null) refreshDetectedUi()
        }
    }

    private fun submitAddress() {
        val raw = binding.browserUrl.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        binding.browserUrl.clearFocus()
        binding.browserWeb.loadUrl(resolveInput(raw))
    }

    /** A term with no dot / with spaces is a search; anything URL-shaped is loaded directly. */
    private fun resolveInput(raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val looksLikeHost = !raw.contains(" ") && raw.contains(".")
        return if (looksLikeHost) {
            "https://$raw"
        } else {
            "https://duckduckgo.com/?q=" + URLEncoder.encode(raw, "UTF-8")
        }
    }

    private fun fileNameOf(url: String): String {
        val segment = Uri.parse(url).lastPathSegment.orEmpty()
        val decoded = runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
        return decoded.ifBlank { getString(R.string.shared_video) }
    }

    private fun refreshDetectedUi() {
        val sniffed = synchronized(detected) {
            detected.map { DetectedMedia(url = it, fileName = fileNameOf(it)) }
        }
        // The recognised page video is pinned above any sniffed direct files.
        val pageEntry = pageVideo?.let {
            DetectedMedia(
                url = it.url,
                fileName = getString(R.string.detected_page_video, it.siteName),
                isPageVideo = true
            )
        }
        val items = listOfNotNull(pageEntry) + sniffed
        binding.browserDetectedButton.text = getString(R.string.detected_videos, items.size)
        detectedAdapter.items = items
        // Empty state: a hint inside the panel instead of a blank list.
        binding.detectedEmptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.detectedList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateBackCallback() {
        backCallback.isEnabled = !isHidden && _binding != null && binding.browserWeb.canGoBack()
    }

    private fun onDirectClicked(item: DetectedMedia) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDirect = item
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        doDirectDownload(item)
    }

    private fun doDirectDownload(item: DetectedMedia) {
        // yt-dlp downloads direct media URLs natively, so the raw URL goes through
        // the normal service path with a DIRECT spec — same staging, publishing
        // (Downloads/VidLoader) and library handling as extracted downloads.
        val spec = DownloadSpec(DownloadKind.DIRECT, selector = "best", label = item.fileName)
        val intent = DownloadService.startIntent(requireContext(), item.url, item.fileName, spec)
        ContextCompat.startForegroundService(requireContext(), intent)
        Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) binding.browserWeb.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) binding.browserWeb.onResume()
    }

    override fun onDestroyView() {
        val web = _binding?.browserWeb
        web?.let {
            it.stopLoading()
            it.webChromeClient = null
            it.webViewClient = WebViewClient()
            (it.parent as? ViewGroup)?.removeView(it)
            it.removeAllViews()
            it.destroy()
        }
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val HOME_URL = "https://duckduckgo.com/"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val MEDIA_EXTENSIONS = listOf(".mp4", ".m3u8", ".mpd", ".webm", ".mkv", ".mp3", ".m4a")
    }
}
