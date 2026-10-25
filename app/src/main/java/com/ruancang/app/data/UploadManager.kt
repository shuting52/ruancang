package com.ruancang.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.ruancang.app.model.Category
import com.ruancang.app.model.slugify
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import org.json.JSONObject

/** 上传来源：手机文件 或 已安装应用（提取 APK）。 */
sealed class UploadSource {
    data class FileUri(val uri: Uri, val displayName: String, val mimeType: String?, val size: Long) : UploadSource()
    data class InstalledApp(
        val label: String,
        val packageName: String,
        val versionName: String?,
        val apkFile: File,
        /** 中文显示名：有中文别名用别名，否则用系统原名。 */
        val displayName: String = label,
        /** 应用图标（从 PackageManager 取，取不到为 null，UI 显示首字占位）。 */
        val icon: android.graphics.Bitmap? = null
    ) : UploadSource()
}

/**
 * 系统应用中文别名：很多系统应用的 manifest 标签本身就是英文
 * （如 aidlserverdemo、AIService），系统语言改中文也不会变，
 * App 自己翻不出来，只能按包名给常见包一张对照表。
 * 没进表的仍显示原名，不瞎猜、不硬翻。
 */
val SYSTEM_APP_CHINESE_NAMES: Map<String, String> = mapOf(
    "org.ifaa.aidl.manager" to "IFAA 指纹认证服务",
    "com.tencent.soter.soterserver" to "腾讯 SOTER 指纹服务",
    "com.vivo.aiservice" to "vivo AI 服务",
    "com.android.adservices.api" to "Android 广告服务",
    "com.google.android.gms" to "Google Play 服务",
    "com.google.android.gsf" to "Google 服务框架",
    "com.android.systemui" to "系统界面",
    "com.android.settings" to "设置",
    "com.android.phone" to "电话",
    "com.android.contacts" to "联系人",
    "com.android.mms" to "信息",
    "com.android.camera" to "相机",
    "com.android.gallery3d" to "相册",
    "com.android.vending" to "Google Play 商店",
    "com.android.chrome" to "Chrome 浏览器",
    "com.tencent.mm" to "微信",
    "com.tencent.mobileqq" to "QQ",
    "com.sina.weibo" to "微博",
    "com.taobao.taobao" to "淘宝",
    "com.alibaba.android.rimet" to "钉钉",
    "com.baidu.BaiduMap" to "百度地图",
    "com.autonavi.minimap" to "高德地图"
)

fun chineseAppName(packageName: String, label: String): String =
    SYSTEM_APP_CHINESE_NAMES[packageName] ?: label

private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable?): android.graphics.Bitmap? {
    if (drawable == null) return null
    if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    return try {
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bitmap
    } catch (e: Exception) {
        null
    }
}

data class UploadTarget(
    val source: UploadSource,
    val category: Category,
    val title: String,
    val description: String,
    val screenshots: List<Uri>
)

/** 从 PackageManager 列出已安装应用（默认先列用户应用，开关可放出系统应用）。 */
fun listInstalledApps(context: Context, includeSystem: Boolean): List<UploadSource.InstalledApp> {
    val pm: PackageManager = context.packageManager
    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    return apps
        .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        .mapNotNull { info ->
            val apk = File(info.sourceDir)
            if (!apk.exists()) return@mapNotNull null
            val label = pm.getApplicationLabel(info)?.toString() ?: info.packageName
            UploadSource.InstalledApp(
                label = label,
                packageName = info.packageName,
                versionName = try {
                    pm.getPackageInfo(info.packageName, 0).versionName
                } catch (e: Exception) { null },
                apkFile = apk,
                displayName = chineseAppName(info.packageName, label),
                icon = try {
                    drawableToBitmap(pm.getApplicationIcon(info))
                } catch (e: Exception) {
                    null
                }
            )
        }
        .sortedBy { it.displayName.lowercase() }
}

