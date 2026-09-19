package org.gts.gen

/**
 * 目标包管理器。
 *
 * APT   -> Debian/Ubuntu
 * BREW  -> macOS
 * CHOCO -> Windows
 */
enum class PkgManager { APT, BREW, CHOCO }

/**
 * 目标平台。
 *
 * LINUX   -> Linux 原生构建
 * ANDROID -> Android APK，走 ubuntu runner + SDK/NDK + Gradle
 * WINDOWS -> windows runner + choco + MSBuild/CMake
 * MACOS   -> macOS 原生构建
 */
enum class CiTarget(val runner: String, val pkg: PkgManager, val label: String) {
    LINUX("ubuntu-latest", PkgManager.APT, "Linux"),
    ANDROID("ubuntu-latest", PkgManager.APT, "Android"),
    WINDOWS("windows-latest", PkgManager.CHOCO, "Windows"),
    MACOS("macos-latest", PkgManager.BREW, "macOS");

    val isAndroid get() = this == ANDROID
    val isWindows get() = this == WINDOWS
    val isMacOS get() = this == MACOS
    val isLinux get() = this == LINUX
}
