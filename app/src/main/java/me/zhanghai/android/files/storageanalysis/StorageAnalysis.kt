/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storageanalysis

import androidx.annotation.WorkerThread
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.calculateTotalSize
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.storage.StorageSpace
import me.zhanghai.android.files.storage.getStorageSpace
import java.io.IOException
import java.io.InterruptedIOException

data class StorageAnalysis(
    val path: Path,
    val entries: List<StorageAnalysisEntry>,
    val directoryCount: Int,
    val fileCount: Int,
    val totalSize: Long,
    val storageSpace: StorageSpace?
)

data class StorageAnalysisEntry(
    val file: FileItem,
    val totalSize: Long
)

@WorkerThread
@Throws(IOException::class)
fun Path.loadStorageAnalysis(isCanceled: () -> Boolean = { false }): StorageAnalysis {
    val entries = mutableListOf<StorageAnalysisEntry>()
    newDirectoryStream().use { directoryStream ->
        for (childPath in directoryStream) {
            if (isCanceled()) {
                throw InterruptedIOException()
            }
            try {
                val file = childPath.loadFileItem()
                val totalSize = file.calculateTotalSize(isCanceled)
                entries.add(StorageAnalysisEntry(file, totalSize))
            } catch (e: DirectoryIteratorException) {
                e.printStackTrace()
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
    val sortedEntries = entries.sortedWith(
        compareByDescending<StorageAnalysisEntry> { it.totalSize }
            .thenBy { it.file.name }
    )
    val directoryCount = sortedEntries.count { it.file.attributes.isDirectory }
    val fileCount = sortedEntries.size - directoryCount
    val totalSize = sortedEntries.sumOf { it.totalSize }
    return StorageAnalysis(
        this,
        sortedEntries,
        directoryCount,
        fileCount,
        totalSize,
        getStorageSpace()
    )
}
