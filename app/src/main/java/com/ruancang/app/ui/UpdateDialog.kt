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
import com.ruancang.app.model.formatSize

/**
 * 自更新弹窗：不是“去浏览器下载”，而是旧版里直接下新版、点安装。
 * 状态由调用方（仓库页）持有：progress / downloading / downloaded / status。
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
                Text("发现新版本 ${info.versionName}", color = theme.textDark, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(6.dp))
                Text("当前：$currentVersionName → 新版：${info.versionName}（code ${info.versionCode}）", color = theme.textDark, fontSize = 13.sp)
                if (info.size > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("安装包约 ${formatSize(info.size)}，在旧版里直接下载安装，不用去浏览器找链接。", color = theme.textDark, fontSize = 13.sp, lineHeight = 18.sp)
                }
                if (info.changelog.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(info.changelog, color = theme.textDark, fontSize = 13.sp, lineHeight = 18.sp)
                }
                if (downloading || progress > 0f) {
                    Spacer(Modifier.height(12.dp))
                    NeuProgress(progress, theme)
                    Spacer(Modifier.height(6.dp))
                    Text(progressText, color = theme.blueDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                    Text("下载完成：点“立即安装”会打开系统安装器；若提示安装未知来源应用，给软仓开一次权限即可。", color = theme.textDark, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}
