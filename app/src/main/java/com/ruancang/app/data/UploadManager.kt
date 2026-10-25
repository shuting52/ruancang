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
    // v1.0.3：图标一律缩到 128px 再持有，不按原图尺寸持有。
    // 之前按 intrinsic 尺寸原样持有，某张大图图标单张就能占几十 MB，
    // 267 个应用的图标全留在列表里，也是 OOM 的帮凶。
    // 128px × 128px × 4 字节 = 64KB 一张，267 张约 17MB。
    return try {
        val size = 128
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
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
    return try { listInstalledAppsUnsafe(context, includeSystem) } catch (e: Exception) { emptyList() } catch (e: StackOverflowError) { emptyList() }
}

private fun listInstalledAppsUnsafe(context: Context, includeSystem: Boolean): List<UploadSource.InstalledApp> {
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

        // v1.0.9：主文件上传自动重试 3 次。以前主文件是一次 HTTP PUT，
        // 46MB 写到一半被“Software caused connection abort”断掉，
        // 整个文件就要用户手动再点一次上传，而且进度一直停在 0%。
        // 现在失败的是 IOException（连接中断/超时/断网）就自动重开文件流再传，
        // 每次重开是因为上一轮的 InputStream 已经消费完、不能复用；
        // 非 IO 错误（比如 HTTP 422、仓库/权限问题）不重试，直接报错。
        // 注意：最开始打开的那条 inputStream 在这里直接关掉，
        // 后续每轮都按来源重新打开，避免“流已关闭”误报。
        inputStream.close()
        var mainUrl = ""
        var lastError: Exception? = null
        var attempt = 0
        var done = false
        while (attempt < 3 && !done) {
            attempt++
            try {
                val freshStream = when (source) {
                    is UploadSource.FileUri -> context.contentResolver.openInputStream(source.uri)
                        ?: throw IllegalStateException("打不开这个文件，请重新选一次")
                    is UploadSource.InstalledApp -> FileInputStream(source.apkFile)
                }
                freshStream.use { stream ->
                    mainUrl = uploadMain(stream, basePath, fileName, totalSize, onProgress, onLog)
                }
                done = true
            } catch (e: java.io.IOException) {
                lastError = e
                onLog("上传中断（第 $attempt 次）：${friendlyNetworkError(e)}")
                if (attempt < 3) {
                    onLog("正在自动重试（第 ${attempt + 1} 次）…")
                    try { Thread.sleep(2000L * attempt) } catch (ignored: InterruptedException) {}
                }
            }
        }
        if (!done) {
            // 3 次都失败：把原始英文异常翻译成中文再抛，UI 不再直接显示
            // “Software caused connection abort”这种用户看不懂的原话。
            throw IllegalStateException("上传中断：${friendlyNetworkError(lastError)}，已自动重试 3 次仍失败，请换个网络环境（或开/关 VPN）后再试一次。")
        }
        onLog("主文件已同步：$mainUrl")

        // 截图：逐张传，截图本身不参与主文件分块逻辑
        var shotIndex = 1
        for (shotUri in target.screenshots) {
            val shotName = "shot-$shotIndex.jpg"
            context.contentResolver.openInputStream(shotUri)?.use { shotStream ->
                // v1.0.3：截图也不再整张读进内存，改流式上传。
                api.uploadStreamed(
                    "$basePath/screenshots/$shotName",
                    shotStream,
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
        if (totalSize >= 90L * 1024 * 1024) {
            onLog("超过 90 MB，自动切块上传（每块 10 MB），传完附 manifest.json")
        } else if (totalSize > 0) {
            onLog("90 MB 以下直接存单个文件，仓库里就是原文件本身")
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

/** 把网络异常翻译成中文，UI 直接显示这句，不再显示英文原话。 */
private fun friendlyNetworkError(e: Exception?): String {
    val msg = e?.message ?: ""
    return when {
        msg.contains("Software caused connection abort", ignoreCase = true) ||
            msg.contains("Connection reset", ignoreCase = true) ||
            msg.contains("Broken pipe", ignoreCase = true) ->
            "网络连接被重置（手机网络/VPN 到 GitHub 不稳定，中途被断掉）"
        msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) ->
            "上传超时（网络太慢，46MB 没能在时限内写完）"
        msg.contains("Unable to resolve host", ignoreCase = true) ||
            msg.contains("UnknownHost", ignoreCase = true) ->
            "解析不到 GitHub 服务器（DNS/网络不通）"
        else -> "网络错误：$msg"
    }
}
