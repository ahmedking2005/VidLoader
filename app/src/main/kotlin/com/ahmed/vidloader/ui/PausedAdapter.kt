package com.ahmed.vidloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.vidloader.R
import com.ahmed.vidloader.data.PausedJob
import com.ahmed.vidloader.databinding.ItemPausedBinding

/** Rows of the «تنزيلات موقوفة» section: resume or delete (record + partial files). */
class PausedAdapter(
    private val onResume: (PausedJob) -> Unit,
    private val onDelete: (PausedJob) -> Unit
) : RecyclerView.Adapter<PausedAdapter.PausedHolder>() {

    var items: List<PausedJob> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PausedHolder {
        val binding = ItemPausedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PausedHolder(binding)
    }

    override fun onBindViewHolder(holder: PausedHolder, position: Int) {
        val job = items[position]
        val binding = holder.binding
        val context = binding.root.context
        binding.pausedTitle.text = job.title
        val meta = StringBuilder()
        if (job.queueTotal > 1) {
            meta.append(context.getString(R.string.playlist_progress, job.queuePosition, job.queueTotal))
        }
        if (job.progressPercent > 0) {
            if (meta.isNotEmpty()) meta.append(" • ")
            meta.append(context.getString(R.string.paused_progress, job.progressPercent))
        }
        if (job.failedCount > 0) {
            if (meta.isNotEmpty()) meta.append(" • ")
            meta.append(context.getString(R.string.playlist_failed_count, job.failedCount))
        }
        binding.pausedMeta.text = meta.toString()
        binding.resumeButton.setOnClickListener { onResume(job) }
        binding.deletePausedButton.setOnClickListener { onDelete(job) }
    }

    override fun getItemCount(): Int = items.size

    class PausedHolder(val binding: ItemPausedBinding) : RecyclerView.ViewHolder(binding.root)
}
