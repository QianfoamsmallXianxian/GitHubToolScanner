package org.gts.compare

import org.gts.fix.TermuxBridge
import org.gts.model.ToolReq

data class Status(val req: ToolReq, val installed: String?, val ok: Boolean)

class EnvComparator(private val termux: TermuxBridge) {

    private val probeLines = mapOf(
        "cmake" to "cmake --version",
        "node" to "node --version",
        "python" to "python3 --version",
        "go" to "go version",
        "rust" to "rustc --version",
        "gradle" to "gradle --version",
        "jdk" to "java -version",
        "git" to "git --version",
        "ninja" to "ninja --version",
        "make" to "make --version",
        "pkg-config" to "pkg-config --version"
    )

    companion object {
        /** 用不常见字符做分隔，避免和 shell 启动输出（motd）混淆。 */
        private const val SEP = "@@GTS@@"
    }

    /**
     * 一次进 Termux 拿全部版本。
     *
     * B6 修复要点：
     * 1. 先用 command -v 判断命令是否存在，不存在就不输出该行。
     *    这样「未安装」和「装了但版本输出为空」能区分开。
     * 2. 用 indexOfLast 找结尾分隔符，容忍前面夹带 motd。
     * 3. stderr 合并到 stdout（2>&1），避免 java -version 这类只写 stderr 的工具取不到值。
     */
    suspend fun probeAll(): Map<String, String> {
        val script = buildString {
            appendLine("printf '%s\\n' '$SEP'")
            probeLines.forEach { (k, cmd) ->
                val bin = cmd.substringBefore(' ')
                appendLine("if command -v $bin >/dev/null 2>&1; then")
                appendLine("  printf '%s=%s\\n' '$k' \"\$($cmd 2>&1 | head -1)\"")
                appendLine("fi")
            }
            appendLine("printf '%s\\n' '$SEP'")
        }

        val out = termux.exec(script) ?: return emptyMap()
        val lines = out.lines()

        val start = lines.indexOfFirst { it.trim() == SEP }
        val end = lines.indexOfLast { it.trim() == SEP }
        if (start < 0 || end <= start) return emptyMap()

        return lines.subList(start + 1, end)
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null
                else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()
    }

    fun compare(reqs: List<ToolReq>, probed: Map<String, String>): List<Status> =
        reqs.map { req ->
            val raw = probed[req.tool]?.takeIf { it.isNotBlank() }
            val ver = raw?.let { extractVersion(it) }
            val ok = when {
                raw == null -> false
                req.version == null -> true
                ver == null -> true
                else -> satisfy(ver, req.version)
            }
            Status(req, ver, ok)
        }

    /** 原始字符串，反斜杠只写一次。 */
    private fun extractVersion(s: String): String? =
        Regex("""(\d+\.\d+(?:\.\d+)?)""").find(s)?.groupValues?.get(1)

    private fun satisfy(installed: String, want: String): Boolean {
        val w = want.trim()
        val target = Regex("""\d+(?:\.\d+)*""").find(w)?.value ?: return true
        val op = w.takeWhile { it in "<>=^~ " }.trim()
        return when {
            op.startsWith(">=") -> cmp(installed, target) >= 0
            op.startsWith(">") -> cmp(installed, target) > 0
            op.startsWith("<=") -> cmp(installed, target) <= 0
            op.startsWith("<") -> cmp(installed, target) < 0
            op.startsWith("^") -> installed.substringBefore(".") == target.substringBefore(".")
            op.startsWith("~") -> installed.substringBeforeLast(".") == target.substringBeforeLast(".")
            else -> installed.startsWith(target.substringBefore("."))
        }
    }

    private fun cmp(a: String, b: String): Int {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
