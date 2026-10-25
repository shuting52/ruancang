package com.ruancang.app.model

/**
 * 自动分类规则。
 *
 * 优先级：已安装应用 > 文件扩展名 > MIME 类型前缀 > 其他。
 * 每个类别对应 GitHub 仓库里的一个顶层文件夹，上传时自动创建（GitHub
 * 没有“空文件夹”概念，文件夹是随第一个文件一起创建的）。
 */
enum class Category(val folder: String, val label: String, val emoji: String) {
    APPS("apps", "应用", "📦"),
    IMAGES("images", "图片", "🖼️"),
    VIDEOS("videos", "视频", "🎬"),
    AUDIO("audio", "音频", "🎵"),
    DOCUMENTS("documents", "文档", "📄"),
    ARCHIVES("archives", "压缩包", "🗜️"),
    CODE("code", "代码", "💻"),
    FONTS("fonts", "字体", "🔤"),
    OTHERS("others", "其他", "📁");

    companion object {
        private val EXT_MAP: Map<String, Category> = buildMap {
            listOf("apk", "aab", "xapk", "apks").forEach { put(it, APPS) }
            listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "avif").forEach { put(it, IMAGES) }
            listOf("mp4", "mkv", "mov", "webm", "avi", "flv", "m4v").forEach { put(it, VIDEOS) }
            listOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus").forEach { put(it, AUDIO) }
            listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "epub", "csv").forEach { put(it, DOCUMENTS) }
            listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz").forEach { put(it, ARCHIVES) }
            listOf("html", "css", "js", "ts", "json", "xml", "kt", "java", "py", "sh", "c", "cpp").forEach { put(it, CODE) }
            listOf("ttf", "otf", "woff", "woff2").forEach { put(it, FONTS) }
        }

        fun detect(fileName: String, mimeType: String?, isInstalledApp: Boolean): Category {
            if (isInstalledApp) return APPS
            val ext = fileName.substringAfterLast('.', "").lowercase()
            EXT_MAP[ext]?.let { return it }
            return when {
                mimeType?.startsWith("image/") == true -> IMAGES
                mimeType?.startsWith("video/") == true -> VIDEOS
                mimeType?.startsWith("audio/") == true -> AUDIO
                mimeType?.startsWith("text/") == true -> CODE
                mimeType == "application/pdf" -> DOCUMENTS
                mimeType?.contains("zip") == true || mimeType?.contains("rar") == true -> ARCHIVES
                else -> OTHERS
            }
        }

        fun fromFolder(folder: String): Category =
            entries.firstOrNull { it.folder == folder } ?: OTHERS
    }
}

/** 仓库里的一个条目：<类别>/<slug>/原文件名 + 截图 + info.json */
data class WarehouseItem(
    val category: Category,
    val slug: String,
    val fileName: String,
    val path: String, // 如 apps/weixin-8-0-50/weixin-8-0-50.apk
    val htmlUrl: String = "",
    val size: Long = 0L
)

fun slugify(name: String): String {
    val base = name.substringBeforeLast('.', name)
        .lowercase()
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5_-]+"), "-")
        .trim('-')
        .ifEmpty { "file" }
    return base.take(80)
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
