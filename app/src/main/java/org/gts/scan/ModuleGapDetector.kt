package org.gts.scan

import org.gts.model.Confidence

/**
 * 检测仓库里「该有但没有」的模块和文件。
 *
 * 这是对「源码残缺」的响应：用户可能拿到一个被裁剪过的仓库，
 * 或者 clone 时漏了子模块，或者 .gitignore 把关键文件排除了。
 * 这里基于完整文件树 + 已抓到的声明文件内容，推断缺什么。
 */
object ModuleGapDetector {

    data class Gap(
        /** 缺什么，如 "gradlew"、"ext/glslang" */
        val what: String,
        /** 为什么会缺，依据是什么 */
        val why: String,
        /** 怎么补 */
        val fix: String,
        val severity: Severity
    )

    enum class Severity { BLOCKER, WARNING, INFO }

    fun detect(scan: RepoScanner.ScanResult): List<Gap> {
        val gaps = mutableListOf<Gap>()
        val paths = scan.tree.map { it.path }.toSet()
        val dirs = scan.tree.filter { it.isDir }.map { it.path }.toSet()
        val files = scan.files

        // ---- 子模块 ----
        files[".gitmodules"]?.let { gm ->
            val declared = Regex("""path\s*=\s*(\S+)""")
                .findAll(gm).map { it.groupValues[1] }.toList()
            for (sub in declared) {
                val hasContent = paths.any { it.startsWith("$sub/") } ||
                    dirs.any { it == sub || it.startsWith("$sub/") }
                if (!hasContent) {
                    gaps += Gap(
                        what = sub,
                        why = ".gitmodules 声明了子模块 $sub，但仓库里没有它的内容",
                        fix = "git submodule update --init --recursive",
                        severity = Severity.BLOCKER
                    )
                }
            }
        }

        // ---- Gradle wrapper ----
        val hasWrapperProps = paths.any { it.endsWith("gradle-wrapper.properties") }
        if (hasWrapperProps) {
            // 找到 wrapper 所在的根（可能是 . 或 android/）
            val wrapperRoots = paths.filter { it.endsWith("gradle/wrapper/gradle-wrapper.properties") }
                .map { it.removeSuffix("gradle/wrapper/gradle-wrapper.properties") }
            for (root in wrapperRoots) {
                val gradlew = "${root}gradlew"
                if (gradlew !in paths) {
                    gaps += Gap(
                        what = gradlew.ifBlank { "gradlew" },
                        why = "有 gradle-wrapper.properties 但缺 $gradlew。" +
                              "很多仓库把它 .gitignore 了，但 CI 里 ./gradlew 会找不到",
                        fix = "用 gradle wrapper 命令重新生成，或改用系统 gradle",
                        severity = Severity.WARNING
                    )
                }
            }
        }

        // ---- CMake 引用的子目录 ----
        files.filterKeys { it.endsWith("CMakeLists.txt") }.forEach { (cmPath, content) ->
            val base = cmPath.removeSuffix("CMakeLists.txt")
            val refs = Regex("""add_subdirectory\s*\(\s*([^\s)]+)""")
                .findAll(content).map { it.groupValues[1] }.toList()
            for (r in refs) {
                if (r.startsWith("${") || r.startsWith("\${")) continue
                val target = "$base$r"
                val exists = dirs.any { it == target || it == target.trimEnd('/') } ||
                    paths.any { it.startsWith("$target/") }
                if (!exists) {
                    gaps += Gap(
                        what = target,
                        why = "$cmPath 里 add_subdirectory($r)，但该目录不存在",
                        fix = "检查是否漏了子模块，或该目录被 .gitignore 排除",
                        severity = Severity.BLOCKER
                    )
                }
            }
        }

        // ---- Go ----
        if ("go.mod" in files && "go.sum" !in paths) {
            val usesDeps = files["go.mod"]?.contains("require") == true
            if (usesDeps) {
                gaps += Gap(
                    what = "go.sum",
                    why = "go.mod 有 require 但缺 go.sum",
                    fix = "go mod download && go mod tidy",
                    severity = Severity.WARNING
                )
            }
        }

        // ---- Node 锁文件 ----
        val pkgJsons = paths.filter { it.endsWith("package.json") }
        val lockFiles = paths.filter {
            it.endsWith("package-lock.json") || it.endsWith("yarn.lock") ||
            it.endsWith("pnpm-lock.yaml") || it.endsWith("bun.lockb") || it.endsWith("bun.lock")
        }
        if (pkgJsons.isNotEmpty() && lockFiles.isEmpty()) {
            gaps += Gap(
                what = "lockfile",
                why = "有 ${pkgJsons.size} 个 package.json 但没有任何锁文件",
                fix = "在仓库根执行 npm install 生成 package-lock.json",
                severity = Severity.INFO
            )
        }

        // ---- Python ----
        if ("pyproject.toml" in files && "poetry.lock" !in paths) {
            val usesPoetry = files["pyproject.toml"]?.contains("[tool.poetry]") == true
            if (usesPoetry) {
                gaps += Gap(
                    what = "poetry.lock",
                    why = "pyproject.toml 用 Poetry 但缺 poetry.lock",
                    fix = "poetry lock",
                    severity = Severity.WARNING
                )
            }
        }

        // ---- Dockerfile 引用的本地文件 ----
        files.filterKeys { it.contains("Dockerfile") }.forEach { (dkPath, content) ->
            val dir = dkPath.substringBeforeLast('/', "")
            val copies = Regex("""^COPY\s+(?:--\S+\s+)*([^\s]+)""", RegexOption.MULTILINE)
                .findAll(content).map { it.groupValues[1] }.toList()
            for (c in copies) {
                if (c == "." || c.startsWith("\$") || c.startsWith("--from=")) continue
                val target = if (dir.isEmpty()) c else "$dir/$c"
                val exists = paths.any { it == target || it.startsWith("$target/") }
                if (!exists) {
                    gaps += Gap(
                        what = target,
                        why = "$dkPath 里 COPY $c，但该路径不存在",
                        fix = "检查文件是否遗漏",
                        severity = Severity.WARNING
                    )
                }
            }
        }

        // ---- 树被截断 ----
        if (scan.treeTruncated) {
            gaps += Gap(
                what = "完整文件树",
                why = "仓库过大，GitHub 返回的树被截断，可能有遗漏",
                fix = "建议在 PC 上 git clone 后本地扫描",
                severity = Severity.INFO
            )
        }

        // ---- 一个声明文件都没抓到 ----
        if (files.isEmpty()) {
            gaps += Gap(
                what = "构建声明文件",
                why = "整棵树里没找到任何已知的构建声明文件",
                fix = "确认仓库是否只有源码、构建配置在别处",
                severity = Severity.INFO
            )
        }

        return gaps.distinctBy { it.what }
    }
}
