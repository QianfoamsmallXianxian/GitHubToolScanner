package org.gts.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * App 内嵌终端用的本地 shell。
 *
 * 用 ProcessBuilder 直接起 /system/bin/sh，不依赖 Termux。
 * 注意：受 Android 沙箱限制，app 的 UID 只能做有限的事：
 *   - 能读 /proc、能跑 /system/bin 下的工具（ls、cat、echo、curl 等）
 *   - 不能装包、不能改系统目录、不能访问别的 app 的私有目录
 * 需要装包时仍要走 Termux。
 */
object LocalShell {

    data class Output(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    suspend fun run(command: String, timeoutMs: Long = 20000): Output =
        withContext(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                pb.redirectErrorStream(false)
                pb.environment()["PATH"] =
                    "/system/bin:/system/xbin:/vendor/bin:/data/local/tmp"
                val proc = pb.start()

                val outText = StringBuilder()
                val errText = StringBuilder()

                val outThread = Thread {
                    runCatching {
                        BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                            r.forEachLine { outText.appendLine(it) }
                        }
                    }
                }
                val errThread = Thread {
                    runCatching {
                        BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                            r.forEachLine { errText.appendLine(it) }
                        }
                    }
                }
                outThread.start(); errThread.start()

                val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    outThread.join(500); errThread.join(500)
                    return@withContext Output(
                        outText.toString(),
                        errText.toString() + "\n[超时 ${timeoutMs}ms，已强制结束]",
                        -1
                    )
                }
                outThread.join(1000); errThread.join(1000)

                Output(outText.toString(), errText.toString(), proc.exitValue())
            } catch (e: Exception) {
                Output("", "${e.javaClass.simpleName}: ${e.message}", -1)
            }
        }
}
