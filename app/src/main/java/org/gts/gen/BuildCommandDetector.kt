package org.gts.gen

import org.gts.model.Confidence

/**
 * 从扫描到的文件推断构建命令。
 *
 * 判定顺序从「最专有」到「最通用」：
 * 一个仓库可能同时有 CMakeLists.txt 和 Makefile，但 Makefile 往往是
 * CMake 生成的产物，所以 CMake 优先。Android 项目同时有 build.gradle.kts
 * 和 gradlew，Android 优先。
 */
object BuildCommandDetector {

    data class Result(
        val command: String,
        /** 为什么选这条命令，显示给用户看 */
        val reason: String,
        val confidence: Confidence
    )

    private const val JOBS = "\$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

    fun detect(files: Map<String, String>): Result {
        val paths = files.keys

        // 1. Android：android/ 下有 gradle 配置
        if (paths.any { it.startsWith("android/") && it.endsWith("build.gradle.kts") } ||
            paths.any { it.startsWith("android/") && it.endsWith("build.gradle") }) {
            return Result(
                "cd android && ./gradlew assembleDebug --no-daemon",
                "检测到 android/build.gradle(.kts)",
                Confidence.HIGH
            )
        }

        // 2. 根目录 Gradle
        if (paths.any { it == "build.gradle.kts" || it == "build.gradle" }) {
            val wrapper = if (paths.any { it.startsWith("gradle/wrapper/") })
                "./gradlew build --no-daemon"
            else
                "gradle build --no-daemon"
            return Result(wrapper, "检测到根目录 build.gradle(.kts)", Confidence.HIGH)
        }

        // 3. 项目自带的构建脚本（PPSSPP 这类）
        paths.firstOrNull { it == "b.sh" || it == "build.sh" }?.let {
            return Result("./$it", "检测到项目自带构建脚本 $it", Confidence.HIGH)
        }

        // 4. CMake
        if (paths.any { it.endsWith("CMakeLists.txt") }) {
            return Result(
                "cmake -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j$JOBS",
                "检测到 CMakeLists.txt",
                Confidence.HIGH
            )
        }

        // 5. Rust
        if (paths.any { it == "Cargo.toml" }) {
            return Result("cargo build --release", "检测到 Cargo.toml", Confidence.HIGH)
        }

        // 6. Go
        if (paths.any { it == "go.mod" }) {
            return Result("go build ./...", "检测到 go.mod", Confidence.HIGH)
        }

        // 7. Node
        files["package.json"]?.let { content ->
            val hasBuild = Regex(""""build"\s*:""").containsMatchIn(content)
            val pm = detectNodePm(files)
            return if (hasBuild) {
                Result("$pm run build", "package.json 里有 build 脚本", Confidence.HIGH)
            } else {
                Result("$pm install", "package.json 里没有 build 脚本", Confidence.MEDIUM)
            }
        }

        // 8. Python
        if (paths.any { it == "pyproject.toml" || it == "setup.py" }) {
            return Result("pip install .", "检测到 pyproject.toml 或 setup.py", Confidence.MEDIUM)
        }

        // 9. Makefile（放最后，因为常是生成产物）
        if (paths.any { it == "Makefile" || it == "makefile" || it == "GNUmakefile" }) {
            return Result("make", "检测到 Makefile", Confidence.MEDIUM)
        }

        // 10. Dockerfile
        if (paths.any { it.endsWith("Dockerfile") }) {
            return Result("docker build .", "检测到 Dockerfile", Confidence.LOW)
        }

        return Result("ls -la", "未识别构建系统，仅列出文件", Confidence.LOW)
    }

    /** 按 lockfile 判断用哪个包管理器 */
    private fun detectNodePm(files: Map<String, String>): String = when {
        files.containsKey("pnpm-lock.yaml") -> "pnpm"
        files.containsKey("yarn.lock") -> "yarn"
        files.containsKey("bun.lockb") || files.containsKey("bun.lock") -> "bun"
        else -> "npm"
    }
}
