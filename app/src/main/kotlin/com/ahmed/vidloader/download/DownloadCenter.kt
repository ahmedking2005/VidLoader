package com.ahmed.vidloader.download

import com.ahmed.vidloader.data.PausedJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared state between DownloadService and the UI (same process). */
object DownloadCenter {

    enum class Status { IDLE, RUNNING, PROCESSING, DONE, ERROR, CANCELLED, PAUSED }

    data class State(
        val status: Status = Status.IDLE,
        val processId: String? = null,
        /** Job the legacy single-download card (InfoActivity) is mirroring. */
        val jobId: String? = null,
        val title: String = "",
        val progress: Float = -1f,
        val etaSeconds: Long = -1,
        val speed: String = "",
        val error: String? = null,
        val itemId: String? = null,
        /** 1-based position of the current item inside its job (0 when not applicable). */
        val queuePosition: Int = 0,
        /** Total items in the current job; values > 1 mean a batch (playlist / queue). */
        val queueTotal: Int = 0,
        /** Items of the current job that failed so far (skipped, not fatal). */
        val failedCount: Int = 0
    )

    /**
     * The legacy single-job view of the download world. With parallel limits
     * above 1 the service mirrors the PRIMARY (earliest started) running job
     * here, so InfoActivity's download card keeps working unchanged.
     */
    val state = MutableStateFlow(State())

    /** Lifecycle of one row in the library's «قيد التنزيل» list. */
    enum class ActiveStatus { RUNNING, QUEUED, FETCHING, WAITING_WIFI, PAUSED }

    /**
     * One row of the library's in-progress list, built by DownloadService from
     * its real queue/runtime state (running jobs, the pending queue, jobs
     * blocked by the mobile-data policy). PAUSED rows are merged in by the UI
     * from [pausedJobs] — the persisted mirror — with [pausedJob] set.
     */
    data class ActiveItem(
        val jobId: String,
        val title: String,
        val status: ActiveStatus,
        val progress: Float = -1f,
        val etaSeconds: Long = -1,
        val speed: String = "",
        val isBatch: Boolean = false,
        /** 1-based position of the current item inside its job (0 when not applicable). */
        val queuePosition: Int = 0,
        /** Total items in the job; values > 1 mean a batch. */
        val queueTotal: Int = 0,
        /** Items of the job that failed so far (skipped, not fatal). */
        val failedCount: Int = 0,
        /** Set only for PAUSED rows: the persisted record behind استئناف/حذف. */
        val pausedJob: PausedJob? = null
    )

    /** Live in-progress rows (RUNNING / QUEUED / FETCHING / WAITING_WIFI), published by the service. */
    val activeItems = MutableStateFlow<List<ActiveItem>>(emptyList())

    /**
     * Jobs stopped with ⏸ pause, mirrored from PausedJobsRepository so the UI
     * can render them without touching disk. The repository stays the source
     * of truth across process death; the service and the library screen keep
     * this flow in sync whenever records change.
     */
    val pausedJobs = MutableStateFlow<List<PausedJob>>(emptyList())

    fun isBusy(): Boolean {
        val s = state.value.status
        return s == Status.RUNNING || s == Status.PROCESSING
    }
}
