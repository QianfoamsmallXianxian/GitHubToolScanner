package org.gts.util

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 内嵌 shell。只用于只读/轻量命令。
 *
 * 注意：/storage/emulated 是 noexec 挂载，无法在那里执行脚本文件。
 * 需要装包或改系统目录时，必须走 Termux。
 */
object LocalShell {

    data class Result(val stdout: String, val stderr: String, val exitCode: Int)

    /** 明显应该走 Termux 的命令前缀 */
    private val termuxOnly = listOf(
        "pkg ", "apt ", "apt-get ", "sdkmanager", "gradle ", "./gradlew",
        "chmod ", "chown ", "mount ", "su ", "sudo "
    )

    /** 判断是否应该拒绝执行并提示改用 Termux */
    fun shouldUseTermux(cmd: String): String? {
        val c = cmd.trim()
        if (c.isEmpty()) return null

        // 直接执行 .sh 文件 → noexec 会失败
        val first = c.split(Regex("\\s+")).firstOrNull().orEmpty()
        if (first.endsWith(".sh")) {
            return "这是脚本文件。/storage/emulated 是 noexec 分区，无法直接执行。请在 Termux 中运行，或用下载功能补齐文件。"
        }
        termuxOnly.forEach { p ->
            if (c.startsWith(p)) {
                return "该命令需要 Termux 环境（内嵌 shell 没有包管理器）。"
            }
        }
        return null
    }

    fun run(cmd: String, timeoutMs: Long = 15000): Result {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.redirectErrorStream(false)
            val p = pb.start()

            val out = StringBuilder()
            val err = StringBuilder()

            val tOut = Thread {
                BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                    r.forEachLine { out.appendLine(it) }
                }
            }
            val tErr = Thread {
                BufferedReader(InputStreamReader(p.errorStream)).use { r ->
                    r.forEachLine { err.appendLine(it) }
                }
            }
            tOut.start(); tErr.start()

            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                return Result(out.toString(), err.toString() + "\n(超时，已终止)", -1)
            }
            tOut.join(1000); tErr.join(1000)

            Result(out.toString(), err.toString(), p.exitValue())
        } catch (e: Exception) {
            Result("", e.message ?: "执行失败", -1)
        }
    }
}
