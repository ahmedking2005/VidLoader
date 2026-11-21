package com.ahmed.vidloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.vidloader.databinding.ItemFormatBinding
import com.ahmed.vidloader.databinding.ItemSectionHeaderBinding
import com.ahmed.vidloader.download.DownloadSpec

sealed interface FormatRow {
    data class Header(val text: String) : FormatRow
    data class Option(
        val label: String,
        val sub: String,
        val iconRes: Int,
        val spec: DownloadSpec
    ) : FormatRow
}

class FormatsAdapter(
    private val onOptionClick: (DownloadSpec) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<FormatRow> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is FormatRow.Header -> 0
        is FormatRow.Option -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            OptionHolder(ItemFormatBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is FormatRow.Header -> (holder as HeaderHolder).binding.headerText.text = row.text
            is FormatRow.Option -> {
                val binding = (holder as OptionHolder).binding
                binding.formatLabel.text = row.label
                binding.formatSub.text = row.sub
                binding.formatSub.visibility = if (row.sub.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                binding.formatIcon.setImageResource(row.iconRes)
                binding.root.setOnClickListener { onOptionClick(row.spec) }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderHolder(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class OptionHolder(val binding: ItemFormatBinding) : RecyclerView.ViewHolder(binding.root)
}
