package io.legado.app.lib.webdav

import android.annotation.SuppressLint
import android.net.Uri
import cn.hutool.core.net.URLDecoder
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.findNS
import io.legado.app.utils.findNSPrefix
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toRequestBody
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.intellij.lang.annotations.Language
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Suppress("unused", "MemberVisibilityCanBePrivate")
open class WebDav(
    val path: String,
    val authorization: Authorization
) {
    companion object {

        fun fromPath(path: String): WebDav {
            val cleanUrl = CustomUrl(path).getUrl()
            val id = AnalyzeUrl(path).serverID
            //serverID记录已删除时仅当同主机才回退默认授权，防止凭据发给其他服务器
            val authorization = id?.let {
                kotlin.runCatching { Authorization(it) }.getOrNull()
            } ?: io.legado.app.help.AppWebDav.defaultAuthorizationFor(cleanUrl)
                ?: throw WebDavException("没有serverID")
            return WebDav(cleanUrl, authorization)
        }

        @SuppressLint("DateTimeFormatter")
        private val dateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

        // 指定返回哪些属性
        @Language("xml")
        private const val DIR =
            """<?xml version="1.0"?>
            <a:propfind xmlns:a="DAV:">
                <a:prop>
                    <a:displayname/>
                    <a:resourcetype/>
                    <a:getcontentlength/>
                    <a:creationdate/>
                    <a:getlastmodified/>
                    %s
                </a:prop>
            </a:propfind>"""

        @Language("xml")
        private const val EXISTS =
            """<?xml version="1.0"?>
            <propfind xmlns="DAV:">
               <prop>
                  <resourcetype />
               </prop>
            </propfind>"""

        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

        /**
         * 拼接目录URL与文件名，对文件名做URL编码（中文/空格/#等）
         */
        fun joinPath(dirUrl: String, fileName: String): String {
            return dirUrl.removeSuffix("/").toHttpUrl().newBuilder()
                .addPathSegment(fileName)
                .build()
                .toString()
        }
    }


    private val url: URL = URL(CustomUrl(path).getUrl())
    val httpUrl: String? by lazy {
        val raw = url.toString()
            .replace("davs://", "https://")
            .replace("dav://", "http://")
        return@lazy kotlin.runCatching {
            raw.toHttpUrl().toString()
        }.getOrNull()
    }
    val webDavClient by lazy {
        val authInterceptor = Interceptor { chain ->
            var request = chain.request()
            if (request.url.host.equals(host, true)) {
                request = request
                    .newBuilder()
                    .header(authorization.name, authorization.data)
                    .build()
            }
            chain.proceed(request)
        }
        okHttpClient.newBuilder().run {
            callTimeout(0, TimeUnit.SECONDS)
            interceptors().add(0, authInterceptor)
            addNetworkInterceptor(authInterceptor)
            build()
        }
    }
    private val host: String?
        get() = url.host?.let {
            if (it.startsWith("[")) {
                it.substring(1, it.lastIndex)
            } else {
                it
            }
        }

    /**
     * 获取当前url文件信息，不存在返回null；其他错误照常抛出
     */
    @Throws(WebDavException::class)
    suspend fun getWebDavFileOrNull(): WebDavFile? {
        return try {
            getWebDavFile()
        } catch (e: ObjectNotFoundException) {
            null
        }
    }

    /**
     * 获取当前url文件信息
     */
    @Throws(WebDavException::class)
    suspend fun getWebDavFile(): WebDavFile? {
        return propFindResponse(depth = 0)?.let {
            parseBody(it).firstOrNull()
        }
    }

    /**
     * 确保当前url是目录，不存在则创建；父目录必须已存在
     */
    @Throws(WebDavException::class)
    suspend fun ensureDirectory() {
        val existing = getWebDavFileOrNull()
        if (existing != null) {
            if (existing.isDir) return
            throw WebDavException("目标已存在同名文件，无法作为目录使用")
        }
        val url = httpUrl ?: throw WebDavException("url不能为空")
        webDavClient.newCallResponse {
            url(url)
            method("MKCOL", null)
        }.use { response ->
            if (response.code == 405) {
                //并发创建时允许已存在
                if (getWebDavFileOrNull()?.isDir == true) return
            }
            checkResult(response)
        }
    }

    /**
     * 列出当前路径下的文件
     * @return 文件列表
     */
    @Throws(WebDavException::class)
    suspend fun listFiles(): List<WebDavFile> {
        propFindResponse()?.let { body ->
            val normalizedPath = path.removeSuffix("/")
            return parseBody(body).filter {
                it.path.removeSuffix("/") != normalizedPath
            }
        }
        return emptyList()
    }

    /**
     * @param propsList 指定列出文件的哪些属性
     */
    @Throws(WebDavException::class)
    private suspend fun propFindResponse(
        propsList: List<String> = emptyList(),
        depth: Int = 1
    ): String? {
        val requestProps = StringBuilder()
        for (p in propsList) {
            requestProps.append("<a:").append(p).append("/>\n")
        }
        val requestPropsStr: String = if (requestProps.toString().isEmpty()) {
            DIR.replace("%s", "")
        } else {
            String.format(DIR, requestProps.toString() + "\n")
        }
        val url = httpUrl ?: return null
        return webDavClient.newCallResponse {
            url(url)
            addHeader("Depth", depth.toString())
            // 添加RequestBody对象，可以只返回的属性。如果设为null，则会返回全部属性
            // 注意：尽量手动指定需要返回的属性。若返回全部属性，可能后由于Prop.java里没有该属性名，而崩溃。
            val requestBody = requestPropsStr.toRequestBody("text/plain".toMediaType())
            method("PROPFIND", requestBody)
        }.apply {
            checkResult(this)
        }.body.text()
    }

    /**
     * 解析webDav返回的xml
     */
    private fun parseBody(s: String): List<WebDavFile> {
        val list = ArrayList<WebDavFile>()
        val document = kotlin.runCatching {
            Jsoup.parse(s, Parser.xmlParser())
        }.getOrElse {
            Jsoup.parse(s)
        }
        val ns = document.findNSPrefix("DAV:")
        val elements = document.findNS("response", ns)
        val urlStr = httpUrl ?: return list
        val baseUrl = NetworkUtils.getBaseUrl(urlStr)
        for (element in elements) {
            //依然是优化支持 caddy 自建的 WebDav ，其目录后缀都为“/”, 所以删除“/”的判定，不然无法获取该目录项
            val href = element.findNS("href", ns)[0].text()
            val hrefDecode = URLDecoder.decodeForPath(href, Charsets.UTF_8)
            val fileName = hrefDecode.removeSuffix("/").substringAfterLast("/")
            val webDavFile: WebDav
            try {
                val urlName = hrefDecode.ifEmpty {
                    url.file.replace("/", "")
                }
                val displayName = element
                    .findNS("displayname", ns)
                    .firstOrNull()?.text()?.takeIf { it.isNotEmpty() }
                    ?.let { URLDecoder.decodeForPath(it, Charsets.UTF_8) } ?: fileName
                val contentType = element
                    .findNS("getcontenttype", ns)
                    .firstOrNull()?.text().orEmpty()
                val resourceType = element
                    .findNS("resourcetype", ns)
                    .firstOrNull()?.html()?.trim().orEmpty()
                val size = kotlin.runCatching {
                    element.findNS("getcontentlength", ns)
                        .firstOrNull()?.text()?.toLong() ?: 0
                }.getOrDefault(0)
                val lastModify: Long = kotlin.runCatching {
                    element.findNS("getlastmodified", ns)
                        .firstOrNull()?.text()?.let {
                            ZonedDateTime.parse(it, dateTimeFormatter)
                                .toInstant().toEpochMilli()
                        }
                }.getOrNull() ?: 0
                var fullURL = NetworkUtils.getAbsoluteURL(baseUrl, hrefDecode)
                if (WebDavFile.isDir(contentType, resourceType) && !fullURL.endsWith("/")) {
                    fullURL += "/"
                }
                webDavFile = WebDavFile(
                    fullURL,
                    authorization,
                    displayName = displayName,
                    urlName = urlName,
                    size = size,
                    contentType = contentType,
                    resourceType = resourceType,
                    lastModify = lastModify
                )
                list.add(webDavFile)
            } catch (e: MalformedURLException) {
                e.printOnDebug()
            }
        }
        return list
    }

    /**
     * 文件是否存在
     */
    suspend fun exists(): Boolean {
        val url = httpUrl ?: return false
        return kotlin.runCatching {
            return webDavClient.newCallResponse {
                url(url)
                addHeader("Depth", "0")
                val requestBody = EXISTS.toRequestBody("application/xml".toMediaType())
                method("PROPFIND", requestBody)
            }.use { it.isSuccessful }
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }.getOrDefault(false)
    }

    /**
     * 检查用户名密码是否有效
     */
    suspend fun check(): Boolean {
        return kotlin.runCatching {
            webDavClient.newCallResponse {
                url(url)
                addHeader("Depth", "0")
                val requestBody = EXISTS.toRequestBody("application/xml".toMediaType())
                method("PROPFIND", requestBody)
            }.use { it.code != 401 }
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }.getOrDefault(true)
    }

    /**
     * 根据自己的URL，在远程处创建对应的文件夹
     * @return 是否创建成功
     */
    suspend fun makeAsDir(): Boolean {
        val url = httpUrl ?: return false
        //防止报错
        return kotlin.runCatching {
            if (!exists()) {
                webDavClient.newCallResponse {
                    url(url)
                    method("MKCOL", null)
                }.use {
                    checkResult(it)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav创建目录失败\n${it.localizedMessage}", it)
        }.isSuccess
    }

    /**
     * 下载到本地
     * @param savedPath       本地的完整路径，包括最后的文件名
     * @param replaceExisting 是否替换本地的同名文件
     */
    @Throws(WebDavException::class)
    suspend fun downloadTo(savedPath: String, replaceExisting: Boolean) {
        val file = File(savedPath)
        if (file.exists() && !replaceExisting) {
            return
        }
        downloadInputStream().use { byteStream ->
            FileOutputStream(file).use {
                byteStream.copyTo(it)
            }
        }
    }

    /**
     * 下载文件,返回ByteArray
     */
    @Throws(WebDavException::class)
    suspend fun download(): ByteArray {
        return downloadInputStream().use {
            it.readBytes()
        }
    }

    /**
     * 上传文件
     */
    @Throws(WebDavException::class)
    suspend fun upload(localPath: String, contentType: String = DEFAULT_CONTENT_TYPE) {
        upload(File(localPath), contentType)
    }

    @Throws(WebDavException::class)
    suspend fun upload(file: File, contentType: String = DEFAULT_CONTENT_TYPE) {
        kotlin.runCatching {
            withContext(IO) {
                if (!file.exists()) throw WebDavException("文件不存在")
                // 务必注意RequestBody不要嵌套，不然上传时内容可能会被追加多余的文件信息
                val fileBody = file.asRequestBody(contentType.toMediaType())
                val url = httpUrl ?: throw WebDavException("url不能为空")
                webDavClient.newCallResponse {
                    url(url)
                    put(fileBody)
                }.use {
                    checkResult(it)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav上传失败\n${it.localizedMessage}", it)
            throw WebDavException("WebDav上传失败\n${it.localizedMessage}")
        }
    }

    @Throws(WebDavException::class)
    suspend fun upload(byteArray: ByteArray, contentType: String = DEFAULT_CONTENT_TYPE) {
        // 务必注意RequestBody不要嵌套，不然上传时内容可能会被追加多余的文件信息
        kotlin.runCatching {
            withContext(IO) {
                val fileBody = byteArray.toRequestBody(contentType.toMediaType())
                val url = httpUrl ?: throw NoStackTraceException("url不能为空")
                webDavClient.newCallResponse {
                    url(url)
                    put(fileBody)
                }.use {
                    checkResult(it)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav上传失败\n${it.localizedMessage}", it)
            throw WebDavException("WebDav上传失败\n${it.localizedMessage}")
        }
    }

    @Throws(WebDavException::class)
    suspend fun upload(uri: Uri, contentType: String = DEFAULT_CONTENT_TYPE) {
        uploadChecked(uri, contentType, expectedLastModify = null, createOnly = false)
    }

    /**
     * 条件上传文件路径版本
     */
    @Throws(WebDavException::class)
    suspend fun uploadChecked(
        file: File,
        contentType: String = DEFAULT_CONTENT_TYPE,
        expectedLastModify: Long?,
        createOnly: Boolean
    ) {
        kotlin.runCatching {
            withContext(IO) {
                if (!file.exists()) throw WebDavException("文件不存在")
                val fileBody = file.asRequestBody(contentType.toMediaType())
                val url = httpUrl ?: throw WebDavException("url不能为空")
                webDavClient.newCallResponse {
                    url(url)
                    if (createOnly) header("If-None-Match", "*")
                    if (expectedLastModify != null && expectedLastModify > 0) {
                        header("If-Unmodified-Since", formatHttpDate(expectedLastModify))
                    }
                    put(fileBody)
                }.use {
                    checkResult(it)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            if (it is WebDavConflictException) throw it
            AppLog.put("WebDav上传失败\n${it.localizedMessage}", it)
            throw WebDavException("WebDav上传失败\n${it.localizedMessage}")
        }
    }

    /**
     * 条件上传：createOnly 要求目标不存在才创建；expectedLastModify 非空时目标未被改动才覆盖。
     * 服务器返回 412 抛 WebDavConflictException，避免确认到上传之间的窗口期覆盖云端。
     */
    @Throws(WebDavException::class)
    suspend fun uploadChecked(
        uri: Uri,
        contentType: String = DEFAULT_CONTENT_TYPE,
        expectedLastModify: Long?,
        createOnly: Boolean
    ) {
        kotlin.runCatching {
            withContext(IO) {
                val fileBody = uri.toRequestBody(contentType.toMediaType())
                val url = httpUrl ?: throw WebDavException("url不能为空")
                webDavClient.newCallResponse {
                    url(url)
                    if (createOnly) header("If-None-Match", "*")
                    if (expectedLastModify != null && expectedLastModify > 0) {
                        header("If-Unmodified-Since", formatHttpDate(expectedLastModify))
                    }
                    put(fileBody)
                }.use {
                    checkResult(it)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            if (it is WebDavConflictException) throw it
            AppLog.put("WebDav上传失败\n${it.localizedMessage}", it)
            throw WebDavException("WebDav上传失败\n${it.localizedMessage}")
        }
    }

    private fun formatHttpDate(timeMillis: Long): String {
        return dateTimeFormatter.format(
            ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(timeMillis), java.time.ZoneOffset.UTC)
        )
    }

    @Throws(WebDavException::class)
    suspend fun downloadInputStream(): InputStream {
        val url = httpUrl ?: throw WebDavException("WebDav下载出错\nurl为空")
        val byteStream = webDavClient.newCallResponse {
            url(url)
        }.apply {
            checkResult(this)
        }.body.byteStream()
        return byteStream
    }

    /**
     * 移除文件/文件夹
     */
    suspend fun delete(): Boolean {
        val url = httpUrl ?: return false
        //防止报错
        return kotlin.runCatching {
            webDavClient.newCallResponse {
                url(url)
                method("DELETE", null)
            }.use {
                checkResult(it)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav删除失败\n${it.localizedMessage}", it)
        }.isSuccess
    }

    /**
     * 检测返回结果是否正确
     */
    private fun checkResult(response: Response) {
        if (!response.isSuccessful) {
            if (response.code == 404 || response.code == 410) {
                throw ObjectNotFoundException("$path doesn't exist. code:${response.code}")
            }
            if (response.code == 412) {
                throw WebDavConflictException("$path 云端文件与预期不一致")
            }
            val body = response.body.string()
            if (response.code == 401) {
                val headers = response.headers("WWW-Authenticate")
                val supportBasicAuth = headers.any {
                    it.startsWith("Basic", ignoreCase = true)
                }
                if (headers.isNotEmpty() && !supportBasicAuth) {
                    AppLog.put("服务器不支持BasicAuth认证")
                }
            }

            if (response.message.isNotBlank() || body.isBlank()) {
                throw WebDavException("${url}\n${response.code}:${response.message}")
            }
            val document = Jsoup.parse(body)
            val exception = document.getElementsByTag("s:exception").firstOrNull()?.text()
            val message = document.getElementsByTag("s:message").firstOrNull()?.text()
            if (exception == "ObjectNotFound") {
                throw ObjectNotFoundException(
                    message ?: "$path doesn't exist. code:${response.code}"
                )
            }
            throw WebDavException(message ?: "未知错误 code:${response.code}")
        }
    }

    @Throws(IOException::class)
    fun readRange(offset: Long, length: Int, fileSize: Long = -1): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (fileSize in 1..offset) return ByteArray(0)

        val end = if (fileSize > 0) {
            minOf(fileSize - 1, offset + length - 1)
        } else {
            offset + length - 1
        }
        val range = "bytes=$offset-$end"

        val url = httpUrl ?: throw IOException("Invalid WebDAV URL")
        val request = Request.Builder()
            .url(url)
            .header("Range", range)
            .build()

        webDavClient.newCall(request).execute().use { response ->
            if (response.code == 200) {
                throw IOException("Server does not support Range requests")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP request failed: ${response.code}")
            }
            return response.body.bytes()
        }
    }

}
