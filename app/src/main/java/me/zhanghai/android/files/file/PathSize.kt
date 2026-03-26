/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import androidx.annotation.WorkerThread
import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import java.io.InterruptedIOException

@WorkerThread
@Throws(IOException::class)
fun FileItem.calculateTotalSize(isCanceled: () -> Boolean = { false }): Long =
    if (attributes.isDirectory) {
        path.calculateContentsSize(isCanceled)
    } else {
        attributes.size()
    }

@WorkerThread
@Throws(IOException::class)
fun Path.calculateContentsSize(isCanceled: () -> Boolean = { false }): Long {
    var size = 0L
    var isTerminated = false
    Files.walkFileTree(this, object : FileVisitor<Path> {
        override fun preVisitDirectory(
            directory: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult = visit(directory, attributes, null)

        override fun visitFile(
            file: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult = visit(file, attributes, null)

        override fun visitFileFailed(
            file: Path,
            exception: IOException
        ): FileVisitResult = visit(file, null, exception)

        override fun postVisitDirectory(
            directory: Path,
            exception: IOException?
        ): FileVisitResult = visit(null, null, exception)

        private fun visit(
            path: Path?,
            attributes: BasicFileAttributes?,
            exception: IOException?
        ): FileVisitResult {
            if (isCanceled()) {
                isTerminated = true
                return FileVisitResult.TERMINATE
            }
            if (path == this@calculateContentsSize) {
                return FileVisitResult.CONTINUE
            }
            attributes?.let { size += it.size() }
            exception?.printStackTrace()
            return FileVisitResult.CONTINUE
        }
    })
    if (isTerminated && isCanceled()) {
        throw InterruptedIOException()
    }
    return size
}
