package org.gts.gen

/**
 * 从仓库自己的 Dockerfile / CI / 构建脚本里抄「平台构建配置」。
 *
 * 关键点：这些是仓库作者亲自写过的、验证过能跑的命令，
 * 比我们瞎猜的系统包列表准确得多。
 */
object PlatformSetup {

    data class Setup(
        /** 系统包（apt 名） */
        val aptPackages: List<String>,
        /** 原始装包命令（Dockerfile 里的 apk/apt 行） */
        val rawInstallLines: List<String>,
        /** 构建前的准备命令（configure、submodule 等） */
        val preBuildCommands: List<String>,
        /** 识别到的语言/平台 */
        val platforms: List<String>,
        /** 环境变量建议 */
        val envVars: Map<String, String>
    )

    // apt 包名去噪：这些是基础工具，重复列没意义
    private val aptNoise = setOf(
        "sudo", "apt", "apt-get", "update", "install", "-y", "-qq",
        "&&", "|", "\\", "--no-install-recommends", "-q"
    )

    fun detect(files: Map<String, String>): Setup {
        val apt = linkedSetOf<String>()
        val raw = mutableListOf<String>()
        val pre = linkedSetOf<String>()
        val platforms = linkedSetOf<String>()
        val env = mutableMapOf<String, String>()

        // ---- Dockerfile：最直接的装包清单 ----
        files.filterKeys { it.contains("Dockerfile") }.forEach { (_, c) ->
            Regex("""apt-get install([^\n&|]+)""").findAll(c).forEach { m ->
                val line = m.groupValues[1].trim()
                raw += "apt-get install $line"
                parsePkgs(line, apt)
            }
            Regex("""apk add([^\n&|]+)""").findAll(c).forEach { m ->
                raw += "apk add ${m.groupValues[1].trim()}"
            }
            // ENV 声明
            Regex("""ENV\s+(\w+)[=\s]+("?)([^\s"]+)\2""").findAll(c).forEach { m ->
                env[m.groupValues[1]] = m.groupValues[3]
            }
        }

        // ---- CI workflow：作者验证过的装包 ----
        files.filterKeys { it.contains(".github/workflows") }.forEach { (_, c) ->
            Regex("""apt-get install\s+(-y\s+)?([^\n&|]+)""").findAll(c).forEach { m ->
                parsePkgs(m.groupValues[2], apt)
            }
            Regex("""sudo apt install\s+(-y\s+)?([^\n&|]+)""").findAll(c).forEach { m ->
                parsePkgs(m.groupValues[2], apt)
            }
        }

        // ---- 平台识别 ----
        val paths = files.keys
        if (paths.any { it.endsWith("CMakeLists.txt") }) platforms += "CMake"
        if (paths.any { it == "Cargo.toml" || it.endsWith("/Cargo.toml") }) platforms += "Rust"
        if (paths.any { it == "go.mod" || it.endsWith("/go.mod") }) platforms += "Go"
        if (paths.any { it.endsWith("build.gradle.kts") || it.endsWith("build.gradle") }) platforms += "Android/Gradle"
        if (paths.any { it.endsWith("package.json") }) platforms += "Node"
        if (paths.any { it.endsWith("pyproject.toml") || it.endsWith("setup.py") }) platforms += "Python"
        if (paths.any { it.endsWith(".sln") || it.endsWith(".csproj") }) platforms += "dotnet"
        if (paths.any { it.endsWith("pubspec.yaml") }) platforms += "Flutter"
        if (paths.any { it == "Package.swift" }) platforms += "Swift"
        if (paths.any { it.endsWith("pom.xml") }) platforms += "Maven"
        if (paths.any { it == "meson.build" || it.endsWith("/meson.build") }) platforms += "Meson"
        if (paths.any { it == "WORKSPACE" || it == "MODULE.bazel" }) platforms += "Bazel"

        // ---- 构建前准备 ----
        if (paths.any { it == "configure" || it == "configure.ac" }) {
            pre += "./configure"
            platforms += "Autotools"
        }
        if (paths.any { it == "autogen.sh" }) pre += "./autogen.sh"

        // ---- 按平台补默认系统包 ----
        if ("CMake" in platforms) {
            apt += "cmake"
            apt += "ninja-build"
            apt += "build-essential"
        }
        if ("Android/Gradle" in platforms) {
            apt += "openjdk-17-jdk"
        }
        if ("Rust" in platforms) {
            apt += "pkg-config"
        }
        if ("Flutter" in platforms) {
            apt += "clang"
            apt += "cmake"
            apt += "ninja-build"
            apt += "pkg-config"
            apt += "libgtk-3-dev"
        }

        return Setup(
            aptPackages = apt.toList(),
            rawInstallLines = raw.distinct(),
            preBuildCommands = pre.toList(),
            platforms = platforms.toList(),
            envVars = env
        )
    }

    private fun parsePkgs(line: String, out: MutableSet<String>) {
        line.split(Regex("\\s+")).forEach { tok ->
            val t = tok.trim()
            if (t.isBlank()) return@forEach
            if (t in aptNoise) return@forEach
            if (t.startsWith("-")) return@forEach
            if (t.startsWith("#")) return@forEach
            // 去掉行尾的续行符
            out += t.removeSuffix("\\")
        }
    }
}
