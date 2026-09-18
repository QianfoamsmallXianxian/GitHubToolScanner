package org.gts.scan

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 扫描 GitHub 仓库。
 *
 * 关键改动：不再逐个猜路径，而是先用 Trees API 拿**完整文件树**，
 * 再按文件名模式从全树里筛出声明文件。这样即使声明文件在非标准位置
 * （monorepo 的 apps/xxx/package.json、nested/ext/CMakeLists.txt），
 * 或者仓库本身就是残缺的，也能发现。
 */
class RepoScanner(private val token: String? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /** 完整文件树里的一个条目 */
    data class TreeEntry(val path: String, val isDir: Boolean, val size: Long)

    data class ScanResult(
        val owner: String,
        val repo: String,
        val branch: String,
        /** 全部路径，用于缺失检测 */
        val tree: List<TreeEntry>,
        /** 抓到的声明文件内容 */
        val files: Map<String, String>,
        /** 树被 GitHub 截断（超大仓库） */
        val treeTruncated: Boolean
    )

    fun parseSlug(url: String): Pair<String, String> {
        val clean = url.trim().removeSuffix(".git").trimEnd('/')
        val parts = clean.substringAfter("github.com/").split("/")
        require(parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "URL 不合法，应形如 https://github.com/owner/repo"
        }
        return parts[0] to parts[1]
    }

    private fun defaultBranch(owner: String, repo: String): String {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching {
                    JSONObject(body).optString("message")
                }.getOrNull().orEmpty()
                val hint = when (r.code) {
                    404 -> "仓库不存在，或是私有仓库（私有仓库需填 Token）"
                    401, 403 -> "被拒绝访问，可能是 Token 无效或已触发 API 限流"
                    429 -> "请求过于频繁，请稍后再试"
                    else -> "HTTP ${r.code}"
                }
                throw IOException("$hint${if (msg.isNotBlank()) "（$msg）" else ""}")
            }
            return JSONObject(body).optString("default_branch", "main")
        }
    }

    /** 用 Trees API 一次拿完整文件树。比逐个猜路径省额度，也更全。 */
    private fun listTree(owner: String, repo: String, branch: String): Pair<List<TreeEntry>, Boolean> {
        val url = "https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"
        val req = Request.Builder().url(url)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .header("Accept", "application/vnd.github+json")
            .build()

        return client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                // 树拿不到不算致命，退化成空树，后面按固定候选列表兜底
                return emptyList<TreeEntry>() to false
            }
            val o = JSONObject(body)
            val arr = o.optJSONArray("tree") ?: JSONArray()
            val out = ArrayList<TreeEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                out += TreeEntry(
                    path = e.optString("path"),
                    isDir = e.optString("type") == "tree",
                    size = e.optLong("size", 0L)
                )
            }
            out to o.optBoolean("truncated", false)
        }
    }

    /**
     * 从完整树里挑出所有可能是声明文件的路径。
     *
     * 用「文件名模式」而不是固定路径，所以能匹配到任意深度的文件。
     * 但会排除明显的噪声目录（node_modules、vendor、.git 等）。
     */
    private fun pickManifestPaths(tree: List<TreeEntry>): List<String> {
        if (tree.isEmpty()) return FALLBACK_CANDIDATES

        val noise = listOf(
            "node_modules/", "vendor/", ".git/", "third_party/",
            "build/", "dist/", ".gradle/", "Pods/", ".venv/",
            "__pycache__/", "target/"
        )

        fun isNoise(p: String): Boolean = noise.any { p.contains(it) }

        fun leafName(p: String): String = p.substringAfterLast('/')

        val out = LinkedHashSet<String>()
        for (e in tree) {
            if (e.isDir || isNoise(e.path)) continue
            val name = leafName(e.path)

            val hit = when {
                name == "package.json" -> true
                name == ".nvmrc" || name == ".node-version" -> true
                name == ".python-version" || name == ".tool-versions" -> true
                name == "go.mod" || name == "go.sum" -> true
                name == "Cargo.toml" || name == "Cargo.lock" -> true
                name.startsWith("rust-toolchain") -> true
                name == "pyproject.toml" || name == "setup.py" -> true
                name == "Gemfile" || name == "Gemfile.lock" -> true
                name == "composer.json" || name == "composer.lock" -> true
                name == "build.gradle" || name == "build.gradle.kts" -> true
                name == "settings.gradle" || name == "settings.gradle.kts" -> true
                name == "gradle.properties" -> true
                name == "gradle-wrapper.properties" -> true
                name == "gradlew" -> true
                name == "CMakeLists.txt" || name == "CMakePresets.json" -> true
                name == "meson.build" || name == "meson_options.txt" -> true
                name == "Makefile" || name == "makefile" || name == "GNUmakefile" -> true
                name == "Makefile.am" || name == "configure.ac" || name == "configure" -> true
                name == "Dockerfile" || name.startsWith("Dockerfile.") -> true
                name == "WORKSPACE" || name == "WORKSPACE.bazel" || name == "MODULE.bazel" -> true
                name == "BUILD" || name == "BUILD.bazel" -> true
                name == "pubspec.yaml" || name == "Package.swift" -> true
                name == "pom.xml" || name == "build.xml" -> true
                name == "pnpm-lock.yaml" || name == "yarn.lock" -> true
                name == "bun.lockb" || name == "bun.lock" -> true
                name == "package-lock.json" -> true
                name == ".gitmodules" -> true
                name == "b.sh" || name == "build.sh" -> true
                else -> false
            }
            if (hit) out += e.path

            // 限制总量，避免超大仓库爆掉
            if (out.size >= 120) break
        }

        // workflows 单独加
        tree.filter { !it.isDir && it.path.contains(".github/workflows") &&
            (it.path.endsWith(".yml") || it.path.endsWith(".yaml")) }
            .take(10)
            .forEach { out += it.path }

        return out.toList()
    }

    private fun fetchFile(owner: String, repo: String, branch: String, path: String): String? {
        val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        val req = Request.Builder().url(url)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                // 声明文件都是文本，超过 512KB 的跳过（可能是二进制误判）
                val body = r.body ?: return null
                if (body.contentLength() > 512 * 1024) return null
                body.string()
            }
        } catch (e: Exception) { null }
    }

    fun scan(repoUrl: String): ScanResult {
        val (owner, repo) = parseSlug(repoUrl)
        val branch = defaultBranch(owner, repo)
        val (tree, truncated) = listTree(owner, repo, branch)
        val paths = pickManifestPaths(tree)

        val files = LinkedHashMap<String, String>()
        for (p in paths) {
            fetchFile(owner, repo, branch, p)?.let { files[p] = it }
        }

        return ScanResult(owner, repo, branch, tree, files, truncated)
    }

    private companion object {
        /** 树拿不到时的兜底候选（老逻辑） */
        val FALLBACK_CANDIDATES = listOf(
            "package.json", ".nvmrc", ".tool-versions", "go.mod",
            "rust-toolchain.toml", "pyproject.toml", ".python-version",
            "Dockerfile", "build.gradle.kts", "build.gradle",
            "gradle/wrapper/gradle-wrapper.properties",
            "CMakeLists.txt", "Makefile", ".gitmodules",
            "Cargo.toml", "setup.py", "pom.xml", "pubspec.yaml"
        )
    }
}
