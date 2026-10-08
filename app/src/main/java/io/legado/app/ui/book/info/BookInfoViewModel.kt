package io.legado.app.ui.book.info

import android.app.Application
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseReadViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.FileBook.WebFile
import io.legado.app.ui.book.read.ReviewListDialog
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO

class BookInfoViewModel(application: Application) : BaseReadViewModel(application) {
    val bookData = MutableLiveData<Book>()
    val waitDialogData = MutableLiveData<Boolean>()
    val actionLive = MutableLiveData<String>()

    /** 云端需要确认后才可覆盖；Activity 弹确认后以 overwrite=true 重入 uploadBook */
    val uploadConflictData = MutableLiveData<UploadConflict>()

    data class UploadConflict(val sameNameOnCloud: Boolean, val remoteLastModify: Long)

    override var curBook: Book?
        get() = bookData.value
        set(value) {
            value?.let { bookData.postValue(it) }
        }

    /** 详情页无章节上下文, 弹书籍级评论 (paragraphIndex=-1) */
    override fun openCommentDialog(activity: AppCompatActivity) {
        val book = curBook ?: return
        activity.showDialogFragment(ReviewListDialog(book, null, -1))
    }

    fun initData() {
        execute {
            if (curBook != null) return@execute
            IntentData.book?.let { upBook(it) }
        }.onError {
            AppLog.put(it.localizedMessage, it)
            context.toastOnUi(it.localizedMessage)
        }
    }

    fun refreshBook(book: Book) {
        executeLazy(executeContext = IO) {
            if (book.isLocal && !book.isImage) {
                refreshWebDavBook(book)
            } else {
                refreshBookSourceName(book)
            }
        }.onError {
            if (it is ObjectNotFoundException) {
                book.origin = BookType.localTag
            } else {
                AppLog.put("下载远程书籍<${book.name}>失败", it)
            }
        }.onFinally {
            execute { loadBookInfo(book) }
        }.start()
    }

    private suspend fun refreshWebDavBook(book: Book) {
        val remoteUrl = book.getRemoteUrl() ?: return
        //按书籍origin里的serverID选择授权，缺失serverID时回退默认配置
        val webDav = io.legado.app.lib.webdav.WebDav.fromPath(remoteUrl)
        val remoteFile = webDav.getWebDavFileOrNull()
        if (remoteFile == null) {
            book.origin = BookType.localTag
            return
        }
        if (remoteFile.lastModify <= book.lastCheckTime) return
        val oldBookUrl = book.bookUrl
        val oldDurIndex = book.durChapterIndex
        val oldDurTitle = book.durChapterTitle
        val oldDurPos = book.durChapterPos
        val oldTotalChapterNum = book.totalChapterNum
        val oldLastCheckTime = book.lastCheckTime
        val oldLatestChapterTitle = book.latestChapterTitle
        val oldWordCount = book.wordCount
        val oldLatestChapterTime = book.latestChapterTime
        val uri = webDav.downloadInputStream().let {
            FileBook.saveBookFile(it, book.originName)
        }
        val newBookUrl = if (uri.isContentScheme()) uri.toString() else uri.path!!
        try {
            book.bookUrl = newBookUrl
            //新文件版本，清理旧内容缓存后重新解析章节目录；解析失败回滚内存状态并保留旧目录
            BookHelp.clearCache(book)
            val chapters = FileBook.getChapterList(book)
            book.durChapterIndex = BookHelp.getDurChapter(
                oldDurIndex, oldDurTitle, chapters, oldTotalChapterNum
                //空目录时lastIndex为-1，coerceIn(0,-1)会抛越界，先归零
            ).coerceIn(0, chapters.lastIndex.coerceAtLeast(0))
            book.durChapterPos = oldDurPos
            book.durChapterTitle = chapters.getOrNull(book.durChapterIndex)?.title
            book.lastCheckTime = remoteFile.lastModify
            appDb.runInTransaction {
                if (oldBookUrl != book.bookUrl) {
                    appDb.bookChapterDao.delByBook(oldBookUrl)
                }
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
                appDb.bookDao.update(book)
            }
            ReadBook.onChapterListUpdated(book)
        } catch (e: Throwable) {
            book.bookUrl = oldBookUrl
            book.durChapterIndex = oldDurIndex
            book.durChapterTitle = oldDurTitle
            book.durChapterPos = oldDurPos
            book.totalChapterNum = oldTotalChapterNum
            book.lastCheckTime = oldLastCheckTime
            book.latestChapterTitle = oldLatestChapterTitle
            book.wordCount = oldWordCount
            book.latestChapterTime = oldLatestChapterTime
            throw e
        }
    }

