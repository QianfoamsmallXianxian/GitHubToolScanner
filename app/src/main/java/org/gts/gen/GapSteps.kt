package org.gts.gen

import org.gts.scan.ModuleGapDetector

/**
 * 把 ModuleGapDetector 检出的「缺失」转成 Actions 步骤。
 *
 * 本地下不了的东西（工具、锁文件），在云端 runner 上都能搞定，
 * 所以统一写进 workflow，一次构建全部补齐。
 */
object GapSteps {

    data class Steps(
        /** 需要 checkout 时递归拉子模块 */
        val needRecursiveSubmodules: Boolean,
        /** 放在 install 阶段的 run 命令（按顺序） */
        val installCommands: List<String>,
        /** 需要在 build 之前跑的 shell 片段 */
        val setupCommands: List<String>,
        /** 无法自动处理的，注释列出 */
        val unresolved: List<String>
    )

    fun build(gaps: List<ModuleGapDetector.Gap>): Steps {
        var recursive = false
        val install = linkedSetOf<String>()
        val setup = linkedSetOf<String>()
        val unresolved = mutableListOf<String>()

        for (g in gaps) {
            when {
                // 子模块：checkout 时递归即可
                g.fix.contains("git submodule") -> {
                    recursive = true
                    setup += "git submodule update --init --recursive"
                }

                // gradlew 缺失：生成 wrapper
                g.what.endsWith("gradlew") -> {
                    setup += "gradle wrapper --gradle-version=8.9 || true"
                }

                // Go 锁文件
                g.what.endsWith("go.sum") -> {
                    setup += "go mod download"
                }

                // Node 锁文件
                g.what == "lockfile" || g.what.endsWith("package-lock.json") -> {
                    setup += "npm install --package-lock-only"
                }

                // Python 锁文件
                g.what.endsWith("poetry.lock") -> {
                    setup += "pip install poetry && poetry lock"
                }

                // Dockerfile COPY 路径缺失：通常是被 .gitignore 排除，云端生成不了
                g.why.contains("COPY") -> unresolved += "${g.what}（${g.why}）"


                else -> {
                    // fix 字段可能是多行文本：逐行取可执行命令，丢掉中文括号说明行
                    val cmds = g.fix.lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .filterNot { it.startsWith("（") || it.startsWith("(") }
                        .filterNot { it.contains("检查") || it.contains("确认") }
                    if (cmds.isEmpty()) {
                        unresolved += "${g.what}（${g.why}）"
                    } else {
                        cmds.forEach { setup += it }
                    }
                }
            }
        }

        return Steps(
            needRecursiveSubmodules = recursive,
            installCommands = install.toList(),
            setupCommands = setup.toList(),
            unresolved = unresolved
        )
    }
}
