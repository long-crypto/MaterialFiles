/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.calculateTotalSize
import me.zhanghai.android.files.util.CloseableLiveData
import java.io.InterruptedIOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class FileListItemSizeLiveData(
    private val files: List<FileItem>
) : CloseableLiveData<Map<Path, Long>>(
    files.filterNot { it.attributes.isDirectory }.associate { it.path to it.attributes.size() }
) {
    private var future: Future<Unit>? = null

    init {
        loadValue()
    }

    private fun loadValue() {
        future?.cancel(true)
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val sizeMap = LinkedHashMap(value ?: emptyMap())
            for (file in files) {
                if (!file.attributes.isDirectory) {
                    continue
                }
                val size = try {
                    file.calculateTotalSize { Thread.currentThread().isInterrupted }
                } catch (e: InterruptedIOException) {
                    return@submit
                } catch (e: Exception) {
                    e.printStackTrace()
                    continue
                }
                sizeMap[file.path] = size
                postValue(LinkedHashMap(sizeMap))
            }
        }
    }

    override fun close() {
        future?.cancel(true)
    }
}
