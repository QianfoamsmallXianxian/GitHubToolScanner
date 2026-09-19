package org.gts.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把文本保存到 /storage/emulated/0/Download/gts-fix/。
 *
 * API 29+ 走 MediaStore.Downloads（分区存储要求）。
 * API 28 及以下直接写文件（需 WRITE_EXTERNAL_STORAGE）。
 *
 * 返回 null 表示成功，否则返回错误说明。
 */
object FileSaver {

    const val SUB_DIR = "gts-fix"

    fun save(context: Context, fileName: String, content: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, fileName, content)
            } else {
                saveViaFile(fileName, content)
            }
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun saveViaMediaStore(context: Context, fileName: String, content: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore 拒绝创建条目")
        resolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: throw IllegalStateException("无法打开输出流")
    }

    @Suppress("DEPRECATION")
    private fun saveViaFile(fileName: String, content: String) {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(base, SUB_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建目录 ${dir.absolutePath}")
        }
        File(dir, fileName).writeText(content, Charsets.UTF_8)
    }

    /** 给界面显示的完整路径。 */
    fun displayPath(fileName: String): String =
        "/storage/emulated/0/Download/$SUB_DIR/$fileName"
}
