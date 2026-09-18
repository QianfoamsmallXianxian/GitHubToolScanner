package org.gts.gen

import org.gts.model.Confidence
import org.gts.model.ToolReq

/**
 * 目标包管理器。
 *
 * 曾经有 ALPINE，已移除。原因：GitHub Actions 用 container: alpine:latest 时，
 * actions/checkout 依赖的 node20 是 glibc 构建的，在 musl 的 alpine 里跑不起来。
 */
enum class PkgManager { APT, BREW }

enum class CiTarget(val runner: String, val pkg: PkgManager, val label: String) {
    UBUNTU("ubuntu-latest", PkgManager.APT, "Ubuntu (apt)"),
    MACOS("macos-latest", PkgManager.BREW, "macOS (brew)")
}

class WorkflowGenerator {

    data class Classified(
        val sysPkgs: List<String>,
        val setupSteps: List<String>,
        val specialSteps: List<String>,
        val unresolved: List<String>,
        val needsSubmodules: Boolean
    )

    /** B8 修复：把 classified 一并带出去，供 ScriptGenerator 复用，避免重复计算。 */
    data class Plan(
        val yaml: String,
        val unresolved: List<String>,
        val classified: Classified
    )

    private val aptTable = mapOf(
        "cmake" to "cmake",
        "git" to "git",
        "ninja" to "ninja-build",
        "make" to "build-essential",
        "pkg-config" to "pkg-config",
        "python" to "python3",
        "jdk" to "openjdk-17-jdk",
        "libsdl3-dev" to "libsdl3-dev",
        "libsdl3-ttf-dev" to "libsdl3-ttf-dev",
        "libgl1-mesa-dev" to "libgl1-mesa-dev",
        "libglu1-mesa-dev" to "libglu1-mesa-dev",
        "libcurl4-openssl-dev" to "libcurl4-openssl-dev",
        "libfontconfig1-dev" to "libfontconfig1-dev",
        "zlib1g-dev" to "zlib1g-dev",
        "libpng-dev" to "libpng-dev",
        "libfreetype-dev" to "libfreetype-dev",
        "libwayland-dev" to "libwayland-dev",
        "libx11-dev" to "libx11-dev"
    )

    private val brewTable = mapOf(
        "cmake" to "cmake",
        "git" to "git",
        "ninja" to "ninja",
        "make" to "make",
        "pkg-config" to "pkg-config",
        "python" to "python@3.12",
        "jdk" to "openjdk@17",
        "libsdl3-dev" to "sdl3",
        "libsdl3-ttf-dev" to "sdl3_ttf",
        "libpng-dev" to "libpng",
        "libfreetype-dev" to "freetype",
        "libcurl4-openssl-dev" to "curl",
        "libfontconfig1-dev" to "fontconfig"
    )

    private val cmakeGuess = mapOf(
        "sdl3" to "libsdl3-dev",
        "sdl3_ttf" to "libsdl3-ttf-dev",
        "curl" to "libcurl4-openssl-dev",
        "zlib" to "zlib1g-dev",
        "png" to "libpng-dev",
        "freetype" to "libfreetype-dev",
        "wayland" to "libwayland-dev",
        "x11" to "libx11-dev",
        "opengl" to "libgl1-mesa-dev",
        "threads" to "make"
    )

    private fun mapPkg(tool: String, t: CiTarget): String? = when (t.pkg) {
        PkgManager.APT -> aptTable[tool]
        PkgManager.BREW -> brewTable[tool]
    }

    /**
     * B7 修复：保留版本范围语义。
     *   ^1.2.3 -> 1.x    （setup-node / setup-python 都接受 x 写法）
     *   ~1.2.3 -> 1.2.x
     *   >=3.16 -> 3.16
     * 原始字符串，反斜杠只写一次。
     */
    private fun cleanVer(v: String?): String {
        if (v.isNullOrBlank()) return ""
        val w = v.trim()
        val nums = Regex("""\d+(?:\.\d+)*""").find(w)?.value ?: return w
        val op = w.takeWhile { it in "<>=^~ " }.trim()
        return when {
            op.startsWith("^") -> nums.substringBefore('.') + ".x"
            op.startsWith("~") -> {
                val parts = nums.split('.')
                if (parts.size >= 2) "${parts[0]}.${parts[1]}.x" else nums
            }
            else -> nums
        }
    }

    private fun actionStep(name: String, action: String, key: String, value: String): String =
        buildString {
            appendLine("      - name: $name")
            appendLine("        uses: $action")
            appendLine("        with:")
            appendLine("          $key: '$value'")
        }.trimEnd()

