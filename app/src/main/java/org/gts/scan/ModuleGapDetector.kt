package org.gts.scan

/**
 * 检测仓库里「该有但没有」的模块和文件。
 *
 * 对「源码残缺」的响应：仓库被裁剪、clone 漏子模块、.gitignore 排除关键文件。
 * 基于完整文件树 + 已抓到的声明文件内容推断缺什么。
 */
object ModuleGapDetector {

    data class Gap(
        val what: String,
        val why: String,
        val fix: String,
        val severity: Severity
    )

    enum class Severity { BLOCKER, WARNING, INFO }

    fun detect(scan: RepoScanner.ScanResult): List<Gap> {
        val gaps = mutableListOf<Gap>()
        val allPaths = scan.tree.map { it.path }
        val paths = allPaths.toSet()
        val dirs = scan.tree.filter { it.isDir }.map { it.path }.toSet()
        val files = scan.files

        // ---- 子模块 ----
        // 注意：GitHub Trees API 里，已提交的子模块是 type="commit" 的单条目，
        // 路径就是子模块名本身（如 ext/glslang），没有尾部斜杠。
        // 所以必须把「条目本身存在」也算作内容存在，否则每个正常子模块都会被误报。
        files[".gitmodules"]?.let { gm ->
            val declared = Regex("""path\s*=\s*(\S+)""")
                .findAll(gm).map { it.groupValues[1] }.toList()
            for (sub in declared) {
                val hasContent =
                    sub in paths ||
                    sub in dirs ||
                    allPaths.any { it.startsWith("$sub/") }
                if (!hasContent) {
                    gaps += Gap(
                        what = sub,
                        why = ".gitmodules 声明了子模块 $sub，但仓库树里没有它的条目",
                        fix = "git submodule update --init --recursive",
                        severity = Severity.BLOCKER
                    )
                }
            }
        }

        // ---- Gradle wrapper ----
        val wrapperProps = allPaths.filter { it.endsWith("gradle-wrapper.properties") }
        for (wp in wrapperProps) {
            val root = wp.removeSuffix("gradle/wrapper/gradle-wrapper.properties")
            val gradlew = "${root}gradlew"
            if (gradlew !in paths) {
                gaps += Gap(
                    what = gradlew,
                    why = "有 $wp 但缺 $gradlew。很多仓库把它 .gitignore 了，" +
                          "但 CI 里 ./gradlew 会找不到",
                    fix = "改用系统 gradle，或重新生成 wrapper",
                    severity = Severity.WARNING
                )
            }
        }

        // ---- CMake add_subdirectory 引用的目录 ----
        files.filterKeys { it.endsWith("CMakeLists.txt") }.forEach { (cmPath, content) ->
            val base = cmPath.removeSuffix("CMakeLists.txt")
            Regex("""add_subdirectory\s*\(\s*([^\s)]+)""")
                .findAll(content).map { it.groupValues[1] }.toList()
                .forEach { r ->
                    // 跳过 CMake 变量引用，如 ${FOO}
                    if (r.startsWith("\${")) return@forEach
                    val target = "$base$r"
                    val exists = target in dirs || target in paths ||
                        allPaths.any { it.startsWith("$target/") }
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
        val goModPath = allPaths.firstOrNull { it == "go.mod" || it.endsWith("/go.mod") }
        if (goModPath != null) {
            val dir = goModPath.removeSuffix("go.mod")
            val goSum = "${dir}go.sum"
            val content = files[goModPath].orEmpty()
            if (goSum !in paths && content.contains("require")) {
                gaps += Gap(
                    what = goSum,
                    why = "$goModPath 有 require 但缺 $goSum",
                    fix = "go mod download && go mod tidy",
                    severity = Severity.WARNING
                )
            }
        }

        // ---- Node 锁文件 ----
        val pkgJsons = allPaths.filter { it.endsWith("package.json") }
        val lockFiles = allPaths.filter {
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
        val pyproject = allPaths.firstOrNull { it == "pyproject.toml" || it.endsWith("/pyproject.toml") }
        if (pyproject != null) {
            val dir = pyproject.removeSuffix("pyproject.toml")
            val lock = "${dir}poetry.lock"
            val content = files[pyproject].orEmpty()
            if (lock !in paths && content.contains("[tool.poetry]")) {
                gaps += Gap(
                    what = lock,
                    why = "$pyproject 用 Poetry 但缺 poetry.lock",
                    fix = "poetry lock",
                    severity = Severity.WARNING
                )
            }
        }

        // ---- Dockerfile COPY 的本地路径 ----
        files.filterKeys { it.contains("Dockerfile") }.forEach { (dkPath, content) ->
            val dir = dkPath.substringBeforeLast('/', "")
            Regex("""^COPY\s+(?:--\S+\s+)*([^\s]+)""", RegexOption.MULTILINE)
                .findAll(content).map { it.groupValues[1] }.toList()
                .forEach { c ->
                    if (c == "." || c.startsWith("\$")) return@forEach
                    val target = if (dir.isEmpty()) c else "$dir/$c"
                    val exists = target in paths || target in dirs ||
                        allPaths.any { it.startsWith("$target/") }
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
