package com.ahmed.vidloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.vidloader.databinding.ItemDetectedBinding

/** One entry in the browser's detected-media list. */
data class DetectedMedia(
    val url: String,
    val fileName: String,
    /**
     * True for the pinned "video of this page" entry (a recognised video page
     * such as a YouTube watch page). It has no direct file to grab, so only
     * the open-in-downloader (extractor) action applies to it.
     */
    val isPageVideo: Boolean = false
)

class DetectedAdapter(
    private val onDirectDownload: (DetectedMedia) -> Unit,
    private val onOpenInDownloader: (DetectedMedia) -> Unit,
    private val onQuickDownload: (DetectedMedia) -> Unit
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
        // A page-video entry is a web page, not a file: direct download is meaningless,
        // but it can quick-download in the background at the user's preset.
        binding.detectedDirect.visibility = if (item.isPageVideo) View.GONE else View.VISIBLE
        binding.detectedQuick.visibility = if (item.isPageVideo) View.VISIBLE else View.GONE
        binding.detectedDirect.setOnClickListener { onDirectDownload(item) }
        binding.detectedQuick.setOnClickListener { onQuickDownload(item) }
        binding.detectedOpen.setOnClickListener { onOpenInDownloader(item) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(val binding: ItemDetectedBinding) : RecyclerView.ViewHolder(binding.root)
}
