package com.ruancang.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruancang.app.data.GithubApi
import com.ruancang.app.data.GithubFile
import com.ruancang.app.data.NeuThemeColors
import com.ruancang.app.data.SettingsStore
import com.ruancang.app.data.ThemeParser
import com.ruancang.app.data.ThemeViewModel
import com.ruancang.app.model.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab { WAREHOUSE, SETTINGS, THEME }

@Composable
fun WarehouseScreen(
    theme: NeuThemeColors,
    settings: SettingsStore,
    themeVm: ThemeViewModel
) {
    var tab by remember { mutableStateOf(Tab.WAREHOUSE) }
    var showUpload by remember { mutableStateOf(false) }
    val config = remember(tab) { settings.githubConfig }

    Box(modifier = Modifier.fillMaxSize().background(theme.bg)) {
        // v1.0.1：之前开了全屏沉浸却没留安全区，底部栏会被系统导航条盖住、
        // 顶部会被状态栏盖住，设置 Tab 看着像没了。这里强制留出安全区。
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // 顶部栏：对应 HTML .appbar（logo 软 + 标题 + 副标题 + 右上角设置按钮）
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(theme.blue, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("软", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                Column(modifier = Modifier.weight(1f)) {
                    Text("软仓", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 1.sp)
                    Text("NEU UI · 柔影 · GitHub 仓库", color = theme.textDark, fontSize = 11.sp, letterSpacing = 1.sp)
                }
                // v1.0.1：设置不再只靠底部 Tab，顶部常驻一个齿轮按钮
                TextButton(onClick = { tab = Tab.SETTINGS }) {
                    Text("⚙ 设置", color = theme.blueDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.WAREHOUSE -> WarehousePage(theme, config, onUpload = { showUpload = true })
                    Tab.SETTINGS -> SettingsPage(theme, settings)
                    Tab.THEME -> ThemePage(theme, settings, themeVm)
                }
            }

            // 底部 Tab 栏：v1.0.1 之前底栏和页面同色、无阴影无描边，
            // 未选中字还是浅灰 theme.text，看着像一排淡字不是按钮。
            // 现在给底栏加阴影+白描边，选中是蓝底白字，未选中是深色字。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .shadow(8.dp, RoundedCornerShape(32.dp))
                    .background(theme.bg, RoundedCornerShape(32.dp))
                    .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.33f)), RoundedCornerShape(32.dp))
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TabButton("仓库", tab == Tab.WAREHOUSE, theme) { tab = Tab.WAREHOUSE }
                TabButton("＋ 上传", false, theme) { showUpload = true }
                TabButton("设置", tab == Tab.SETTINGS, theme) { tab = Tab.SETTINGS }
                TabButton("主题", tab == Tab.THEME, theme) { tab = Tab.THEME }
            }
        }
    }

    if (showUpload) {
        val cfg = settings.githubConfig
        UploadWindow(
            theme = theme,
            githubApi = GithubApi(cfg),
            tokenMissing = cfg.token.isBlank(),
            onClose = { showUpload = false }
        )
    }
}

