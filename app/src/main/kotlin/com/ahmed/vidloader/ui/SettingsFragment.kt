package com.ahmed.vidloader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ahmed.vidloader.BuildConfig
import com.ahmed.vidloader.R
import com.ahmed.vidloader.VidLoaderApp
import com.ahmed.vidloader.databinding.FragmentSettingsBinding
import com.ahmed.vidloader.util.friendlyError
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.aboutText.text = getString(R.string.settings_about_desc, BuildConfig.VERSION_NAME)
        binding.updateButton.setOnClickListener { updateEngine() }
        refreshEngineVersion()
    }

    private fun refreshEngineVersion() {
        viewLifecycleOwner.lifecycleScope.launch {
            val version = withContext(Dispatchers.IO) {
                try {
                    VidLoaderApp.instance.initEngineBlocking()
                    YoutubeDL.getInstance().version(requireContext())
                } catch (e: Exception) {
                    null
                }
            }
            if (_binding != null) {
                binding.engineVersion.text = if (version.isNullOrBlank()) {
                    getString(R.string.engine_version_unknown)
                } else {
                    getString(R.string.engine_version_format, version)
                }
            }
        }
    }

    private fun updateEngine() {
        binding.updateButton.isEnabled = false

        val progress = ProgressBar(requireContext()).apply {
            isIndeterminate = true
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.updating_engine)
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    VidLoaderApp.instance.initEngineBlocking()
                    YoutubeDL.getInstance().updateYoutubeDL(
                        requireContext().applicationContext,
                        YoutubeDL.UpdateChannel.STABLE
                    )
                }
            }
            dialog.dismiss()
            if (_binding == null) return@launch
            binding.updateButton.isEnabled = true
            result.onSuccess { status ->
                val message = when (status) {
                    YoutubeDL.UpdateStatus.DONE -> R.string.update_success
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> R.string.update_already_latest
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                refreshEngineVersion()
            }.onFailure { e ->
                Toast.makeText(requireContext(), getString(R.string.update_failed, friendlyError(e)), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
