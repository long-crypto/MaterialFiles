/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class FileListLiveData(private val path: Path) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    private val observer: PathObserver

    @Volatile
    private var isChangedWhileInactive = false

    init {
        loadValue()
        observer = PathObserver(path) { onChangeObserved() }
    }

    fun loadValue() {
        future?.cancel(true)
        value = Loading(value?.value)
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val value: Stateful<List<FileItem>> = run {
                var result: Stateful<List<FileItem>>? = null
                var retry: Boolean
                do {
                    retry = false
                    try {
                        path.newDirectoryStream().use { directoryStream ->
                            val fileList = mutableListOf<FileItem>()
                            for (path in directoryStream) {
                                try {
                                    fileList.add(path.loadFileItem())
                                } catch (e: DirectoryIteratorException) {
                                    // TODO: Ignoring such a file can be misleading and we need to support
                                    //  files without information.
                                    e.printStackTrace()
                                } catch (e: IOException) {
                                    e.printStackTrace()
                                }
                            }
                            result = Success(fileList as List<FileItem>)
                        }
                    } catch (e: me.zhanghai.android.files.provider.common.UserActionRequiredException) {
                        val proceed: Boolean = try {
                            kotlinx.coroutines.runBlocking {
                                kotlin.coroutines.suspendCoroutine { continuation ->
                                    val userAction = e.getUserAction(continuation, me.zhanghai.android.files.app.application)
                                    me.zhanghai.android.files.app.BackgroundActivityStarter.startActivity(
                                        userAction.intent,
                                        userAction.title,
                                        userAction.message,
                                        me.zhanghai.android.files.app.application
                                    )
                                }
                            }
                        } catch (ie: InterruptedException) {
                            result = Failure(valueCompat.value, java.io.InterruptedIOException().apply { initCause(ie) })
                            false
                        }
                        if (proceed) {
                            // User provided input (e.g., archive password). Retry loading.
                            retry = true
                        } else {
                            result = Failure(valueCompat.value, e)
                        }
                    } catch (e: Exception) {
                        result = Failure(valueCompat.value, e)
                    }
                } while (retry && result == null)
                result ?: Failure(valueCompat.value, IllegalStateException("Unknown error"))
            }
            postValue(value)
        }
    }

    private fun onChangeObserved() {
        if (hasActiveObservers()) {
            loadValue()
        } else {
            isChangedWhileInactive = true
        }
    }

    override fun onActive() {
        if (isChangedWhileInactive) {
            loadValue()
            isChangedWhileInactive = false
        }
    }

    override fun close() {
        observer.close()
        future?.cancel(true)
    }
}
