package org.gts.fix

import org.gts.compare.Status
import org.gts.model.Confidence

data class FixAction(val tool: String, val command: String, val note: String)

/**
 * 生成在 Termux 中执行的补齐命令。
 * 包名必须是 Termux 的包名，不是 Ubuntu 的。
 */
class FixPlanner {

    private val termuxPkg = mapOf(
        "cmake" to "cmake",
        "git" to "git",
        "ninja" to "ninja",
        "make" to "make",
        "pkg-config" to "pkg-config",
        "python" to "python",
        "jdk" to "openjdk-17",
        "libsdl3-dev" to "sdl3",
        "libsdl3-ttf-dev" to "sdl3-ttf",
        "libgl1-mesa-dev" to "mesa-dev",
        "libglu1-mesa-dev" to "glu",
        "libcurl4-openssl-dev" to "libcurl",
        "libfontconfig1-dev" to "fontconfig",
        "zlib1g-dev" to "zlib",
        "libpng-dev" to "libpng",
        "libfreetype-dev" to "freetype",
        "libwayland-dev" to "wayland",
        "libx11-dev" to "x11-repo"
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

    private val needClang = setOf("make", "cmake")

    fun plan(statuses: List<Status>): List<FixAction> {
        val actions = mutableListOf<FixAction>()
        val batch = linkedSetOf<String>()
        val submodules = linkedSetOf<String>()
        val sdkComponents = linkedSetOf<String>()

        for (s in statuses) {
            if (s.ok) continue
            val tool = s.req.tool
            when {
                tool == "submodule" -> submodules += (s.req.version ?: s.req.source)

                tool == "android-sdk-platform" -> {
                    val v = s.req.version ?: "34"
                    sdkComponents += "platforms;android-$v"
                }
                tool == "android-build-tools" -> {
                    val v = s.req.version ?: "34.0.0"
                    sdkComponents += "build-tools;$v"
                }
                // targetSdk / minSdk 无法单独安装，只提示
                tool == "android-sdk-target" || tool == "android-sdk-min" -> Unit

                // AGP / Kotlin 由 Gradle 拉取，无需手工装
                tool == "android-gradle-plugin" || tool == "kotlin" -> Unit

                tool == "android-ndk" -> actions += FixAction(tool,
                    "echo 'NDK ${s.req.version} 需在 PC 上安装'",
                    "NDK 无法在 Termux 内安装，请到 PC 处理")

                tool == "node" || tool == "go" || tool == "rust" || tool == "ruby" -> {
                    val v = s.req.version ?: "latest"
                    actions += FixAction(tool,
                        "pkg install -y $tool",
                        "安装 $tool（Termux 包名）。期望版本 $v，若不符请手动处理")
                }
                tool == "python" -> batch += "python"

                tool == "gradle" -> {
                    val v = s.req.version ?: "8.9"
                    actions += FixAction(tool,
                        "pkg install -y openjdk-17 unzip && " +
                        "cd \$HOME && wget -q https://services.gradle.org/distributions/gradle-$v-bin.zip && " +
                        "unzip -q -o gradle-$v-bin.zip && " +
                        "echo 'export PATH=\$HOME/gradle-$v/bin:\$PATH' >> \$HOME/.bashrc && " +
                        "export PATH=\$HOME/gradle-$v/bin:\$PATH",
                        "下载并解压 Gradle $v，PATH 已写入 .bashrc")
                }

                termuxPkg.containsKey(tool) -> {
                    batch += termuxPkg.getValue(tool)
                    if (tool in needClang) batch += "clang"
                }

                s.req.confidence == Confidence.LOW -> {
                    val g = cmakeGuess[tool]
                    val mapped = g?.let { termuxPkg[it] ?: it }
                    if (mapped != null) {
                        actions += FixAction(tool, "pkg install -y $mapped",
                            "CMake find_package($tool) 推测需要 $mapped，请确认")
                    }
                }
            }
        }

        if (sdkComponents.isNotEmpty()) {
            actions += FixAction("android-sdk",
                "sdkmanager ${sdkComponents.joinToString(" ") { "\"$it\"" }}",
                "安装 ${sdkComponents.size} 个 Android SDK 组件（需已装 Android SDK）")
        }

        if (submodules.isNotEmpty()) {
            actions += FixAction("submodule",
                "git submodule update --init --recursive",
                "初始化 ${submodules.size} 个子模块")
        }

        if (batch.isNotEmpty()) {
            actions.add(0, FixAction("batch",
                "pkg install -y ${batch.joinToString(" ")}",
                "批量安装 ${batch.size} 个 Termux 包"))
        }
        return actions
    }
}
