package com.ahmed.vidloader.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.ahmed.vidloader.InfoActivity
import com.ahmed.vidloader.R
import com.ahmed.vidloader.data.QuickSettings
import com.ahmed.vidloader.databinding.FragmentHomeBinding
import com.ahmed.vidloader.download.DownloadKind
import com.ahmed.vidloader.download.DownloadService
import com.ahmed.vidloader.download.DownloadSpec
import com.ahmed.vidloader.util.FormatUtils
import com.unity3d.services.banners.BannerErrorInfo
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /** v1.12 Unity Ads banner — home screen only; see [setupBanner]. */
    private var bannerView: BannerView? = null

    private var pendingSharedUrl: String? = null
    private var pendingAutoFetch: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.pasteButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString()
            val urls = FormatUtils.extractUrls(text)
            if (urls.isEmpty()) {
                Toast.makeText(requireContext(), R.string.error_no_clipboard, Toast.LENGTH_SHORT).show()
            } else {
                // Keep every link found (multi-link paste: one URL per line).
                binding.urlInput.setText(urls.joinToString("\n"))
                binding.urlLayout.error = null
            }
        }

        binding.fetchButton.setOnClickListener {
            val urls = FormatUtils.extractUrls(binding.urlInput.text?.toString())
            when {
                urls.isEmpty() -> binding.urlLayout.error = getString(R.string.error_invalid_url)
                urls.size == 1 -> {
                    binding.urlLayout.error = null
                    InfoActivity.start(requireContext(), urls[0])
                }
                else -> {
                    binding.urlLayout.error = null
                    enqueueLinks(urls)
                }
            }
        }

        binding.quickButton.setOnClickListener {
            val urls = FormatUtils.extractUrls(binding.urlInput.text?.toString())
            when {
                urls.isEmpty() -> binding.urlLayout.error = getString(R.string.error_invalid_url)
                urls.size == 1 -> {
                    // One link: a playlist link becomes a quick batch, anything
                    // else a single quick download — decided in the background.
                    binding.urlLayout.error = null
                    val intent = DownloadService.startQuickPlaylistIntent(
                        requireContext(), urls[0], QuickSettings.spec(requireContext())
                    )
                    ContextCompat.startForegroundService(requireContext(), intent)
                    binding.urlInput.text?.clear()
                    Toast.makeText(requireContext(), R.string.quick_download_started, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    binding.urlLayout.error = null
                    enqueueQuickLinks(urls)
                }
            }
        }

        binding.scheduleButton.setOnClickListener {
            val urls = FormatUtils.extractUrls(binding.urlInput.text?.toString())
            if (urls.isEmpty()) {
                binding.urlLayout.error = getString(R.string.error_invalid_url)
            } else {
                // «جدولة التنزيل» (v1.10): pick a future date+time once; every
                // entered URL is scheduled for it at the quick preset.
                binding.urlLayout.error = null
                com.ahmed.vidloader.util.SchedulePicker.scheduleAll(requireContext(), urls)
                binding.urlInput.text?.clear()
            }
        }

        pendingSharedUrl?.let { url ->
            binding.urlInput.setText(url)
            if (pendingAutoFetch) {
                InfoActivity.start(requireContext(), url)
            }
            pendingSharedUrl = null
            pendingAutoFetch = false
        }

        setupBanner()
    }

    /**
     * v1.12 — small Unity Ads banner pinned at the bottom of the HOME tab
     * only, under Ahmed's free + low-annoyance rule: the container stays
     * GONE (zero height, no empty gap) until an ad actually loads, and when
     * visible it reserves its own space in the layout — it never overlays
     * content. Any load failure simply leaves the container hidden.
     */
    private fun setupBanner() {
        val activity = activity ?: return
        val container = binding.bannerContainer
        val banner = BannerView(
            activity,
            com.ahmed.vidloader.ads.AdsManager.BANNER_PLACEMENT,
            UnityBannerSize(320, 50)
        )
        banner.listener = object : BannerView.Listener() {
            override fun onBannerLoaded(bannerAdView: BannerView?) {
                if (_binding == null || bannerAdView == null) return
                if (bannerAdView.parent == null) container.addView(bannerAdView)
                container.visibility = View.VISIBLE
            }

            override fun onBannerFailedToLoad(bannerAdView: BannerView?, errorInfo: BannerErrorInfo?) {
                if (_binding == null) return
                container.removeAllViews()
                container.visibility = View.GONE
            }

            override fun onBannerShown(bannerAdView: BannerView?) = Unit
            override fun onBannerClick(bannerAdView: BannerView) = Unit
            override fun onBannerLeftApplication(bannerAdView: BannerView?) = Unit
        }
        bannerView = banner
        try {
            banner.load()
        } catch (e: Exception) {
            container.visibility = View.GONE
        }
    }

    /**
     * Multi-link paste: every URL becomes its own single-download job in the
     * service queue, with the default spec (best video ≤1080p — the service's
     * built-in default selector — subtitles off, --no-playlist per item, so a
     * playlist link in the list counts as one video only).
     */
    private fun enqueueLinks(urls: List<String>) {
        val spec = DownloadSpec(DownloadKind.VIDEO)
        for (url in urls) {
            val intent = DownloadService.startIntent(requireContext(), url, url, spec)
            ContextCompat.startForegroundService(requireContext(), intent)
        }
        binding.urlInput.text?.clear()
        Toast.makeText(requireContext(), getString(R.string.links_added, urls.size), Toast.LENGTH_LONG).show()
    }

    /** Multi-link quick download: same queueing as [enqueueLinks], but at the user's quick preset. */
    private fun enqueueQuickLinks(urls: List<String>) {
        val spec = QuickSettings.spec(requireContext())
        for (url in urls) {
            val intent = DownloadService.startQuickIntent(requireContext(), url, spec)
            ContextCompat.startForegroundService(requireContext(), intent)
        }
        binding.urlInput.text?.clear()
        Toast.makeText(requireContext(), getString(R.string.links_added, urls.size), Toast.LENGTH_LONG).show()
    }

    fun setSharedUrl(url: String, autoFetch: Boolean) {
        if (_binding == null) {
            pendingSharedUrl = url
            pendingAutoFetch = autoFetch
        } else {
            binding.urlInput.setText(url)
            if (autoFetch) {
                InfoActivity.start(requireContext(), url)
            }
        }
    }

    override fun onDestroyView() {
        try {
            bannerView?.destroy()
        } catch (e: Exception) {
            // Banner teardown must never crash view destruction.
        }
        bannerView = null
        super.onDestroyView()
        _binding = null
    }
}
