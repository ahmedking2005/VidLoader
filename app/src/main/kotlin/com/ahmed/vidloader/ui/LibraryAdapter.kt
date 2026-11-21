package com.ahmed.vidloader.ui

import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.vidloader.R
import com.ahmed.vidloader.data.LibraryItem
import com.ahmed.vidloader.databinding.ItemLibraryBinding
import com.ahmed.vidloader.util.FormatUtils
import java.text.DateFormat
import java.util.Date

class LibraryAdapter(
    private val onOpen: (LibraryItem) -> Unit,
    private val onShare: (LibraryItem) -> Unit,
    private val onDelete: (LibraryItem) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.Holder>() {

    var items: List<LibraryItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemLibraryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val binding = holder.binding
        binding.itemIcon.setImageResource(if (item.isAudio) R.drawable.ic_music else R.drawable.ic_play)
        binding.itemName.text = item.fileName
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.dateMillis))
        binding.itemMeta.text = "${FormatUtils.humanSize(item.size)} • $date"
        binding.root.setOnClickListener { onOpen(item) }
        binding.itemMore.setOnClickListener { view ->
            val popup = PopupMenu(view.context, view)
            popup.menuInflater.inflate(R.menu.library_item, popup.menu)
            popup.setOnMenuItemClickListener { menuItem: MenuItem ->
                when (menuItem.itemId) {
                    R.id.action_open -> onOpen(item)
                    R.id.action_share -> onShare(item)
                    R.id.action_delete -> onDelete(item)
                }
                true
            }
            popup.show()
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(val binding: ItemLibraryBinding) : RecyclerView.ViewHolder(binding.root)
}
