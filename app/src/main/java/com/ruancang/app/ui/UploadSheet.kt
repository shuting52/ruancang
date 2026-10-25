package com.ruancang.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ruancang.app.data.GithubApi
import com.ruancang.app.data.NeuThemeColors
import com.ruancang.app.data.UploadManager
import com.ruancang.app.data.UploadSource
import com.ruancang.app.data.UploadTarget
import com.ruancang.app.data.listInstalledApps
import com.ruancang.app.model.Category
import com.ruancang.app.model.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 独立的上传窗口：不是底部小弹窗，是占满屏的 Dialog，
 * 点底部“＋ 上传”打开，关掉不影响仓库页。
 *
 * 窗口内顺序：①选来源（文件 / 已安装应用）②自动识别类别（可手动改）
 * ③截图 ④名称+说明 ⑤上传（实时进度+日志）⑥完成后的 GitHub 链接。
 */
@Composable
fun UploadWindow(
    theme: NeuThemeColors,
    githubApi: GithubApi,
    tokenMissing: Boolean = false,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<UploadSource?>(null) }
    var category by remember { mutableStateOf(Category.OTHERS) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val screenshots = remember { mutableStateListOf<Uri>() }
    var showAppPicker by remember { mutableStateOf(false) }
    var includeSystemApps by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var log by remember { mutableStateOf("") }
    var resultUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun appendLog(line: String) { log = if (log.isEmpty()) line else "$log\n$line" }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (name, size) = queryFileInfo(context, uri)
        val mime = context.contentResolver.getType(uri)
        source = UploadSource.FileUri(uri, name, mime, size)
        category = Category.detect(name, mime, false)
        if (title.isBlank()) title = name.substringBeforeLast('.')
        appendLog("已选文件：$name（${formatSize(size)}），自动识别为「${category.label}」")
    }
    val pickScreenshots = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        screenshots.addAll(uris)
        if (uris.isNotEmpty()) appendLog("已加 ${uris.size} 张截图")
    }

    Dialog(
        onDismissRequest = { if (!uploading) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.bg)
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    NeuTitle("上传到软仓", theme)
                    TextButton(onClick = { if (!uploading) onClose() }) {
                        Text("关闭", color = theme.textDark)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "选好文件或应用 → 自动分到对应类别 → 点上传，实时同步到 GitHub 仓库。",
                    color = theme.text, fontSize = 13.sp, lineHeight = 19.sp
                )
                Spacer(Modifier.height(16.dp))

                if (showAppPicker) {
                    AppPicker(
                        theme = theme,
                        includeSystem = includeSystemApps,
                        onToggleSystem = { includeSystemApps = !includeSystemApps },
                        onPick = { app ->
                            source = app
                            category = Category.APPS
                            if (title.isBlank()) title = app.label
                            showAppPicker = false
                            appendLog("已选应用：${app.label}（${app.packageName}，${app.versionName ?: ""}），类别固定为「应用」")
                        },
                        onBack = { showAppPicker = false }
                    )
                } else {
                    // ① 来源
                    NeuCard(theme = theme) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("1 · 选择一个应用程序 / 文件", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NeuButton("选择文件", onClick = { pickFile.launch("*/*") }, theme = theme, modifier = Modifier.weight(1f))
                                NeuButton("已安装应用", onClick = { showAppPicker = true }, theme = theme, primary = true, modifier = Modifier.weight(1f))
                            }
                            when (val s = source) {
                                is UploadSource.FileUri -> Text(
                                    "文件：${s.displayName} · ${formatSize(s.size)}",
                                    color = theme.textDark, fontSize = 13.sp
                                )
                                is UploadSource.InstalledApp -> Text(
                                    "应用：${s.label} · ${s.packageName} · ${s.versionName ?: ""} · APK ${formatSize(s.apkFile.length())}",
                                    color = theme.textDark, fontSize = 13.sp
                                )
                                null -> Text("还没选，上面两个入口二选一。", color = theme.text, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    // ② 类别（自动识别 + 手动改）
                    NeuCard(theme = theme) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("2 · 类别：自动识别，可手动改", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                "当前：${category.emoji} ${category.label} → 仓库文件夹 ${category.folder}/",
                                color = theme.blueDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                // 一行放不下全部类别时，LazyRow 更稳；这里用可换行近似：分两行芯片
                            }
                            Category.values().chunked(4).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { c ->
                                        NeuChip(
                                            text = "${c.emoji} ${c.label}",
                                            selected = c == category,
                                            onClick = { category = c; appendLog("类别手动改为「${c.label}」") },
                                            theme = theme
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    // ③ 截图
                    NeuCard(theme = theme) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("3 · 截图：让别人一看就知道这是干什么的", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            NeuButton(
                                if (screenshots.isEmpty()) "添加截图（可多选）" else "再加截图（已选 ${screenshots.size} 张）",
                                onClick = { pickScreenshots.launch("image/*") },
                                theme = theme
                            )
                            if (screenshots.isNotEmpty()) {
                                TextButton(onClick = { screenshots.clear(); appendLog("截图已清空") }) {
                                    Text("清空截图", color = theme.text)
                                }
                            }
                            Text("截图会传到 <类别>/<名称>/screenshots/，并计入 info.json。", color = theme.text, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    // ④ 名称 + 说明
                    NeuCard(theme = theme) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("4 · 名称与说明", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            NeuField(title, { title = it }, "名称", theme, placeholder = "如：微信")
                            NeuField(description, { description = it }, "说明（这个文件是干什么的）", theme, singleLine = false, placeholder = "一句话说明用途、版本、注意事项…")
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // ⑤ 上传
                    NeuButton(
                        text = if (uploading) "上传中…" else "上传应用程序 / 文件",
                        onClick = {
                            val src = source
                            if (src == null) { error = "先选一个文件或应用。"; return@NeuButton }
                            if (tokenMissing) {
                                error = "先去“设置”页填 GitHub Token（只存本机），再回来上传。"
                                return@NeuButton
                            }
                            uploading = true; progress = 0f; error = ""; resultUrl = ""
                            scope.launch(Dispatchers.IO) {
                                val result = UploadManager(githubApi).upload(
                                    context = context,
                                    target = UploadTarget(src, category, title, description, screenshots.toList()),
                                    onProgress = { p -> progress = p },
                                    onLog = { line -> appendLog(line) }
                                )
                                withContext(Dispatchers.Main) {
                                    uploading = false
                                    result.onSuccess { url ->
                                        resultUrl = url
                                        appendLog("✅ 全部同步完成")
                                    }.onFailure { e ->
                                        error = e.message ?: "上传失败"
                                        appendLog("❌ ${e.message}")
                                    }
                                }
                            }
                        },
                        theme = theme, primary = true, modifier = Modifier.fillMaxWidth(),
                        enabled = !uploading
                    )
                    if (uploading || progress > 0f) {
                        Spacer(Modifier.height(12.dp))
                        NeuProgress(progress, theme)
                        Text("${(progress * 100).toInt()}%", color = theme.blueDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (error.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("⚠️ $error", color = Color(0xFFC0392B), fontSize = 13.sp)
                    }
                    if (resultUrl.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        NeuCard(theme = theme) {
                            Column {
                                Text("✅ 已同步到 GitHub", color = theme.blueDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(resultUrl, color = theme.text, fontSize = 12.sp)
                                Text("仓库路径：${category.folder}/${title.ifBlank { "file" }}/", color = theme.text, fontSize = 12.sp)
                            }
                        }
                    }
                    if (log.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("上传日志", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(log, color = theme.text, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

private fun queryFileInfo(context: Context, uri: Uri): Pair<String, Long> {
    var name = "file"
    var size = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
        }
    }
    if (size == 0L) {
        // SAF 有时不给 SIZE；流式上传不依赖这个值，进度会按已传字节显示
        size = -1L
    }
    return name to size
}

@Composable
private fun AppPicker(
    theme: NeuThemeColors,
    includeSystem: Boolean,
    onToggleSystem: () -> Unit,
    onPick: (UploadSource.InstalledApp) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val apps = remember(includeSystem) { listInstalledApps(context, includeSystem) }
    Column {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("选择已安装应用（${apps.size} 个）", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            TextButton(onClick = onBack) { Text("返回", color = theme.textDark) }
        }
        NeuChip(
            text = if (includeSystem) "☑ 含系统应用" else "☐ 只看用户应用",
            selected = includeSystem,
            onClick = onToggleSystem,
            theme = theme
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.height(420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(apps) { app ->
                NeuCard(theme = theme, onClick = { onPick(app) }) {
                    Column {
                        Text(app.label, color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${app.packageName} · ${app.versionName ?: ""} · APK ${formatSize(app.apkFile.length())}", color = theme.text, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
