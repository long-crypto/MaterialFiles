/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java.io.IOException

internal class SevenZipNativeException(
    val errorCode: Int,
    message: String?
) : IOException(message) {
    companion object {
        const val ERROR_OPEN_FAILED = 1
        const val ERROR_PASSWORD_REQUIRED = 2
        const val ERROR_ENTRY_NOT_FOUND = 3
        const val ERROR_EXTRACTION_FAILED = 4
    }
}
