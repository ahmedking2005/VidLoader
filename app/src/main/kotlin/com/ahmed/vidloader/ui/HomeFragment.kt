package com.ahmed.vidloader.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.ahmed.vidloader.InfoActivity
import com.ahmed.vidloader.R
import com.ahmed.vidloader.databinding.FragmentHomeBinding
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
            val url = FormatUtils.extractUrl(text)
            if (url == null) {
                Toast.makeText(requireContext(), R.string.error_no_clipboard, Toast.LENGTH_SHORT).show()
            } else {
                binding.urlInput.setText(url)
                binding.urlLayout.error = null
            }
        }

        binding.fetchButton.setOnClickListener {
            val url = FormatUtils.extractUrl(binding.urlInput.text?.toString())
            if (url == null) {
                binding.urlLayout.error = getString(R.string.error_invalid_url)
            } else {
                binding.urlLayout.error = null
                InfoActivity.start(requireContext(), url)
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
