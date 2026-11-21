package com.ahmed.vidloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.vidloader.R
import com.ahmed.vidloader.data.LinkHistoryEntry
import com.ahmed.vidloader.databinding.ItemHistoryBinding
import java.text.DateFormat
import java.util.Date

/** Rows for «سجل الروابط» (v1.9): tap re-opens the link, long-press deletes the entry. */
class HistoryAdapter(
    private val onOpen: (LinkHistoryEntry) -> Unit,
    private val onDelete: (LinkHistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

    var items: List<LinkHistoryEntry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        val binding = holder.binding
        binding.itemTitle.text = entry.title.ifBlank { entry.url }
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(entry.timestampMillis))
        val statusText = when (entry.status) {
            LinkHistoryEntry.STATUS_COMPLETED -> holder.itemView.context.getString(R.string.history_status_completed)
            LinkHistoryEntry.STATUS_FAILED -> holder.itemView.context.getString(R.string.history_status_failed)
            else -> holder.itemView.context.getString(R.string.history_status_started)
        }
        binding.itemMeta.text = "$statusText • $date\n${entry.url}"
        binding.root.setOnClickListener { onOpen(entry) }
        binding.root.setOnLongClickListener {
            onDelete(entry)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)
}
