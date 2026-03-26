/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

internal object SevenZipBridge {
    init {
        System.loadLibrary("sevenzip")
    }

    @Throws(SevenZipNativeException::class)
    external fun listEntries(archivePath: String, passwords: Array<String>): Array<SevenZipEntryData>

    @Throws(SevenZipNativeException::class)
    external fun extractEntry(
        archivePath: String,
        passwords: Array<String>,
        entryName: String,
        targetPath: String
    )
}
