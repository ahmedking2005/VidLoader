package com.ahmed.vidloader.ui

import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
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
    private val onPlay: (LibraryItem) -> Unit,
    private val onOpen: (LibraryItem) -> Unit,
    private val onShare: (LibraryItem) -> Unit,
    private val onDelete: (LibraryItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onTag: (LibraryItem) -> Unit = {},
    private val onConvertMp3: (LibraryItem) -> Unit = {},
    private val onConvertCompress: (LibraryItem) -> Unit = {}
) : RecyclerView.Adapter<LibraryAdapter.Holder>() {

    var items: List<LibraryItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Multi-select state for bulk delete (v1.8); scoped to this list only. */
    private val selectedIds = LinkedHashSet<String>()

    var selectionMode: Boolean = false
        private set

    fun selectedItems(): List<LibraryItem> = items.filter { it.id in selectedIds }

    /** Leaves selection mode and clears the selection; a no-op when already out. */
    fun exitSelection() {
        if (!selectionMode && selectedIds.isEmpty()) return
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private fun enterSelection(item: LibraryItem) {
        selectionMode = true
        selectedIds.add(item.id)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    private fun toggle(item: LibraryItem) {
        if (!selectedIds.remove(item.id)) selectedIds.add(item.id)
        if (selectedIds.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
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
        val meta = "${FormatUtils.humanSize(item.size)} • $date"
        binding.itemMeta.text = if (item.tag.isNotBlank()) "$meta • وسم: ${item.tag}" else meta
        binding.itemCheck.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.itemCheck.isChecked = item.id in selectedIds
        binding.itemMore.visibility = if (selectionMode) View.GONE else View.VISIBLE
        binding.root.setOnClickListener {
            if (selectionMode) toggle(item) else onPlay(item)
        }
        binding.root.setOnLongClickListener {
            if (!selectionMode) {
                enterSelection(item)
                true
            } else {
                false
            }
        }
        binding.itemMore.setOnClickListener { view ->
            val popup = PopupMenu(view.context, view)
            popup.menuInflater.inflate(R.menu.library_item, popup.menu)
            popup.setOnMenuItemClickListener { menuItem: MenuItem ->
                when (menuItem.itemId) {
                    R.id.action_open -> onOpen(item)
                    R.id.action_share -> onShare(item)
                    R.id.action_tag -> onTag(item)
                    R.id.action_convert_mp3 -> onConvertMp3(item)
                    R.id.action_convert_compress -> onConvertCompress(item)
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
