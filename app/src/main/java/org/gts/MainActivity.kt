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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
import org.gts.scan.RepoScanner

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
    var buildCmd by remember { mutableStateOf("") }
    var useSubmodules by remember { mutableStateOf(true) }
    var genYaml by remember { mutableStateOf("") }
    var genScript by remember { mutableStateOf("") }
    var unresolved by remember { mutableStateOf<List<String>>(emptyList()) }
    var buildReason by remember { mutableStateOf("") }

    fun doScan() {
        busy = true; log = ""; statuses = emptyList(); actions = emptyList()
        genYaml = ""; genScript = ""; reqs = emptyList(); unresolved = emptyList()
        buildReason = ""
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    RepoScanner(token.ifBlank { null }).scan(url)
                }

                // 自动推断构建命令
                val det = withContext(Dispatchers.Default) {
                    BuildCommandDetector.detect(files)
                }
                buildCmd = det.command
                buildReason = det.reason
                useSubmodules = files.containsKey(".gitmodules")

                val parsed = withContext(Dispatchers.Default) { Parsers.parseAll(files) }
                reqs = parsed
                val probed = withContext(Dispatchers.IO) {
                    if (termux.isInstalled()) EnvComparator(termux).probeAll()
                    else emptyMap()
                }
                val cmp = EnvComparator(termux).compare(parsed, probed)
                statuses = cmp
                actions = FixPlanner().plan(cmp)

                // 自动生成工作流，无需再点按钮
                val plan = WorkflowGenerator().generate(parsed, target, det.command, useSubmodules)
                genYaml = plan.yaml
                unresolved = plan.unresolved
                genScript = ScriptGenerator().generate(
                    plan.classified, target, det.command, useSubmodules
                )

                log = buildString {
                    append("扫描 ${files.size} 个文件 · 识别 ${parsed.size} 条要求")
                    if (!termux.isInstalled())
                        append(" · 未检测到 Termux，版本比对已跳过")
                    else
                        append(" · 缺失 ${cmp.count { !it.ok }} 项")
                }
                // 自动切到工作流页，因为扫描后最想看的就是它
                tab = 2
            } catch (e: Exception) {
                log = "失败：${e.message}"
            } finally { busy = false }
        }
    }

    // 用户改了目标环境或构建命令后，重新生成
    fun regenerate() {
        if (reqs.isEmpty()) return
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
            Modifier
                .padding(pad)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {

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

            Button(
                onClick = { doScan() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "扫描并生成中…" else "扫描并生成工作流")
            }

            if (log.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(log, style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF444444))
            }

            Spacer(Modifier.height(8.dp))
            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.White,
                contentColor = Color(0xFF1565C0)
            ) {
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
                            colors = ListItemDefaults.colors(containerColor = Color.White),
                            headlineContent = {
                                Text(s.req.tool + (s.req.version?.let { "  $it" } ?: ""))
                            },
                            supportingContent = {
                                Text("${s.req.source} · ${s.req.confidence}")
                            },
                            trailingContent = {
                                Text(
                                    if (s.ok) "OK ${s.installed ?: ""}" else "缺",
                                    color = if (s.ok) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                            }
                        )
                        HorizontalDivider(color = Color(0xFFE0E0E0))
                    }
                }

                1 -> LazyColumn(Modifier.weight(1f)) {
                    items(actions) { a ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.White),
                            headlineContent = { Text(a.note) },
                            supportingContent = {
                                Text(a.command, style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                    color = Color(0xFF444444))
                            }
                        )
                        HorizontalDivider(color = Color(0xFFE0E0E0))
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
                        value = buildCmd, onValueChange = {
                            buildCmd = it; regenerate()
                        },
                        label = { Text("构建命令（自动推断，可改）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (buildReason.isNotBlank()) {
                        Text("推断依据：$buildReason",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useSubmodules, onCheckedChange = {
                            useSubmodules = it; regenerate()
                        })
                        Text("递归初始化子模块")
                    }

                    if (unresolved.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("未映射的工具（需手工补充）：" + unresolved.joinToString(", "),
                            color = Color(0xFFC62828),
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
                                        .copy(fontFamily = FontFamily.Monospace),
                                    color = Color(0xFF1A1A1A))
                            }
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text("点上面的「扫描并生成工作流」",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF666666))
                    }
                }
            }
        }
    }
}
