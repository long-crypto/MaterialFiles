/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.os.Environment
import java8.nio.file.Path
import me.zhanghai.android.files.file.JavaFile
import me.zhanghai.android.files.provider.linux.isLinuxPath

data class StorageSpace(
    val totalSpace: Long,
    val freeSpace: Long
)

fun Path.getStorageSpace(): StorageSpace? =
    if (isLinuxPath) {
        getStorageSpace(toFile().path)
    } else {
        null
    }

fun getStorageSpace(linuxPath: String): StorageSpace? {
    var totalSpace = JavaFile.getTotalSpace(linuxPath)
    val freeSpace: Long
    when {
        totalSpace != 0L -> freeSpace = JavaFile.getFreeSpace(linuxPath)
        linuxPath == FileSystemRoot.LINUX_PATH -> {
            // Root directory may not be an actual partition on legacy Android versions.
            val systemPath = Environment.getRootDirectory().path
            totalSpace = JavaFile.getTotalSpace(systemPath)
            freeSpace = JavaFile.getFreeSpace(systemPath)
        }
        else -> freeSpace = 0L
    }
    return if (totalSpace != 0L) {
        StorageSpace(totalSpace, freeSpace)
    } else {
        null
    }
}
