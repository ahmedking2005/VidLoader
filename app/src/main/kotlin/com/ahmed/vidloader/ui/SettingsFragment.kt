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
import com.ahmed.vidloader.data.QuickPreset
import com.ahmed.vidloader.data.QuickSettings
import com.ahmed.vidloader.data.UpdateChecker
import com.ahmed.vidloader.data.UpdatePrompt
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
        binding.appUpdateButton.setOnClickListener { checkAppUpdate() }
        setupQuickSettings()
        refreshEngineVersion()
    }

    /** «التنزيل السريع» section: the one-time quality preset + the share-behaviour toggle. */
    private fun setupQuickSettings() {
        // Set the current values first, then attach the listeners, so opening
        // the screen doesn't write the same values straight back.
        binding.quickPresetGroup.check(
            when (QuickSettings.preset(requireContext())) {
                QuickPreset.BEST -> R.id.preset_best
                QuickPreset.P720 -> R.id.preset_720
                QuickPreset.P480 -> R.id.preset_480
                QuickPreset.P360 -> R.id.preset_360
                QuickPreset.MP3 -> R.id.preset_mp3
                QuickPreset.M4A -> R.id.preset_m4a
            }
        )
        binding.quickPresetGroup.setOnCheckedChangeListener { _, checkedId ->
            val preset = when (checkedId) {
                R.id.preset_720 -> QuickPreset.P720
                R.id.preset_480 -> QuickPreset.P480
                R.id.preset_360 -> QuickPreset.P360
                R.id.preset_mp3 -> QuickPreset.MP3
                R.id.preset_m4a -> QuickPreset.M4A
                else -> QuickPreset.BEST
            }
            QuickSettings.setPreset(requireContext(), preset)
        }
        binding.sharePickerSwitch.isChecked = QuickSettings.shareOpensPicker(requireContext())
        binding.sharePickerSwitch.setOnCheckedChangeListener { _, checked ->
            QuickSettings.setShareOpensPicker(requireContext(), checked)
        }
    }

    /** Manual app-update check: ignores the 24h throttle and the «لاحقًا» dismissal. */
    private fun checkAppUpdate() {
        binding.appUpdateButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val latest = UpdateChecker.fetchLatest()
            if (_binding == null) return@launch
            binding.appUpdateButton.isEnabled = true
            when {
                latest == null ->
                    Toast.makeText(requireContext(), R.string.update_check_failed, Toast.LENGTH_LONG).show()
                latest.versionCode > UpdateChecker.installedVersionCode(requireContext()) ->
                    UpdatePrompt.show(requireContext(), latest)
                else ->
                    Toast.makeText(requireContext(), R.string.app_up_to_date, Toast.LENGTH_LONG).show()
            }
        }
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
                    null -> R.string.error_generic
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
