package org.gts.fix

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 与 Termux 通信。
 *
 * 前提（Termux 侧）：
 *   1) 已安装 Termux
 *   2) ~/.termux/termux.properties 里设置 allow-external-apps=true
 *   3) 执行 termux-reload-settings
 */
class TermuxBridge(private val context: Context) {

    companion object {
        const val TERMUX_PKG = "com.termux"
        const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        const val EXTRA_RESULT_BUNDLE = "com.termux.RUN_COMMAND_RESULT_BUNDLE"

        private const val ACTION_RESULT = "org.gts.TERMUX_RESULT"
        private const val EXTRA_ID = "id"
        private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val HOME = "/data/data/com.termux/files/home"

        /** PendingIntent.FLAG_MUTABLE 是 API 31 引入的，低版本上取 0。 */
        private val FLAG_MUTABLE_COMPAT: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** 保护 receiver 的注册与注销。 */
    private val receiverLock = Any()

    @Volatile
    private var receiver: BroadcastReceiver? = null

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PKG, 0)
        true
    } catch (e: Exception) { false }

    private fun ensureReceiver() {
        if (receiver != null) return
        synchronized(receiverLock) {
            if (receiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val id = i?.getStringExtra(EXTRA_ID) ?: return
                    val bundle: Bundle? = i.getBundleExtra(EXTRA_RESULT_BUNDLE)
                    val stdout = bundle?.getString("stdout").orEmpty()
                    val stderr = bundle?.getString("stderr").orEmpty()
                    pending.remove(id)?.complete(if (stdout.isNotBlank()) stdout else stderr)
                }
            }
            val filter = IntentFilter(ACTION_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
            receiver = r
        }
    }

    /** B17 修复：离开界面时注销 receiver，避免泄漏。 */
    fun release() {
        synchronized(receiverLock) {
            receiver?.let {
                runCatching { context.unregisterReceiver(it) }
                receiver = null
            }
        }
    }

    suspend fun exec(command: String, timeoutMs: Long = 20000): String? {
        if (!isInstalled()) return null
        ensureReceiver()

        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred

        val resultIntent = Intent(ACTION_RESULT).setPackage(context.packageName)
        resultIntent.putExtra(EXTRA_ID, id)
        val pi = PendingIntent.getBroadcast(
            context, id.hashCode(), resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or FLAG_MUTABLE_COMPAT
        )

        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PKG, TERMUX_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, HOME)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, pi)
        }

        return try {
            context.startService(intent)
            // 用 finally 清理：withTimeoutOrNull 超时返回 null 而不抛异常，
            // 只在 catch 里清理会让超时的 id 永久留在 pending 里，造成泄漏。
            try {
                withTimeoutOrNull(timeoutMs) { deferred.await() }
            } finally {
                pending.remove(id)
            }
        } catch (e: Exception) {
            pending.remove(id)
            null
        }
    }

    fun runDetached(command: String): Boolean {
        if (!isInstalled()) return false
        return try {
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                setClassName(TERMUX_PKG, TERMUX_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, BASH)
                putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_WORKDIR, HOME)
                putExtra(EXTRA_BACKGROUND, true)
            }
            context.startService(intent)
            true
        } catch (e: Exception) { false }
    }
}