    private fun refreshBookSourceName(book: Book) {
        curBookSource?.let { source ->
            if (book.originName != source.bookSourceName) {
                book.originName = source.bookSourceName
            }
        }
    }

    fun loadGroup(groupId: Long, success: ((groupNames: String?) -> Unit)) {
        execute {
            appDb.bookGroupDao.getGroupNames(groupId).joinToString(",")
        }.onSuccess {
            success.invoke(it)
        }
    }

    fun importWebFile(webFile: WebFile, success: ((Book) -> Unit)?) {
        execute {
            waitDialogData.postValue(true)
            val book = bookData.value ?: throw NoStackTraceException("book is null")
            val fileName = book.getExportFileName(webFile.suffix)
            val uri = FileBook.saveBookFile(webFile.url, fileName, curBookSource)
            changeToLocalBook(FileBook.mergeBook(FileBook.importLocalFile(uri), book))
        }.onSuccess {
            success?.invoke(it)
        }.onError {
            when (it) {
                is NoBooksDirException -> actionLive.postValue("selectBooksDir")
                else -> {
                    AppLog.put("ImportWebFileError\n${it.localizedMessage}", it, true)
                    webFiles.remove(webFile)
                }
            }
        }.onFinally {
            waitDialogData.postValue(false)
        }
    }

    fun downloadWebFile(webFile: WebFile, success: ((Uri) -> Unit)?) {
        execute {
            waitDialogData.postValue(true)
            val book = bookData.value ?: throw NoStackTraceException("book is null")
            val fileName = book.getExportFileName(webFile.suffix)
            FileBook.saveBookFile(webFile.url, fileName, curBookSource)
        }.onSuccess {
            success?.invoke(it)
        }.onError {
            when (it) {
                is NoBooksDirException -> actionLive.postValue("selectBooksDir")
                else -> {
                    AppLog.put("DownloadWebFileError\n${it.localizedMessage}", it, true)
                    webFiles.remove(webFile)
                }
            }
        }.onFinally {
            waitDialogData.postValue(false)
        }
    }

    fun getArchiveFilesName(archiveFileUri: Uri, onSuccess: (List<String>) -> Unit) {
        execute {
            ArchiveUtils.getArchiveFilesName(archiveFileUri) {
                AppPattern.bookFileRegex.matches(it)
            }
        }.onError {
            AppLog.put("getArchiveEntriesName Error:\n${it.localizedMessage}", it, true)
        }.onSuccess {
            onSuccess.invoke(it)
        }
    }

    fun importBookFromArchive(
        archiveFileUri: Uri, archiveEntryName: String, success: ((Book) -> Unit)? = null
    ) {
        execute {
            waitDialogData.postValue(true)
            val suffix = archiveEntryName.substringAfterLast(".")
            val book = bookData.value ?: throw NoStackTraceException("book is null")
            FileBook.importFromArchive(
                archiveFileUri, book.getExportFileName(suffix)
            ) {
                it.contains(archiveEntryName)
            }.first()
        }.onSuccess {
            success?.invoke(changeToLocalBook(it))
        }.onError {
            AppLog.put("importArchiveBook Error:\n${it.localizedMessage}", it, true)
        }.onFinally {
            waitDialogData.postValue(false)
        }
    }

    fun topBook() {
        execute {
            bookData.value?.let { book ->
                book.order = appDb.bookDao.minOrder - 1
                book.durChapterTime = System.currentTimeMillis()
                appDb.bookDao.update(book)
            }
        }
    }

    fun saveBook(book: Book?, success: (() -> Unit)? = null) {
        book ?: return
        curBook = book
        addToBookshelf(success)
    }

    fun getBook(toastNull: Boolean = true): Book? {
        val book = bookData.value
        if (toastNull && book == null) {
            context.toastOnUi("book is null")
        }
        return book
    }

    fun downloadToLocal(book: Book) {
        execute {
            FileBook.downloadRemoteBook(book)
        }.onSuccess {
            context.toastOnUi("下载成功")
            bookData.postValue(book)
        }.onError {
            AppLog.put("下载远程书籍<${book.name}>失败", it, true)
        }
    }

