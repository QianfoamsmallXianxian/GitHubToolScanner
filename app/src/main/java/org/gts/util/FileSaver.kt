package org.gts.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 统一的落盘入口。
 *
 * 优先写公共 Download 目录：/storage/emulated/0/Download/gts-fix/<rel>
 * 失败时回退 App 私有目录：/data/data/org.gts/files/<rel>
 *
 * 回退时会通过 returnedViaPrivate 告知调用方，避免用户以为文件在 Download。
 */
object FileSaver {

    private const val PUBLIC_ROOT = "gts-fix"

    /** 最近一次实际落盘位置，供 UI 显示 */
    @Volatile
    var lastLocation: String = ""
        private set

    fun displayPath(name: String): String =
        "${Environment.getExternalStorageDirectory().absolutePath}/Download/$PUBLIC_ROOT/$name"

    fun save(ctx: Context, name: String, content: String): String? =
        saveBytes(ctx, name, content.toByteArray(Charsets.UTF_8))

    /**
     * 保存任意字节。
     * @return null 表示成功；非 null 是错误说明（且已回退私有目录也会说明）
     */
    fun saveBytes(ctx: Context, relPath: String, bytes: ByteArray): String? {
        val safe = relPath.replace("\\", "/").split("/")
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
        if (safe.isBlank()) return "路径非法"

        // 1) 公共目录
        var publicErr: String? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(ctx, safe, bytes)
            } else {
                saveViaFile(
                    File(Environment.getExternalStorageDirectory(), "Download/$PUBLIC_ROOT/$safe"),
                    bytes
                )
            }
            lastLocation = "Download/$PUBLIC_ROOT/$safe"
            return null
        } catch (e: Exception) {
            publicErr = e.message ?: e.javaClass.simpleName
        }

        // 2) 回退私有目录
        return try {
            saveViaFile(File(ctx.filesDir, safe), bytes)
            lastLocation = "(私有) files/$safe"
            // 成功但位置不同，明确告知
            "已保存到 App 私有目录（公共目录不可写：$publicErr）"
        } catch (e: Exception) {
            "公共目录失败（$publicErr）；私有目录也失败（${e.message ?: e.javaClass.simpleName}）"
        }
    }

    private fun saveViaFile(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    private fun saveViaMediaStore(ctx: Context, relPath: String, bytes: ByteArray) {
        val resolver = ctx.contentResolver
        val name = relPath.substringAfterLast('/')
        val sub = relPath.substringBeforeLast('/', "")
        val relDir = if (sub.isEmpty())
            Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_ROOT
        else
            Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_ROOT + "/" + sub

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, guessMime(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
            // 已存在时覆盖，而不是报错
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert 返回 null")

        resolver.openOutputStream(uri, "w")?.use { os -> os.write(bytes) }
            ?: throw IllegalStateException("无法打开输出流")
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".sh") -> "text/x-shellscript"
        name.endsWith(".yml") || name.endsWith(".yaml") -> "text/yaml"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".txt") || name.endsWith(".md") -> "text/plain"
        name.endsWith(".kt") || name.endsWith(".java") -> "text/plain"
        name.endsWith(".zip") -> "application/zip"
        else -> "application/octet-stream"
    }
}
