package org.gts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gts.compare.EnvComparator
import org.gts.compare.Status
import org.gts.fix.FixAction
import org.gts.fix.FixPlanner
import org.gts.fix.TermuxBridge
import org.gts.gen.BuildCommandDetector
import org.gts.gen.CiTarget
import org.gts.gen.ScriptGenerator
import org.gts.gen.WorkflowGenerator
import org.gts.model.ToolReq
import org.gts.parse.Parsers
import org.gts.scan.ModuleGapDetector
import org.gts.scan.RepoScanner
import org.gts.util.FileSaver
import org.gts.util.LocalShell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }

        // API 28 及以下写公共 Download 目录需要运行时权限；29+ 走 MediaStore 不需要。
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001
                )
            }
        }

        setContent {
            MaterialTheme(colorScheme = WhiteScheme) { App() }
        }
    }
}

private val WhiteScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFF546E7A),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF444444),
    error = Color(0xFFC62828),
    onError = Color.White,
    outline = Color(0xFFBDBDBD)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val termux = remember { TermuxBridge(ctx) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { termux.release() } }

    var url by remember { mutableStateOf("https://github.com/hrydgard/PPSSPP") }
    var token by remember { mutableStateOf("") }
    var reqs by remember { mutableStateOf<List<ToolReq>>(emptyList()) }
    var statuses by remember { mutableStateOf<List<Status>>(emptyList()) }
    var actions by remember { mutableStateOf<List<FixAction>>(emptyList()) }
    var gaps by remember { mutableStateOf<List<ModuleGapDetector.Gap>>(emptyList()) }
    var files by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    var target by remember { mutableStateOf(CiTarget.UBUNTU) }
    var buildCmd by remember { mutableStateOf("") }
    var useSubmodules by remember { mutableStateOf(true) }
    var genYaml by remember { mutableStateOf("") }
    var genScript by remember { mutableStateOf("") }
    var unresolved by remember { mutableStateOf<List<String>>(emptyList()) }
    var buildReason by remember { mutableStateOf("") }

    var termCmd by remember { mutableStateOf("") }
    var termOut by remember { mutableStateOf("") }
    var termBusy by remember { mutableStateOf(false) }
    var termHistory by remember { mutableStateOf<List<String>>(emptyList()) }

    fun saveText(name: String, content: String) {
        if (content.isBlank()) {
            Toast.makeText(ctx, "内容为空，未保存", Toast.LENGTH_SHORT).show()
            return
        }
        val err = FileSaver.save(ctx, name, content)
        val msg = if (err == null)
            "已保存到 " + FileSaver.displayPath(name)
        else
            "保存失败：" + err
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        log = msg
    }

    fun doScan() {
        busy = true; log = ""; statuses = emptyList(); actions = emptyList()
        gaps = emptyList(); genYaml = ""; genScript = ""; reqs = emptyList()
        unresolved = emptyList(); buildReason = ""; files = emptyMap()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RepoScanner(token.ifBlank { null }).scan(url)
                }
                files = result.files
                gaps = withContext(Dispatchers.Default) { ModuleGapDetector.detect(result) }
                val det = withContext(Dispatchers.Default) {
                    BuildCommandDetector.detect(result.files)
                }
                buildCmd = det.command
                buildReason = det.reason
                useSubmodules = result.files.containsKey(".gitmodules")

                val parsed = withContext(Dispatchers.Default) { Parsers.parseAll(result.files) }
                reqs = parsed
                val probed = withContext(Dispatchers.IO) {
                    if (termux.isInstalled()) EnvComparator(termux).probeAll() else emptyMap()
                }
                val cmp = EnvComparator(termux).compare(parsed, probed)
                statuses = cmp
                actions = FixPlanner().plan(cmp)

                val plan = WorkflowGenerator().generate(
                    parsed, target, det.command, useSubmodules, result.files
                )
                genYaml = plan.yaml
                unresolved = plan.unresolved
                genScript = ScriptGenerator().generate(
                    plan.classified, target, det.command, useSubmodules
                )

                val blockers = gaps.count { it.severity == ModuleGapDetector.Severity.BLOCKER }
                log = "树 " + result.tree.size + " 项 · 声明文件 " + result.files.size +
                    " · 工具 " + parsed.size +
                    (if (blockers > 0) " · 缺失模块 " + blockers + " 个" else "") +
                    (if (!termux.isInstalled()) " · 未检测到 Termux" else "")
                tab = if (blockers > 0) 3 else 2
            } catch (e: Exception) {
                log = "失败：" + e.message
            } finally { busy = false }
        }
    }

    fun regenerate() {
        if (reqs.isEmpty()) return
        val plan = WorkflowGenerator().generate(reqs, target, buildCmd, useSubmodules, files)
        genYaml = plan.yaml
        unresolved = plan.unresolved
        genScript = ScriptGenerator().generate(plan.classified, target, buildCmd, useSubmodules)
    }

    fun gapFixScript(): String {
        val sb = StringBuilder()
        sb.appendLine("#!/usr/bin/env bash")
        sb.appendLine("# 补齐缺失模块")
        sb.appendLine("set -e")
        sb.appendLine()
        gaps.forEach { g ->
            if (g.severity != ModuleGapDetector.Severity.INFO) {
                sb.appendLine("# " + g.what + "：" + g.why)
                sb.appendLine(g.fix)
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    fun runTerm() {
        val c = termCmd.trim()
        if (c.isEmpty() || termBusy) return
        termBusy = true
        termHistory = termHistory + ("\$ " + c)
        scope.launch {
            val r = LocalShell.run(c)
            val chunk = buildString {
                if (r.stdout.isNotBlank()) append(r.stdout.trimEnd())
                if (r.stderr.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    append("[stderr] ").append(r.stderr.trimEnd())
                }
                if (isEmpty()) append("(无输出)")
                appendLine()
                append("[exit ").append(r.exitCode).append("]")
            }
            termHistory = termHistory + chunk
            termOut = termHistory.joinToString("\n")
            termBusy = false
            termCmd = ""
        }
    }

    Scaffold(
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("GitHub Tool Scanner") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = Color(0xFF1A1A1A)
                )
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).safeDrawingPadding()
                .padding(horizontal = 16.dp).fillMaxSize()
        ) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("GitHub 仓库 URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Token（私有仓库才需要，可留空）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { doScan() }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "扫描并生成中…" else "扫描并生成工作流") }

            if (log.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(log, style = MaterialTheme.typography.bodySmall, color = Color(0xFF444444))
            }

            Spacer(Modifier.height(8.dp))
            ScrollableTabRow(
                selectedTabIndex = tab,
                containerColor = Color.White,
                contentColor = Color(0xFF1565C0),
                edgePadding = 0.dp
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("清单 " + statuses.size) })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("补齐 " + actions.size) })
                Tab(selected = tab == 2, onClick = { tab = 2 },
                    text = { Text("工作流") })
                Tab(selected = tab == 3, onClick = { tab = 3 },
                    text = {
                        val b = gaps.count { it.severity == ModuleGapDetector.Severity.BLOCKER }
                        Text("缺失 " + gaps.size + (if (b > 0) " (" + b + "!)" else ""))
                    })
                Tab(selected = tab == 4, onClick = { tab = 4 },
                    text = { Text("终端") })
            }

            when (tab) {
                0 -> LazyColumn(Modifier.weight(1f)) {
                    items(statuses) { s ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.White),
                            headlineContent = {
                                Text(s.req.tool + (s.req.version?.let { "  " + it } ?: ""))
                            },
                            supportingContent = { Text(s.req.source + " · " + s.req.confidence) },
                            trailingContent = {
                                Text(
                                    if (s.ok) "OK " + (s.installed ?: "") else "缺",
                                    color = if (s.ok) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                            }
                        )
                        HorizontalDivider(color = Color(0xFFE0E0E0))
                    }
                }

                1 -> LazyColumn(Modifier.weight(1f)) {
                    item {
                        Button(onClick = {
                            val sb = StringBuilder()
                            actions.forEach { sb.appendLine(it.command) }
                            saveText("termux-fix.sh", sb.toString())
                        }) { Text("保存 Termux 补齐脚本") }
                        Spacer(Modifier.height(8.dp))
                    }
                    items(actions) { a ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.White),
                            headlineContent = { Text(a.note) },
                            supportingContent = {
                                Text(a.command, style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace), color = Color(0xFF444444))
                            }
                        )
                        HorizontalDivider(color = Color(0xFFE0E0E0))
                    }
                }

                3 -> Column(Modifier.weight(1f)) {
                    if (gaps.isEmpty()) {
                        Text("没发现缺失模块", style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32))
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { saveText("fix-gaps.sh", gapFixScript()) }) {
                                Text("保存补齐脚本")
                            }
                            OutlinedButton(onClick = {
                                termux.runDetached(gapFixScript())
                            }) { Text("在 Termux 执行") }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            items(gaps) { g ->
                                val color = when (g.severity) {
                                    ModuleGapDetector.Severity.BLOCKER -> Color(0xFFC62828)
                                    ModuleGapDetector.Severity.WARNING -> Color(0xFFE65100)
                                    ModuleGapDetector.Severity.INFO -> Color(0xFF546E7A)
                                }
                                val tag = when (g.severity) {
                                    ModuleGapDetector.Severity.BLOCKER -> "阻塞"
                                    ModuleGapDetector.Severity.WARNING -> "警告"
                                    ModuleGapDetector.Severity.INFO -> "提示"
                                }
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.White),
                                    headlineContent = { Text("[" + tag + "] " + g.what, color = color) },
                                    supportingContent = {
                                        Column {
                                            Text(g.why, style = MaterialTheme.typography.bodySmall)
                                            Text(g.fix, style = MaterialTheme.typography.bodySmall
                                                .copy(fontFamily = FontFamily.Monospace),
                                                color = Color(0xFF444444))
                                        }
                                    }
                                )
                                HorizontalDivider(color = Color(0xFFE0E0E0))
                            }
                        }
                    }
                }

                4 -> Column(Modifier.weight(1f)) {
                    Text(
                        "内嵌终端：用 /system/bin/sh 执行。能跑 ls/cat/echo/curl 等；装包、改系统目录需改用 Termux。",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = termCmd, onValueChange = { termCmd = it },
                            label = { Text("命令") }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { runTerm() }, enabled = !termBusy) {
                            Text(if (termBusy) "…" else "运行")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { termHistory = emptyList(); termOut = "" }) {
                            Text("清空")
                        }
                        OutlinedButton(onClick = { saveText("terminal.log", termOut) }) {
                            Text("保存输出")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                termOut.ifBlank { "(等待命令)" },
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF1A1A1A)
                            )
                        }
                    }
                }

                else -> Column(Modifier.weight(1f)) {
                    Text("目标环境", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CiTarget.entries.forEach { t ->
                            FilterChip(
                                selected = target == t,
                                onClick = { target = t; regenerate() },
                                label = { Text(t.label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = buildCmd, onValueChange = { buildCmd = it; regenerate() },
                        label = { Text("构建命令（自动推断，可改）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (buildReason.isNotBlank()) {
                        Text("推断依据：" + buildReason,
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useSubmodules,
                            onCheckedChange = { useSubmodules = it; regenerate() })
                        Text("递归初始化子模块")
                    }

                    if (unresolved.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("未映射的工具（需手工补充）：" + unresolved.joinToString(", "),
                            color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall)
                    }

                    if (genYaml.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { saveText("build.yml", genYaml) }) {
                                Text("保存 build.yml")
                            }
                            OutlinedButton(onClick = { saveText("setup.sh", genScript) }) {
                                Text("保存 setup.sh")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("build.yml 预览", style = MaterialTheme.typography.labelLarge)
                        LazyColumn(Modifier.weight(1f)) {
                            item {
                                Text(genYaml, style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace), color = Color(0xFF1A1A1A))
                            }
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text("点上面的「扫描并生成工作流」",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
                    }
                }
            }
        }
    }
}
