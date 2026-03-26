/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storageanalysis

import android.os.AsyncTask
import java8.nio.file.Path
import me.zhanghai.android.files.fileproperties.PathObserverLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.InterruptedIOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class StorageAnalysisLiveData(path: Path) : PathObserverLiveData<Stateful<StorageAnalysis>>(path) {
    private var future: Future<Unit>? = null

    init {
        loadValue()
        observe()
    }

    override fun loadValue() {
        future?.cancel(true)
        value = Loading(value?.value)
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val value = try {
                Success(path.loadStorageAnalysis { Thread.currentThread().isInterrupted })
            } catch (e: InterruptedIOException) {
                return@submit
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }

    override fun close() {
        super.close()
        future?.cancel(true)
    }
}
