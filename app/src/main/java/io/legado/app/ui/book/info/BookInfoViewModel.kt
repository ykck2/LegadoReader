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
                context.toastOnUi("云端刷新失败，已保留原书内容（详情见日志）")
            }
        }.onFinally {
            execute { loadBookInfo(book) }
        }.start()
    }

    private fun readBookFileBytes(book: Book): ByteArray? {
        val uri = book.bookUrl.toUri()
        return if (uri.isContentScheme()) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } else {
            java.io.File(uri.path!!).takeIf { it.exists() }?.readBytes()
        }
    }

    private fun writeBookFileBytes(book: Book, data: ByteArray) {
        val uri = book.bookUrl.toUri()
        if (uri.isContentScheme()) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                ?: throw java.io.IOException("无法写回原文件")
        } else {
            java.io.File(uri.path!!).writeBytes(data)
        }
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
        //覆盖前备份原文件内容：解析失败时恢复，避免"新文件+旧目录"的半成品状态
        val oldContent = readBookFileBytes(book)
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
            //恢复原文件内容并清理新文件缓存，保证磁盘与数据库一致
            if (oldContent != null) {
                runCatching {
                    writeBookFileBytes(book, oldContent)
                    BookHelp.clearCache(book)
                }.onFailure { restoreErr ->
                    AppLog.put("恢复原书文件失败<${book.name}>", restoreErr)
                }
            } else {
                AppLog.put("云端刷新<${book.name}>失败：原文件内容备份缺失，无法恢复")
            }
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
     * 确认后带 confirmedLastModify 重入；上传前再次校验云端未被改动（服务端不支持条件头时，
     * 客户端复校是唯一防线），同步基线取确认时的远端时间而非后置PROPFIND。
     */
    fun uploadBook(
        book: Book,
        overwrite: Boolean = false,
        confirmedLastModify: Long? = null
    ) {
        //新建(true)/更新(false)区分成功文案；execute 块返回 UploadConflict? 表示需用户确认
        var created = false
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
            if (remoteFile == null) {
                created = true
                uploadBookFile(webDav, book, expectedLastModify = null, createOnly = true)
                //新建文件：以创建后的服务器时间作基线（新建文件被第三方抢先覆盖的概率可忽略）
                book.lastCheckTime = webDav.getWebDavFileOrNull()?.lastModify
                    ?: System.currentTimeMillis()
            } else {
                if (!overwrite) {
                    //fail-closed：远端缺元数据视为无法判定，必须走确认；已关联书仅在云端较新时确认
                    val needConfirm = remoteUrl == null || remoteFile.lastModify <= 0
                        || remoteFile.lastModify > book.lastCheckTime
                    if (needConfirm) {
                        return@execute UploadConflict(remoteUrl == null, remoteFile.lastModify)
                    }
                } else if (confirmedLastModify != null
                    && remoteFile.lastModify != confirmedLastModify
                ) {
                    //确认到重传之间云端又被改动：服务端条件头不可靠，客户端复校是唯一防线
                    throw NoStackTraceException("云端文件刚被再次修改，已取消上传，请重试")
                }
                val confirmedBase = remoteFile.lastModify.takeIf { it > 0 } ?: confirmedLastModify
                uploadBookFile(
                    webDav, book,
                    expectedLastModify = confirmedBase, createOnly = false
                )
                //更新场景以确认时的远端时间为基线，后置PROPFIND会把第三方抢先写入误记为基线
                book.lastCheckTime = confirmedBase ?: book.lastCheckTime
            }
            if (remoteUrl == null) {
                book.origin = BookType.webDavTag + targetUrl
            }
            book.save()
            null
        }.onSuccess { conflict ->
            if (conflict != null) {
                uploadConflictData.postValue(conflict)
            } else {
                context.toastOnUi(
                    if (created) R.string.upload_success_new else R.string.upload_success_update
                )
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