@Composable
private fun TabButton(text: String, selected: Boolean, theme: NeuThemeColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) theme.blue else Color.Transparent,
                RoundedCornerShape(24.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else theme.textDark,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun WarehousePage(
    theme: NeuThemeColors,
    config: com.ruancang.app.data.GithubConfig,
    onUpload: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Category?>(null) }
    var items by remember { mutableStateOf<List<GithubFile>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (config.token.isBlank()) {
            status = "还没填 GitHub Token：先去“设置”页粘贴（只存本机），再点刷新。"
            items = emptyList()
            return
        }
        loading = true
        status = "正在读取仓库…"
        scope.launch(Dispatchers.IO) {
            val api = GithubApi(config)
            val result = if (selected == null) api.listRoot() else api.listCategory(selected!!.folder)
            withContext(Dispatchers.Main) {
                loading = false
                result.onSuccess {
                    items = it
                    status = if (it.isEmpty()) "这个类别还是空的，传第一个文件它就会出现。" else "共 ${it.size} 项"
                }.onFailure { e -> status = "读取失败：${e.message}" }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        NeuButton("＋ 上传应用程序 / 文件", onClick = onUpload, theme = theme, primary = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        NeuField(query, { query = it }, "搜索仓库", theme, placeholder = "搜名称，如：微信、壁纸…")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeuChip("全部", selected == null, { selected = null; refresh() }, theme)
            NeuChip("应用", selected == Category.APPS, { selected = Category.APPS; refresh() }, theme)
            NeuChip("图片", selected == Category.IMAGES, { selected = Category.IMAGES; refresh() }, theme)
            NeuChip("视频", selected == Category.VIDEOS, { selected = Category.VIDEOS; refresh() }, theme)
        }
        Spacer(Modifier.height(8.dp))
        // v1.0.1：之前这排 6 个芯片没有横滑，窄屏上直接被裁掉。
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Category.values().filter { it !in listOf(Category.APPS, Category.IMAGES, Category.VIDEOS) }.forEach { c ->
                NeuChip("${c.emoji} ${c.label}", selected == c, { selected = c; refresh() }, theme)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(status, color = theme.textDark, fontSize = 12.sp)
            TextButton(onClick = { refresh() }) { Text(if (loading) "读取中…" else "刷新", color = theme.blueDark) }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }) { file ->
                NeuCard(theme = theme) {
                    Column {
                        Text("${Category.fromFolder(file.path.substringBefore('/')).emoji} ${file.name}", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(file.path, color = theme.text, fontSize = 12.sp)
                        if (file.htmlUrl.isNotEmpty()) Text("在 GitHub 打开：${file.htmlUrl}", color = theme.blue, fontSize = 11.sp)
                    }
                }
            }
        }
        if (items.isEmpty() && !loading) {
            Spacer(Modifier.height(16.dp))
            Text("仓库默认地址：github.com/${config.owner}/${config.repo}（分支 ${config.branch}）", color = theme.textDark, fontSize = 12.sp)
            Text("上传后这里会按类别自动出现文件夹：apps / images / videos / documents / archives / code / fonts / others。", color = theme.textDark, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun SettingsPage(theme: NeuThemeColors, settings: SettingsStore) {
    var owner by remember { mutableStateOf(settings.githubConfig.owner) }
    var repo by remember { mutableStateOf(settings.githubConfig.repo) }
    var branch by remember { mutableStateOf(settings.githubConfig.branch) }
    var token by remember { mutableStateOf(settings.githubConfig.token) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        NeuTitle("GitHub 连接", theme)
        Spacer(Modifier.height(6.dp))
        Text("Token 只存本机，绝不写进代码、绝不随源码提交。建议用细粒度 Token，只给 ruancang 这一个仓库的 Contents 读写权限。", color = theme.text, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(14.dp))
        NeuField(owner, { owner = it }, "仓库所有者 Owner", theme)
        Spacer(Modifier.height(10.dp))
        NeuField(repo, { repo = it }, "仓库名 Repo", theme)
        Spacer(Modifier.height(10.dp))
        NeuField(branch, { branch = it }, "分支 Branch", theme)
        Spacer(Modifier.height(10.dp))
        NeuField(token, { token = it }, "GitHub Token（粘贴后只存本机）", theme, placeholder = "ghp_… 或 github_pat_…")
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeuButton("保存", onClick = {
                settings.githubConfig = com.ruancang.app.data.GithubConfig(owner.trim(), repo.trim(), branch.trim(), token.trim())
                status = "已保存到本机。点“测试连接”确认能读到仓库。"
            }, theme = theme, primary = true, modifier = Modifier.weight(1f))
            NeuButton("测试连接", onClick = {
                val cfg = com.ruancang.app.data.GithubConfig(owner.trim(), repo.trim(), branch.trim(), token.trim())
                status = "测试中…"
                scope.launch(Dispatchers.IO) {
                    val r = GithubApi(cfg).testConnection()
                    withContext(Dispatchers.Main) {
                        r.onSuccess { status = "✅ 连通！默认分支：$it，仓库：${cfg.owner}/${cfg.repo}" }
                            .onFailure { status = "❌ ${it.message}" }
                    }
                }
            }, theme = theme, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        NeuButton("清除本机 Token", onClick = {
            settings.githubConfig = settings.githubConfig.copy(token = "")
            token = ""
            status = "本机 Token 已清除。"
        }, theme = theme, modifier = Modifier.fillMaxWidth())
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(status, color = theme.textDark, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text("安全提醒：任何曾经出现在聊天记录、截图里的 Token 都应视为已泄露，去 GitHub Settings → Developer settings → Tokens 里吊销并换新，再粘贴到这里。", color = theme.text, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun ThemePage(theme: NeuThemeColors, settings: SettingsStore, themeVm: ThemeViewModel) {
    var code by remember { mutableStateOf(settings.customCode) }
    var summary by remember { mutableStateOf(ThemeParser.summary(code)) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        NeuTitle("自定义主题代码", theme)
        Spacer(Modifier.height(6.dp))
        Text("把 HTML / CSS 代码粘贴进来，点“应用”：App 会提取里面的 --bg / --blue / --txt 等设计 token，全局换到按钮、卡片、Tab、进度条上。", color = theme.text, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(6.dp))
        Text("注意：原生 App 跑不了整页 HTML 布局，能全局应用的是颜色与圆角这些 token；整页 HTML 请用浏览器打开预览。", color = theme.text, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(14.dp))
        NeuField(code, { code = it }, "HTML / CSS 代码", theme, singleLine = false, placeholder = "粘贴 <style>…:root{ --bg:#e0e8ef; --blue:#5f9de7; … }…</style>")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeuButton("应用到全局", onClick = {
                settings.customCode = code
                themeVm.applyCode(code)
                summary = ThemeParser.summary(code)
            }, theme = theme, primary = true, modifier = Modifier.weight(1f))
            NeuButton("恢复默认柔影", onClick = {
                code = ""
                settings.customCode = ""
                themeVm.reset()
                summary = "已恢复默认柔影主题。"
            }, theme = theme, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text(summary, color = theme.blueDark, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(14.dp))
        NeuCard(theme = theme) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("实时预览：下面这些组件用的就是当前全局主题", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                NeuButton("柔影按钮", onClick = {}, theme = theme)
                NeuButton("蓝色按钮", onClick = {}, theme = theme, primary = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeuChip("芯片", true, {}, theme)
                    NeuChip("芯片", false, {}, theme)
                }
                NeuProgress(0.72f, theme)
                Text("进度 72% · 卡片圆角 ${theme.corner.toInt()}px", color = theme.text, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
