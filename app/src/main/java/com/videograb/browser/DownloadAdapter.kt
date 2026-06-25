package com.videograb.browser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator

class DownloadAdapter(
    private val onCancel: (DownloadManager.DownloadTask) -> Unit,
    private val onOpen: (DownloadManager.DownloadTask) -> Unit
) : ListAdapter<DownloadManager.DownloadTask, DownloadAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.downloadIcon)
        private val nameView: TextView = itemView.findViewById(R.id.downloadName)
        private val statusView: TextView = itemView.findViewById(R.id.downloadStatus)
        private val progressBar: LinearProgressIndicator = itemView.findViewById(R.id.downloadProgress)
        private val actionButton: Button = itemView.findViewById(R.id.downloadAction)

        fun bind(task: DownloadManager.DownloadTask) {
            nameView.text = task.fileName

            val typeStr = when (task.type) {
                VideoType.DIRECT -> "MP4"
                VideoType.HLS -> "HLS流"
                VideoType.DASH -> "DASH"
                VideoType.TS_SEGMENT -> "TS分段"
                VideoType.M4S_SEGMENT -> "fMP4"
            }

            when (task.status) {
                DownloadManager.DownloadStatus.QUEUED -> {
                    statusView.text = "等待中... [$typeStr]"
                    progressBar.progress = 0
                    progressBar.isIndeterminate = true
                    actionButton.text = "取消"
                    actionButton.setOnClickListener { onCancel(task) }
                }
                DownloadManager.DownloadStatus.DOWNLOADING -> {
                    val size = if (task.totalBytes > 0) {
                        formatSize(task.downloadedBytes) + " / " + formatSize(task.totalBytes)
                    } else {
                        formatSize(task.downloadedBytes)
                    }
                    statusView.text = "下载中... ${task.progress}% | $size"
                    progressBar.progress = task.progress
                    progressBar.isIndeterminate = false
                    actionButton.text = "取消"
                    actionButton.setOnClickListener { onCancel(task) }
                }
                DownloadManager.DownloadStatus.COMPLETED -> {
                    val size = if (task.totalBytes > 0) formatSize(task.totalBytes) else ""
                    statusView.text = "已完成 $size"
                    progressBar.progress = 100
                    progressBar.isIndeterminate = false
                    actionButton.text = "打开"
                    actionButton.setOnClickListener { onOpen(task) }
                }
                DownloadManager.DownloadStatus.FAILED -> {
                    statusView.text = "失败: ${task.error ?: "未知错误"}"
                    progressBar.progress = 0
                    progressBar.isIndeterminate = false
                    actionButton.text = "重试"
                    // Retry is not fully implemented yet
                    actionButton.setOnClickListener { onCancel(task) }
                }
                DownloadManager.DownloadStatus.CANCELLED -> {
                    statusView.text = "已取消"
                    progressBar.progress = 0
                    progressBar.isIndeterminate = false
                    actionButton.text = "移除"
                    actionButton.setOnClickListener { onCancel(task) }
                }
            }
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
                else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DownloadManager.DownloadTask>() {
        override fun areItemsTheSame(
            old: DownloadManager.DownloadTask,
            new: DownloadManager.DownloadTask
        ): Boolean = old.id == new.id

        override fun areContentsTheSame(
            old: DownloadManager.DownloadTask,
            new: DownloadManager.DownloadTask
        ): Boolean = old.status == new.status &&
                old.progress == new.progress &&
                old.downloadedBytes == new.downloadedBytes &&
                old.error == new.error
    }
}
