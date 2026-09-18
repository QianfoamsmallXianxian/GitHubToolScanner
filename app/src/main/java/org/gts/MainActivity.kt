package org.gts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gts.compare.EnvComparator
import org.gts.compare.Status
import org.gts.fix.FixAction
import org.gts.fix.FixPlanner
import org.gts.fix.TermuxBridge
import org.gts.gen.CiTarget
import org.gts.gen.ScriptGenerator
import org.gts.gen.WorkflowGenerator
import org.gts.model.ToolReq
import org.gts.parse.Parsers
import org.gts.scan.RepoScanner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val termux = remember { TermuxBridge(ctx) }
    val scope = rememberCoroutineScope()

    // B17 修复：离开组合时注销 BroadcastReceiver
    DisposableEffect(Unit) {
        onDispose { termux.release() }
    }

    var url by remember { mutableStateOf("https://github.com/hrydgard/PPSSPP") }
    var token by remember { mutableStateOf("") }
    var reqs by remember { mutableStateOf<List<ToolReq>>(emptyList()) }
    var statuses by remember { mutableStateOf<List<Status>>(emptyList()) }
    var actions by remember { mutableStateOf<List<FixAction>>(emptyList()) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    var target by remember { mutableStateOf(CiTarget.UBUNTU) }
    var buildCmd by remember { mutableStateOf("make") }
    var useSubmodules by remember { mutableStateOf(true) }
    var genYaml by remember { mutableStateOf("") }
    var genScript by remember { mutableStateOf("") }
    var unresolved by remember { mutableStateOf<List<String>>(emptyList()) }

    fun doScan() {
        busy = true; log = ""; statuses = emptyList(); actions = emptyList()
        genYaml = ""; genScript = ""; reqs = emptyList(); unresolved = emptyList()
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    RepoScanner(token.ifBlank { null }).scan(url)
                }
                val parsed = withContext(Dispatchers.Default) { Parsers.parseAll(files) }
                reqs = parsed
                val probed = withContext(Dispatchers.IO) {
                    if (termux.isInstalled()) EnvComparator(termux).probeAll()
                    else emptyMap()
                }
                val cmp = EnvComparator(termux).compare(parsed, probed)
                statuses = cmp
                actions = FixPlanner().plan(cmp)
                log = buildString {
                    append("扫描 ${files.size} 个文件 · 识别 ${parsed.size} 条要求")
                    if (!termux.isInstalled())
                        append(" · 未检测到 Termux，版本比对已跳过")
                    else
                        append(" · 缺失 ${cmp.count { !it.ok }} 项")
                }
            } catch (e: Exception) {
                log = "失败：${e.message}"
            } finally { busy = false }
        }
    }

    // B8 修复：只调用一次 generate，classified 从 Plan 里取，不再重复计算
    fun doGenerate() {
        val plan = WorkflowGenerator().generate(reqs, target, buildCmd, useSubmodules)
        genYaml = plan.yaml
        unresolved = plan.unresolved
        genScript = ScriptGenerator().generate(plan.classified, target, buildCmd, useSubmodules)
    }

    fun share(text: String, name: String) {
        if (text.isBlank()) return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(i, "分享 $name"))
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("GitHub Tool Scanner") }) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {

            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("GitHub 仓库 URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Token（私有仓库才需要，可留空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            Button(onClick = { doScan() }, enabled = !busy) {
                Text(if (busy) "扫描中…" else "扫描")
            }

            if (log.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(log, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("清单 ${statuses.size}") })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("补齐 ${actions.size}") })
                Tab(selected = tab == 2, onClick = { tab = 2 },
                    text = { Text("工作流") })
            }

            when (tab) {
                0 -> LazyColumn(Modifier.weight(1f)) {
                    items(statuses) { s ->
                        ListItem(
                            headlineContent = {
                                Text(s.req.tool + (s.req.version?.let { "  $it" } ?: ""))
                            },
                            supportingContent = {
                                Text("${s.req.source} · ${s.req.confidence}")
                            },
                            trailingContent = {
                                Text(
                                    if (s.ok) "OK ${s.installed ?: ""}" else "缺",
                                    color = if (s.ok) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                )
                            }
                        )
                        HorizontalDivider()
                    }
                }

                1 -> LazyColumn(Modifier.weight(1f)) {
                    items(actions) { a ->
                        ListItem(
                            headlineContent = { Text(a.note) },
                            supportingContent = {
                                Text(a.command, style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace))
                            }
                        )
                        HorizontalDivider()
                    }
                }

                else -> Column(Modifier.weight(1f)) {
                    Text("目标环境", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CiTarget.entries.forEach { t ->
                            FilterChip(
                                selected = target == t,
                                onClick = { target = t },
                                label = { Text(t.label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = buildCmd, onValueChange = { buildCmd = it },
                        label = { Text("构建命令，例如 ./b.sh 或 make") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useSubmodules, onCheckedChange = { useSubmodules = it })
                        Text("递归初始化子模块")
                    }
                    Spacer(Modifier.height(8.dp))

                    Button(onClick = { doGenerate() }, enabled = reqs.isNotEmpty()) {
                        Text("生成工作流")
                    }
                    if (reqs.isEmpty()) {
                        Text("请先扫描仓库", style = MaterialTheme.typography.bodySmall)
                    }

                    if (unresolved.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("未映射的工具（需手工补充）：" + unresolved.joinToString(", "),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }

                    if (genYaml.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { share(genYaml, "build.yml") }) {
                                Text("分享 build.yml")
                            }
                            OutlinedButton(onClick = { share(genScript, "setup.sh") }) {
                                Text("分享 setup.sh")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("build.yml 预览", style = MaterialTheme.typography.labelLarge)
                        LazyColumn(Modifier.weight(1f)) {
                            item {
                                Text(genYaml,
                                    style = MaterialTheme.typography.bodySmall
                                        .copy(fontFamily = FontFamily.Monospace))
                            }
                        }
                    }
                }
            }
        }
    }
}
