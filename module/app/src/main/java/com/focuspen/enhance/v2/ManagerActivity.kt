package com.focuspen.enhance.v2

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import androidx.compose.ui.res.painterResource
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Only the standalone process loads Compose. Hook hosts continue using native views. */
class ManagerActivity : ComponentActivity() {
    private var status by mutableStateOf(Bundle())
    private var revision by mutableLongStateOf(0)
    private var preview by mutableStateOf("auto")
    private var pendingImport by mutableStateOf<String?>(null)
    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var licenseText by mutableStateOf<String?>(null)
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) fileOperation {
            val bytes = ConfigBackup.encode(ConfigStore.read(this)).toByteArray(Charsets.UTF_8)
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("无法写入文件")
            "设置已导出"
        }
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readNBytes(ConfigBackup.MAX_BYTES + 1) }
                        ?: error("无法读取文件")
                    require(bytes.size <= ConfigBackup.MAX_BYTES) { "备份文件过大" }
                    bytes.toString(Charsets.UTF_8).also { ConfigBackup.decode(it) }
                }
                pendingImport = text
            } catch (e: Exception) { message = "未导入设置：${e.message ?: "无法读取备份"}" }
            finally { busy = false }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImport = savedInstanceState?.getString("pendingImport")
        preview = UiPreferences.model(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val pair = withContext(Dispatchers.IO) {
                        runCatching {
                            (contentResolver.call(ConfigProvider.URI, "runtimeStatus", null, null) ?: Bundle()) to
                                ConfigStore.read(this@ManagerActivity).getLong("revision")
                        }.getOrElse { Bundle() to 0L }
                    }
                    status = pair.first; revision = pair.second
                    delay(2000)
                }
            }
        }
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT) { dark },
                )
            }
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) { Manager() }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingImport", pendingImport)
        super.onSaveInstanceState(outState)
    }
    private fun fileOperation(operation: () -> String) {
        lifecycleScope.launch {
            busy = true
            try { message = withContext(Dispatchers.IO) { operation() } }
            catch(e: Exception) { message = "操作未完成：${e.message ?: "文件不可用"}" }
            finally { busy = false }
        }
    }
    private fun launch(intent: Intent) {
        try { startActivity(intent) } catch(e: Exception) { message = "此页面暂不可用，请检查模块是否已加载。" }
    }
    private fun nativeSettings() = launch(Intent("com.miui.miinput.action.KEYBOARD_AND_STYLUS_SETTINGS").setPackage("com.miui.securitycore"))
    private fun guide() = launch(Intent().setClassName("com.miui.securitycore", "com.miui.miinput.stylus.StylusPinchSettingsActivity").putExtra("focuspen.v2.page", "guide"))

    @Composable private fun Manager() {
        var page by rememberSaveable { mutableIntStateOf(0) }
        val lists = List(4) { rememberLazyListState() }
        val barStates = List(4) { rememberTopAppBarState() }
        val overflow = List(4) { index -> remember {
            derivedStateOf {
                val list = lists[index]
                val info = list.layoutInfo
                // Compare with the viewport when the title is fully expanded.
                // Collapsing the title must not make a barely-long page alternate
                // between scrollable and fixed as its viewport changes.
                list.canScrollBackward || list.canScrollForward ||
                    (info.visibleItemsInfo.isNotEmpty() &&
                        info.visibleItemsInfo.sumOf { it.size } + info.beforeContentPadding + info.afterContentPadding >
                        info.viewportSize.height + barStates[index].heightOffset)
            }
        } }
        val scrollBehaviors = List(4) { index -> MiuixScrollBehavior(state = barStates[index], canScroll = { overflow[index].value }) }
        val hasOverflow = overflow[page].value
        LaunchedEffect(page, hasOverflow) {
            if (!hasOverflow) {
                barStates[page].heightOffset = 0f
                barStates[page].contentOffset = 0f
            }
        }
        val displayPrefs = remember { getSharedPreferences("manager_display", MODE_PRIVATE) }
        val railState = rememberNavigationRailState(initialValue = if(displayPrefs.getBoolean("railExpanded", false)) NavigationRailValue.Expanded else NavigationRailValue.Collapsed)
        LaunchedEffect(railState.currentValue) { displayPrefs.edit().putBoolean("railExpanded", railState.isExpanded).apply() }
        val titles = listOf("首页", "配置备份", "调试", "关于")
        val icons = remember { listOf(MiuixIcons.Regular.Home, MiuixIcons.Regular.Backup, MiuixIcons.Regular.Tune, MiuixIcons.Regular.Info) }
        BackHandler(page != 0 && pendingImport == null && message == null && licenseText == null) { page = 0 }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val rail = maxWidth >= 840.dp || (maxWidth >= 600.dp && maxHeight / maxWidth < 1.2f)
            Scaffold(contentWindowInsets = WindowInsets(0)) { _ ->
                Row(Modifier.fillMaxSize()) {
                    if (rail) NavigationRail(
                        state = railState, color = MiuixTheme.colorScheme.surface,
                        expandContentDescription = "展开侧栏", collapseContentDescription = "收起侧栏",
                    ) {
                        titles.forEachIndexed { index, title ->
                            NavigationRailItem(selected = page == index, onClick = { page = index }, icon = icons[index], label = title)
                        }
                    }
                    val behavior = scrollBehaviors[page]
                    Scaffold(
                        modifier = Modifier.weight(1f),
                        topBar = { TopAppBar(title = if(page == 0) "焦点笔增强" else titles[page], scrollBehavior = behavior) },
                        bottomBar = { if (!rail) NavigationBar {
                            titles.forEachIndexed { index, title ->
                                NavigationBarItem(selected = page == index, onClick = { page = index }, icon = icons[index], label = title)
                            }
                        } },
                        popupHost = {},
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                            LazyColumn(
                                state = lists[page],
                                modifier = Modifier.fillMaxWidth().then(if(hasOverflow) Modifier.nestedScroll(behavior.nestedScrollConnection) else Modifier),
                                overscrollEffect = null,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item(key = titles[page]) {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        when(page) { 0 -> Home(); 1 -> Backup(); 2 -> Debug(); else -> About() }
                                    }
                                }
                            }
                        }
                    }
                }
                OverlayDialog(
                    show = pendingImport != null,
                    title = "导入这份设置？",
                    summary = "备份已通过校验。将替换全局设置、应用配置和模板，现有设置不会自动合并。",
                    onDismissRequest = { pendingImport = null },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton("取消", onClick = { pendingImport = null }, modifier = Modifier.weight(1f))
                        TextButton("导入", onClick = {
                            val source = pendingImport ?: return@TextButton
                            pendingImport = null
                            fileOperation {
                                check(ConfigStore.restore(this@ManagerActivity, ConfigBackup.decode(source))) { "无法保存设置" }
                                "设置已导入并同步"
                            }
                        }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    }
                }
                OverlayDialog(show = licenseText != null, title = "开源许可与来源", onDismissRequest = { licenseText = null }) {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text(licenseText.orEmpty(), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton("完成", onClick = { licenseText = null }, modifier = Modifier.fillMaxWidth())
                }
                OverlayDialog(show = message != null, title = "焦点笔增强", summary = message,
                    onDismissRequest = { message = null }) {
                    TextButton("知道了", onClick = { message = null }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    private fun current(key: String) = status.getBoolean(key) && status.getString(key + "Version") == BuildConfig.VERSION_NAME

    @Composable private fun Leading(icon: ImageVector, color: Color = MiuixTheme.colorScheme.onSurface) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 14.dp).size(24.dp), tint = color)
    }
    @Composable private fun Group(title: String, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallTitle(title, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp))
            content()
        }
    }
    @Composable private fun Badge(text: String, color: Color) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.background(color.copy(alpha = .10f), RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
    }
    @Composable private fun Home() {
        val keys = listOf("input", "ui", "motion", "settings")
        val old = keys.any { status.getBoolean(it) && !current(it) }
        val ready = current("input") && current("ui")
        val dark = isSystemInDarkTheme()
        val accent = when { old -> if(dark) Color(0xFFFFCE81) else Color(0xFF925800); ready -> if(dark) Color(0xFF93D9AD) else Color(0xFF22733F); else -> MiuixTheme.colorScheme.primary }
        val background = when { old -> if(dark) Color(0xFF382C1C) else Color(0xFFFFECCE); ready -> if(dark) Color(0xFF1C3527) else Color(0xFFE0F5E7); else -> if(dark) Color(0xFF1C2C45) else Color(0xFFE5EEFF) }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = background)) {
            Box(Modifier.fillMaxWidth()) {
                Icon(if(old) MiuixIcons.Regular.Update else MiuixIcons.Regular.SearchDevice, null,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(12.dp, 15.dp).size(98.dp), tint = accent.copy(alpha = .13f))
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(when { old -> "等待宿主更新"; ready -> "核心服务已连接"; else -> "等待模块连接" }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = accent)
                    Text(when { old -> "部分宿主仍在运行旧版本，输入功能更新将在重启后加载。"; ready -> "按键与系统界面已就绪，详细连接状态见下方。"; else -> "在 LSPosed 中启用模块后，这里会自动显示连接状态。" },
                        modifier = Modifier.padding(end = 36.dp), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge(BuildConfig.VERSION_NAME, accent)
                        Badge("静态作用域", accent)
                    }
                }
            }
        }
        Group("服务状态") {
            Card {
                val titles = listOf("系统输入", "系统界面", "触控服务", "笔设置")
                val summaries = listOf("按键、手势与鼠标", "弧形菜单与笔尖预览", "触控笔运动事件", "原生设置与使用指南")
                val marks = listOf(MiuixIcons.Regular.Tune, MiuixIcons.Regular.Layers, MiuixIcons.Regular.Edit, MiuixIcons.Regular.Settings)
                keys.forEachIndexed { index, key ->
                    BasicComponent(title = titles[index], summary = summaries[index], insideMargin = PaddingValues(16.dp, 13.dp),
                        startAction = { Leading(marks[index]) }, endActions = {
                        val connected = current(key)
                        Badge(when { connected -> "已连接"; status.getBoolean(key) -> "待更新"; key == "settings" -> "等待打开"; else -> "未连接" },
                            if(connected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    })
                }
            }
        }
        Card {
            ArrowPreference("功能设置", summary = "按键、弧形菜单与应用配置", startAction = { Leading(MiuixIcons.Regular.Settings) }, onClick = ::nativeSettings)
            ArrowPreference("使用指南", summary = "了解手写笔的功能与操作方式", startAction = { Leading(MiuixIcons.Regular.Help) }, onClick = ::guide)
        }
        Group("版本信息") {
            Card {
                BasicComponent(title = "模块版本", summary = BuildConfig.VERSION_NAME, startAction = { Leading(MiuixIcons.Regular.Info) })
                BasicComponent(title = "配置版本", summary = "Schema ${ConfigSpec.SCHEMA} · 修订 $revision", startAction = { Leading(MiuixIcons.Regular.Backup) })
            }
        }
    }
    @Composable private fun Backup() {
        Card {
            BasicComponent(title = "保留你的使用习惯", summary = "将全局设置、应用配置和模板保存为备份，随时恢复。",
                startAction = { Leading(MiuixIcons.Regular.Backup, MiuixTheme.colorScheme.primary) })
        }
        Card {
            ArrowPreference("导出设置", summary = "保存到你选择的位置", startAction = { Leading(MiuixIcons.Regular.Download) }, enabled = !busy,
                onClick = { exportDocument.launch("FocusPen-settings.json") })
            ArrowPreference("导入设置", summary = "从备份恢复，确认后替换当前设置", startAction = { Leading(MiuixIcons.Regular.Import) }, enabled = !busy,
                onClick = { importDocument.launch(arrayOf("application/json", "text/plain")) })
        }
        if(busy) Text("正在处理文件…", modifier = Modifier.padding(16.dp))
        Group("备份包含") {
            Card {
                BasicComponent(title = "按键与手势", summary = "全局行为、应用配置与模板", startAction = { Leading(MiuixIcons.Regular.Tune) })
                BasicComponent(title = "书写与快捷功能", summary = "弧形菜单、笔尖预览、书写声音与息屏速记", startAction = { Leading(MiuixIcons.Regular.Edit) })
            }
        }
        Text("支持当前配置版本的完整备份。导入前会校验内容，确认后自动同步到已连接的宿主。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    @Composable private fun Debug() {
        Card { BasicComponent(title = "笔型预览", summary = "查看不同型号对应的原生设置与教程。",
            startAction = { Leading(MiuixIcons.Regular.SearchDevice, MiuixTheme.colorScheme.primary) }) }
        Group("预览型号") {
            Card {
                listOf("auto" to "跟随已连接的笔", "standard" to "标准版焦点笔", "pro" to "焦点笔 Pro").forEach { (id,title) ->
                    BasicComponent(title = title, onClick = { selectPreview(id) }, endActions = {
                        RadioButton(selected = preview == id, onClick = { selectPreview(id) })
                    })
                }
            }
        }
        Card { ArrowPreference("打开笔设置", summary = "查看所选型号的界面", startAction = { Leading(MiuixIcons.Regular.Settings) }, onClick = ::nativeSettings) }
        Text("预览只改变界面展示。Pro 的实际手势与鼠标行为仍需对应硬件验证。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    private fun selectPreview(id: String) {
        runCatching { UiPreferences.model(this,id); preview = id }.onFailure { message = "未能保存预览选项" }
    }
    @Composable private fun About() {
        Card {
            Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(R.drawable.ic_focuspen), null, modifier = Modifier.size(64.dp), tint = Color.Unspecified)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("焦点笔增强", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(BuildConfig.VERSION_NAME, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text("让手写笔更合你的习惯", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        Group("特别感谢") {
            Card {
                Link("Stylus Plus", "ljlVink · 标准版焦点笔鼠标参考", "https://github.com/ljlVink/StylusPlus", MiuixIcons.Regular.Edit)
                Link("FocusPen Pro X", "HMQYHM · Pro 手势与鼠标参考", "https://github.com/HMQYHM/FocusPenProX", MiuixIcons.Regular.Tune)
            }
        }
        Group("界面与参考项目") {
            Card {
                Link("MIUIX", "独立应用的界面、图标与交互组件", "https://github.com/compose-miuix-ui/miuix", MiuixIcons.Regular.Layers)
                Link("KernelSU", "导航与自适应布局参考", "https://github.com/tiann/KernelSU", MiuixIcons.Regular.Home)
                Link("LSPosed", "模块运行框架与管理界面参考", "https://github.com/LSPosed/LSPosed", MiuixIcons.Regular.Settings)
            }
        }
        Group("开源依赖") {
            Card {
                Link("Kotlin 与协程", "Kotlin 2.4.20 · Apache-2.0", "https://github.com/JetBrains/kotlin")
                Link("Compose Multiplatform", "1.12.0 · Apache-2.0", "https://github.com/JetBrains/compose-multiplatform")
                Link("AndroidX", "Activity、Lifecycle 等 · Apache-2.0", "https://android.googlesource.com/platform/frameworks/support/")
                Link("Xposed / libxposed API", "框架接口 · Apache-2.0", "https://github.com/libxposed/api")
                Link("Material Color Utilities", "MaterialKolor 5.0.1 · MIT", "https://github.com/jordond/MaterialKolor")
                ArrowPreference("开源许可与来源", summary = "查看许可文本、版本与来源说明", onClick = {
                    lifecycleScope.launch {
                        licenseText = withContext(Dispatchers.IO) { assets.open("open-source-notices.txt").bufferedReader().use { it.readText() } }
                    }
                })
            }
        }
        Text("教程素材来自设备上的小米系统应用，版权归原权利人所有。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    @Composable private fun Link(title: String, detail: String, url: String, icon: ImageVector? = null) {
        ArrowPreference(title, summary = detail, startAction = icon?.let { { Leading(it) } }, onClick = { launch(Intent(Intent.ACTION_VIEW,Uri.parse(url))) })
    }
}
