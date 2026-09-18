package org.gts.scan

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class RepoScanner(private val token: String? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val candidates = listOf(
        "package.json", ".nvmrc", ".node-version", ".tool-versions",
        "go.mod", "rust-toolchain.toml", "rust-toolchain",
        "pyproject.toml", ".python-version",
        "Gemfile", "composer.json", "Dockerfile",
        "build.gradle.kts", "gradle.properties",
        "gradle/wrapper/gradle-wrapper.properties",
        "CMakeLists.txt", "CMakePresets.json",
        ".gitmodules", "Makefile"
    )

    fun parseSlug(url: String): Pair<String, String> {
        val clean = url.trim().removeSuffix(".git").trimEnd('/')
        val parts = clean.substringAfter("github.com/").split("/")
        require(parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "URL 不合法，应形如 https://github.com/owner/repo"
        }
        return parts[0] to parts[1]
    }

    /**
     * 查默认分支。失败时**抛出明确异常**，不再静默返回 main。
     * 否则仓库不存在 / 私有 / 被限流时，用户只会看到「扫描 0 个文件」，无从判断原因。
     */
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

    private fun fetchFile(owner: String, repo: String, branch: String, path: String): String? {
        val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        val req = Request.Builder().url(url)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
    }

    private fun listWorkflows(owner: String, repo: String, branch: String): List<String> {
        val url = "https://api.github.com/repos/$owner/$repo/contents/.github/workflows?ref=$branch"
        val req = Request.Builder().url(url)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .header("Accept", "application/vnd.github+json")
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val arr = JSONArray(r.body?.string() ?: return emptyList())
                (0 until arr.length()).mapNotNull {
                    val o = arr.getJSONObject(it)
                    val name = o.optString("name")
                    if (name.endsWith(".yml") || name.endsWith(".yaml")) o.optString("path") else null
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun scan(repoUrl: String): Map<String, String> {
        val (owner, repo) = parseSlug(repoUrl)
        val branch = defaultBranch(owner, repo)
        val out = LinkedHashMap<String, String>()
        for (p in candidates) {
            fetchFile(owner, repo, branch, p)?.let { out[p] = it }
        }
        listWorkflows(owner, repo, branch).forEach { wf ->
            fetchFile(owner, repo, branch, wf)?.let { out[wf] = it }
        }
        return out
    }
}
