package com.ahmed.vidloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.vidloader.R
import com.ahmed.vidloader.data.ScheduledJob
import com.ahmed.vidloader.data.WatchEntry
import com.ahmed.vidloader.databinding.ItemScheduledBinding
import com.ahmed.vidloader.databinding.ItemWatchBinding
import java.text.DateFormat
import java.util.Date

/** Rows for «المراقبة» (v1.10): switch toggles a watch, long-press deletes it. */
class WatchAdapter(
    private val onToggle: (WatchEntry, Boolean) -> Unit,
    private val onDelete: (WatchEntry) -> Unit
) : RecyclerView.Adapter<WatchAdapter.Holder>() {

    var items: List<WatchEntry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemWatchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val watch = items[position]
        val binding = holder.binding
        val context = holder.itemView.context
        binding.itemTitle.text = watch.label.ifBlank { watch.url }
        val lastChecked = if (watch.lastCheckedAt > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(watch.lastCheckedAt))
        } else {
            context.getString(R.string.watch_never_checked)
        }
        binding.itemMeta.text =
            context.getString(R.string.watch_last_checked, lastChecked) + "\n" + watch.url
        binding.itemEnabled.setOnCheckedChangeListener(null)
        binding.itemEnabled.isChecked = watch.enabled
        binding.itemEnabled.setOnCheckedChangeListener { _, checked -> onToggle(watch, checked) }
        binding.root.setOnLongClickListener {
            onDelete(watch)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(val binding: ItemWatchBinding) : RecyclerView.ViewHolder(binding.root)
}

/** Rows for «التنزيلات المجدولة» (v1.10): planned time + a cancel action. */
class ScheduledAdapter(
    private val onCancel: (ScheduledJob) -> Unit
) : RecyclerView.Adapter<ScheduledAdapter.Holder>() {

    var items: List<ScheduledJob> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemScheduledBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val job = items[position]
        val binding = holder.binding
        val context = holder.itemView.context
        binding.itemTitle.text = job.title.ifBlank { job.url }
        val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(job.triggerAtMillis))
        binding.itemMeta.text =
            context.getString(R.string.scheduled_at, whenText) + "\n" + job.url
        binding.itemCancel.setOnClickListener { onCancel(job) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(val binding: ItemScheduledBinding) : RecyclerView.ViewHolder(binding.root)
}
