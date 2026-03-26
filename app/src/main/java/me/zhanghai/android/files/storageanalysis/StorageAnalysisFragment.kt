/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storageanalysis

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.StorageAnalysisFragmentBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.viewModels
import me.zhanghai.android.files.util.showToast

class StorageAnalysisFragment : Fragment(), StorageAnalysisAdapter.Listener {
    private val path by lazy { requireActivity().intent.extraPath!! }

    private val viewModel by viewModels { { StorageAnalysisViewModel(path) } }

    private lateinit var binding: StorageAnalysisFragmentBinding
    private lateinit var adapter: StorageAnalysisAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        StorageAnalysisFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setTitle(R.string.storage_analysis_title)
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.subtitle = path.toUserFriendlyString()
        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        adapter = StorageAnalysisAdapter(this)
        binding.recyclerView.adapter = adapter
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.reload() }

        viewModel.analysisLiveData.observe(viewLifecycleOwner) { onAnalysisChanged(it) }
    }

    private fun onAnalysisChanged(stateful: Stateful<StorageAnalysis>) {
        val analysis = stateful.value
        binding.progress.isVisible = stateful is Loading && analysis == null
        binding.swipeRefreshLayout.isRefreshing = stateful is Loading && analysis != null
        binding.summaryLayout.fadeToVisibilityUnsafe(analysis != null)
        binding.recyclerView.fadeToVisibilityUnsafe(
            analysis != null && analysis.entries.isNotEmpty()
        )
        binding.emptyView.fadeToVisibilityUnsafe(
            stateful is Success && analysis?.entries?.isEmpty() == true
        )
        binding.errorText.fadeToVisibilityUnsafe(stateful is Failure && analysis == null)
        if (stateful is Failure) {
            stateful.throwable.printStackTrace()
            val error = stateful.throwable.toString()
            if (analysis != null) {
                showToast(error)
            } else {
                binding.errorText.text = error
            }
        }
        if (analysis != null) {
            bindSummary(analysis)
            adapter.replace(analysis)
        }
    }

    private fun bindSummary(analysis: StorageAnalysis) {
        val context = requireContext()
        binding.totalSizeText.text = analysis.totalSize.asFileSize().formatHumanReadable(context)
        binding.summaryText.text = getSummaryText(analysis)
        val storageSpace = analysis.storageSpace
        binding.spaceText.fadeToVisibilityUnsafe(storageSpace != null)
        if (storageSpace != null) {
            val freeSpace = storageSpace.freeSpace.asFileSize().formatHumanReadable(context)
            val totalSpace = storageSpace.totalSpace.asFileSize().formatHumanReadable(context)
            binding.spaceText.text = getString(
                R.string.navigation_storage_subtitle_format, freeSpace, totalSpace
            )
        }
    }

    private fun getSummaryText(analysis: StorageAnalysis): String {
        val directoryCountText = if (analysis.directoryCount > 0) {
            getQuantityString(
                R.plurals.file_list_subtitle_directory_count_format,
                analysis.directoryCount,
                analysis.directoryCount
            )
        } else {
            null
        }
        val fileCountText = if (analysis.fileCount > 0) {
            getQuantityString(
                R.plurals.file_list_subtitle_file_count_format,
                analysis.fileCount,
                analysis.fileCount
            )
        } else {
            null
        }
        return when {
            !directoryCountText.isNullOrEmpty() && !fileCountText.isNullOrEmpty() ->
                directoryCountText + getString(R.string.file_list_subtitle_separator) + fileCountText
            !directoryCountText.isNullOrEmpty() -> directoryCountText
            !fileCountText.isNullOrEmpty() -> fileCountText
            else -> getString(R.string.empty)
        }
    }

    override fun analyzeDirectory(file: FileItem) {
        startActivitySafe(StorageAnalysisActivity.createIntent(file.path))
    }
}
