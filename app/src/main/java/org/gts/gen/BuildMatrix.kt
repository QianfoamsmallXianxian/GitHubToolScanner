package org.gts.gen

import org.gts.model.ToolReq

/**
 * 按平台生成「能真正编译出软件」的构建步骤。
 *
 * 与 WorkflowGenerator 的分工：
 *   WorkflowGenerator 负责装依赖（工具链）
 *   BuildMatrix 负责真正的编译命令 + 产物收集
 */
object BuildMatrix {

    data class Cfg(
        /** 构建命令（逐行写进 run: |） */
        val commands: List<String>,
        /** 产物路径，用于 upload-artifact */
        val artifacts: List<String>,
        /** 构建前准备（如 chmod +x gradlew） */
        val pre: List<String> = emptyList(),
        /** 产物名 */
        val artifactName: String = "build-output"
    )

    fun forTarget(
        target: CiTarget,
        reqs: List<ToolReq>,
        files: Map<String, String>
    ): Cfg {
        val paths = files.keys
        return when {
            target.isAndroid -> android(paths)
            target.isWindows -> windows(paths)
            target.isMacOS -> macos(target, paths)
            else -> linux(paths)
        }
    }

    // ---------- Android ----------
    private fun android(paths: Set<String>): Cfg {
        val hasWrapper = paths.any { it == "gradlew" || it == "android/gradlew" }
        val inAndroidDir = paths.any { it.startsWith("android/") }
        val root = if (inAndroidDir) "android" else ""
        val gradlew = if (root.isEmpty()) "gradlew" else "$root/gradlew"

        val pre = mutableListOf<String>()
        // 产物目录先建好，避免后续找不到
        if (hasWrapper) pre += "chmod +x $gradlew"

        // 有 wrapper 用 wrapper，没有就用系统 gradle（WorkflowGenerator 会 setup-gradle）
        val build = if (hasWrapper) {
            if (root.isEmpty()) "./$gradlew assembleDebug --no-daemon"
            else "cd $root && ./gradlew assembleDebug --no-daemon"
        } else {
            if (root.isEmpty()) "gradle assembleDebug --no-daemon"
            else "cd $root && gradle assembleDebug --no-daemon"
        }

        return Cfg(
            commands = listOf(build),
            artifacts = listOf(
                "**/build/outputs/apk/**/*.apk",
                "**/build/outputs/bundle/**/*.aab"
            ),
            pre = pre,
            artifactName = "android-apk"
        )
    }

    // ---------- Windows ----------
    private fun windows(paths: Set<String>): Cfg {
        val sln = paths.firstOrNull { it.endsWith(".sln") }
        val cmake = paths.firstOrNull { it.endsWith("CMakeLists.txt") }
        val csproj = paths.firstOrNull { it.endsWith(".csproj") }
        return when {
            sln != null -> {
                // Windows 路径用反斜杠
                val winPath = sln.replace("/", "\\")
                val cmd = "msbuild \"" + winPath + "\" /p:Configuration=Release /m"
                Cfg(
                    commands = listOf(cmd),
                    artifacts = listOf(
                        "**/Release/**/*.exe",
                        "**/Release/**/*.dll",
                        "**/x64/Release/**"
                    ),
                    artifactName = "windows-build"
                )
            }
            cmake != null -> Cfg(
                commands = listOf(
                    "cmake -B build -DCMAKE_BUILD_TYPE=Release",
                    "cmake --build build --config Release --parallel"
                ),
                artifacts = listOf("build/**/*.exe", "build/**/*.dll"),
                artifactName = "windows-build"
            )
            csproj != null -> Cfg(
                commands = listOf("dotnet build -c Release"),
                artifacts = listOf("**/bin/Release/**/*.dll", "**/bin/Release/**/*.exe"),
                artifactName = "windows-build"
            )
            else -> Cfg(
                // Windows 上没有 make。这里给明确失败提示，而不是生成跑不通的命令。
                commands = listOf(
                    "echo '未在仓库中找到 .sln / CMakeLists.txt / .csproj，无法确定构建方式'",
                    "exit 1"
                ),
                artifacts = listOf("**/*.exe", "**/*.dll"),
                artifactName = "windows-build"
            )
        }
    }

