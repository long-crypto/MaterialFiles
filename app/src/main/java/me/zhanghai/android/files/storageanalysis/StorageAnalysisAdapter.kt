/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storageanalysis

import android.view.ViewGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import me.zhanghai.android.files.databinding.StorageAnalysisItemBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.filelist.getMimeTypeName
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.layoutInflater

class StorageAnalysisAdapter(
    private val listener: Listener
) : SimpleAdapter<StorageAnalysisEntry, StorageAnalysisAdapter.ViewHolder>() {
    private var totalSize = 0L

    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).file.path.hashCode().toLong()

    fun replace(analysis: StorageAnalysis) {
        totalSize = analysis.totalSize
        replace(analysis.entries)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            StorageAnalysisItemBinding.inflate(parent.context.layoutInflater, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val file = entry.file
        val binding = holder.binding
        binding.root.isEnabled = file.attributes.isDirectory
        if (file.attributes.isDirectory) {
            binding.root.setOnClickListener { listener.analyzeDirectory(file) }
        } else {
            binding.root.setOnClickListener(null)
        }
        binding.iconImage.setImageResource(file.mimeType.iconRes)
        binding.nameText.text = file.name
        binding.descriptionText.text = file.getMimeTypeName(binding.root.context)
        binding.sizeText.text =
            entry.totalSize.asFileSize().formatHumanReadable(binding.root.context)
        bindProgress(binding.progressIndicator, entry.totalSize)
    }

    private fun bindProgress(progressIndicator: LinearProgressIndicator, size: Long) {
        val progress = if (totalSize > 0L) {
            ((size * PROGRESS_MAX) / totalSize).toInt()
        } else {
            0
        }
        progressIndicator.max = PROGRESS_MAX
        progressIndicator.setProgressCompat(progress, false)
    }

    class ViewHolder(val binding: StorageAnalysisItemBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    interface Listener {
        fun analyzeDirectory(file: FileItem)
    }

    companion object {
        private const val PROGRESS_MAX = 10000
    }
}
