/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

internal data class SevenZipEntryData(
    val name: String,
    val isEncrypted: Boolean,
    val lastModifiedTimeMillis: Long,
    val lastAccessTimeMillis: Long,
    val creationTimeMillis: Long,
    val type: Int,
    val size: Long,
    val userId: Int,
    val userName: String?,
    val groupId: Int,
    val groupName: String?,
    val mode: Int,
    val symbolicLinkTarget: String?
) {
    companion object {
        const val TYPE_UNKNOWN = 0
        const val TYPE_REGULAR_FILE = 1
        const val TYPE_DIRECTORY = 2
        const val TYPE_SYMBOLIC_LINK = 3

        const val UNKNOWN_ID = -1
        const val UNKNOWN_MODE = -1
        const val UNKNOWN_TIME_MILLIS = Long.MIN_VALUE
    }
}