/**
 * 执行一次完整上传，顺序固定：
 *  1. 主文件 → <类别>/<slug>/<原文件名>（大文件自动分块 + manifest.json）
 *  2. 截图 → <类别>/<slug>/screenshots/shot-1.jpg …
 *  3. info.json → <类别>/<slug>/info.json（名称、类别、说明、来源、包名、版本、大小、时间）
 * 每一步都实时回调进度（0..1）与日志，UI 直接显示，不用等全部传完才知道卡在哪。
 */
class UploadManager(private val api: GithubApi) {

    fun upload(
        context: Context,
        target: UploadTarget,
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit
    ): Result<String> = runCatching {
        val source = target.source
        val (fileName, inputStream, totalSize) = when (source) {
            is UploadSource.FileUri -> {
                val stream = context.contentResolver.openInputStream(source.uri)
                    ?: throw IllegalStateException("打不开这个文件，请重新选一次")
                Triple(source.displayName, stream, source.size)
            }
            is UploadSource.InstalledApp -> {
                val name = "${source.displayName}-${source.versionName ?: "1.0"}.apk"
                    .replace(Regex("[\\\\/:*?\"<>|]"), "-")
                Triple(name, FileInputStream(source.apkFile), source.apkFile.length())
            }
        }
        val slug = slugify(target.title.ifBlank { fileName })
        val basePath = "${target.category.folder}/$slug"
        onLog("类别：${target.category.label}（${target.category.folder}/），目录：$basePath")

        val mainUrl: String
        inputStream.use { stream ->
            mainUrl = uploadMain(stream, basePath, fileName, totalSize, onProgress, onLog)
        }
        onLog("主文件已同步：$mainUrl")

        // 截图：逐张传，截图本身不参与主文件分块逻辑
        var shotIndex = 1
        for (shotUri in target.screenshots) {
            val shotName = "shot-$shotIndex.jpg"
            context.contentResolver.openInputStream(shotUri)?.use { shotStream ->
                val bytes = shotStream.readBytes()
                api.uploadBytes(
                    "$basePath/screenshots/$shotName",
                    bytes,
                    "软仓：上传 $fileName 的截图 $shotName"
                ).getOrThrow()
                onLog("截图已同步：screenshots/$shotName")
            }
            shotIndex++
        }

        // info.json：让仓库里的每个条目都自带说明，别人打开仓库就知道这是干什么的
        val info = JSONObject().apply {
            put("title", target.title.ifBlank { fileName })
            put("fileName", fileName)
            put("category", target.category.folder)
            put("categoryLabel", target.category.label)
            put("description", target.description)
            put("size", totalSize)
            put("uploadedAt", System.currentTimeMillis())
            if (source is UploadSource.InstalledApp) {
                put("source", "installed-app")
                put("packageName", source.packageName)
                put("appVersion", source.versionName ?: "")
            } else {
                put("source", "file")
            }
            put("screenshots", target.screenshots.size)
        }
        api.uploadBytes(
            "$basePath/info.json",
            info.toString(2).toByteArray(Charsets.UTF_8),
            "软仓：更新 $fileName 的说明"
        ).getOrThrow()
        onLog("说明已同步：info.json")
        mainUrl
    }

    private fun uploadMain(
        stream: InputStream,
        basePath: String,
        fileName: String,
        totalSize: Long,
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit
    ): String {
        onLog("开始上传主文件：$fileName" + if (totalSize > 0) "（${totalSize / 1024 / 1024} MB）" else "")
        if (totalSize >= 45L * 1024 * 1024) {
            onLog("超过 45 MB，自动切块上传（每块 45 MB），传完附 manifest.json")
        }
        return api.uploadStream(
            basePath = basePath,
            fileName = fileName,
            input = stream,
            totalSize = totalSize,
            message = "软仓：上传 $fileName"
        ) { uploaded, total ->
            if (total > 0) onProgress(uploaded.toFloat() / total.toFloat()) else onProgress(0f)
        }.getOrThrow()
    }
}
