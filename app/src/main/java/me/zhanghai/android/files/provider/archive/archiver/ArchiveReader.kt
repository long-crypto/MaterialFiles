/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import androidx.preference.PreferenceManager
import java8.nio.charset.StandardCharsets
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.provider.root.rootContext
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

object ArchiveReader {
    @Throws(IOException::class)
    fun readEntries(
        file: Path,
        passwords: List<String>,
        rootPath: Path
    ): Pair<Map<Path, ArchiveFileEntry>, Map<Path, List<Path>>> {
        val entries = mutableMapOf<Path, ArchiveFileEntry>()
        val rawEntries = readEntries(file, passwords)
        for (entry in rawEntries) {
            var path = rootPath.resolve(entry.name)
            // Normalize an absolute path to prevent path traversal attack.
            if (!path.isAbsolute) {
                throw AssertionError("Path must be absolute: $path")
            }
            if (path.nameCount > 0) {
                path = path.normalize()
                if (path.nameCount == 0) {
                    continue
                }
            } else if (!entry.isDirectory) {
                continue
            }
            entries.getOrPut(path) { entry }
        }
        entries.getOrPut(rootPath) { createDirectoryEntry("") }
        val tree = mutableMapOf<Path, MutableList<Path>>()
        tree[rootPath] = mutableListOf()
        val paths = entries.keys.toList()
        for (path in paths) {
            var currentPath = path
            while (true) {
                val parentPath = currentPath.parent ?: break
                val entry = entries[currentPath]!!
                if (entry.isDirectory) {
                    tree.getOrPut(currentPath) { mutableListOf() }
                }
                tree.getOrPut(parentPath) { mutableListOf() }.add(currentPath)
                if (entries.containsKey(parentPath)) {
                    break
                }
                entries[parentPath] = createDirectoryEntry(parentPath.toString())
                currentPath = parentPath
            }
        }
        return entries to tree
    }

    @Throws(IOException::class)
    fun newInputStream(
        file: Path,
        passwords: List<String>,
        entry: ArchiveFileEntry
    ): InputStream? =
        when {
            SevenZipArchiveReader.supports(file) -> {
                if (entry.isDirectory || entry.isSymbolicLink) {
                    null
                } else {
                    SevenZipArchiveReader.newInputStream(file, passwords, entry)
                }
            }
            else -> LibarchiveArchiveReader.newInputStream(
                file, passwords, entry, archiveFileNameCharset
            )
        }

    private fun createDirectoryEntry(name: String): ArchiveFileEntry {
        require(!name.endsWith("/")) { "name $name should not end with a slash" }
        return ArchiveFileEntry(
            name, false, null, null, null, PosixFileType.DIRECTORY, 0, null, null,
            PosixFileMode.DIRECTORY_DEFAULT, null
        )
    }

    @Throws(IOException::class)
    private fun readEntries(file: Path, passwords: List<String>): List<ArchiveFileEntry> =
        when {
            SevenZipArchiveReader.supports(file) -> SevenZipArchiveReader.readEntries(file, passwords)
            else -> LibarchiveArchiveReader.readEntries(file, passwords, archiveFileNameCharset)
        }

    private val archiveFileNameCharset: Charset
        get() =
            if (isRunningAsRoot) {
                try {
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(rootContext)
                    val key = rootContext.getString(R.string.pref_key_archive_file_name_encoding)
                    val defaultValue = rootContext.getString(
                        R.string.pref_default_value_archive_file_name_encoding
                    )
                    Charset.forName(sharedPreferences.getString(key, defaultValue)!!)
                } catch (e: Exception) {
                    e.printStackTrace()
                    StandardCharsets.UTF_8
                }
            } else {
                Charset.forName(Settings.ARCHIVE_FILE_NAME_ENCODING.valueCompat)
            }
}
