package com.ahmed.vidloader.download

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared state between DownloadService and the UI (same process). */
object DownloadCenter {

    enum class Status { IDLE, RUNNING, PROCESSING, DONE, ERROR, CANCELLED }

    data class State(
        val status: Status = Status.IDLE,
        val processId: String? = null,
        val title: String = "",
        val progress: Float = -1f,
        val etaSeconds: Long = -1,
        val speed: String = "",
        val error: String? = null,
        val itemId: String? = null
    )

    val state = MutableStateFlow(State())

    @Volatile
    var cancelRequested: Boolean = false

    fun reset() {
        cancelRequested = false
        state.value = State()
    }

    fun isBusy(): Boolean {
        val s = state.value.status
        return s == Status.RUNNING || s == Status.PROCESSING
    }
}
