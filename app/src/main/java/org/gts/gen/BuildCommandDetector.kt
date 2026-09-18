package org.gts.gen

import org.gts.model.Confidence

/**
 * 从扫描到的文件推断构建命令。
 *
 * 优先级（高到低）：
 *  1. 仓库自己的 CI 配置里写死的 run 命令 —— 作者验证过，最权威
 *  2. Android（android/gradlew）
 *  3. 根目录 Gradle
 *  4. 项目自带构建脚本 b.sh / build.sh
 *  5. CMake
 *  6. Cargo / Go / Node / Python
 *  7. Meson / Bazel / Autotools / .NET / Flutter
 *  8. Makefile（放最后，常是生成产物）
 *  9. Dockerfile
 *
 * 所有涉及 ./gradlew 的分支都先确认该文件真的在仓库里，
 * 因为很多项目 .gitignore 掉了 gradlew，只留 wrapper 目录。
 */
object BuildCommandDetector {

    data class Result(
        val command: String,
        val reason: String,
        val confidence: Confidence
    )

    private const val JOBS = "\$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

    fun detect(files: Map<String, String>): Result {
        val paths = files.keys

        // 1. 先看仓库自己的 CI 写的是什么命令。
        //    这是最可靠的来源，作者已经验证过能在干净环境跑通。
        fromCi(files)?.let { return it }

        // 2. Android
        if (paths.any { it.startsWith("android/") &&
                (it.endsWith("build.gradle.kts") || it.endsWith("build.gradle")) }) {
            val hasWrapper = paths.any { it == "android/gradlew" }
            return Result(
                if (hasWrapper) "cd android && ./gradlew assembleDebug --no-daemon"
                else "cd android && gradle assembleDebug --no-daemon",
                if (hasWrapper) "android/ 下有 gradle 配置和 gradlew"
                else "android/ 下有 gradle 配置，但没有 gradlew，改用系统 gradle",
                Confidence.HIGH
            )
        }

        // 3. 根目录 Gradle
        if (paths.any { it == "build.gradle.kts" || it == "build.gradle" }) {
            val hasWrapper = paths.any { it == "gradlew" }
            return Result(
                if (hasWrapper) "./gradlew build --no-daemon"
                else "gradle build --no-daemon",
                if (hasWrapper) "根目录有 build.gradle 和 gradlew"
                else "根目录有 build.gradle，但没有 gradlew，改用系统 gradle",
                Confidence.HIGH
            )
        }

        // 4. 项目自带构建脚本。PPSSPP 这类项目用 b.sh，
        //    但通常需要参数（如 --headless），这里给出提醒。
        paths.firstOrNull { it == "b.sh" || it == "build.sh" }?.let {
            return Result(
                "./$it",
                "检测到 $it。若它需要参数（如 --headless），请在下面手工补上",
                Confidence.MEDIUM
            )
        }

        // 5. CMake
        if (paths.any { it.endsWith("CMakeLists.txt") }) {
            return Result(
                "cmake -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j$JOBS",
                "检测到 CMakeLists.txt",
                Confidence.HIGH
            )
        }

        // 6. 各语言
        if (paths.any { it == "Cargo.toml" }) {
            return Result("cargo build --release", "检测到 Cargo.toml", Confidence.HIGH)
        }
        if (paths.any { it == "go.mod" }) {
            return Result("go build ./...", "检测到 go.mod", Confidence.HIGH)
        }
        files["package.json"]?.let { content ->
            val hasBuild = Regex(""""build"\s*:""").containsMatchIn(content)
            val pm = detectNodePm(files)
            return if (hasBuild) {
                Result("$pm run build", "package.json 里有 build 脚本", Confidence.HIGH)
            } else {
                Result("$pm install", "package.json 里没有 build 脚本", Confidence.MEDIUM)
            }
        }
        if (paths.any { it == "pyproject.toml" }) {
            val usesPoetry = files["pyproject.toml"]?.contains("[tool.poetry]") == true
            val usesHatch = files["pyproject.toml"]?.contains("[tool.hatch]") == true
            val cmd = when {
                usesPoetry -> "poetry build"
                usesHatch -> "python -m build"
                else -> "python -m build"
            }
            return Result(cmd, "检测到 pyproject.toml", Confidence.MEDIUM)
        }
        if (paths.any { it == "setup.py" }) {
            return Result("pip install .", "检测到 setup.py", Confidence.MEDIUM)
        }

        // 7. 其他构建系统
        if (paths.any { it == "meson.build" }) {
            return Result(
                "meson setup build && meson compile -C build",
                "检测到 meson.build", Confidence.HIGH
            )
        }
        if (paths.any { it == "WORKSPACE" || it == "WORKSPACE.bazel" || it == "MODULE.bazel" }) {
            return Result("bazel build //...", "检测到 Bazel 工作区文件", Confidence.HIGH)
        }
        if (paths.any { it == "configure" || it == "configure.ac" || it == "Makefile.am" }) {
            return Result(
                "./configure && make -j$JOBS",
                "检测到 Autotools（configure/configure.ac/Makefile.am）",
                Confidence.MEDIUM
            )
        }
        if (paths.any { it.endsWith(".sln") || it.endsWith(".csproj") }) {
            return Result("dotnet build -c Release", "检测到 .NET 项目文件", Confidence.HIGH)
        }
        if (paths.any { it == "pubspec.yaml" }) {
            return Result("flutter build apk --release", "检测到 pubspec.yaml", Confidence.MEDIUM)
        }
        if (paths.any { it == "Package.swift" }) {
            return Result("swift build -c release", "检测到 Package.swift", Confidence.HIGH)
        }
        if (paths.any { it == "pom.xml" }) {
            return Result("mvn -B package -DskipTests", "检测到 pom.xml", Confidence.HIGH)
        }

        // 8. Makefile，放最后
        if (paths.any { it == "Makefile" || it == "makefile" || it == "GNUmakefile" }) {
            return Result("make -j$JOBS", "检测到 Makefile", Confidence.MEDIUM)
        }

        // 9. Dockerfile
        if (paths.any { it.endsWith("Dockerfile") }) {
            return Result("docker build .", "检测到 Dockerfile", Confidence.LOW)
        }

        return Result("ls -la", "未识别构建系统，仅列出文件", Confidence.LOW)
    }

