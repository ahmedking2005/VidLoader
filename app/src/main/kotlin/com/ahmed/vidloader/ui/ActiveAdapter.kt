package com.ahmed.vidloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.vidloader.R
import com.ahmed.vidloader.data.PausedJob
import com.ahmed.vidloader.databinding.ItemActiveBinding
import com.ahmed.vidloader.download.DownloadCenter
import com.ahmed.vidloader.util.FormatUtils

/**
 * Rows of the library's «قيد التنزيل» tab: running / queued / fetching /
 * Wi-Fi-waiting jobs come from DownloadCenter.activeItems (the service's
 * real queue state); paused jobs are merged in as PAUSED rows carrying
 * their persisted [PausedJob] record for استئناف/حذف.
 */
class ActiveAdapter(
    private val onPause: (DownloadCenter.ActiveItem) -> Unit,
    private val onCancel: (DownloadCenter.ActiveItem) -> Unit,
    private val onResume: (PausedJob) -> Unit,
    private val onDeletePaused: (PausedJob) -> Unit
) : RecyclerView.Adapter<ActiveAdapter.ActiveHolder>() {

    var items: List<DownloadCenter.ActiveItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActiveHolder {
        val binding = ItemActiveBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ActiveHolder(binding)
    }

    override fun onBindViewHolder(holder: ActiveHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding
        val context = binding.root.context
        binding.activeTitle.text = item.title

        val meta = StringBuilder()
        fun appendPart(part: String) {
            if (part.isEmpty()) return
            if (meta.isNotEmpty()) meta.append(" • ")
            meta.append(part)
        }
        if (item.queueTotal > 1) {
            appendPart(context.getString(R.string.playlist_progress, item.queuePosition, item.queueTotal))
        }
        when (item.status) {
            DownloadCenter.ActiveStatus.RUNNING -> {
                if (item.progress >= 0) {
                    appendPart(context.getString(R.string.download_progress, item.progress.toInt()))
                } else {
                    appendPart(context.getString(R.string.downloading))
                }
                if (item.etaSeconds > 0) {
                    appendPart(context.getString(R.string.eta_format, FormatUtils.eta(item.etaSeconds)))
                }
                if (item.speed.isNotBlank()) appendPart(item.speed)
            }
            DownloadCenter.ActiveStatus.FETCHING ->
                appendPart(context.getString(R.string.playlist_fetching))
            DownloadCenter.ActiveStatus.QUEUED ->
                appendPart(context.getString(R.string.status_queued))
            DownloadCenter.ActiveStatus.WAITING_WIFI ->
                appendPart(context.getString(R.string.waiting_wifi))
            DownloadCenter.ActiveStatus.PAUSED -> {
                appendPart(context.getString(R.string.paused_label))
                if (item.progress > 0) {
                    appendPart(context.getString(R.string.paused_progress, item.progress.toInt()))
                }
            }
        }
        if (item.failedCount > 0) {
            appendPart(context.getString(R.string.playlist_failed_count, item.failedCount))
        }
        binding.activeMeta.text = meta.toString()

        when (item.status) {
            DownloadCenter.ActiveStatus.RUNNING -> {
                binding.activeProgress.visibility = View.VISIBLE
                binding.activeProgress.isIndeterminate = item.progress < 0
                if (item.progress >= 0) binding.activeProgress.progress = item.progress.toInt().coerceIn(0, 100)
            }
            DownloadCenter.ActiveStatus.FETCHING -> {
                binding.activeProgress.visibility = View.VISIBLE
                binding.activeProgress.isIndeterminate = true
            }
            DownloadCenter.ActiveStatus.PAUSED -> {
                if (item.progress > 0) {
                    binding.activeProgress.visibility = View.VISIBLE
                    binding.activeProgress.isIndeterminate = false
                    binding.activeProgress.progress = item.progress.toInt().coerceIn(0, 100)
                } else {
                    binding.activeProgress.visibility = View.GONE
                }
            }
            else -> binding.activeProgress.visibility = View.GONE
        }

        val running = item.status == DownloadCenter.ActiveStatus.RUNNING ||
            item.status == DownloadCenter.ActiveStatus.FETCHING
        val queued = item.status == DownloadCenter.ActiveStatus.QUEUED ||
            item.status == DownloadCenter.ActiveStatus.WAITING_WIFI
        val paused = item.status == DownloadCenter.ActiveStatus.PAUSED
        binding.activePause.visibility = if (running) View.VISIBLE else View.GONE
        binding.activeCancel.visibility = if (running || queued) View.VISIBLE else View.GONE
        binding.activeResume.visibility = if (paused) View.VISIBLE else View.GONE
        binding.activeDelete.visibility = if (paused) View.VISIBLE else View.GONE

        binding.activePause.setOnClickListener { onPause(item) }
        binding.activeCancel.setOnClickListener { onCancel(item) }
        binding.activeResume.setOnClickListener { item.pausedJob?.let(onResume) }
        binding.activeDelete.setOnClickListener { item.pausedJob?.let(onDeletePaused) }
    }

    override fun getItemCount(): Int = items.size

    class ActiveHolder(val binding: ItemActiveBinding) : RecyclerView.ViewHolder(binding.root)
}