    fun classify(reqs: List<ToolReq>, target: CiTarget): Classified {
        val unresolved = linkedSetOf<String>()
        val sysPkgs = linkedSetOf<String>()
        val setupSteps = mutableListOf<String>()
        val specialSteps = mutableListOf<String>()
        var needsSubmodules = false

        for ((tool, list) in reqs.groupBy { it.tool }) {
            val req = list.firstOrNull { it.version != null } ?: list.first()
            val ver = req.version

            when (tool) {
                "jdk" -> {
                    val v = cleanVer(ver).ifBlank { "17" }
                    setupSteps += buildString {
                        appendLine("      - name: Set up JDK $v")
                        appendLine("        uses: actions/setup-java@v4")
                        appendLine("        with:")
                        appendLine("          distribution: temurin")
                        appendLine("          java-version: '$v'")
                    }.trimEnd()
                }
                "node" -> {
                    val v = cleanVer(ver).ifBlank { "20" }
                    setupSteps += actionStep("Set up Node $v", "actions/setup-node@v4", "node-version", v)
                }
                "python" -> {
                    val v = cleanVer(ver).ifBlank { "3.12" }
                    setupSteps += actionStep("Set up Python $v", "actions/setup-python@v5", "python-version", v)
                }
                "go" -> {
                    val v = cleanVer(ver).ifBlank { "1.22" }
                    setupSteps += actionStep("Set up Go $v", "actions/setup-go@v5", "go-version", v)
                }
                "rust" -> {
                    val v = ver ?: "stable"
                    setupSteps += actionStep("Set up Rust $v", "dtolnay/rust-toolchain@master", "toolchain", v)
                }
                "android-ndk" -> {
                    val v = ver ?: "r29"
                    specialSteps += actionStep("Set up Android NDK $v", "nttld/setup-ndk@v1", "ndk-version", v)
                }
                "gradle" -> {
                    val v = cleanVer(ver).ifBlank { "8.9" }
                    specialSteps += actionStep("Set up Gradle $v", "gradle/actions/setup-gradle@v4", "gradle-version", v)
                }
                "android-sdk" -> {
                    specialSteps += buildString {
                        appendLine("      - name: Set up Android SDK")
                        appendLine("        uses: android-actions/setup-android@v3")
                    }.trimEnd()
                }
                "submodule" -> needsSubmodules = true
                "docker-base" -> { }
                else -> {
                    val pkg = mapPkg(tool, target)
                    if (pkg != null) {
                        sysPkgs += pkg
                    } else if (req.confidence == Confidence.LOW) {
                        val g = cmakeGuess[tool]
                        val mapped = g?.let { mapPkg(it, target) ?: it }
                        if (mapped != null) sysPkgs += mapped else unresolved += tool
                    } else {
                        unresolved += tool
                    }
                }
            }
        }

        return Classified(
            sysPkgs = sysPkgs.toList(),
            setupSteps = setupSteps,
            specialSteps = specialSteps,
            unresolved = unresolved.toList(),
            needsSubmodules = needsSubmodules
        )
    }

    fun generate(
        reqs: List<ToolReq>,
        target: CiTarget,
        buildCommand: String,
        useSubmodules: Boolean
    ): Plan {
        val c = classify(reqs, target)

        val yaml = buildString {
            appendLine("name: CI")
            appendLine()
            appendLine("on:")
            appendLine("  push:")
            appendLine("    branches: [ main, master ]")
            appendLine("  pull_request:")
            appendLine("  workflow_dispatch:")
            appendLine()
            appendLine("jobs:")
            appendLine("  build:")
            appendLine("    runs-on: ${target.runner}")
            appendLine("    steps:")
            appendLine("      - name: Checkout")
            appendLine("        uses: actions/checkout@v4")
            if (useSubmodules || c.needsSubmodules) {
                appendLine("        with:")
                appendLine("          submodules: recursive")
            }
            appendLine()

            c.setupSteps.forEach { appendLine(it); appendLine() }
            c.specialSteps.forEach { appendLine(it); appendLine() }

            if (c.sysPkgs.isNotEmpty()) {
                appendLine("      - name: Install system packages")
                appendLine("        run: |")
                when (target.pkg) {
                    PkgManager.APT -> {
                        appendLine("          sudo apt-get update -qq")
                        appendLine("          sudo apt-get install -y ${c.sysPkgs.joinToString(" ")}")
                    }
                    PkgManager.BREW -> {
                        appendLine("          brew install ${c.sysPkgs.joinToString(" ")}")
                    }
                }
                appendLine()
            }

            appendLine("      - name: Build")
            appendLine("        run: ${buildCommand.ifBlank { "make" }}")
            appendLine()

            if (c.unresolved.isNotEmpty()) {
                appendLine("      # 以下工具无法自动映射到包名，请手工补充到上面的安装步骤：")
                c.unresolved.forEach { appendLine("      #   - $it") }
            }
        }

        return Plan(yaml, c.unresolved, c)
    }
}
