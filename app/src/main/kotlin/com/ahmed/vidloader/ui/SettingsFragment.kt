package com.ahmed.vidloader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ahmed.vidloader.BuildConfig
import com.ahmed.vidloader.R
import com.ahmed.vidloader.VidLoaderApp
import com.ahmed.vidloader.data.DownloadSettings
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

    /** SAF folder picker for «مسار التنزيل»: persists the chosen tree + its access permission. */
    private val treeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Some providers don't grant persistable access; the choice is
                // still saved and publishing falls back if it stops working.
            }
            DownloadSettings.setTreeUri(requireContext(), uri)
            refreshDownloadPath()
        }
    }

    /** Cookies file picker: the document is copied into internal storage by DownloadSettings. */
    private val cookiesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = DownloadSettings.importCookies(requireContext(), uri)
            if (name != null) {
                Toast.makeText(requireContext(), R.string.cookies_imported, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.cookies_import_failed, Toast.LENGTH_LONG).show()
            }
            refreshCookies()
        }
    }

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
        setupDownloadSettings()
        setupTheme()
        refreshEngineVersion()
    }

    // region إعدادات التنزيل

    private fun setupDownloadSettings() {
        refreshDownloadPath()
        refreshDownloadSettingsLabels()
        binding.downloadPathChange.setOnClickListener {
            treeLauncher.launch(DownloadSettings.treeUri(requireContext()))
        }
        binding.downloadPathReset.setOnClickListener {
            DownloadSettings.setTreeUri(requireContext(), null)
            refreshDownloadPath()
        }
        binding.limitWifiRow.setOnClickListener {
            showLimitDialog(R.string.max_tasks_wifi, DownloadSettings.wifiLimit(requireContext())) {
                DownloadSettings.setWifiLimit(requireContext(), it)
            }
        }
        binding.limitMobileRow.setOnClickListener {
            showLimitDialog(R.string.max_tasks_mobile, DownloadSettings.mobileLimit(requireContext())) {
                DownloadSettings.setMobileLimit(requireContext(), it)
            }
        }
        binding.rateLimitRow.setOnClickListener { showRateLimitDialog() }
        binding.mobileDataSwitch.isChecked = DownloadSettings.mobileDataAllowed(requireContext())
        binding.mobileDataSwitch.setOnCheckedChangeListener { _, checked ->
            DownloadSettings.setMobileDataAllowed(requireContext(), checked)
        }
        binding.sponsorblockSwitch.isChecked = DownloadSettings.sponsorBlock(requireContext())
        binding.sponsorblockSwitch.setOnCheckedChangeListener { _, checked ->
            DownloadSettings.setSponsorBlock(requireContext(), checked)
        }
        refreshCookies()
        binding.cookiesImport.setOnClickListener {
            cookiesLauncher.launch(arrayOf("*/*"))
        }
        binding.cookiesRemove.setOnClickListener {
            DownloadSettings.clearCookies(requireContext())
            refreshCookies()
            Toast.makeText(requireContext(), R.string.cookies_removed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshCookies() {
        if (_binding == null) return
        val name = DownloadSettings.cookiesName(requireContext())
        binding.cookiesValue.text = name ?: getString(R.string.cookies_none)
        binding.cookiesRemove.isEnabled = name != null
    }

    private fun refreshDownloadPath() {
        if (_binding == null) return
        val tree = DownloadSettings.treeUri(requireContext())
        binding.downloadPathValue.text = if (tree == null) {
            getString(R.string.download_path_default)
        } else {
            treeDisplayName(tree)
        }
        binding.downloadPathReset.isEnabled = tree != null
    }

    /** Human-readable form of a SAF tree uri: the document id's path part (e.g. "Download/Movies"). */
    private fun treeDisplayName(uri: Uri): String {
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            null
        }
        if (!docId.isNullOrBlank()) {
            val path = docId.substringAfter(':', docId)
            return path.ifBlank { docId }
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun refreshDownloadSettingsLabels() {
        if (_binding == null) return
        binding.limitWifiValue.text = DownloadSettings.wifiLimit(requireContext()).toString()
        binding.limitMobileValue.text = DownloadSettings.mobileLimit(requireContext()).toString()
        binding.rateLimitValue.text = rateLimitLabel(DownloadSettings.rateLimit(requireContext()))
    }

    private fun rateLimitLabel(value: String): String =
        if (value.isBlank()) getString(R.string.rate_unlimited) else value

    private fun showLimitDialog(titleRes: Int, current: Int, apply: (Int) -> Unit) {
        val options = DownloadSettings.LIMIT_OPTIONS
        val labels = options.map { it.toString() }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setSingleChoiceItems(labels, options.indexOf(current)) { dialog, which ->
                apply(options[which])
                refreshDownloadSettingsLabels()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRateLimitDialog() {
        val options = DownloadSettings.RATE_OPTIONS
        val labels = options.map { rateLimitLabel(it) }.toTypedArray()
        val current = DownloadSettings.rateLimit(requireContext())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rate_limit)
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                DownloadSettings.setRateLimit(requireContext(), options[which])
                refreshDownloadSettingsLabels()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // endregion

    // region السمة

    private fun setupTheme() {
        // Set the current value first, then attach the listener (same
        // no-write-back-on-open pattern as the quick settings).
        binding.themeGroup.check(
            when (DownloadSettings.theme(requireContext())) {
                DownloadSettings.THEME_LIGHT -> R.id.theme_light
                DownloadSettings.THEME_DARK -> R.id.theme_dark
                else -> R.id.theme_system
            }
        )
        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.theme_light -> DownloadSettings.THEME_LIGHT
                R.id.theme_dark -> DownloadSettings.THEME_DARK
                else -> DownloadSettings.THEME_SYSTEM
            }
            DownloadSettings.setTheme(requireContext(), theme)
        }
    }

    // endregion

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
                Toast.makeText(requireContext(), getString(R.string.update_failed, friendlyError(requireContext(), e)), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
