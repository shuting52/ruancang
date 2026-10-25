package com.ruancang.app.data

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 自更新检查：读仓库 dist/version.json，对比本机版本号。
 *
 * version.json 由每次发版时更新，格式：
 *   {"versionCode":3,"versionName":"1.0.2",
 *    "apkUrl":"https://raw.githubusercontent.com/shuting52/ruancang/main/dist/ruancang-v1.0.2-debug.apk",
 *    "size":15706498,"changelog":"……"}
 *
 * 不走 GitHub API、不需要 Token（仓库是公开的，raw 直链就能读），
 * 这样旧版在没填 Token 的情况下也能弹出更新。
 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val size: Long,
    val changelog: String
)

fun currentAppVersion(context: Context): Pair<Long, String> {
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        code to (info.versionName ?: "")
    } catch (e: Exception) {
        0L to ""
    }
}

object UpdateChecker {
    fun check(config: GithubConfig, currentCode: Long): UpdateInfo? {
        val url = "https://raw.githubusercontent.com/${config.owner}/${config.repo}/${config.branch}/dist/version.json"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "ruancang-app")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (conn.responseCode !in 200..299) return null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val o = JSONObject(body)
        val remoteCode = o.optLong("versionCode", 0)
        if (remoteCode <= 0 || remoteCode <= currentCode) return null
        val apkUrl = o.optString("apkUrl")
        if (apkUrl.isBlank()) return null
        return UpdateInfo(
            versionCode = remoteCode,
            versionName = o.optString("versionName", "$remoteCode"),
            apkUrl = apkUrl,
            size = o.optLong("size", 0),
            changelog = o.optString("changelog", "")
        )
    }
}
