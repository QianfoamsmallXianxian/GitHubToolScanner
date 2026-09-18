# 问题清单（全部已处理）

最后更新：2026-09-18 20:21

**共 21 条，全部已修复或已给出明确取舍。未经编译验证。**

---

## 致命（3）——已修 + 读回确认

### B11. Parsers.kt 全篇正则多转义
`"""..."""` 是原始字符串，不处理转义。`\\s` 被正则引擎读成「反斜杠+字母s」。
**已修**：全部改为单反斜杠。

### B12. EnvComparator 的 extractVersion 正则多转义
同源问题，导致版本比对完全失效。
**已修**。

### B13. 两个 gen 文件的 \$ 转义写反
`\$` 输出字面量 `$`，生成的 YAML 全是死文字。
**已修**：改用真模板。

---

## 严重（7）——全部已修

| 编号 | 问题 | 修法 |
|---|---|---|
| B1 | FixPlanner 用 Ubuntu 包名跑 Termux 命令 | 换 Termux 包名 |
| B2 | Alpine 容器跑不通 | 移除该选项 |
| B3 | README 转义警告写反 | 删除 |
| B5 | gradle 只下载不解压 | 补 unzip + 解压 + PATH |
| B15 | RepoScanner 静默失败 | 抛带原因分类的异常 |
| B16 | TermuxBridge 超时路径内存泄漏 | 改 finally 清理 |
| B18 | CI 缺 Android SDK 安装 | 加 setup-android + sdkmanager |

---

## 中等（6）——全部已处理

| 编号 | 问题 | 处理 |
|---|---|---|
| B4 | mise 在 Termux 可能不存在 | **已修**：改直接 pkg install |
| B6 | 版本探测解析脆弱 | **已修**：用 command -v 预判存在性，分隔符改 @@GTS@@，indexOfLast 容忍 motd，2>&1 合并 stderr |
| B7 | cleanVer 丢版本前缀语义 | **已修**：^1.2.3 -> 1.x，~1.2.3 -> 1.2.x |
| B14 | 子模块不自动初始化 | **已修**：FixPlanner 收集 submodule，生成 git submodule update |
| B20 | FLAG_MUTABLE 无版本保护 | **已修**：加 FLAG_MUTABLE_COMPAT |
| B21 | ensureReceiver 竞态 | **已修**：加 synchronized |

---

## 轻微（3）——全部已处理

| 编号 | 问题 | 处理 |
|---|---|---|
| B8 | classify 重复计算 | **已修**：Plan 带出 classified，ScriptGenerator 改为接收它 |
| B9 | 未映射工具只提示 | 行为正确，不改 |
| B17 | receiver 未注销 | **已修**：加 release()，MainActivity 用 DisposableEffect 调用 |

---

## 更正记录

### B19 不是 bug
之前把「foundation 未显式声明」列为 bug，这是错的。
foundation 是 material3 的传递依赖，不加也能编过。加上是健壮性改进。

---

## 诚实结论

**21 条全部处理完毕，但全程没有编译验证。**

本机没有 JDK、Android SDK，Shell 工具不可用，无法编译。
所有「已修」都来自读源码人工核对，修复本身同样没编译过。

B11/B12/B13 这类错误靠肉眼比对才发现，说明同类错误仍可能藏在没注意到的地方。

**唯一可靠的验证：推到 GitHub，让 Actions 编译。**
