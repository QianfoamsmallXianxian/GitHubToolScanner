package org.gts.parse

import org.gts.model.Confidence
import org.gts.model.ToolReq
import org.json.JSONObject

object Parsers {

    // 凡是 """...""" 原始字符串里的正则，反斜杠只写一次。

    fun parseAll(files: Map<String, String>): List<ToolReq> {
        val out = mutableListOf<ToolReq>()
        files.forEach { (path, content) ->
            runCatching {
                when {
                    path.endsWith("package.json") -> out += parsePackageJson(path, content)
                    path == ".nvmrc" || path == ".node-version" -> out += nodeVersion(path, content)
                    path == ".tool-versions" -> out += parseToolVersions(path, content)
                    path.endsWith("go.mod") -> out += parseGoMod(path, content)
                    path.contains("rust-toolchain") -> out += parseRustToolchain(path, content)
                    path == ".python-version" -> out += pyVersion(path, content)
                    path.endsWith("pyproject.toml") -> out += parsePyProject(path, content)
                    path.endsWith("gradle-wrapper.properties") -> out += parseGradleWrapper(path, content)
                    path.endsWith("build.gradle.kts") -> out += parseGradleKts(path, content)
                    path.endsWith("CMakeLists.txt") -> out += parseCMake(path, content)
                    path.endsWith("Dockerfile") -> out += parseDockerfile(path, content)
                    path.endsWith(".gitmodules") -> out += parseGitmodules(path, content)
                    path.contains(".github/workflows") -> out += parseWorkflow(path, content)
                    else -> { }
                }
            }
        }
        return out.distinctBy { it.tool + "|" + it.version + "|" + it.source }
    }

    private fun parsePackageJson(p: String, c: String): List<ToolReq> {
        val o = JSONObject(c)
        val res = mutableListOf<ToolReq>()
        o.optJSONObject("engines")?.let { e ->
            e.keys().forEach { k ->
                val v = e.optString(k)
                if (v.isNotBlank()) res += ToolReq(k, v, p, Confidence.MEDIUM)
            }
        }
        val pm = o.optString("packageManager")
        if (pm.isNotBlank() && pm.contains("@")) {
            val idx = pm.lastIndexOf('@')
            res += ToolReq(pm.substring(0, idx), pm.substring(idx + 1), p, Confidence.HIGH)
        }
        return res
    }

    private fun nodeVersion(p: String, c: String) =
        listOf(ToolReq("node", c.trim().removePrefix("v"), p, Confidence.HIGH))

