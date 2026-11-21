package com.ahmed.vidloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.ahmed.vidloader.data.PausedJobsRepository
import com.ahmed.vidloader.databinding.ActivityMainBinding
import com.ahmed.vidloader.download.DownloadCenter
import com.ahmed.vidloader.ui.BrowserFragment
import com.ahmed.vidloader.ui.HomeFragment
import com.ahmed.vidloader.ui.LibraryFragment
import com.ahmed.vidloader.ui.SettingsFragment
import com.ahmed.vidloader.util.FormatUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val homeFragment = HomeFragment()
    private val browserFragment = BrowserFragment()
    private val libraryFragment = LibraryFragment()
    private val settingsFragment = SettingsFragment()
    private var activeFragment: Fragment? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result doesn't block anything */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Paused jobs persist on disk across process death; seed the shared
        // flow so the library's «تنزيلات موقوفة» section is correct immediately.
        DownloadCenter.pausedJobs.value = PausedJobsRepository(this).load()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.nav_host, settingsFragment, TAG_SETTINGS).hide(settingsFragment)
                .add(R.id.nav_host, libraryFragment, TAG_LIBRARY).hide(libraryFragment)
                .add(R.id.nav_host, browserFragment, TAG_BROWSER).hide(browserFragment)
                .add(R.id.nav_host, homeFragment, TAG_HOME)
                .commit()
            activeFragment = homeFragment
        } else {
            activeFragment = supportFragmentManager.fragments.firstOrNull { !it.isHidden } ?: homeFragment
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> show(homeFragment)
                R.id.nav_browser -> show(browserFragment)
                R.id.nav_library -> show(libraryFragment)
                R.id.nav_settings -> show(settingsFragment)
                else -> return@setOnItemSelectedListener false
            }
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun show(fragment: Fragment) {
        if (fragment == activeFragment) return
        val tx = supportFragmentManager.beginTransaction()
        activeFragment?.let { tx.hide(it) }
        tx.show(fragment).commit()
        activeFragment = fragment
    }

    fun openLibrary() {
        binding.bottomNav.selectedItemId = R.id.nav_library
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false)) {
            openLibrary()
            intent.removeExtra(EXTRA_OPEN_LIBRARY)
            return
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            val url = FormatUtils.extractUrl(shared)
            if (url != null) {
                binding.bottomNav.selectedItemId = R.id.nav_home
                homeFragment.setSharedUrl(url, autoFetch = true)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_LIBRARY = "open_library"
        private const val TAG_HOME = "home"
        private const val TAG_BROWSER = "browser"
        private const val TAG_LIBRARY = "library"
        private const val TAG_SETTINGS = "settings"
    }
}
