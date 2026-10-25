package com.ruancang.app.data

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在线主题：主题代码不再只存本机，可以上传到仓库的 themes/ 目录，
 * 换手机、重装后也能从仓库拉回来。
 *
 * 仓库里的形态：
 *   themes/<slug>.html        主题代码原文（HTML / CSS）
 *   themes/index.json         [{name, slug, file, updatedAt}]
 * index.json 是列表的唯一来源：在线列表读它，上传时先读它、合并、再写回。
 * 在线列表和单个主题文件都走公开 raw 直链读，不需要 Token；
 * 上传和更新 index.json 需要 Token（和上传文件同一个 Token）。
 */
data class OnlineTheme(val name: String, val slug: String, val file: String, val updatedAt: Long)

object OnlineThemes {
    const val INDEX_PATH = "themes/index.json"

    fun list(config: GithubConfig): Result<List<OnlineTheme>> = runCatching {
        val url = "https://raw.githubusercontent.com/${config.owner}/${config.repo}/${config.branch}/$INDEX_PATH"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "ruancang-app")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (conn.responseCode == 404) return@runCatching emptyList()
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(body)
        val out = ArrayList<OnlineTheme>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += OnlineTheme(
                name = o.optString("name"),
                slug = o.optString("slug"),
                file = o.optString("file"),
                updatedAt = o.optLong("updatedAt")
            )
        }
        out.sortedByDescending { it.updatedAt }
    }

    fun download(config: GithubConfig, theme: OnlineTheme): Result<String> = runCatching {
        val url = "https://raw.githubusercontent.com/${config.owner}/${config.repo}/${config.branch}/${theme.file}"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "ruancang-app")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        conn.inputStream.bufferedReader().use { it.readText() }
    }

    fun upload(config: GithubConfig, name: String, slug: String, code: String): Result<String> = runCatching {
        val api = GithubApi(config)
        val file = "themes/$slug.html"
        api.uploadBytes(file, code.toByteArray(Charsets.UTF_8), "软仓：上传在线主题 $name").getOrThrow()
        val existing = api.readFile(INDEX_PATH).getOrNull()?.takeIf { it.isNotBlank() }
        val arr = if (existing != null) JSONArray(existing) else JSONArray()
        val kept = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("slug") != slug) kept += o
        }
        kept += JSONObject().apply {
            put("name", name)
            put("slug", slug)
            put("file", file)
            put("updatedAt", System.currentTimeMillis())
        }
        val updated = JSONArray(kept).toString(2)
        api.uploadBytes(INDEX_PATH, updated.toByteArray(Charsets.UTF_8), "软仓：更新在线主题列表").getOrThrow()
        file
    }
}
