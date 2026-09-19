package org.gts.fix

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.gts.scan.ModuleGapDetector
import org.gts.util.FileSaver
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 把「缺失的文件」直接下载到本地，不需要任何终端。
 *
 * 能处理的：
 *   - 子模块：从 .gitmodules 取 URL，下载 zip 快照，解压到目标路径
 *   - 单文件：从仓库 raw 地址下载
 *
 * 不能处理的（会明确返回「不可下载」）：
 *   - 工具类缺失（cmake/gradle/ndk 等）——这些不是文件，是软件包
 *   - 需要包管理器生成的锁文件（go.sum / poetry.lock）
 *   - 仓库自身就不含的内容（.gitignore 排除的构建产物）
 */
class GapDownloader(private val context: Context) {

    data class Item(
        val what: String,
        val kind: Kind,
        val url: String?,      // 可下载时有值
        val target: String,    // 本地目标路径（相对仓库根）
        val note: String       // 给用户看的说明
    )

    enum class Kind { SUBMODULE, FILE, NOT_DOWNLOADABLE }

    data class Result(
        val item: Item,
        val ok: Boolean,
        val detail: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // 下载大文件给足时间
        .build()

    private val token: String?
        get() = null   // 由调用方在 headers 里按需加；这里保持公开仓库可用

    /**
     * 把 gaps 转成可下载清单。
     *
     * @param owner 仓库 owner
     * @param repo  仓库名
     * @param branch 分支
     * @param files 已抓到的声明文件（用来解析 .gitmodules）
     */
    fun plan(
        gaps: List<ModuleGapDetector.Gap>,
        owner: String,
        repo: String,
        branch: String,
        files: Map<String, String>
    ): List<Item> {
        // 先建一张 子模块路径 -> 仓库 URL 的映射
        val subUrl = mutableMapOf<String, String>()
        files[".gitmodules"]?.let { gm ->
            var path: String? = null
            gm.lines().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("path") -> {
                        path = line.substringAfter('=').trim()
                    }
                    line.startsWith("url") -> {
                        val u = line.substringAfter('=').trim()
                        path?.let { subUrl[it] = u }
                    }
                }
            }
        }

        return gaps.map { g ->
            // 子模块：目标路径能在 .gitmodules 里找到对应 URL
            val u = subUrl[g.what] ?: subUrl.entries.firstOrNull {
                g.what == it.key || g.what.startsWith(it.key + "/")
            }?.value

            when {
                u != null -> {
                    val normalized = normalizeRepoUrl(u)
                    Item(
                        what = g.what,
                        kind = Kind.SUBMODULE,
                        url = if (normalized != null)
                            "https://codeload.github.com/$normalized/zip/HEAD"
                        else null,
                        target = g.what,
                        note = if (normalized != null)
                            "子模块，将下载 zip 并解压到 ${g.what}/"
                        else
                            "子模块 URL 不是 GitHub，无法直接下载"
                    ).let { if (normalized == null) it.copy(kind = Kind.NOT_DOWNLOADABLE) else it }
                }

                // gradlew：从仓库自身下载
                g.what.endsWith("gradlew") -> Item(
                    what = g.what,
                    kind = Kind.FILE,
                    url = "https://raw.githubusercontent.com/$owner/$repo/$branch/${g.what}",
                    target = g.what,
                    note = "从仓库自身下载 ${g.what}"
                )

                // 锁文件类：必须由包管理器生成
                g.what.endsWith("go.sum") || g.what.endsWith("poetry.lock") ||
                g.what == "lockfile" || g.what.endsWith("package-lock.json") -> Item(
                    what = g.what,
                    kind = Kind.NOT_DOWNLOADABLE,
                    url = null,
                    target = g.what,
                    note = "锁文件必须由包管理器生成（go mod tidy / poetry lock / npm install），无法直接下载"
                )

                // 其余（CMake 引用的目录、Dockerfile COPY 的路径等）
                else -> Item(
                    what = g.what,
                    kind = Kind.NOT_DOWNLOADABLE,
                    url = null,
                    target = g.what,
                    note = "无法定位下载源。若它是子模块，请检查 .gitmodules；若被 .gitignore 排除，需在本地生成"
                )
            }
        }
    }

    /** github.com/owner/repo(.git) -> owner/repo；非 GitHub 返回 null */
    private fun normalizeRepoUrl(raw: String): String? {
        val s = raw.trim().removeSuffix(".git")
        if (!s.contains("github.com")) return null
        val after = s.substringAfter("github.com/").trimEnd('/')
        return after.split("/").take(2).joinToString("/").ifBlank { null }
    }

    /** 执行下载。逐项串行，避免并发打爆网络。 */
    suspend fun run(items: List<Item>, onProgress: (String) -> Unit): List<Result> {
        val out = mutableListOf<Result>()
        for (it in items) {
            if (it.kind == Kind.NOT_DOWNLOADABLE || it.url == null) {
                out += Result(it, false, it.note)
                continue
            }
            onProgress("下载 ${it.what} …")
            val r = withContext(Dispatchers.IO) { downloadOne(it) }
            out += r
            onProgress(if (r.ok) "完成 ${it.what}" else "失败 ${it.what}：${r.detail}")
        }
        return out
    }

    private fun downloadOne(item: Item): Result {
        return try {
            val req = Request.Builder().url(item.url!!)
                .header("User-Agent", "GTS-Android")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result(item, false, "HTTP ${resp.code}")
                }
                val bytes = resp.body?.bytes()
                    ?: return Result(item, false, "响应为空")

                when (item.kind) {
                    Kind.SUBMODULE -> unzipInto(item, bytes)
                    Kind.FILE -> {
                        val err = FileSaver.saveBytes(context, item.target, bytes)
                        if (err == null) Result(item, true, "已写入 ${item.target}")
                        else Result(item, false, err)
                    }
                    Kind.NOT_DOWNLOADABLE -> Result(item, false, item.note)
                }
            }
        } catch (e: Exception) {
            Result(item, false, e.message ?: "未知错误")
        }
    }

    /**
     * 解压 zip 到 target/ 下。
     * GitHub 的 zip 顶层多一层 <repo>-<ref>/，要剥掉。
     */
    private fun unzipInto(item: Item, bytes: ByteArray): Result {
        var count = 0
        var skipped = 0
        try {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                // 记录顶层目录名，稍后剥离
                var topDir: String? = null
                while (entry != null) {
                    val name = entry.name
                    if (topDir == null && name.contains('/')) {
                        topDir = name.substringBefore('/')
                    }
                    val rel = if (topDir != null && name.startsWith("$topDir/"))
                        name.removePrefix("$topDir/") else name

                    if (rel.isNotBlank() && !entry.isDirectory) {
                        val buf = ByteArrayOutputStream()
                        val tmp = ByteArray(8192)
                        while (true) {
                            val n = zis.read(tmp)
                            if (n <= 0) break
                            buf.write(tmp, 0, n)
                        }
                        val target = "${item.target}/$rel"
                        val err = FileSaver.saveBytes(context, target, buf.toByteArray())
                        if (err == null) count++ else skipped++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return Result(item, false, "解压失败：${e.message}")
        }
        return Result(
            item, count > 0,
            "解压 $count 个文件到 ${item.target}/" + if (skipped > 0) "，跳过 $skipped 个" else ""
        )
    }
}