    // ---------- macOS ----------
    private fun macos(target: CiTarget, paths: Set<String>): Cfg {
        val hasBsh = paths.contains("b.sh")
        val hasCMake = paths.any { it.endsWith("CMakeLists.txt") }
        val hasCargo = paths.any { it == "Cargo.toml" || it.endsWith("/Cargo.toml") }
        val hasGo = paths.any { it == "go.mod" || it.endsWith("/go.mod") }
        val hasSwift = paths.any { it == "Package.swift" }
        val hasNpm = paths.any { it.endsWith("package.json") }
        val hasXcode = paths.any { it.endsWith(".xcodeproj") || it.endsWith(".xcworkspace") }

        val cmds = mutableListOf<String>()
        val arts: List<String>

        when {
            hasXcode -> {
                // xcodebuild 需要 scheme 名，这里只能给通用命令，用户需自行确认
                cmds += "xcodebuild -configuration Release -derivedDataPath build"
                arts = listOf("build/**/*.app", "build/**/*.dylib")
            }
            hasSwift -> {
                cmds += "swift build -c release"
                arts = listOf(".build/release/**")
            }
            hasBsh -> {
                cmds += "./b.sh"
                arts = listOf("build/**", "**/*.dylib", "**/*.a")
            }
            hasCMake -> {
                cmds += "cmake -B build -DCMAKE_BUILD_TYPE=Release"
                cmds += "cmake --build build --parallel"
                arts = listOf("build/**", "**/*.dylib")
            }
            hasCargo -> {
                cmds += "cargo build --release"
                arts = listOf("target/release/**")
            }
            hasGo -> {
                cmds += "go build ./..."
                arts = listOf("**/*.bin", "**/*.out")
            }
            hasNpm -> {
                cmds += "npm ci || npm install"
                cmds += "npm run build --if-present"
                arts = listOf("dist/**")
            }
            else -> {
                cmds += "make -j4"
                arts = listOf("**/*.dylib", "**/*.a", "**/*.bin")
            }
        }

        return Cfg(
            commands = cmds,
            artifacts = arts,
            artifactName = "macos-build"
        )
    }

    // ---------- Linux ----------
    private fun linux(paths: Set<String>): Cfg {
        val hasBsh = paths.contains("b.sh")
        val hasCMake = paths.any { it.endsWith("CMakeLists.txt") }
        val hasMeson = paths.any { it == "meson.build" || it.endsWith("/meson.build") }
        val hasCargo = paths.any { it == "Cargo.toml" || it.endsWith("/Cargo.toml") }
        val hasGo = paths.any { it == "go.mod" || it.endsWith("/go.mod") }
        val hasGradle = paths.any {
            it.endsWith("build.gradle") || it.endsWith("build.gradle.kts")
        }
        val hasNpm = paths.any { it.endsWith("package.json") }
        val hasMakefile = paths.any { it == "Makefile" || it.endsWith("/Makefile") }

        val cmds = mutableListOf<String>()
        val arts: List<String>

        when {
            hasBsh -> {
                cmds += "chmod +x b.sh"
                cmds += "./b.sh"
                arts = listOf("build/**", "**/*.so", "**/*.a")
            }
            hasCMake -> {
                cmds += "cmake -B build -DCMAKE_BUILD_TYPE=Release"
                cmds += "cmake --build build --parallel"
                arts = listOf("build/**")
            }
            hasMeson -> {
                cmds += "meson setup build"
                cmds += "meson compile -C build"
                arts = listOf("build/**")
            }
            hasCargo -> {
                cmds += "cargo build --release"
                arts = listOf("target/release/**")
            }
            hasGo -> {
                cmds += "go build ./..."
                arts = listOf("**/*.bin", "**/*.out")
            }
            hasGradle -> {
                cmds += "gradle build --no-daemon"
                arts = listOf("**/build/libs/**")
            }
            hasNpm -> {
                cmds += "npm ci || npm install"
                cmds += "npm run build --if-present"
                arts = listOf("dist/**")
            }
            hasMakefile -> {
                cmds += "make -j4"
                arts = listOf("**/*.so", "**/*.a", "**/*.bin")
            }
            else -> {
                cmds += "make -j4"
                arts = listOf("**/*.so", "**/*.a", "**/*.bin")
            }
        }

        return Cfg(
            commands = cmds,
            artifacts = arts,
            artifactName = "linux-build"
        )
    }
}
