/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.file.FileSystemException
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardCopyOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.archive.ArchivePasswordRequiredException
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.toByteString
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal object SevenZipArchiveReader {
    private val supportedSuffixes = setOf(".7z", ".rar", ".r00")

    @Throws(IOException::class)
    fun readEntries(file: Path, passwords: List<String>): List<ArchiveFileEntry> {
        val localArchiveFile = getLocalArchiveFile(file)
        return try {
            SevenZipBridge.listEntries(localArchiveFile.path, passwords.toTypedArray()).map {
                it.toArchiveFileEntry()
            }
        } catch (e: SevenZipNativeException) {
            throw e.toFileSystemException(file)
        }
    }

    fun supports(file: Path): Boolean {
        val fileName = file.fileName?.toString()?.lowercase(Locale.ROOT) ?: return false
        return supportedSuffixes.any { fileName.endsWith(it) }
    }

    @Throws(IOException::class)
    fun newInputStream(file: Path, passwords: List<String>, entry: ArchiveFileEntry): FileInputStream {
        val localArchiveFile = getLocalArchiveFile(file)
        val cacheDirectory = getCacheDirectory()
        val suffix = entry.name.substringAfterLast('/', "").substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() }
            ?.let { ".$it" }
            ?: ".tmp"
        val tempFile = File.createTempFile("sevenzip-entry-", suffix, cacheDirectory)
        var successful = false
        try {
            SevenZipBridge.extractEntry(
                localArchiveFile.path, passwords.toTypedArray(), entry.name, tempFile.path
            )
            successful = true
            return DeleteOnCloseFileInputStream(tempFile)
        } catch (e: SevenZipNativeException) {
            throw e.toFileSystemException(file)
        } finally {
            if (!successful) {
                tempFile.delete()
            }
        }
    }

    @Throws(IOException::class)
    private fun getLocalArchiveFile(file: Path): File {
        val attributes = file.readAttributes(BasicFileAttributes::class.java)
        val cacheDirectory = getCacheDirectory()
        val sourceKey = buildString {
            append(file.fileSystem.provider().scheme)
            append(':')
            append(file)
        }
        val hash = sourceKey.sha256Hex()
        val extension = file.fileName?.toString()?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() }
            ?.let { ".$it" }
            .orEmpty()
        val cachedFile = File(
            cacheDirectory,
            "${hash}_${attributes.lastModifiedTime().toMillis()}_${attributes.size()}$extension"
        )
        if (cachedFile.isFile) {
            return cachedFile
        }
        cacheDirectory.listFiles()?.forEach {
            if (it.name.startsWith("${hash}_")) {
                it.delete()
            }
        }
        val tempFile = File.createTempFile("${hash}_", ".tmp", cacheDirectory)
        val tempPath = Paths.get(tempFile.path)
        val cachedPath = Paths.get(cachedFile.path)
        var successful = false
        try {
            file.copyTo(tempPath, StandardCopyOption.REPLACE_EXISTING)
            tempPath.moveTo(cachedPath, StandardCopyOption.REPLACE_EXISTING)
            successful = true
        } finally {
            if (!successful) {
                tempFile.delete()
            }
        }
        return cachedFile
    }

    private fun getCacheDirectory(): File =
        File(application.cacheDir, "sevenzip").apply {
            mkdirs()
        }

    private fun SevenZipEntryData.toArchiveFileEntry(): ArchiveFileEntry {
        val archiveType = when (type) {
            SevenZipEntryData.TYPE_DIRECTORY -> PosixFileType.DIRECTORY
            SevenZipEntryData.TYPE_SYMBOLIC_LINK -> PosixFileType.SYMBOLIC_LINK
            SevenZipEntryData.TYPE_REGULAR_FILE -> PosixFileType.REGULAR_FILE
            else -> PosixFileType.UNKNOWN
        }
        val owner = if (userId != SevenZipEntryData.UNKNOWN_ID || !userName.isNullOrEmpty()) {
            PosixUser(userId, userName?.toByteString())
        } else {
            null
        }
        val group = if (groupId != SevenZipEntryData.UNKNOWN_ID || !groupName.isNullOrEmpty()) {
            PosixGroup(groupId, groupName?.toByteString())
        } else {
            null
        }
        val modeBits = if (mode != SevenZipEntryData.UNKNOWN_MODE) {
            PosixFileMode.fromInt(mode)
        } else {
            null
        }
        return ArchiveFileEntry(
            name,
            isEncrypted,
            lastModifiedTimeMillis.toFileTimeOrNull(),
            lastAccessTimeMillis.toFileTimeOrNull(),
            creationTimeMillis.toFileTimeOrNull(),
            archiveType,
            size,
            owner,
            group,
            modeBits,
            symbolicLinkTarget
        )
    }

    private fun Long.toFileTimeOrNull(): FileTime? =
        if (this == SevenZipEntryData.UNKNOWN_TIME_MILLIS) null else FileTime.fromMillis(this)

    private fun SevenZipNativeException.toFileSystemException(file: Path): IOException =
        when (errorCode) {
            SevenZipNativeException.ERROR_PASSWORD_REQUIRED ->
                ArchivePasswordRequiredException(file, message)
            SevenZipNativeException.ERROR_ENTRY_NOT_FOUND ->
                FileSystemException(file.toString(), null, message ?: "Archive entry not found")
            else ->
                FileSystemException(file.toString(), null, message ?: "Failed to read archive")
        }.apply { initCause(this@toFileSystemException) }

    private fun String.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }

    private class DeleteOnCloseFileInputStream(
        private val file: File
    ) : FileInputStream(file) {
        override fun close() {
            @Suppress("ConvertTryFinallyToUseCall")
            try {
                super.close()
            } finally {
                file.delete()
            }
        }
    }
}