    /**
     * 上传本地书籍到WebDav。overwrite=false 时云端有更新则通过 uploadConflictData 请求用户确认，
     * 确认后以 overwrite=true 重入；条件PUT避免确认到上传间被他人覆盖。
     */
    fun uploadBook(book: Book, overwrite: Boolean = false) {
        execute {
            waitDialogData.postValue(true)
            if (book.bookUrl.startsWith(BookType.webDavTag)) {
                throw NoStackTraceException("书籍尚未下载到本地，请先下载后再上传")
            }
            val remoteUrl = book.getRemoteUrl()
            //已有云端关联时回写原地址（剥离CustomUrl属性），否则上传到默认books目录
            val targetUrl: String
            val webDav: io.legado.app.lib.webdav.WebDav
            if (remoteUrl != null) {
                targetUrl = io.legado.app.model.analyzeRule.CustomUrl(remoteUrl).getUrl()
                //保留origin中的serverID供授权解析，不能用剥离属性后的URL建连接
                webDav = io.legado.app.lib.webdav.WebDav.fromPath(remoteUrl)
            } else {
                val bookWebDav =
                    AppWebDav.defaultBookWebDav ?: throw NoStackTraceException("未配置webDav")
                targetUrl =
                    io.legado.app.lib.webdav.WebDav.joinPath(bookWebDav.rootBookUrl, book.originName)
                webDav = io.legado.app.lib.webdav.WebDav(targetUrl, bookWebDav.authorization)
            }
            val remoteFile = webDav.getWebDavFileOrNull()
            if (remoteFile != null && !overwrite) {
                //未关联书籍遇到云端同名文件一律确认；已关联书籍仅在云端较新时确认
                val needConfirm = remoteUrl == null || remoteFile.lastModify > book.lastCheckTime
                if (needConfirm) {
                    return@execute UploadConflict(remoteUrl == null, remoteFile.lastModify)
                }
            }
            if (remoteFile == null) {
                uploadBookFile(webDav, book, expectedLastModify = null, createOnly = true)
            } else {
                uploadBookFile(
                    webDav, book,
                    expectedLastModify = remoteFile.lastModify, createOnly = false
                )
            }
            //以服务器实际修改时间作为同步基线
            book.lastCheckTime = webDav.getWebDavFileOrNull()?.lastModify
                ?: System.currentTimeMillis()
            if (remoteUrl == null) {
                book.origin = BookType.webDavTag + targetUrl
            }
            book.save()
            null
        }.onSuccess { conflict ->
            if (conflict != null) {
                uploadConflictData.postValue(conflict)
            } else {
                context.toastOnUi("上传成功")
                bookData.postValue(book)
            }
        }.onError {
            if (it is io.legado.app.lib.webdav.WebDavConflictException) {
                context.toastOnUi("云端文件刚被修改，已取消上传，请确认后重试")
            } else {
                AppLog.put("上传书籍<${book.name}>失败", it, true)
            }
        }.onFinally {
            waitDialogData.postValue(false)
        }
    }

    private suspend fun uploadBookFile(
        webDav: io.legado.app.lib.webdav.WebDav,
        book: Book,
        expectedLastModify: Long?,
        createOnly: Boolean
    ) {
        val localBookUri = book.bookUrl.toUri()
        if (localBookUri.isContentScheme()) {
            webDav.uploadChecked(
                localBookUri,
                expectedLastModify = expectedLastModify,
                createOnly = createOnly
            )
        } else {
            webDav.uploadChecked(
                java.io.File(localBookUri.path!!),
                expectedLastModify = expectedLastModify,
                createOnly = createOnly
            )
        }
    }

    fun clearCache() {
        execute {
            val book = bookData.value ?: throw NoStackTraceException("book is null")
            BookHelp.clearCache(book)
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.clearTextChapter()
            }
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }.onError {
            context.toastOnUi("清理缓存出错\n${it.localizedMessage}")
        }
    }

    fun upEditBook() {
        bookData.postValue(IntentData.book as? Book)
    }

    private fun changeToLocalBook(localBook: Book): Book {
        return FileBook.mergeBook(localBook, bookData.value).let {
            execute { loadChapterList(it) }
            inBookshelf = true
            it
        }
    }

}
