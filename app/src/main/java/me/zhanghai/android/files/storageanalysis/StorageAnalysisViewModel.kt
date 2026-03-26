/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storageanalysis

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import java8.nio.file.Path
import me.zhanghai.android.files.util.Stateful

class StorageAnalysisViewModel(path: Path) : ViewModel() {
    private val _analysisLiveData = StorageAnalysisLiveData(path)
    val analysisLiveData: LiveData<Stateful<StorageAnalysis>>
        get() = _analysisLiveData

    fun reload() {
        _analysisLiveData.loadValue()
    }

    override fun onCleared() {
        _analysisLiveData.close()
    }
}
