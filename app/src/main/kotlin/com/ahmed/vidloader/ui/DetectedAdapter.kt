package com.ahmed.vidloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.vidloader.databinding.ItemDetectedBinding

/** One sniffed direct-media URL found by the in-app browser. */
data class DetectedMedia(
    val url: String,
    val fileName: String
)

class DetectedAdapter(
    private val onDirectDownload: (DetectedMedia) -> Unit,
    private val onOpenInDownloader: (DetectedMedia) -> Unit
) : RecyclerView.Adapter<DetectedAdapter.Holder>() {

    var items: List<DetectedMedia> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDetectedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val binding = holder.binding
        binding.detectedName.text = item.fileName
        binding.detectedUrl.text = item.url
        binding.detectedDirect.setOnClickListener { onDirectDownload(item) }
        binding.detectedOpen.setOnClickListener { onOpenInDownloader(item) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(val binding: ItemDetectedBinding) : RecyclerView.ViewHolder(binding.root)
}
