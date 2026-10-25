package com.ruancang.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ruancang.app.data.NeuThemeColors
import com.ruancang.app.data.UpdateInfo

/** 把更新日志拆成条目：先按换行，再按中文分号；单条过长时按句号拆。 */
fun changelogItems(changelog: String): List<String> {
    if (changelog.isBlank()) return emptyList()
    val byLine = changelog.split("\n", "；", ";").map { it.trim() }.filter { it.isNotEmpty() }
    if (byLine.size > 1) return byLine
    val single = byLine.firstOrNull() ?: return emptyList()
    val bySentence = single.split("。").map { it.trim() }.filter { it.isNotEmpty() }
    return if (bySentence.size > 1) bySentence else listOf(single)
}

/**
 * 自更新弹窗 v1.0.4 内容优化：
 *  - 顶部一眼看清：当前版 → 新版、安装包大小、可直接覆盖安装（不用先卸载）；
 *  - 更新内容拆成 • 条目，不再是一大段挤在一起；
 *  - 下载进度带百分比和已下/总量；
 *  - 下载完成后自动打开安装器（调用方在下载成功时直接调安装），
 *    这里的“立即安装”只是兜底：自动安装没弹出来时再点一次。
 */
@Composable
fun UpdateDialog(
    theme: NeuThemeColors,
    info: UpdateInfo,
    currentVersionName: String,
    progress: Float,
    progressText: String,
    downloading: Boolean,
    downloaded: Boolean,
    status: String,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = { if (!downloading) onClose() }) {
        NeuCard(theme = theme) {
            Column {
                Text("🎉 发现新版本 ${info.versionName}", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(8.dp))
                Text("当前 $currentVersionName  →  新版 ${info.versionName}（code ${info.versionCode}）", color = theme.textDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                // v1.0.6：用户要求删掉“安装包约…·可直接覆盖安装，不用先卸载旧版”这一行，
                // 这里整段删掉，只留上面当前版 → 新版这一行。
                val items = changelogItems(info.changelog)
                if (items.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("更新内容", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    items.forEach { item ->
                        Text("• $item", color = theme.textDark, fontSize = 13.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(3.dp))
                    }
                }
                if (downloading || progress > 0f) {
                    Spacer(Modifier.height(12.dp))
                    NeuProgress(progress, theme)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (downloading) "下载中 ${(progress * 100).toInt()}% · $progressText" else progressText,
                        color = theme.blueDark, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = theme.textDark, fontSize = 13.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    if (downloaded) {
                        NeuButton("立即安装", onClick = onInstall, theme = theme, primary = true, modifier = Modifier.weight(1f))
                    } else if (!downloading) {
                        NeuButton("立即更新", onClick = onDownload, theme = theme, primary = true, modifier = Modifier.weight(1f))
                    } else {
                        NeuButton("取消下载", onClick = onCancel, theme = theme, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = { if (!downloading) onClose() }) {
                        Text(if (downloading) "下载中…" else "稍后", color = theme.textDark)
                    }
                }
                if (downloaded) {
                    Spacer(Modifier.height(8.dp))
                    Text("下载完成后会自动打开安装器；若安装器没弹出来，点“立即安装”再试一次。安装完成后旧的缓存安装包会在下次打开 App 时自动清理。", color = theme.textDark, fontSize = 12.sp, lineHeight = 17.sp)
                } else if (!downloading) {
                    Spacer(Modifier.height(8.dp))
                    Text("在旧版里直接下载安装，不用去浏览器找链接；若提示安装未知来源应用，给软仓开一次权限即可。", color = theme.textDark, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}