    /**
     * 从 .github/workflows/*.yml 里提取作者写的构建命令。
     *
     * 只挑看起来像构建步骤的 run：跳过 apt install、checkout、setup 之类的。
     * 取第一条命中的，因为工作流通常按顺序写，构建在中后段。
     */
    private fun fromCi(files: Map<String, String>): Result? {
        val workflows = files.filterKeys { it.contains(".github/workflows") }
        if (workflows.isEmpty()) return null

        val skipWords = listOf(
            "apt-get install", "apt install", "apk add", "brew install",
            "pip install", "npm ci", "npm install", "yarn install", "pnpm install",
            "sdkmanager", "echo ", "git submodule", "chmod ", "mkdir ",
            "curl ", "wget ", "sudo ", "export ", "cd "
        )

        for ((path, content) in workflows) {
            // 匹配 run: | 块 或 run: xxx 单行
            val lines = content.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                if (trimmed.startsWith("run:")) {
                    val after = trimmed.removePrefix("run:").trim()
                    val cmd = if (after == "|" || after == ">" || after == "|-") {
                        // 多行块：收集后续缩进更深的行
                        val baseIndent = line.indexOf("run:")
                        val sb = StringBuilder()
                        var j = i + 1
                        while (j < lines.size) {
                            val l = lines[j]
                            if (l.isBlank()) { j++; continue }
                            val ind = l.indexOfFirst { !it.isWhitespace() }
                            if (ind <= baseIndent) break
                            sb.appendLine(l.trim())
                            j++
                        }
                        i = j
                        sb.toString().trim()
                    } else {
                        after.trim('\'', '"')
                    }

                    if (cmd.isNotBlank()) {
                        // 判断这条是否值得作为构建命令
                        val firstLine = cmd.lines().first().trim()
                        val looksLikeBuild = firstLine.isNotBlank() &&
                            skipWords.none { firstLine.startsWith(it) } &&
                            (firstLine.contains("build") ||
                             firstLine.contains("make") ||
                             firstLine.contains("cmake") ||
                             firstLine.contains("gradle") ||
                             firstLine.contains("cargo") ||
                             firstLine.contains("go build") ||
                             firstLine.contains("mvn") ||
                             firstLine.contains("meson") ||
                             firstLine.contains("bazel") ||
                             firstLine.contains("dotnet") ||
                             firstLine.contains("./"))

                        if (looksLikeBuild) {
                            // 多行块拼成 && 连接的单行，方便放进 run: |
                            val oneLine = cmd.lines()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .joinToString(" && ")
                            return Result(
                                oneLine,
                                "取自仓库自己的 $path",
                                Confidence.HIGH
                            )
                        }
                    }
                } else {
                    i++
                }
            }
        }
        return null
    }

    private fun detectNodePm(files: Map<String, String>): String = when {
        files.containsKey("pnpm-lock.yaml") -> "pnpm"
        files.containsKey("yarn.lock") -> "yarn"
        files.containsKey("bun.lockb") || files.containsKey("bun.lock") -> "bun"
        else -> "npm"
    }
}
