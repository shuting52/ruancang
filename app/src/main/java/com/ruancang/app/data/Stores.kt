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
            // v1.0.4：读出来的是解密后的明文（供 API 调用），
            // 但 SharedPreferences 里存的是 ENC1: 加密串，老明文值也能照常读。
            token = TokenCrypto.decrypt(prefs.getString("gh_token", "") ?: "")
        )
        set(value) {
            prefs.edit()
                .putString("gh_owner", value.owner)
                .putString("gh_repo", value.repo)
                .putString("gh_branch", value.branch)
                .putString("gh_token", TokenCrypto.encrypt(value.token))
                .apply()
        }

    /** 更新缓存清理：删 cache/update 下的残留 APK 和断点记录，返回清掉的字节数。 */
    fun cleanupUpdateCache(context: android.content.Context): Long {
        val dir = java.io.File(context.cacheDir, "update")
        if (!dir.exists()) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            freed += f.length()
            f.delete()
        }
        return freed
    }

    /** 待安装的新版 code：下载完成时记下，装上新版后启动时据此清理残留 APK。 */
    var pendingUpdateCode: Long
        get() = prefs.getLong("pending_update_code", 0L)
        set(value) { prefs.edit().putLong("pending_update_code", value).apply() }

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

    private fun colorFromValue(value: String): Color? {
        val v = value.trim()
        if (v.startsWith("#")) {
            if (v.length == 7) return hexToColor(v)
            if (v.length == 4) {
                val r = v[1].toString().repeat(2)
                val g = v[2].toString().repeat(2)
                val b = v[3].toString().repeat(2)
                return hexToColor("#$r$g$b")
            }
            return null
        }
        val rgb = Regex("rgba?\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").find(v) ?: return null
        return try {
            Color(
                rgb.groupValues[1].toInt().coerceIn(0, 255),
                rgb.groupValues[2].toInt().coerceIn(0, 255),
                rgb.groupValues[3].toInt().coerceIn(0, 255)
            )
        } catch (e: Exception) { null }
    }

    // v1.0.6 变量别名表。之前只认 --bg / --txt / --blue 等自家名字，
    // 外面拷来的 CSS 常用 --primary / --background / --text-color 等名字，
    // 一律认不到就等于没应用，这就是图三“应用全局没效果”的主因。
    private val ALIASES: Map<String, List<String>> = mapOf(
        "bg" to listOf("bg", "background", "background-color", "page-bg", "page-background", "surface", "app-bg"),
        "txt" to listOf("txt", "text", "text-color", "font-color", "muted", "secondary", "body-color"),
        "txt-d" to listOf("txt-d", "text-dark", "heading", "heading-color", "title", "title-color", "foreground"),
        "blue" to listOf("blue", "primary", "primary-color", "main", "main-color", "accent", "accent-color", "brand", "brand-color", "link"),
        "blue-d" to listOf("blue-d", "primary-dark", "primary-d", "accent-dark", "dark", "dark-color"),
        "gold" to listOf("gold", "warning", "highlight", "amber", "yellow", "star")
    )

    private fun findVar(code: String, key: String): Color? {
        return try { findVarUnsafe(code, key) } catch (e: Exception) { null } catch (e: StackOverflowError) { null }
    }

    private fun findVarUnsafe(code: String, key: String): Color? {
        for (name in ALIASES[key] ?: listOf(key)) {
            val regex = Regex("--$name\\s*:\\s*([^;\\n}]+)")
            val m = regex.find(code) ?: continue
            colorFromValue(m.groupValues[1])?.let { return it }
        }
        return null
    }

    private fun findProp(code: String, selectorHint: String, prop: String): Color? {
        // v1.0.8 闪退修复：之前这里用 ([^{}]*hint[^{}]*)\{([^}]*)} 做 findAll，
        // 在一段没有 CSS 大括号的 HTML 正文里（比如只粘了聊天 HTML 结构），
        // hint 这个词每出现一次，正则就要从当前位置一路试到下一个大括号或文末，
        // 大段 HTML 下是平方级扫描，启动时解析保存过的主题代码就可能卡死甚至被系统杀掉闪退。
        // 现在改按 } 切规则、逐条看是否含 hint 再取 { 后的体，线性扫描，不回溯。
        // 另外：调用方只在有 <style> 块时才调 findProp（见 parse），
        // 没有 style 块的纯 HTML 本来就没有 CSS 规则可找，没必要扫全文。
        return try {
            for (rule in code.split("}")) {
                if (!rule.contains(selectorHint)) continue
                val brace = rule.indexOf("{")
                if (brace < 0) continue
                val body = rule.substring(brace + 1)
                val m = Regex("$prop\s*:\s*(#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}|rgba?\s*\([^)]+\))")
                    .find(body) ?: continue
                colorFromValue(m.groupValues[1])?.let { return it }
            }
            null
        } catch (e: Exception) { null } catch (e: StackOverflowError) { null }
    }

    private fun luminance(c: Color): Float =
        0.299f * c.red + 0.587f * c.green + 0.114f * c.blue

    fun hasCustomTokens(code: String): Boolean {
        if (code.isBlank()) return false
        val t = parse(code)
        val d = NeuThemeColors.Default
        return t.bg != d.bg || t.blue != d.blue || t.blueDark != d.blueDark ||
            t.text != d.text || t.textDark != d.textDark || t.gold != d.gold ||
            t.corner != d.corner
    }

    fun parse(code: String): NeuThemeColors {
        return try { parseUnsafe(code) } catch (e: Exception) { NeuThemeColors.Default } catch (e: StackOverflowError) { NeuThemeColors.Default } catch (e: OutOfMemoryError) { NeuThemeColors.Default }
    }

    private fun parseUnsafe(code: String): NeuThemeColors {
        if (code.isBlank()) return NeuThemeColors.Default
        val styles = Regex("<style[^>]*>(.*?)</style>", RegexOption.DOT_MATCHES_ALL)
            .findAll(code).map { it.groupValues[1] }.toList()
        val hasStyle = styles.isNotEmpty()
        // v1.0.8：style 文本超过 100KB 只取前 100KB 解析，token 一般都在文件头，
        // 后面再长也不值得让启动时全量扫一遍。
        val style = if (hasStyle) styles.joinToString("\n").take(100 * 1024) else code
        val corner = Regex("--(?:r|radius|border-radius|corner)\\s*:\\s*([0-9]+)px").find(style)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: NeuThemeColors.Default.corner
        val bg = findVar(style, "bg")
            ?: (if (hasStyle) findProp(style, "body", "background-color") else null)
            ?: (if (hasStyle) findProp(style, "body", "background") else null)
            ?: NeuThemeColors.Default.bg
        var text = findVar(style, "txt") ?: NeuThemeColors.Default.text
        var textDark = findVar(style, "txt-d") ?: NeuThemeColors.Default.textDark
        if (luminance(bg) < 0.45f) {
            if (luminance(text) < 0.6f) text = Color(0xFFC7D6E8)
            if (luminance(textDark) < 0.6f) textDark = Color(0xFFF2F7FC)
        } else {
            if (luminance(textDark) > 0.6f) textDark = NeuThemeColors.Default.textDark
            if (luminance(text) > 0.85f) text = NeuThemeColors.Default.text
        }
        val blue = findVar(style, "blue")
            ?: (if (hasStyle) findProp(style, "primary", "background") else null)
            ?: (if (hasStyle) findProp(style, "btn", "background") else null)
            ?: (if (hasStyle) findProp(style, "button", "background") else null)
            ?: NeuThemeColors.Default.blue
        return NeuThemeColors(
            bg = bg,
            text = text,
            textDark = textDark,
            blue = blue,
            blueDark = findVar(style, "blue-d") ?: NeuThemeColors.Default.blueDark,
            gold = findVar(style, "gold") ?: NeuThemeColors.Default.gold,
            corner = corner
        )
    }

    fun summary(code: String): String {
        if (code.isBlank()) return "未输入自定义代码，当前为默认柔影主题。"
        if (!hasCustomTokens(code)) {
            return "未识别到任何颜色变量：这段代码里没有 --bg / --primary 这类颜色变量，" +
                "也没有 body / 主按钮的背景色，全局没有变化。请连同 <style> 一起粘进来，" +
                "或在代码里加 :root { --bg:#e0e8ef; --blue:#5f9de7; --txt:#7e97b8; } 再应用。"
        }
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

    fun applyCode(code: String) {
        // v1.0.7 闪退加固：主题代码再怪也不能让 App 在启动时闪退，
        // 解析失败就退回默认主题，绝不把异常抛到界面层。
        _colors.value = try { ThemeParser.parse(code) } catch (t: Throwable) { NeuThemeColors.Default }
    }
    fun reset() { _colors.value = NeuThemeColors.Default }
}
