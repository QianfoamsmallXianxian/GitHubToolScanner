# GitHub Tool Scanner (GTS)

扫描 GitHub 仓库的构建声明文件，提取所需工具及其版本，与本机环境比对，并自动生成 CI 工作流和安装脚本。

## 功能

### 1. 扫描

联网读取仓库声明文件，提取工具名加版本。覆盖格式：

- package.json（engines、packageManager）
- .nvmrc / .node-version / .tool-versions
- go.mod / rust-toolchain.toml / pyproject.toml / .python-version
- Dockerfile（FROM、apk add、apt-get install）
- build.gradle.kts（ndkVersion、compileSdk、sourceCompatibility）
- gradle/wrapper/gradle-wrapper.properties
- CMakeLists.txt（cmake_minimum_required、find_package）
- .gitmodules（子模块清单）
- .github/workflows/*.yml（setup 动作的版本、apt/apk 包）

### 2. 比对

与 Termux 环境比对，标出缺失项和版本不符项。

### 3. 补齐

生成 Termux 安装命令，可一键派发执行。

注意：这里用的是 **Termux 包名**，不是 Ubuntu 包名。两者差异很大。

### 4. 工作流生成

根据扫描结果自动生成：

- build.yml —— GitHub Actions 工作流
- setup.sh —— 等价的本地安装脚本

支持 Ubuntu（apt）、Alpine（apk）、macOS（brew）三种目标，包名映射表各自独立。

## 目录结构

```
.
├── .github/workflows/build.yml
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── BUGS.md
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/values/strings.xml
        ├── res/values/themes.xml
        └── java/org/gts/
            ├── MainActivity.kt
            ├── model/ToolReq.kt
            ├── scan/RepoScanner.kt
            ├── parse/Parsers.kt
            ├── compare/EnvComparator.kt
            ├── fix/
            │   ├── FixPlanner.kt
            │   └── TermuxBridge.kt
            └── gen/
                ├── WorkflowGenerator.kt
                └── ScriptGenerator.kt
```

## 用 GitHub Actions 构建本 APK

1. 推到 GitHub 仓库的 main 分支
2. Actions 自动触发，Artifacts 里下载 gts-debug-apk
3. 打 tag（如 v1.0）走 Release 流程

工作流用 gradle/actions/setup-gradle 安装 Gradle，不需要提交 gradlew 和 gradle-wrapper.jar。

## 使用

1. 输入仓库 URL（私有仓库再填 Token）
2. 点扫描
3. 清单标签页看识别到的工具和缺失项
4. 补齐标签页看 Termux 安装命令
5. 工作流标签页：选目标环境、填构建命令、生成、分享

## 使用前提（补齐功能）

1. 已安装 Termux
2. ~/.termux/termux.properties 里设置 allow-external-apps=true
3. 执行 termux-reload-settings

未装 Termux 时扫描、清单、工作流生成仍可用。

## 已知边界

详见 BUGS.md。核心几条：

- 包名映射表按目标环境分别维护，不完整，未命中的会列在「未映射」里提示
- CMake 的 find_package 只有包名没有版本，标 LOW，靠 cmakeGuess 猜
- NDK 和 Android SDK 只生成 setup action，不负责实际安装
- 不编译目标仓库源码

## 关于字符串模板

源码里的 `$tool`、`$v`、`${s.req.version}` 是 **有意** 使用的 Kotlin 字符串模板，
不要改成 `\$tool`。加了反斜杠会变成字面量，功能会坏。

只有当你确实想让生成的字符串里出现字面量美元符号时，才需要反斜杠。

## 扩展点

| 位置 | 作用 |
|---|---|
| RepoScanner.candidates | 要扫描的声明文件列表 |
| Parsers.parseAll | 新增文件格式的解析分支 |
| FixPlanner.termuxPkg | Termux 包名映射 |
| FixPlanner.cmakeGuess | CMake find_package 名称猜测 |
| WorkflowGenerator.aptTable | Ubuntu 包名映射 |
| WorkflowGenerator.apkTable | Alpine 包名映射 |
| WorkflowGenerator.brewTable | macOS 包名映射 |
