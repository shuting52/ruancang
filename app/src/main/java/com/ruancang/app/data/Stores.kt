package com.ruancang.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本机设置：GitHub 连接 + 自定义主题代码。
 * Token 只存本机 SharedPreferences（ruancang_prefs），不进代码、不进仓库、不上报。
 * 换手机 / 卸载重装后需要重新粘贴 Token，这是故意的——Token 不应跟着源码走。
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ruancang_prefs", Context.MODE_PRIVATE)

    var githubConfig: GithubConfig
        get() = GithubConfig(
            owner = prefs.getString("gh_owner", "shuting52") ?: "shuting52",
            repo = prefs.getString("gh_repo", "ruancang") ?: "ruancang",
            branch = prefs.getString("gh_branch", "main") ?: "main",
            token = prefs.getString("gh_token", "") ?: ""
        )
        set(value) {
            prefs.edit()
                .putString("gh_owner", value.owner)
                .putString("gh_repo", value.repo)
                .putString("gh_branch", value.branch)
                .putString("gh_token", value.token)
                .apply()
        }

    var customCode: String
        get() = prefs.getString("custom_code", "") ?: ""
        set(value) { prefs.edit().putString("custom_code", value).apply() }

    var customTheme: NeuThemeColors
        get() = ThemeParser.parse(customCode)
        set(value) { /* 由 customCode 派生，不单独存 */ }
}

/** NEU 柔影主题色板，默认值 = 所给 HTML 里的 :root。 */
data class NeuThemeColors(
    val bg: Color = Color(0xFFE0E8EF),
    val text: Color = Color(0xFF7E97B8),
    val textDark: Color = Color(0xFF516D91),
    val blue: Color = Color(0xFF5F9DE7),
    val blueDark: Color = Color(0xFF3F7FC4),
    val gold: Color = Color(0xFFF0A93C),
    val corner: Float = 24f // 对应 CSS --r:24px
) {
    companion object { val Default = NeuThemeColors() }
}

object ThemeParser {
    private val HEX = Regex("#([0-9a-fA-F]{6})")

    private fun hexToColor(hex: String): Color? {
        val m = HEX.find(hex) ?: return null
        return try {
            Color(("FF" + m.groupValues[1]).toLong(16))
        } catch (e: Exception) { null }
    }

    private fun findVar(code: String, name: String): Color? {
        // 匹配 --bg:#e0e8ef / --bg: #e0e8ef; / background:#e0e8ef 等写法
        val regex = Regex("--$name\\s*:\\s*(#[0-9a-fA-F]{6})")
        val m = regex.find(code) ?: return null
        return hexToColor(m.groupValues[1])
    }

    /**
     * 解析用户粘贴的 HTML / CSS 代码。
     * 能解析：:root 里的 --bg / --txt / --txt-d / --blue / --blue-d / --gold / --r，
     * 以及 <style> 块里的同名变量。解析不到的项保持默认值。
     *
     * 诚实说明：原生 App 不能把一段 HTML 布局直接当成界面跑起来；
     * 这里“全局应用”指的是把代码里的设计 token（颜色、圆角）提取出来，
     * 全 App 的按钮、卡片、Tab、进度条立刻换成这套 token。
     * 想要整页 HTML 效果，请用“主题”页的 WebView 预览（见 README）。
     */
    private fun luminance(c: Color): Float =
        0.299f * c.red + 0.587f * c.green + 0.114f * c.blue

    fun parse(code: String): NeuThemeColors {
        if (code.isBlank()) return NeuThemeColors.Default
        val style = Regex("<style[^>]*>(.*?)</style>", RegexOption.DOT_MATCHES_ALL)
            .find(code)?.groupValues?.get(1) ?: code
        val corner = Regex("--r\\s*:\\s*([0-9]+)px").find(style)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: NeuThemeColors.Default.corner
        val bg = findVar(style, "bg") ?: NeuThemeColors.Default.bg
        var text = findVar(style, "txt") ?: NeuThemeColors.Default.text
        var textDark = findVar(style, "txt-d") ?: NeuThemeColors.Default.textDark
        // v1.0.1：文字/背景冲突的兜底。只改了背景没改文字时，
        // 深底配深字、浅底配浅字都会看不清，这里按背景亮度强制拉开对比：
        // 深底 -> 文字一律提亮，浅底 -> 过浅的深色字压回默认深色。
        if (luminance(bg) < 0.45f) {
            if (luminance(text) < 0.6f) text = Color(0xFFC7D6E8)
            if (luminance(textDark) < 0.6f) textDark = Color(0xFFF2F7FC)
        } else {
            if (luminance(textDark) > 0.6f) textDark = NeuThemeColors.Default.textDark
            if (luminance(text) > 0.85f) text = NeuThemeColors.Default.text
        }
        return NeuThemeColors(
            bg = bg,
            text = text,
            textDark = textDark,
            blue = findVar(style, "blue") ?: NeuThemeColors.Default.blue,
            blueDark = findVar(style, "blue-d") ?: NeuThemeColors.Default.blueDark,
            gold = findVar(style, "gold") ?: NeuThemeColors.Default.gold,
            corner = corner
        )
    }

    /** 解析结果摘要，供“主题”页展示“应用后识别到了什么”。 */
    fun summary(code: String): String {
        if (code.isBlank()) return "未输入自定义代码，当前为默认柔影主题。"
        val t = parse(code)
        fun hex(c: Color): String {
            val r = (c.red * 255).toInt()
            val g = (c.green * 255).toInt()
            val b = (c.blue * 255).toInt()
            return "#%02X%02X%02X".format(r, g, b)
        }
        return "已识别：背景 ${hex(t.bg)} · 主色 ${hex(t.blue)} · 深色 ${hex(t.blueDark)} · 文字 ${hex(t.text)} · 圆角 ${t.corner.toInt()}px"
    }
}

/** 主题状态：MainActivity 持有，上传窗口、仓库页、设置页共用同一份。 */
class ThemeViewModel {
    private val _colors = MutableStateFlow(NeuThemeColors.Default)
    val colors: StateFlow<NeuThemeColors> = _colors.asStateFlow()

    fun applyCode(code: String) { _colors.value = ThemeParser.parse(code) }
    fun reset() { _colors.value = NeuThemeColors.Default }
}
