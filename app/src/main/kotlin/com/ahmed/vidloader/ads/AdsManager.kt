package com.ahmed.vidloader.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ahmed.vidloader.BuildConfig
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds

/**
 * VidLoader v1.12 — Unity Ads, standalone (direct, NO mediation, no other
 * network), integrated under Ahmed's standing FREE + LOW-ANNOYANCE rule:
 *
 *  - Banner: HOME screen only, space reserved in the layout (never overlays
 *    content); the container stays GONE when no ad is available.
 *  - Interstitial: ONLY after a download job completes successfully, ONLY
 *    while the app is in the foreground, at most once per [CAP_MS], and
 *    never twice for the same job. A completion that happens in the
 *    background is skipped forever — never shown retroactively.
 *  - FORBIDDEN by the rule and therefore absent here: app-open ads,
 *    notification ads, ads before a download starts, rewarded placements.
 *
 * Everything fails silently: no ad, no fill, or an SDK error must never
 * disturb the app or the user.
 */
object AdsManager {

    const val GAME_ID = "6229721"
    const val BANNER_PLACEMENT = "Banner_Android"
    const val INTERSTITIAL_PLACEMENT = "Interstitial_Android"

    private const val TAG = "AdsManager"
    private const val PREFS = "ads_prefs"
    private const val KEY_LAST_INTERSTITIAL_MS = "last_interstitial_ms"
    private const val CAP_MS = 10 * 60 * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    private var interstitialLoaded = false

    @Volatile
    private var currentActivity: Activity? = null

    @Volatile
    private var resumedActivities = 0

    /** Job ids an interstitial decision was already made for (shown or skipped). */
    private val decidedJobIds = mutableSetOf<String>()

    private val appForeground: Boolean
        get() = resumedActivities > 0 && currentActivity != null

    /**
     * Initialise the SDK once from [VidLoaderApp.onCreate] and start tracking
     * the foreground Activity. Any failure is swallowed — ads are optional
     * decoration, never a dependency of the app.
     */
    fun init(app: Application) {
        try {
            app.registerActivityLifecycleCallbacks(LifecycleTracker())
            if (!UnityAds.isSupported) {
                Log.i(TAG, "Unity Ads not supported on this device; ads disabled")
                return
            }
            UnityAds.initialize(app, GAME_ID, BuildConfig.DEBUG, object : IUnityAdsInitializationListener {
                override fun onInitializationComplete() {
                    isInitialized = true
                    loadInterstitial(app)
                }

                override fun onInitializationFailed(
                    error: UnityAds.UnityAdsInitializationError?,
                    message: String?
                ) {
                    // No retry storm: a later process start tries again.
                    Log.w(TAG, "Unity Ads init failed: $error $message")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Unity Ads init threw", e)
        }
    }

    private fun loadInterstitial(context: Context) {
        if (!isInitialized || interstitialLoaded) return
        try {
            UnityAds.load(INTERSTITIAL_PLACEMENT, object : IUnityAdsLoadListener {
                override fun onUnityAdsAdLoaded(placementId: String?) {
                    if (placementId == INTERSTITIAL_PLACEMENT) interstitialLoaded = true
                }

                override fun onUnityAdsFailedToLoad(
                    placementId: String?,
                    error: UnityAds.UnityAdsLoadError?,
                    message: String?
                ) {
                    if (placementId == INTERSTITIAL_PLACEMENT) interstitialLoaded = false
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "interstitial load threw", e)
        }
    }

    /**
     * Called by DownloadService exactly once when a job finishes with at
     * least one successful item. Applies, in order: per-job dedupe,
     * foreground gate, 10-minute cap, loaded gate — then shows on the
     * current Activity. Every rejection path is silent and final for that
     * job (no retroactive show later).
     */
    fun onDownloadJobCompleted(context: Context, jobId: String) {
        synchronized(decidedJobIds) {
            if (!decidedJobIds.add(jobId)) return
            // Keep the set bounded; job ids are unique per run anyway.
            if (decidedJobIds.size > 64) decidedJobIds.clear().also { decidedJobIds.add(jobId) }
        }
        if (!isInitialized || !interstitialLoaded) return
        val activity = currentActivity ?: return
        if (!appForeground || activity.isFinishing || activity.isDestroyed) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_INTERSTITIAL_MS, 0L) < CAP_MS) return
        prefs.edit().putLong(KEY_LAST_INTERSTITIAL_MS, now).apply()

        mainHandler.post {
            // Re-check on the UI thread: the user may have left meanwhile.
            val act = currentActivity ?: return@post
            if (!appForeground || act.isFinishing || act.isDestroyed) return@post
            try {
                UnityAds.show(act, INTERSTITIAL_PLACEMENT, object : IUnityAdsShowListener {
                    override fun onUnityAdsShowStart(placementId: String?) {
                        interstitialLoaded = false
                    }

                    override fun onUnityAdsShowComplete(
                        placementId: String?,
                        state: UnityAds.UnityAdsShowCompletionState?
                    ) {
                        loadInterstitial(act.applicationContext)
                    }

                    override fun onUnityAdsShowFailure(
                        placementId: String?,
                        error: UnityAds.UnityAdsShowError?,
                        message: String?
                    ) {
                        interstitialLoaded = false
                        loadInterstitial(act.applicationContext)
                    }

                    override fun onUnityAdsShowClick(placementId: String?) = Unit
                })
            } catch (e: Exception) {
                Log.w(TAG, "interstitial show threw", e)
                interstitialLoaded = false
                loadInterstitial(act.applicationContext)
            }
        }
    }

    /** Tracks the current resumed Activity so ads only ever attach to a live, foreground screen. */
    private class LifecycleTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivities++
            currentActivity = activity
        }

        override fun onActivityPaused(activity: Activity) {
            resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
            if (currentActivity === activity) currentActivity = null
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivity === activity) currentActivity = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
