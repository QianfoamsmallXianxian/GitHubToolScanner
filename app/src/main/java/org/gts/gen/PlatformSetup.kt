package org.gts.gen

/**
 * 从仓库自己的 Dockerfile / CI / 构建脚本里抄「平台构建配置」。
 *
 * 关键点：这些是仓库作者亲自写过的、验证过能跑的命令，
 * 比瞎猜的系统包列表准确得多。
 *
 * 注意：detect() 必须带 target。同一个包名在 apt/choco/brew 之间不通用，
 * 不做过滤的话，选 Windows 会去 choco install libgtk-3-dev（不存在）。
 */
object PlatformSetup {

    data class Setup(
        /** 系统包：已按目标平台转换成该平台的包名 */
        val sysPackages: List<String>,
        /** 原始装包命令（只对 APT 目标有意义） */
        val rawInstallLines: List<String>,
        /** 构建前的准备命令 */
        val preBuildCommands: List<String>,
        /** 识别到的语言/构建系统 */
        val platforms: List<String>,
        /** 环境变量建议 */
        val envVars: Map<String, String>
    )

    private val aptNoise = setOf(
        "sudo", "apt", "apt-get", "update", "install", "-y", "-qq",
        "&&", "|", "\\", "--no-install-recommends", "-q", "apk", "add",
        "brew", "choco", "dnf", "yum", "pacman"
    )

    /**
     * Debian 系包名 -> 其它平台包名的映射。
     * 只覆盖常见几个；映射不到的一律丢弃，绝不让它跑到别的包管理器上。
     */
    private val toBrew = mapOf(
        "cmake" to "cmake",
        "ninja-build" to "ninja",
        "build-essential" to "make",
        "pkg-config" to "pkg-config",
        "openjdk-17-jdk" to "openjdk@17",
        "clang" to "llvm",
        "libgtk-3-dev" to "gtk+3",
        "libgl1-mesa-dev" to "mesa",
        "libglu1-mesa-dev" to "glu",
        "libcurl4-openssl-dev" to "curl",
        "libpng-dev" to "libpng",
        "libfreetype-dev" to "freetype",
        "zlib1g-dev" to "zlib",
        "libfontconfig1-dev" to "fontconfig",
        "libwayland-dev" to "wayland",
        "libx11-dev" to "xorgproto"
    )

    private val toChoco = mapOf(
        "cmake" to "cmake",
        "ninja-build" to "ninja",
        "build-essential" to "make",
        "pkg-config" to "pkgconfiglite",
        "openjdk-17-jdk" to "temurin17",
        "clang" to "llvm",
        "python3" to "python3"
    )

    fun detect(files: Map<String, String>, target: CiTarget): Setup {
        val apt = linkedSetOf<String>()
        val raw = mutableListOf<String>()
        val pre = linkedSetOf<String>()
        val platforms = linkedSetOf<String>()
        val env = mutableMapOf<String, String>()

        // ---- Dockerfile ----
        files.filterKeys { it.contains("Dockerfile") }.forEach { (_, c) ->
            Regex("""apt-get install([^\n&|]+)""").findAll(c).forEach { m ->
                val line = m.groupValues[1].trim()
                raw += "apt-get install $line"
                parsePkgs(line, apt)
            }
            Regex("""apk add([^\n&|]+)""").findAll(c).forEach { m ->
                raw += "apk add ${m.groupValues[1].trim()}"
            }
            Regex("""ENV\s+(\w+)[=\s]+("?)([^\s"]+)\2""").findAll(c).forEach { m ->
                env[m.groupValues[1]] = m.groupValues[3]
            }
        }

        // ---- CI workflow ----
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
        if (paths.any { it.endsWith(".sln") || it.endsWith(".vcxproj") }) platforms += "MSBuild"
        if (paths.any { it.endsWith("pubspec.yaml") }) platforms += "Flutter"
        if (paths.any { it == "Package.swift" }) platforms += "Swift"
        if (paths.any { it.endsWith("pom.xml") }) platforms += "Maven"
        if (paths.any { it == "meson.build" || it.endsWith("/meson.build") }) platforms += "Meson"
        if (paths.any { it == "WORKSPACE" || it == "MODULE.bazel" }) platforms += "Bazel"

        if (paths.any { it == "configure" || it == "configure.ac" }) {
            pre += "./configure"
            platforms += "Autotools"
        }
        if (paths.any { it == "autogen.sh" }) pre += "./autogen.sh"

        // ---- 按识别到的构建系统补默认包（Debian 名） ----
        if ("CMake" in platforms) {
            apt += "cmake"; apt += "ninja-build"; apt += "build-essential"
        }
        if ("Meson" in platforms) {
            apt += "meson"; apt += "ninja-build"
        }
        if ("Android/Gradle" in platforms) {
            apt += "openjdk-17-jdk"
        }
        if ("Rust" in platforms) {
            apt += "pkg-config"
        }
        if ("Flutter" in platforms) {
            apt += "clang"; apt += "cmake"; apt += "ninja-build"
            apt += "pkg-config"; apt += "libgtk-3-dev"
        }

        // ---- 关键一步：按目标平台转换包名 ----
        val converted: List<String> = when (target.pkg) {
            PkgManager.APT -> apt.toList()
            PkgManager.BREW -> apt.mapNotNull { toBrew[it] }.distinct()
            PkgManager.CHOCO -> apt.mapNotNull { toChoco[it] }.distinct()
        }

        // 非 APT 目标下，原始 apt 行没有意义，清空
        val rawForTarget = if (target.pkg == PkgManager.APT) raw.distinct() else emptyList()

        return Setup(
            sysPackages = converted,
            rawInstallLines = rawForTarget,
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
            out += t.removeSuffix("\\")
        }
    }
}