    private fun parseToolVersions(p: String, c: String): List<ToolReq> =
        c.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val parts = t.split(Regex("\\s+"))
            if (parts.size >= 2) ToolReq(parts[0], parts[1], p, Confidence.HIGH) else null
        }

    private fun parseGoMod(p: String, c: String): List<ToolReq> {
        val m = Regex("""^go\s+(\S+)""", RegexOption.MULTILINE).find(c) ?: return emptyList()
        return listOf(ToolReq("go", m.groupValues[1], p, Confidence.HIGH))
    }

    private fun parseRustToolchain(p: String, c: String): List<ToolReq> {
        val m = Regex("""channel\s*=\s*"([^"]+)""").find(c) ?: return emptyList()
        return listOf(ToolReq("rust", m.groupValues[1], p, Confidence.HIGH))
    }

    private fun pyVersion(p: String, c: String) =
        listOf(ToolReq("python", c.trim(), p, Confidence.HIGH))

    private fun parsePyProject(p: String, c: String): List<ToolReq> {
        val m = Regex("""requires-python\s*=\s*"([^"]+)""").find(c) ?: return emptyList()
        return listOf(ToolReq("python", m.groupValues[1], p, Confidence.MEDIUM))
    }

    private fun parseGradleWrapper(p: String, c: String): List<ToolReq> {
        val m = Regex("""gradle-([\d.]+)-(?:bin|all)\.zip""").find(c) ?: return emptyList()
        return listOf(ToolReq("gradle", m.groupValues[1], p, Confidence.HIGH))
    }

    /**
     * 解析 Gradle Kotlin DSL。
     * 覆盖 Android 平台工具链：NDK、build-tools、compileSdk、targetSdk、
     * minSdk、JDK、AGP、Kotlin。
     */
    private fun parseGradleKts(p: String, c: String): List<ToolReq> {
        val res = mutableListOf<ToolReq>()

        Regex("""ndkVersion\s*=\s*"([^"]+)""").find(c)?.let {
            res += ToolReq("android-ndk", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""buildToolsVersion\s*=\s*"([^"]+)""").find(c)?.let {
            res += ToolReq("android-build-tools", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""compileSdk\s*=\s*(\d+)""").find(c)?.let {
            res += ToolReq("android-sdk-platform", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""targetSdk\s*=\s*(\d+)""").find(c)?.let {
            res += ToolReq("android-sdk-target", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""minSdk\s*=\s*(\d+)""").find(c)?.let {
            res += ToolReq("android-sdk-min", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""sourceCompatibility\s*=\s*JavaVersion\.VERSION_(\d+)""").find(c)?.let {
            res += ToolReq("jdk", it.groupValues[1], p, Confidence.HIGH)
        }
        // AGP：id("com.android.application") version "8.5.2"
        Regex("""id\("com\.android\.application"\)\s*version\s*"([^"]+)""").find(c)?.let {
            res += ToolReq("android-gradle-plugin", it.groupValues[1], p, Confidence.HIGH)
        }
        // Kotlin：id("org.jetbrains.kotlin.android") version "2.0.20"
        Regex("""id\("org\.jetbrains\.kotlin\.android"\)\s*version\s*"([^"]+)""").find(c)?.let {
            res += ToolReq("kotlin", it.groupValues[1], p, Confidence.HIGH)
        }
        // Kotlin 另一种写法：kotlin("android") version "2.0.20"
        Regex("""kotlin\("android"\)\s*version\s*"([^"]+)""").find(c)?.let {
            res += ToolReq("kotlin", it.groupValues[1], p, Confidence.HIGH)
        }
        return res
    }

    private fun parseCMake(p: String, c: String): List<ToolReq> {
        val res = mutableListOf<ToolReq>()
        Regex("""cmake_minimum_required\(VERSION\s+([\d.]+)""").find(c)?.let {
            res += ToolReq("cmake", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""find_package\(\s*([A-Za-z0-9_]+)""").findAll(c).forEach {
            res += ToolReq(it.groupValues[1].lowercase(), null, p, Confidence.LOW)
        }
        return res
    }

    private fun parseDockerfile(p: String, c: String): List<ToolReq> {
        val res = mutableListOf<ToolReq>()
        Regex("""^FROM\s+(\S+)""", RegexOption.MULTILINE).findAll(c).forEach {
            res += ToolReq("docker-base", it.groupValues[1], p, Confidence.MEDIUM)
        }
        Regex("""(?:apk add|apt-get install)([^\n&|]+)""").findAll(c).forEach { m ->
            m.groupValues[1].trim().split(Regex("\\s+")).forEach { pkg ->
                if (pkg.isNotBlank() && !pkg.startsWith("-"))
                    res += ToolReq(pkg, null, p, Confidence.MEDIUM)
            }
        }
        return res
    }

    private fun parseGitmodules(p: String, c: String): List<ToolReq> =
        Regex("""url\s*=\s*(\S+)""").findAll(c).map {
            ToolReq("submodule", it.groupValues[1], p, Confidence.MEDIUM)
        }.toList()

    private fun parseWorkflow(p: String, c: String): List<ToolReq> {
        val res = mutableListOf<ToolReq>()
        Regex("""ndk-version:\s*['"]?([\w.]+)""").find(c)?.let {
            res += ToolReq("android-ndk", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""node-version:\s*['"]?([\w.]+)""").find(c)?.let {
            res += ToolReq("node", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""python-version:\s*['"]?([\w.]+)""").find(c)?.let {
            res += ToolReq("python", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""java-version:\s*['"]?([\w.]+)""").find(c)?.let {
            res += ToolReq("jdk", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""gradle-version:\s*['"]?([\w.]+)""").find(c)?.let {
            res += ToolReq("gradle", it.groupValues[1], p, Confidence.HIGH)
        }
        // sdkmanager "platforms;android-34"
        Regex("""platforms;android-(\d+)""").findAll(c).forEach {
            res += ToolReq("android-sdk-platform", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""build-tools;([\d.]+)""").findAll(c).forEach {
            res += ToolReq("android-build-tools", it.groupValues[1], p, Confidence.HIGH)
        }
        Regex("""(?:apt-get install|apk add)([^\n&|]+)""").findAll(c).forEach { m ->
            m.groupValues[1].trim().split(Regex("\\s+")).forEach { pkg ->
                if (pkg.isNotBlank() && !pkg.startsWith("-"))
                    res += ToolReq(pkg, null, p, Confidence.HIGH)
            }
        }
        return res
    }
}
