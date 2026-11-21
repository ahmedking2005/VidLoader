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

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

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

        pendingSharedUrl?.let { url ->
            binding.urlInput.setText(url)
            if (pendingAutoFetch) {
                InfoActivity.start(requireContext(), url)
            }
            pendingSharedUrl = null
            pendingAutoFetch = false
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
        super.onDestroyView()
        _binding = null
    }
}
