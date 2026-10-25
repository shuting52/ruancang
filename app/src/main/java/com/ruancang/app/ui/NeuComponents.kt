package com.ruancang.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruancang.app.data.NeuThemeColors

/**
 * NEU 柔影组件的 Compose 翻译（对照所给 HTML）：
 *  - 表面与背景同色 #E0E8EF，靠双向阴影塑形；
 *  - 描边统一 2px rgba(255,255,255,.333)；
 *  - 按钮全大写感觉用字距 + 粗细近似（Compose 无 letterSpacing 大写签名之外的等价物，
 *    这里用 FontWeight + 较宽字距近似，见 letterSpacing 参数）。
 * Compose 的 shadow 只有单向，无法 1:1 复刻“左上白 + 右下蓝”双阴影，
 * 因此这里用：外层 shadow（蓝向）+ 2px 白描边 + 同色背景，视觉上已是柔影；
 * 想要像素级双阴影，可在后续版本用 drawBehind 手绘两层阴影。
 */

@Composable
fun NeuButton(
    text: String,
    onClick: () -> Unit,
    theme: NeuThemeColors,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.shadow(8.dp, RoundedCornerShape(40.dp)),
        shape = RoundedCornerShape(40.dp),
        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.33f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) theme.blue else theme.bg,
            // v1.0.1：非主按钮之前用浅灰 theme.text，浅底上几乎看不清，改深色字
            contentColor = if (primary) Color.White else theme.textDark,
            disabledContainerColor = theme.bg,
            disabledContentColor = theme.textDark.copy(alpha = 0.55f)
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp
        )
    }
}

@Composable
fun NeuCard(
    theme: NeuThemeColors,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(theme.corner.dp)
    Box(
        modifier = modifier
            .shadow(10.dp, shape)
            .background(theme.bg, shape)
            .border(2.dp, Color.White.copy(alpha = 0.33f), shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(16.dp)
    ) { content() }
}

@Composable
fun NeuChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    theme: NeuThemeColors,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(32.dp)
    Box(
        modifier = modifier
            .shadow(if (selected) 2.dp else 6.dp, shape)
            .background(
                if (selected) theme.blue.copy(alpha = 0.07f) else theme.bg,
                shape
            )
            .border(2.dp, Color.White.copy(alpha = 0.33f), shape)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) theme.blueDark else theme.textDark
        )
    }
}

@Composable
fun NeuField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    theme: NeuThemeColors,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    placeholder: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = theme.textDark) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = theme.text.copy(alpha = 0.6f)) },
        singleLine = singleLine,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = theme.bg,
            unfocusedContainerColor = theme.bg,
            focusedTextColor = theme.textDark,
            unfocusedTextColor = theme.textDark,
            cursorColor = theme.blue,
            focusedIndicatorColor = theme.blue,
            unfocusedIndicatorColor = Color.White.copy(alpha = 0.33f)
        )
    )
}

@Composable
fun NeuProgress(
    progress: Float,
    theme: NeuThemeColors,
    modifier: Modifier = Modifier
) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth(),
        color = theme.blue,
        trackColor = theme.bg
    )
}

@Composable
fun NeuTitle(text: String, theme: NeuThemeColors, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = theme.textDark,
        style = MaterialTheme.typography.titleMedium
    )
}
