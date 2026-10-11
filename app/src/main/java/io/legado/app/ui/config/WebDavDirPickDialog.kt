package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import cn.hutool.core.net.URLDecoder
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogWebdavDirPickBinding
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx

/**
 * WebDav工作目录选择器：浏览远端目录，确认后返回选定的具体目录URL。
 * 打开时自动探测legado目录（根/legado、根/*/legado，限两层），
 * 唯一命中则自动定位，未命中或多个命中时由用户手动导航。
 */
class WebDavDirPickDialog : BaseDialogFragment(R.layout.dialog_webdav_dir_pick) {

    private val binding by viewBinding(DialogWebdavDirPickBinding::bind)

    private val authorization by lazy {
        Authorization(
            appCtx.getPrefString(PreferKey.webDavAccount).orEmpty(),
            appCtx.getPrefString(PreferKey.webDavPassword).orEmpty()
        )
    }

    interface Callback {
        fun onWebDavDirPicked(url: String)
    }

    private val callback: Callback?
        get() = (parentFragment as? Callback) ?: (activity as? Callback)

    //浏览起点：偏好中的服务器地址
    private var baseUrl: String = ""

    //已进入的目录栈；栈顶即当前目录，为空表示仍在起点
    private val dirStack = arrayListOf<WebDavFile>()
    private val rowFiles = arrayListOf<WebDavFile>()
    //请求序号：快速连续导航时丢弃过期响应
    private var listSeq = 0

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        baseUrl = appCtx.getPrefString(PreferKey.webDavUrl).orEmpty()
            .let { if (it.isNotEmpty() && !it.endsWith("/")) "$it/" else it }
        if (baseUrl.isEmpty()) {
            requireContext().toastOnUi(R.string.web_dav_pick_need_config)
            dismissAllowingStateLoss()
            return
        }
        binding.btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.btnUse.setOnClickListener {
            callback?.onWebDavDirPicked(currentUrl())
            dismissAllowingStateLoss()
        }
        binding.lvDirs.setOnItemClickListener { _, _, position, _ ->
            if (dirStack.isNotEmpty() && position == 0) {
                goUp()
            } else {
                val offset = if (dirStack.isNotEmpty()) 1 else 0
                rowFiles.getOrNull(position - offset)?.let { enterDir(it) }
            }
        }
        autoDetectLegado()
    }

    private fun currentUrl(): String = dirStack.lastOrNull()?.path ?: baseUrl

    private fun enterDir(file: WebDavFile) {
        dirStack.add(file)
        listDirs(file.path)
    }

    private fun goUp() {
        dirStack.removeLastOrNull()
        listDirs(currentUrl())
    }

    private fun confirmPathTitle(): String {
        val decoded = dirStack.joinToString("/") { it.displayName }
        return if (dirStack.isEmpty()) {
            URLDecoder.decodeForPath(baseUrl, Charsets.UTF_8)
        } else {
            "/$decoded/"
        }
    }

    private fun listDirs(url: String) {
        val seq = ++listSeq
        binding.tvPath.text = getString(R.string.loading)
        execute {
            WebDav(url, authorization).listFiles()
                .filter { it.isDir }
                .sortedBy { it.displayName.lowercase() }
        }.onSuccess { dirs ->
            if (!isAdded || seq != listSeq) return@onSuccess
            rowFiles.clear()
            rowFiles.addAll(dirs)
            val rows = mutableListOf<String>()
            if (dirStack.isNotEmpty()) rows.add("..")
            dirs.forEach { rows.add(it.displayName) }
            binding.lvDirs.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_list_item_1, rows
            )
            binding.tvPath.text = confirmPathTitle()
        }.onError {
            if (!isAdded || seq != listSeq) return@onError
            binding.tvPath.text = confirmPathTitle()
            requireContext().toastOnUi("获取目录失败\n${it.localizedMessage}")
        }
    }

    /**
     * 探测legado目录并自动定位；结果null表示未唯一命中，展示起点由用户手动导航。
     */
    private fun autoDetectLegado() {
        execute {
            val rootDirs = runCatching {
                WebDav(baseUrl, authorization).listFiles().filter { it.isDir }
            }.getOrElse { return@execute null }
            rootDirs.firstOrNull { it.displayName.equals("legado", true) }?.let {
                return@execute Pair(emptyList<WebDavFile>(), it)
            }
            var hit: Pair<List<WebDavFile>, WebDavFile>? = null
            for (dir in rootDirs) {
                val subHit = runCatching {
                    WebDav(dir.path, authorization).listFiles()
                        .filter { it.isDir }
                        .firstOrNull { it.displayName.equals("legado", true) }
                }.getOrNull()
                if (subHit != null) {
                    if (hit != null) return@execute null
                    hit = Pair(listOf(dir), subHit)
                }
            }
            hit
        }.onSuccess { result ->
            if (!isAdded) return@onSuccess
            if (result == null) {
                listDirs(baseUrl)
            } else {
                val (stack, target) = result
                dirStack.addAll(stack)
                requireContext().toastOnUi(R.string.web_dav_pick_detected)
                enterDir(target)
            }
        }.onError {
            if (!isAdded) return@onError
            requireContext().toastOnUi("获取目录失败\n${it.localizedMessage}")
        }
    }

}
