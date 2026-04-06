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
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.toByteString
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.deleteRecursively
import kotlin.math.max

internal object SevenZipArchiveReader {
    private val directSupportedSuffixes = setOf(".7z", ".rar", ".r00")

    @Throws(IOException::class)
    fun readEntries(file: Path, passwords: List<String>): List<ArchiveFileEntry> {
        val archiveFile = resolveArchiveFile(file)
        val localArchiveFile = getLocalArchiveFile(archiveFile)
        return try {
            SevenZipBridge.listEntries(localArchiveFile.path, passwords.toTypedArray()).map {
                it.toArchiveFileEntry()
            }
        } catch (e: SevenZipNativeException) {
            throw e.toFileSystemException(archiveFile)
        }
    }

    fun supports(file: Path): Boolean = resolveArchiveFileOrNull(file) != null

    @Throws(IOException::class)
    fun newInputStream(file: Path, passwords: List<String>, entry: ArchiveFileEntry): FileInputStream {
        val archiveFile = resolveArchiveFile(file)
        val localArchiveFile = getLocalArchiveFile(archiveFile)
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
            throw e.toFileSystemException(archiveFile)
        } finally {
            if (!successful) {
                tempFile.delete()
            }
        }
    }

    @Throws(IOException::class)
    fun resolveArchiveFile(file: Path): Path =
        resolveArchiveFileOrNull(file)
            ?: throw FileSystemException(file.toString(), null, "Unsupported split archive file")

    fun resolveArchiveFileOrSame(file: Path): Path = resolveArchiveFileOrNull(file) ?: file

    private fun resolveArchiveFileOrNull(file: Path): Path? {
        val fileName = file.fileName?.toString() ?: return null
        val lowercaseFileName = fileName.lowercase(Locale.ROOT)
        matchNumberedArchive(fileName)?.let { match ->
            resolveNumberedArchive(file, match)?.let { return it }
        }
        if (directSupportedSuffixes.any { lowercaseFileName.endsWith(it) }) {
            return when {
                lowercaseFileName.endsWith(".r00") -> resolveOldStyleRarArchive(file, fileName)
                lowercaseFileName.endsWith(".rar") -> resolvePartRarArchive(file, fileName) ?: file
                else -> file
            }
        }
        if (lowercaseFileName.endsWith(".zip") && collectOldStyleZipVolumes(file, lowercaseFileName) != null) {
            return file
        }
        if (OLD_STYLE_RAR_PART_REGEX.matches(lowercaseFileName)) {
            return resolveOldStyleRarArchive(file, fileName)
        }
        if (OLD_STYLE_ZIP_PART_REGEX.matches(lowercaseFileName)) {
            return resolveOldStyleZipArchive(file, fileName)
        }
        resolvePartRarArchive(file, fileName)?.let { return it }
        return null
    }

    private fun resolveOldStyleZipArchive(file: Path, fileName: String): Path {
        val mainFileName = fileName.dropLast(4) + ".zip"
        val mainFile = file.resolveSibling(mainFileName)
        return mainFile.takeIf { it.exists() } ?: file
    }

    private fun resolveNumberedArchive(file: Path, match: NumberedArchiveMatch): Path? {
        val mainFile = file.resolveSibling("${match.baseName}.001")
        val secondFile = file.resolveSibling("${match.baseName}.002")
        if (!mainFile.exists() || !secondFile.exists()) {
            return null
        }
        if (match.number == 1) {
            return mainFile
        }
        val previousFile = file.resolveSibling("${match.baseName}.${(match.number - 1).toString().padStart(3, '0')}")
        if (!previousFile.exists()) {
            return null
        }
        return mainFile
    }

    private fun resolveOldStyleRarArchive(file: Path, fileName: String): Path {
        val mainFileName = fileName.dropLast(4) + ".rar"
        val mainFile = file.resolveSibling(mainFileName)
        return mainFile.takeIf { it.exists() } ?: file
    }

    private fun resolvePartRarArchive(file: Path, fileName: String): Path? {
        val match = PART_RAR_REGEX.matchEntire(fileName) ?: return null
        val prefix = match.groupValues[1]
        val digits = match.groupValues[2]
        val mainFileName = buildString {
            append(prefix)
            append(".part")
            append("1".padStart(max(1, digits.length), '0'))
            append(".rar")
        }
        val mainFile = file.resolveSibling(mainFileName)
        return mainFile.takeIf { it.exists() }
    }

    @Throws(IOException::class)
    private fun getLocalArchiveFile(file: Path): File {
        val archiveFile = resolveArchiveFile(file)
        getNumberedArchiveInfoOrNull(archiveFile)?.let {
            return getLocalNumberedArchiveFile(archiveFile, it)
        }
        return when (getMultipartArchiveInfoOrNull(archiveFile)) {
            null -> getLocalSingleArchiveFile(archiveFile)
            else -> getLocalMultipartArchiveFile(archiveFile)
        }
    }

    @Throws(IOException::class)
    private fun getLocalSingleArchiveFile(file: Path): File {
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
                it.deleteRecursively()
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

    @Throws(IOException::class)
    private fun getLocalNumberedArchiveFile(file: Path, info: MultipartArchiveInfo): File {
        val cacheDirectory = getCacheDirectory()
        val hash = buildString {
            append(file.fileSystem.provider().scheme)
            append(':')
            append(file.parent)
            append('/')
            append(info.baseName)
            append('#')
            append(info.extension)
        }.sha256Hex()
        val cachePrefix = "${hash}_"
        val state = info.volumeNames.joinToString(separator = "|") { volumeName ->
            val volume = file.resolveSibling(volumeName)
            val attributes = volume.readAttributes(BasicFileAttributes::class.java)
            "$volumeName:${attributes.lastModifiedTime().toMillis()}:${attributes.size()}"
        }
        val cachedFile = File(cacheDirectory, "$cachePrefix${state.sha256Hex()}.${info.extension}")
        if (cachedFile.isFile) {
            return cachedFile
        }
        cacheDirectory.listFiles()?.forEach {
            if (it.name.startsWith(cachePrefix)) {
                it.deleteRecursively()
            }
        }
        val tempFile = File.createTempFile(cachePrefix, ".${info.extension}", cacheDirectory)
        val tempPath = Paths.get(tempFile.path)
        val cachedPath = Paths.get(cachedFile.path)
        var successful = false
        try {
            tempPath.newOutputStream().use { outputStream ->
                for (volumeName in info.volumeNames) {
                    val source = file.resolveSibling(volumeName)
                    source.newInputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
            tempPath.moveTo(cachedPath, StandardCopyOption.REPLACE_EXISTING)
            successful = true
            return cachedFile
        } finally {
            if (!successful) {
                tempFile.delete()
            }
        }
    }

    @Throws(IOException::class)
    private fun getLocalMultipartArchiveFile(file: Path): File {
        val info = getMultipartArchiveInfoOrNull(file)
            ?: return getLocalSingleArchiveFile(file)
        val cacheDirectory = getCacheDirectory()
        val hash = buildString {
            append(file.fileSystem.provider().scheme)
            append(':')
            append(file.parent)
            append('/')
            append(info.baseName)
            append('#')
            append(info.extension)
        }.sha256Hex()
        val cachePrefix = "${hash}_"
        val state = info.volumeNames.joinToString(separator = "|") { volumeName ->
            val volume = file.resolveSibling(volumeName)
            val attributes = volume.readAttributes(BasicFileAttributes::class.java)
            "$volumeName:${attributes.lastModifiedTime().toMillis()}:${attributes.size()}"
        }
        val cachedDirectory = File(cacheDirectory, "$cachePrefix${state.sha256Hex()}")
        val cachedFile = File(cachedDirectory, info.mainVolumeName)
        if (cachedFile.isFile) {
            return cachedFile
        }
        cacheDirectory.listFiles()?.forEach {
            if (it.name.startsWith(cachePrefix)) {
                it.deleteRecursively()
            }
        }
        val tempDirectory = File(cacheDirectory, "$cachePrefix${System.nanoTime()}")
        if (!tempDirectory.mkdirs()) {
            throw IOException("Failed to create cache directory $tempDirectory")
        }
        var successful = false
        try {
            for (volumeName in info.volumeNames) {
                val source = file.resolveSibling(volumeName)
                val target = File(tempDirectory, volumeName)
                source.copyTo(Paths.get(target.path), StandardCopyOption.REPLACE_EXISTING)
            }
            if (!tempDirectory.renameTo(cachedDirectory)) {
                throw IOException("Failed to prepare cached archive directory $cachedDirectory")
            }
            successful = true
            return cachedFile
        } finally {
            if (!successful) {
                tempDirectory.deleteRecursively()
                cachedDirectory.deleteRecursively()
            }
        }
    }

    private fun getNumberedArchiveInfoOrNull(file: Path): MultipartArchiveInfo? {
        val fileName = file.fileName?.toString() ?: return null
        val match = matchNumberedArchive(fileName) ?: return null
        if (match.extension !in supportedNumberedExtensions) {
            return null
        }
        return collectNumberedVolumes(file, fileName)
    }

    private fun getMultipartArchiveInfoOrNull(file: Path): MultipartArchiveInfo? {
        val fileName = file.fileName?.toString() ?: return null
        return when {
            PART_RAR_REGEX.matches(fileName) -> collectPartRarVolumes(file, fileName)
            fileName.lowercase(Locale.ROOT).endsWith(".rar") ||
                OLD_STYLE_RAR_PART_REGEX.matches(fileName) -> collectOldStyleRarVolumes(file, fileName)
            fileName.lowercase(Locale.ROOT).endsWith(".zip") ||
                OLD_STYLE_ZIP_PART_REGEX.matches(fileName) -> collectOldStyleZipVolumes(file, fileName)
            else -> null
        }
    }

    private fun collectPartRarVolumes(file: Path, fileName: String): MultipartArchiveInfo? {
        val match = PART_RAR_REGEX.matchEntire(fileName) ?: return null
        val prefix = match.groupValues[1]
        val digits = max(1, match.groupValues[2].length)
        val volumeNames = mutableListOf<String>()
        var index = 1
        while (true) {
            val volumeName = "$prefix.part${index.toString().padStart(digits, '0')}.rar"
            if (!file.resolveSibling(volumeName).exists()) {
                break
            }
            volumeNames += volumeName
            index += 1
        }
        if (volumeNames.size <= 1) {
            return null
        }
        return MultipartArchiveInfo(prefix, "rar", volumeNames.first(), volumeNames)
    }

    private fun collectOldStyleRarVolumes(file: Path, fileName: String): MultipartArchiveInfo? {
        val mainVolumeName = if (fileName.lowercase(Locale.ROOT).endsWith(".rar")) {
            fileName
        } else {
            resolveOldStyleRarArchive(file, fileName).fileName?.toString() ?: return null
        }
        val prefix = mainVolumeName.dropLast(4)
        if (!file.resolveSibling(mainVolumeName).exists()) {
            return null
        }
        val volumeNames = mutableListOf(mainVolumeName)
        var index = 0
        while (true) {
            val volumeName = "$prefix.r${index.toString().padStart(2, '0')}"
            if (!file.resolveSibling(volumeName).exists()) {
                break
            }
            volumeNames += volumeName
            index += 1
        }
        if (volumeNames.size <= 1) {
            return null
        }
        return MultipartArchiveInfo(prefix, "rar", mainVolumeName, volumeNames)
    }

    private fun collectOldStyleZipVolumes(file: Path, fileName: String): MultipartArchiveInfo? {
        val mainVolumeName = if (fileName.lowercase(Locale.ROOT).endsWith(".zip")) {
            fileName
        } else {
            resolveOldStyleZipArchive(file, fileName).fileName?.toString() ?: return null
        }
        val prefix = mainVolumeName.dropLast(4)
        if (!file.resolveSibling(mainVolumeName).exists()) {
            return null
        }
        val volumeNames = mutableListOf<String>()
        var index = 1
        while (true) {
            val volumeName = "$prefix.z${index.toString().padStart(2, '0')}"
            if (!file.resolveSibling(volumeName).exists()) {
                break
            }
            volumeNames += volumeName
            index += 1
        }
        if (volumeNames.isEmpty()) {
            return null
        }
        volumeNames += mainVolumeName
        return MultipartArchiveInfo(prefix, "zip", mainVolumeName, volumeNames)
    }

    private fun collectNumberedVolumes(file: Path, fileName: String): MultipartArchiveInfo? {
        val match = matchNumberedArchive(fileName) ?: return null
        val firstVolume = "${match.baseName}.001"
        if (!file.resolveSibling(firstVolume).exists()) {
            return null
        }
        val volumeNames = mutableListOf<String>()
        var index = 1
        while (true) {
            val volumeName = "${match.baseName}.${index.toString().padStart(3, '0')}"
            if (!file.resolveSibling(volumeName).exists()) {
                break
            }
            volumeNames += volumeName
            index += 1
        }
        if (volumeNames.size <= 1) {
            return null
        }
        return MultipartArchiveInfo(match.baseName, match.extension, firstVolume, volumeNames)
    }

    private data class NumberedArchiveMatch(
        val baseName: String,
        val extension: String,
        val number: Int
    )

    private data class MultipartArchiveInfo(
        val baseName: String,
        val extension: String,
        val mainVolumeName: String,
        val volumeNames: List<String>
    )

    private fun matchNumberedArchive(fileName: String): NumberedArchiveMatch? {
        val match = NUMBERED_ARCHIVE_REGEX.matchEntire(fileName) ?: return null
        val extensionPart = match.groupValues[2]
        val extension = extensionPart.lowercase(Locale.ROOT)
        val baseName = "${match.groupValues[1]}.$extensionPart"
        val number = match.groupValues[3].toIntOrNull() ?: return null
        return NumberedArchiveMatch(baseName, extension, number)
    }

    private val PART_RAR_REGEX = Regex("(?i)(.+)\\.part(\\d+)\\.rar")
    private val OLD_STYLE_RAR_PART_REGEX = Regex("(?i).+\\.r\\d{2}")
    private val OLD_STYLE_ZIP_PART_REGEX = Regex("(?i).+\\.z\\d{2}")
    private val NUMBERED_ARCHIVE_REGEX = Regex("(?i)(.+)\\.(7z|zip)\\.(\\d{3})")
    private val supportedNumberedExtensions = setOf("7z", "zip")

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
