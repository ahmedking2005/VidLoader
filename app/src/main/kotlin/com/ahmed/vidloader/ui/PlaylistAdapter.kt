package com.ahmed.vidloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.vidloader.data.PlaylistEntry
import com.ahmed.vidloader.databinding.ItemPlaylistEntryBinding
import com.ahmed.vidloader.util.FormatUtils

class PlaylistAdapter(
    private val entries: List<PlaylistEntry>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.EntryHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
        val binding = ItemPlaylistEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EntryHolder(binding)
    }

    override fun onBindViewHolder(holder: EntryHolder, position: Int) {
        val entry = entries[position]
        val binding = holder.binding
        binding.entryIndex.text = entry.index.toString()
        binding.entryTitle.text = entry.title
        binding.entryDuration.text =
            if (entry.durationSeconds > 0) FormatUtils.duration(entry.durationSeconds.toDouble()) else ""
        binding.entryDuration.visibility = if (entry.durationSeconds > 0) View.VISIBLE else View.GONE

        binding.entryCheck.setOnCheckedChangeListener(null)
        binding.entryCheck.isChecked = entry.selected
        binding.entryCheck.setOnCheckedChangeListener { _, checked ->
            entry.selected = checked
            onSelectionChanged()
        }
        binding.root.setOnClickListener {
            binding.entryCheck.isChecked = !binding.entryCheck.isChecked
        }
    }

    override fun getItemCount(): Int = entries.size

    class EntryHolder(val binding: ItemPlaylistEntryBinding) : RecyclerView.ViewHolder(binding.root)
}
