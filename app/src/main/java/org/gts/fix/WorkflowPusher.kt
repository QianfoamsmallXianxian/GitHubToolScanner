package org.gts.fix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.util.Base64

/**
 * 把生成的 workflow 一键写入目标 GitHub 仓库。
 *
 * 走 Contents API：
 *   PUT /repos/{owner}/{repo}/contents/.github/workflows/{name}
 *
 * 已存在则带 sha 更新，不存在则新建。
 * 需要 token 具备 repo + workflow 权限。
 */
class WorkflowPusher(private val token: String) {

    data class Result(val ok: Boolean, val message: String, val htmlUrl: String? = null)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun auth(b: Request.Builder) = b
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")

    /**
     * @param ownerRepo 形如 "hrydgard/PPSSPP"
     * @param fileName  形如 "gts-build.yml"
     * @param yaml      工作流内容
     */
    suspend fun push(ownerRepo: String, fileName: String, yaml: String): Result =
        withContext(Dispatchers.IO) {
            try {
                val path = ".github/workflows/$fileName"
                val url = "https://api.github.com/repos/$ownerRepo/contents/$path"

                // 1) 查是否已存在，拿 sha
                val sha = run {
                    val req = auth(Request.Builder().url(url)).build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) return@run null
                        val body = r.body?.string() ?: return@run null
                        JSONObject(body).optString("sha").ifBlank { null }
                    }
                }

                // 2) 提交
                val content = Base64.encodeToString(
                    yaml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                )
                val payload = JSONObject().apply {
                    put("message", if (sha == null) "add $path" else "update $path")
                    put("content", content)
                    if (sha != null) put("sha", sha)
                }

                val putReq = auth(
                    Request.Builder().url(url)
                        .put(payload.toString().toRequestBody(jsonType))
                ).build()

                client.newCall(putReq).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        val msg = runCatching {
                            JSONObject(body).optString("message")
                        }.getOrNull().orEmpty()
                        val hint = when (r.code) {
                            401, 403 -> "token 权限不足（需要 repo + workflow）"
                            404 -> "仓库不存在或无权访问"
                            409 -> "冲突，可能同时有人在改"
                            422 -> "内容校验失败"
                            else -> "HTTP ${r.code}"
                        }
                        return@withContext Result(
                            false,
                            "$hint${if (msg.isNotBlank()) "（$msg）" else ""}"
                        )
                    }
                    val html = runCatching {
                        JSONObject(body)
                            .optJSONObject("content")
                            ?.optString("html_url")
                    }.getOrNull()
                    Result(true, if (sha == null) "已新建 $path" else "已更新 $path", html)
                }
            } catch (e: Exception) {
                Result(false, "推送失败：${e.message}")
            }
        }
}
