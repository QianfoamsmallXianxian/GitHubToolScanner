# 验证状态

最后更新：2026-09-18 20:21

**本文档记录「人工读回源码核对」的结果，不是编译验证。**
本机没有 JDK、Android SDK，Shell 工具不可用，无法编译。

---

## 文件覆盖：20/20

| 文件 | 读回 | 状态 |
|---|---|---|
| settings.gradle.kts | 是 | 正确 |
| build.gradle.kts（根） | 是 | 正确 |
| gradle.properties | 是 | 正确 |
| .github/workflows/build.yml | 是 | B18 已修 |
| app/build.gradle.kts | 是 | 加 foundation、lint 降级 |
| app/proguard-rules.pro | 是 | 正确 |
| AndroidManifest.xml | 是 | 正确 |
| res/values/strings.xml | 是 | 正确 |
| res/values/themes.xml | 是 | 正确 |
| MainActivity.kt | 是 | B8/B17 已修 |
| model/ToolReq.kt | 是 | 正确 |
| scan/RepoScanner.kt | 是 | B15 已修 |
| parse/Parsers.kt | 是 | B11 已修 |
| compare/EnvComparator.kt | 是 | B6/B12 已修 |
| fix/FixPlanner.kt | 是 | B1/B5/B14 已修 |
| fix/TermuxBridge.kt | 是 | B16/B17/B20/B21 已修 |
| gen/WorkflowGenerator.kt | 是 | B2/B7/B8/B13 已修 |
| gen/ScriptGenerator.kt | 是 | B8/B13 已修 |
| README.md | 是 | B3 已修 |
| BUGS.md | — | 问题清单 |

---

## 接口变更记录

**ScriptGenerator.generate 签名变了：**

```
旧：generate(reqs: List<ToolReq>, target, buildCommand, useSubmodules)
新：generate(classified: WorkflowGenerator.Classified, target, buildCommand, useSubmodules)
```

调用点已在 MainActivity.doGenerate 同步更新：

```kotlin
val plan = WorkflowGenerator().generate(reqs, target, buildCmd, useSubmodules)
genScript = ScriptGenerator().generate(plan.classified, target, buildCmd, useSubmodules)
```

---

## 关于「准确率」

不能给出百分比。能说的是：

- 20 个文件全部人工读回
- 21 条问题全部处理
- 3 条致命 bug 的修复已读回确认

**但都不是编译验证。**

**唯一可靠的验证：推 GitHub 跑 Actions。**
第一次几乎必然还有报错，把报错贴回来逐个修。
