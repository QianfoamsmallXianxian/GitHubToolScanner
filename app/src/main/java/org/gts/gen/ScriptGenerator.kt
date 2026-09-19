package org.gts.gen

/**
 * 接收已算好的 Classified，不再内部 new WorkflowGenerator 重算。
 */
class ScriptGenerator {

    fun generate(
        classified: WorkflowGenerator.Classified,
        target: CiTarget,
        buildCommand: String,
        useSubmodules: Boolean
    ): String {
        val c = classified
        val sb = StringBuilder()

        sb.appendLine("#!/usr/bin/env bash")
        sb.appendLine("# 由 GitHub Tool Scanner 自动生成")
        sb.appendLine("# 目标环境：${target.label}")
        sb.appendLine("set -euo pipefail")
        sb.appendLine()

        if (c.sysPkgs.isNotEmpty()) {
            sb.appendLine("echo '==> 安装系统包'")
            // 必须覆盖 PkgManager 的全部取值，否则 when 不是穷尽的。
            when (target.pkg) {
                PkgManager.APT -> {
                    sb.appendLine("sudo apt-get update -qq")
                    sb.appendLine("sudo apt-get install -y ${c.sysPkgs.joinToString(" ")}")
                }
                PkgManager.BREW -> {
                    sb.appendLine("brew install ${c.sysPkgs.joinToString(" ")}")
                }
                PkgManager.CHOCO -> {
                    sb.appendLine("# Windows 目标请用 PowerShell 执行：")
                    sb.appendLine("# choco install ${c.sysPkgs.joinToString(" ")} -y --no-progress")
                }
            }
            sb.appendLine()
        }

        if (c.setupSteps.isNotEmpty() || c.specialSteps.isNotEmpty()) {
            sb.appendLine("# 以下工具在本脚本中不自动安装，需手工处理：")
            c.setupSteps.forEach { sb.appendLine("#   " + it.trim().lines().first()) }
            c.specialSteps.forEach { sb.appendLine("#   " + it.trim().lines().first()) }
            sb.appendLine()
        }

        if (c.sdkPackages.isNotEmpty()) {
            sb.appendLine("# Android SDK 组件（需已装 sdkmanager）：")
            sb.appendLine("# sdkmanager ${c.sdkPackages.joinToString(" ") { "\"$it\"" }}")
            sb.appendLine()
        }

        if (useSubmodules || c.needsSubmodules) {
            sb.appendLine("echo '==> 初始化子模块'")
            sb.appendLine("git submodule update --init --recursive")
            sb.appendLine()
        }

        sb.appendLine("echo '==> 构建'")
        sb.appendLine(buildCommand.ifBlank { "make" })
        sb.appendLine()

        if (c.unresolved.isNotEmpty()) {
            sb.appendLine("# 未映射的工具，请手工补充：")
            c.unresolved.forEach { sb.appendLine("#   - $it") }
        }

        return sb.toString()
    }
}
