package org.gts.gen

import org.gts.model.Confidence
import org.gts.model.ToolReq

enum class PkgManager { APT, BREW, CHOCO }

/**
 * 目标平台。
 *
 * UBUNTU / MACOS：通用原生构建
 * ANDROID：Android APK，走 ubuntu runner + SDK/NDK + Gradle
 * WINDOWS：windows runner + choco + MSBuild/CMake
 *
 * 曾经有 ALPINE，已移除：alpine 容器里 actions/checkout 依赖的 node20
 * 是 glibc 构建，跑不起来。
 */
enum class CiTarget(val runner: String, val pkg: PkgManager, val label: String) {
    UBUNTU("ubuntu-latest", PkgManager.APT, "Ubuntu (apt)"),
    ANDROID("ubuntu-latest", PkgManager.APT, "Android APK"),
    WINDOWS("windows-latest", PkgManager.CHOCO, "Windows"),
    MACOS("macos-latest", PkgManager.BREW, "macOS (brew)");

    val isAndroid get() = this == ANDROID
    val isWindows get() = this == WINDOWS
}

class WorkflowGenerator {

    data class Classified(
        val sysPkgs: List<String>,
        val setupSteps: List<String>,
        val specialSteps: List<String>,
        val unresolved: List<String>,
        val needsSubmodules: Boolean,
        /** Android SDK 组件，形如 platforms;android-34 */
        val sdkPackages: List<String>,
        val needsAndroidSdk: Boolean,
        /** Android NDK 版本，如 29.0.14206865 */
        val ndkVersion: String?,
        /** 需要 MSBuild（Windows 上有 .sln） */
        val needsMsbuild: Boolean
    )

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
        "cmake" to "cmake", "git" to "git", "ninja" to "ninja",
        "make" to "make", "pkg-config" to "pkg-config",
        "python" to "python@3.12", "jdk" to "openjdk@17",
        "libsdl3-dev" to "sdl3", "libsdl3-ttf-dev" to "sdl3_ttf",
        "libpng-dev" to "libpng", "libfreetype-dev" to "freetype",
        "libcurl4-openssl-dev" to "curl", "libfontconfig1-dev" to "fontconfig"
    )

    /** Windows 用 choco。只映射确实需要单独装的。 */
    private val chocoTable = mapOf(
        "cmake" to "cmake",
        "ninja" to "ninja",
        "python" to "python3",
        "pkg-config" to "pkgconfiglite",
        "make" to "make"
    )

    private val cmakeGuess = mapOf(
        "sdl3" to "libsdl3-dev", "sdl3_ttf" to "libsdl3-ttf-dev",
        "curl" to "libcurl4-openssl-dev", "zlib" to "zlib1g-dev",
        "png" to "libpng-dev", "freetype" to "libfreetype-dev",
        "wayland" to "libwayland-dev", "x11" to "libx11-dev",
        "opengl" to "libgl1-mesa-dev", "threads" to "make"
    )

    /** Android 走 NDK 工具链，这些系统库不需要单独装 */
    private val androidSkipPkgs = setOf(
        "libsdl3-dev", "libsdl3-ttf-dev", "libgl1-mesa-dev", "libglu1-mesa-dev",
        "libcurl4-openssl-dev", "libfontconfig1-dev", "libwayland-dev", "libx11-dev"
    )

    private fun mapPkg(tool: String, t: CiTarget): String? = when (t.pkg) {
        PkgManager.APT -> aptTable[tool]
        PkgManager.BREW -> brewTable[tool]
        PkgManager.CHOCO -> chocoTable[tool]
    }

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

    fun classify(reqs: List<ToolReq>, target: CiTarget, files: Map<String, String> = emptyMap()): Classified {
        val unresolved = linkedSetOf<String>()
        val sysPkgs = linkedSetOf<String>()
        val setupSteps = mutableListOf<String>()
        val specialSteps = mutableListOf<String>()
        val sdkPackages = linkedSetOf<String>()
        var needsSubmodules = false
        var needsAndroidSdk = false
        var ndkVersion: String? = null
        val paths = files.keys

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
                    ndkVersion = ver ?: "r29"
                    specialSteps += actionStep("Set up Android NDK $ver", "nttld/setup-ndk@v1", "ndk-version", ver ?: "r29")
                }
                "gradle" -> {
                    val v = cleanVer(ver).ifBlank { "8.9" }
                    specialSteps += actionStep("Set up Gradle $v", "gradle/actions/setup-gradle@v4", "gradle-version", v)
                }
                "android-sdk-platform" -> {
                    val v = cleanVer(ver).ifBlank { "34" }
                    sdkPackages += "platforms;android-$v"
                    needsAndroidSdk = true
                }
                "android-build-tools" -> {
                    val v = cleanVer(ver).ifBlank { "34.0.0" }
                    sdkPackages += "build-tools;$v"
                    needsAndroidSdk = true
                }
                "android-sdk-target", "android-sdk-min" -> { }
                "android-gradle-plugin", "kotlin" -> { }
                "submodule" -> needsSubmodules = true
                "docker-base" -> { }
                else -> {
                    // Android 目标下，NDK 自带工具链，跳过系统图形/网络库
                    if (target.isAndroid && tool in androidSkipPkgs) continue

                    val pkg = mapPkg(tool, target)
                    if (pkg != null) {
                        sysPkgs += pkg
                    } else if (req.confidence == Confidence.LOW) {
                        val g = cmakeGuess[tool]
                        val mapped = g?.let { mapPkg(it, target) ?: it }
                        if (mapped != null && !(target.isAndroid && mapped in androidSkipPkgs))
                            sysPkgs += mapped
                        else if (mapped == null) unresolved += tool
                    } else {
                        unresolved += tool
                    }
                }
            }
        }

        // Android 目标：确保有 SDK 和 JDK
        if (target.isAndroid) {
            if (sdkPackages.isEmpty()) {
                sdkPackages += "platforms;android-34"
                sdkPackages += "build-tools;34.0.0"
                needsAndroidSdk = true
            }
            val hasJdk = setupSteps.any { it.contains("setup-java") }
            if (!hasJdk) {
                setupSteps.add(0, buildString {
                    appendLine("      - name: Set up JDK 17")
                    appendLine("        uses: actions/setup-java@v4")
                    appendLine("        with:")
                    appendLine("          distribution: temurin")
                    appendLine("          java-version: '17'")
                }.trimEnd())
            }
        }

        // Windows 目标：有 .sln 就用 MSBuild
        val needsMsbuild = target.isWindows && paths.any {
            it.endsWith(".sln") || it.endsWith(".vcxproj")
        }

        return Classified(
            sysPkgs = sysPkgs.toList(),
            setupSteps = setupSteps,
            specialSteps = specialSteps,
            unresolved = unresolved.toList(),
            needsSubmodules = needsSubmodules,
            sdkPackages = sdkPackages.toList(),
            needsAndroidSdk = needsAndroidSdk,
            ndkVersion = ndkVersion,
            needsMsbuild = needsMsbuild
        )
    }

    fun generate(
        reqs: List<ToolReq>,
        target: CiTarget,
        buildCommand: String,
        useSubmodules: Boolean,
        files: Map<String, String> = emptyMap(),
        gaps: List<org.gts.scan.ModuleGapDetector.Gap> = emptyList()
    ): Plan {
        val gapSteps = GapSteps.build(gaps)
        val platformSetup = PlatformSetup.detect(files)
        val c = classify(reqs, target, files)
        val cmd = buildCommand.ifBlank {
            when {
                target.isAndroid -> "cd android && ./gradlew assembleDebug --no-daemon"
                else -> "make"
            }
        }

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
            if (useSubmodules || c.needsSubmodules || gapSteps.needRecursiveSubmodules) {
                appendLine("        with:")
                appendLine("          submodules: recursive")
            }
            appendLine()

            c.setupSteps.forEach { appendLine(it); appendLine() }

            // 缺失项补齐：子模块 / 锁文件 / 生成脚本，全部丢给云端做
            if (gapSteps.setupCommands.isNotEmpty()) {
                appendLine("      - name: Repair missing modules")
                appendLine("        run: |")
                gapSteps.setupCommands.forEach { appendLine("          $it") }
                appendLine()

            // 平台识别 + 从 Dockerfile / CI 抄来的装包命令
            if (platformSetup.platforms.isNotEmpty()) {
                appendLine("      # 识别到的平台：" + platformSetup.platforms.joinToString(", "))
            }
            if (platformSetup.envVars.isNotEmpty()) {
                appendLine("      - name: Set platform env")
                appendLine("        run: |")
                platformSetup.envVars.forEach { (k, v) ->
                     appendLine("          echo \"$k=$v\" >> \$GITHUB_ENV")
                }
                appendLine()
            }
            if (platformSetup.rawInstallLines.isNotEmpty()) {
                appendLine("      - name: Install packages (from repo Dockerfile/CI)")
                appendLine("        run: |")
                platformSetup.rawInstallLines.forEach { appendLine("          $it") }
                appendLine()
            }
            if (platformSetup.preBuildCommands.isNotEmpty()) {
                appendLine("      - name: Pre-build setup")
                appendLine("        run: |")
                platformSetup.preBuildCommands.forEach { appendLine("          $it") }
                appendLine()
            }
            }
            gapSteps.unresolved.forEach {
                appendLine("      # 无法自动补齐：$it")
            }
            if (gapSteps.unresolved.isNotEmpty()) appendLine()

            // Windows：MSBuild
            if (target.isWindows && c.needsMsbuild) {
                appendLine("      - name: Add MSBuild to PATH")
                appendLine("        uses: microsoft/setup-msbuild@v2")
                appendLine()
            }

            c.specialSteps.forEach { appendLine(it); appendLine() }

            // Android SDK 组件
            if (c.needsAndroidSdk && target.pkg == PkgManager.APT) {
                appendLine("      - name: Install Android SDK components")
                appendLine("        run: |")
                appendLine("          SDKMANAGER=\$(command -v sdkmanager || find \"\$ANDROID_SDK_ROOT\" -name sdkmanager 2>/dev/null | head -1)")
                appendLine("          yes | \"\$SDKMANAGER\" --licenses > /dev/null 2>&1 || true")
                appendLine("          \"\$SDKMANAGER\" ${c.sdkPackages.joinToString(" ") { "\"$it\"" }}")
                appendLine()
            }

            // 系统包
            if (c.sysPkgs.isNotEmpty()) {
                when (target.pkg) {
                    PkgManager.APT -> {
                        appendLine("      - name: Install system packages")
                        appendLine("        run: |")
                        appendLine("          sudo apt-get update -qq")
                        appendLine("          sudo apt-get install -y ${c.sysPkgs.joinToString(" ")}")
                    }
                    PkgManager.BREW -> {
                        appendLine("      - name: Install system packages")
                        appendLine("        run: brew install ${c.sysPkgs.joinToString(" ")}")
                    }
                    PkgManager.CHOCO -> {
                        appendLine("      - name: Install system packages")
                        appendLine("        run: choco install ${c.sysPkgs.joinToString(" ")} -y --no-progress")
                    }
                }
                appendLine()
            }

            // Windows 上确保有 CMake 和 Ninja
            if (target.isWindows && c.sysPkgs.isEmpty() && cmd.contains("cmake")) {
                appendLine("      - name: Install build tools")
                appendLine("        run: choco install cmake ninja -y --no-progress")
                appendLine()
            }

            appendLine("      - name: Build")
            if (target.isWindows) {
                appendLine("        run: |")
                cmd.lines().forEach { appendLine("          $it") }
            } else {
                appendLine("        run: |")
                cmd.lines().forEach { appendLine("          $it") }
            }
            appendLine()

            if (c.unresolved.isNotEmpty()) {
                appendLine("      # 以下工具无法自动映射到包名，请手工补充：")
                c.unresolved.forEach { appendLine("      #   - $it") }
            }
        }

        return Plan(yaml, c.unresolved, c)
    }
}
