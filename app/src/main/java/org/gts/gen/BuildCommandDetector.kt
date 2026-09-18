package org.gts.gen

import org.gts.model.Confidence

object BuildCommandDetector {

    data class Result(
        val command: String,
        val reason: String,
        val confidence: Confidence
    )

    private const val JOBS = "\$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

    // 单独提出来的正则，避免在原始字符串里嵌引号
    private val BUILD_SCRIPT_RE = Regex("\"build\"\\s*:")

    fun detect(files: Map<String, String>): Result {
        val paths = files.keys

        fromCi(files)?.let { return it }

        // Android / Gradle
        val gradlePath = paths.firstOrNull {
            it.endsWith("build.gradle.kts") || it.endsWith("build.gradle")
        }
        if (gradlePath != null) {
            val root = gradlePath.substringBeforeLast('/', "")
            val pre = if (root.isEmpty()) "" else "cd $root && "
            val gradlew = if (root.isEmpty()) "gradlew" else "$root/gradlew"
            val hasWrapper = gradlew in paths
            val task = if (root == "android") "assembleDebug" else "build"
            val cmd = if (hasWrapper) "${pre}./gradlew $task --no-daemon"
                      else "${pre}gradle $task --no-daemon"
            val why = if (hasWrapper) "检测到 $gradlePath 和 gradlew"
                      else "检测到 $gradlePath 但缺 gradlew，改用系统 gradle"
            return Result(cmd, why, Confidence.HIGH)
        }

        // 项目自带脚本
        paths.firstOrNull {
            it == "b.sh" || it == "build.sh" || it.endsWith("/b.sh") || it.endsWith("/build.sh")
        }?.let {
            return Result("./$it", "检测到构建脚本 $it，若需参数请手工补", Confidence.MEDIUM)
        }

        // CMake
        paths.firstOrNull { it.endsWith("CMakeLists.txt") }?.let { cm ->
            val dir = cm.removeSuffix("CMakeLists.txt").trimEnd('/')
            return if (dir.isEmpty()) {
                Result("cmake -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j$JOBS",
                    "检测到根 CMakeLists.txt", Confidence.HIGH)
            } else {
                Result("cmake -S $dir -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j$JOBS",
                    "检测到 $cm", Confidence.HIGH)
            }
        }

        if (paths.any { it == "Cargo.toml" || it.endsWith("/Cargo.toml") })
            return Result("cargo build --release", "检测到 Cargo.toml", Confidence.HIGH)
        if (paths.any { it == "go.mod" || it.endsWith("/go.mod") })
            return Result("go build ./...", "检测到 go.mod", Confidence.HIGH)

        // Node
        files.entries.firstOrNull { it.key.endsWith("package.json") }?.let { (p, content) ->
            val hasBuild = BUILD_SCRIPT_RE.containsMatchIn(content)
            val pm = detectNodePm(files, p)
            val dir = p.substringBeforeLast('/', "")
            val pre = if (dir.isEmpty()) "" else "cd $dir && "
            return if (hasBuild)
                Result("${pre}$pm run build", "$p 里有 build 脚本", Confidence.HIGH)
            else
                Result("${pre}$pm install", "$p 里没有 build 脚本", Confidence.MEDIUM)
        }

        if (paths.any { it == "pyproject.toml" || it.endsWith("/pyproject.toml") })
            return Result("python -m build", "检测到 pyproject.toml", Confidence.MEDIUM)
        if (paths.any { it == "setup.py" || it.endsWith("/setup.py") })
            return Result("pip install .", "检测到 setup.py", Confidence.MEDIUM)
        if (paths.any { it == "meson.build" || it.endsWith("/meson.build") })
            return Result("meson setup build && meson compile -C build", "检测到 meson.build", Confidence.HIGH)
        if (paths.any { it == "WORKSPACE" || it == "MODULE.bazel" || it.endsWith("/WORKSPACE") })
            return Result("bazel build //...", "检测到 Bazel 工作区", Confidence.HIGH)
        if (paths.any { it == "configure" || it == "configure.ac" || it == "Makefile.am" })
            return Result("./configure && make -j$JOBS", "检测到 Autotools", Confidence.MEDIUM)
        if (paths.any { it.endsWith(".sln") || it.endsWith(".csproj") })
            return Result("dotnet build -c Release", "检测到 .NET 项目", Confidence.HIGH)
        if (paths.any { it == "pubspec.yaml" || it.endsWith("/pubspec.yaml") })
            return Result("flutter build apk --release", "检测到 pubspec.yaml", Confidence.MEDIUM)
        if (paths.any { it == "Package.swift" })
            return Result("swift build -c release", "检测到 Package.swift", Confidence.HIGH)
        if (paths.any { it == "pom.xml" || it.endsWith("/pom.xml") })
            return Result("mvn -B package -DskipTests", "检测到 pom.xml", Confidence.HIGH)
        if (paths.any { it == "Makefile" || it.endsWith("/Makefile") })
            return Result("make -j$JOBS", "检测到 Makefile", Confidence.MEDIUM)
        if (paths.any { it.contains("Dockerfile") })
            return Result("docker build .", "检测到 Dockerfile", Confidence.LOW)

        return Result("ls -la", "未识别构建系统", Confidence.LOW)
    }

    private fun fromCi(files: Map<String, String>): Result? {
        val workflows = files.filterKeys { it.contains(".github/workflows") }
        if (workflows.isEmpty()) return null

        val skip = listOf(
            "apt-get install", "apt install", "apk add", "brew install",
            "pip install", "npm ci", "npm install", "yarn install", "pnpm install",
            "sdkmanager", "echo ", "git submodule", "chmod ", "mkdir ",
            "curl ", "wget ", "sudo ", "export ", "set ", "if ", "for "
        )

        for ((path, content) in workflows) {
            val lines = content.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                if (trimmed.startsWith("run:")) {
                    val after = trimmed.removePrefix("run:").trim()
                    val cmd: String
                    if (after == "|" || after == ">" || after == "|-") {
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
                        cmd = sb.toString().trim()
                    } else {
                        cmd = after.trim('\'', '"')
                        i++
                    }

                    if (cmd.isNotBlank()) {
                        val first = cmd.lines().first().trim()
                        val ok = first.isNotBlank() && skip.none { first.startsWith(it) } &&
                            (first.contains("build") || first.contains("make") ||
                             first.contains("cmake") || first.contains("gradle") ||
                             first.contains("cargo") || first.contains("go build") ||
                             first.contains("mvn") || first.contains("meson") ||
                             first.contains("bazel") || first.contains("dotnet") ||
                             first.startsWith("./"))
                        if (ok) {
                            val one = cmd.lines().map { it.trim() }
                                .filter { it.isNotBlank() }.joinToString(" && ")
                            return Result(one, "取自仓库自己的 $path", Confidence.HIGH)
                        }
                    }
                } else i++
            }
        }
        return null
    }

    private fun detectNodePm(files: Map<String, String>, pkgPath: String): String {
        val dir = pkgPath.substringBeforeLast('/', "")
        val pre = if (dir.isEmpty()) "" else "$dir/"
        return when {
            files.containsKey("${pre}pnpm-lock.yaml") -> "pnpm"
            files.containsKey("${pre}yarn.lock") -> "yarn"
            files.containsKey("${pre}bun.lockb") || files.containsKey("${pre}bun.lock") -> "bun"
            else -> "npm"
        }
    }
}
