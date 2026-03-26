/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.Path
import me.zhanghai.android.files.provider.archive.ArchiveExceptionInputStream
import me.zhanghai.android.files.provider.archive.toFileSystemOrInterruptedIOException
import me.zhanghai.android.files.provider.common.DelegateForceableSeekableByteChannel
import me.zhanghai.android.files.provider.common.DelegateNonForceableSeekableByteChannel
import me.zhanghai.android.files.provider.common.ForceableChannel
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

internal object LibarchiveArchiveReader {
    @Throws(IOException::class)
    fun readEntries(file: Path, passwords: List<String>, charset: Charset): List<ArchiveFileEntry> {
        val (archive, closeable) = openArchive(file, passwords)
        return closeable.use {
            buildList {
                while (true) {
                    this += archive.readEntry(charset)?.toArchiveFileEntry() ?: break
                }
            }
        }
    }

    @Throws(IOException::class)
    fun newInputStream(
        file: Path,
        passwords: List<String>,
        entry: ArchiveFileEntry,
        charset: Charset
    ): InputStream? {
        val (archive, closeable) = openArchive(file, passwords)
        var found = false
        return try {
            while (true) {
                val currentEntry = archive.readEntry(charset) ?: break
                if (currentEntry.name != entry.name) {
                    continue
                }
                found = true
                break
            }
            if (found) {
                ArchiveExceptionInputStream(
                    CloseableInputStream(archive.newDataInputStream(), closeable), file
                )
            } else {
                null
            }
        } catch (e: ArchiveException) {
            closeable.close()
            throw e.toFileSystemOrInterruptedIOException(file)
        } finally {
            if (!found) {
                closeable.close()
            }
        }
    }

    @Throws(IOException::class)
    private fun openArchive(
        file: Path,
        passwords: List<String>
    ): Pair<ReadArchive, ArchiveCloseable> {
        val channel = try {
            cacheSizeSeekableByteChannel(file.newByteChannel())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        if (channel != null) {
            var successful = false
            try {
                val archive = ReadArchive(channel, passwords)
                successful = true
                return archive to ArchiveCloseable(archive, channel)
            } catch (e: ArchiveException) {
                throw e.toFileSystemOrInterruptedIOException(file)
            } finally {
                if (!successful) {
                    channel.close()
                }
            }
        }
        val inputStream = file.newInputStream()
        var successful = false
        try {
            val archive = ReadArchive(inputStream, passwords)
            successful = true
            return archive to ArchiveCloseable(archive, inputStream)
        } catch (e: ArchiveException) {
            throw e.toFileSystemOrInterruptedIOException(file)
        } finally {
            if (!successful) {
                inputStream.close()
            }
        }
    }

    // size() may be called repeatedly for ZIP and 7Z, so make it cached to improve performance.
    private fun cacheSizeSeekableByteChannel(channel: SeekableByteChannel): SeekableByteChannel =
        if (channel is ForceableChannel) {
            CacheSizeForceableSeekableByteChannel(channel)
        } else {
            CacheSizeNonForceableSeekableByteChannel(channel)
        }

    private fun ReadArchive.Entry.toArchiveFileEntry() =
        ArchiveFileEntry(
            name, isEncrypted, lastModifiedTime, lastAccessTime, creationTime, type, size, owner,
            group, mode, symbolicLinkTarget
        )

    private class CacheSizeNonForceableSeekableByteChannel(
        channel: SeekableByteChannel
    ) : DelegateNonForceableSeekableByteChannel(channel) {
        private val size: Long by lazy { super.size() }

        override fun size(): Long = size
    }

    private class CacheSizeForceableSeekableByteChannel(
        channel: SeekableByteChannel
    ) : DelegateForceableSeekableByteChannel(channel) {
        private val size: Long by lazy { super.size() }

        override fun size(): Long = size
    }

    private class ArchiveCloseable(
        private val archive: ReadArchive,
        private val closeable: Closeable
    ) : Closeable {
        override fun close() {
            @Suppress("ConvertTryFinallyToUseCall")
            try {
                archive.close()
            } finally {
                closeable.close()
            }
        }
    }

    private class CloseableInputStream(
        inputStream: InputStream,
        private val closeable: Closeable
    ) : InputStream() {
        private val delegate = inputStream

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray): Int = delegate.read(b)

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun skip(n: Long): Long = delegate.skip(n)

        override fun available(): Int = delegate.available()

        override fun close() {
            @Suppress("ConvertTryFinallyToUseCall")
            try {
                delegate.close()
            } finally {
                closeable.close()
            }
        }

        override fun mark(readlimit: Int) {
            delegate.mark(readlimit)
        }

        override fun reset() {
            delegate.reset()
        }

        override fun markSupported(): Boolean = delegate.markSupported()
    }
}
