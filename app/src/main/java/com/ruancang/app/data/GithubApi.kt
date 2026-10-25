package com.ruancang.app.data

import android.util.Base64
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * GitHub REST API 最小客户端（只用 HttpURLConnection + org.json，不引新库）。
 *
 * 仓库默认值：shuting52 / ruancang / main，可在 App 设置里改。
 * Token 只存在本机 SharedPreferences，绝不写进代码、绝不随源码提交。
 *
 * “不限制文件大小”是这样实现的（GitHub 侧的硬限制见 README）：
 *  - < 90 MB：一次流式上传，原文件名不变，仓库里就是单个 APK 文件；
 *  - ≥ 90 MB：按 10 MB 一块切分，每块流式 PUT 为 .part 文件，
 *    并附一份 manifest.json（原文件名、总大小、分块数、每块 SHA-256），
 *    下载方用 tools/reassemble.py 或 App 内“合并分块”拼回原文件。
 *  之所以敢把单传阈值从 10MB 放到 90MB，是因为 v1.0.3 起上传是流式
 *  Base64（24KB 一块边读边编码），不再整段拼大字符串，90MB 单传
 *  峰值内存也只有十几 MB，不会重蹈 125,829,136 字节 OOM。
 * App 本身不设大小上限，分块数也不设上限。
 */
data class GithubConfig(
    val owner: String = "shuting52",
    val repo: String = "ruancang",
    val branch: String = "main",
    val token: String = ""
)

data class GithubFile(val path: String, val name: String, val type: String, val htmlUrl: String, val size: Long)

class GithubApi(private val config: GithubConfig) {

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun open(method: String, url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "ruancang-app")
        if (config.token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${config.token}")
        }
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    /** 连通性测试：能读到仓库即算通，顺带返回默认分支。 */
    fun testConnection(): Result<String> = runCatching {
        val conn = open("GET", "https://api.github.com/repos/${config.owner}/${config.repo}")
        val body = readBody(conn)
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}：$body".take(400))
        JSONObject(body).optString("default_branch", config.branch)
    }

    /** 列出仓库根目录（类别文件夹），用于“仓库”页。 */
    fun listRoot(): Result<List<GithubFile>> = runCatching {
        val conn = open("GET", "https://api.github.com/repos/${config.owner}/${config.repo}/contents?ref=${config.branch}")
        val body = readBody(conn)
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}：$body".take(400))
        parseFileArray(JSONArray(body))
    }

    /** 列出某类别文件夹下的条目（每个子文件夹 = 一个已上传文件）。 */
    fun listCategory(folder: String): Result<List<GithubFile>> = runCatching {
        val url = "https://api.github.com/repos/${config.owner}/${config.repo}/contents/${encodePath(folder)}?ref=${config.branch}"
        val conn = open("GET", url)
        val body = readBody(conn)
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}：$body".take(400))
        parseFileArray(JSONArray(body))
    }

    private fun parseFileArray(arr: JSONArray): List<GithubFile> {
        val out = ArrayList<GithubFile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += GithubFile(
                path = o.optString("path"),
                name = o.optString("name"),
                type = o.optString("type"),
                htmlUrl = o.optString("html_url"),
                size = o.optLong("size")
            )
        }
        return out
    }

    /** 读仓库里一个文本文件的内容（Base64 解码后），404 返回 null。 */
    fun readFile(path: String): Result<String?> = runCatching {
        val conn = open("GET", "https://api.github.com/repos/${config.owner}/${config.repo}/contents/${encodePath(path)}?ref=${config.branch}")
        val body = readBody(conn)
        if (conn.responseCode == 404) return@runCatching null
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}：$body".take(400))
        val content = JSONObject(body).optString("content").replace("\\s".toRegex(), "")
        if (content.isEmpty()) "" else String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
    }

    /** 删仓库里一个文件（先取 sha 再 DELETE），不存在返回 false，不抛异常。 */
    fun deleteFile(path: String, message: String): Boolean {
        return try {
            val sha = existingSha(path) ?: return false
            val payload = JSONObject().apply {
                put("message", message)
                put("sha", sha)
                put("branch", config.branch)
            }
            val conn = open("DELETE", "https://api.github.com/repos/${config.owner}/${config.repo}/contents/${encodePath(path)}")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val body = readBody(conn)
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 重传清理（尽力而为，清理失败不影响本次上传结果）：
     *  - 单传成功后，若该条目之前是分块形态，删掉旧的 parts 目录和 manifest.json，
     *    否则仓库里会同时留着单个 APK 和旧分块，看着像两份；
     *  - 分块成功后，若该条目之前是单文件形态，删掉旧的单个文件。
     */
    private fun cleanupStaleChunked(basePath: String, fileName: String) {
        try {
            val manifestText = readFile("$basePath/manifest.json").getOrNull() ?: return
            val manifest = JSONObject(manifestText)
            if (manifest.optString("originalName") != fileName) return
            val parts = manifest.optJSONArray("parts") ?: return
            for (i in 0 until parts.length()) {
                deleteFile("$basePath/parts/${parts.getString(i)}", "软仓：清理旧分块 ${parts.getString(i)}")
            }
            deleteFile("$basePath/manifest.json", "软仓：清理旧分块清单")
        } catch (e: Exception) {
            // 尽力而为
        }
    }

    private fun cleanupStaleSingle(basePath: String, fileName: String) {
        try {
            deleteFile("$basePath/$fileName", "软仓：清理旧单文件")
        } catch (e: Exception) {
            // 尽力而为
        }
    }

    /** 已存在文件先取 sha，PUT 时带上 sha 才是“更新”，不带会报 422。 */
    private fun existingSha(path: String): String? {
        val conn = open("GET", "https://api.github.com/repos/${config.owner}/${config.repo}/contents/${encodePath(path)}?ref=${config.branch}")
        val body = readBody(conn)
        if (conn.responseCode !in 200..299) return null
        return JSONObject(body).optString("sha").ifEmpty { null }
    }

    /**
     * 上传一个小文件（字节数组已在内存里）。
     * 只用于 info.json / manifest 这种 KB 级小文件，大文件一律走下面的流式上传。
     * @return 该文件在 GitHub 上的 html_url
     */
    fun uploadBytes(path: String, bytes: ByteArray, message: String): Result<String> = runCatching {
        val payload = JSONObject().apply {
            put("message", message)
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("branch", config.branch)
            existingSha(path)?.let { put("sha", it) }
        }
        val conn = open("PUT", "https://api.github.com/repos/${config.owner}/${config.repo}/contents/${encodePath(path)}")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val body = readBody(conn)
        if (conn.responseCode !in 200..299) throw IllegalStateException("上传失败 HTTP ${conn.responseCode}：$body".take(500))
        JSONObject(body).getJSONObject("content").optString("html_url")
    }

    /**
     * 流式上传一个文件：Base64 按 24KB 一块边读边编码边写出，不在内存里
     * 拼整段 Base64 字符串、不拼整个 JSON 字符串。
     *
     * v1.0.3 修 OOM 的关键：旧版对 45MB 一块调用 Base64.encodeToString，
     * 那一次分配正好是 45MB/3*4 个字符 × 2 字节 = 125,829,136 字节，
     * 和用户报错截图里的数字一字不差；再加上同时在内存里的
     * 首块 45MB + 当前块 45MB + 下一块 45MB，256MB 堆直接爆掉。
     * 现在每块只占 24KB 编码缓冲，chunk 也从 45MB 降到 10MB，
     * 大文件（130MB 的应用 APK）上传时峰值内存只有十几 MB。
     * 用 chunked streaming，不让 HttpURLConnection 把整个请求体再缓冲一遍。
     */
    fun uploadStreamed(path: String, input: InputStream, message: String): Result<String> = runCatching {
        val sha = existingSha(path)
        val conn = open("PUT", "https://api.github.com/repos/${config.owner}/${config.repo}/contents/${encodePath(path)}")
        conn.doOutput = true
        conn.setChunkedStreamingMode(0)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { out ->
            val header = StringBuilder()
                .append("{\"message\":").append(JSONObject.quote(message))
                .append(",\"branch\":").append(JSONObject.quote(config.branch))
            if (sha != null) header.append(",\"sha\":").append(JSONObject.quote(sha))
            header.append(",\"content\":\"")
            out.write(header.toString().toByteArray(Charsets.UTF_8))
            // Base64 必须是 3 字节一组的倍数块编码，最后一块才带 padding。
            val block = ByteArray(3 * 8192)
            while (true) {
                var len = 0
                while (len < block.size) {
                    val n = input.read(block, len, block.size - len)
                    if (n < 0) break
                    len += n
                }
                if (len == 0) break
                val encoded = if (len == block.size) {
                    Base64.encodeToString(block, Base64.NO_WRAP)
                } else {
                    Base64.encodeToString(block.copyOf(len), Base64.NO_WRAP)
                }
                out.write(encoded.toByteArray(Charsets.UTF_8))
                if (len < block.size) break
            }
            out.write("\"}".toByteArray(Charsets.UTF_8))
            out.flush()
        }
        val body = readBody(conn)
        if (conn.responseCode !in 200..299) throw IllegalStateException("上传失败 HTTP ${conn.responseCode}：$body".take(500))
        JSONObject(body).getJSONObject("content").optString("html_url")
    }

    /**
     * 流式上传：按 chunkSize 读输入流，分块上传。
     * @param onProgress 已上传字节数 / 总字节数（总字节数未知时传 -1）
     * @return 主文件（或 manifest）的 html_url
     */
    fun uploadStream(
        basePath: String,
        fileName: String,
        input: InputStream,
        totalSize: Long,
        message: String,
        onProgress: (uploaded: Long, total: Long) -> Unit
    ): Result<String> = runCatching {
        // v1.0.5：单传阈值 90MB，分块大小仍是 10MB。
        // 关键：不再用“读满 90MB 一块缓冲再判断”——那会一下占 90MB 内存。
        // 大小已知时直接按大小分流；大小未知时先落盘到临时文件（磁盘，不占堆）
        // 数清大小再分流，临时文件用完就删。
        if (totalSize > 0) {
            return@runCatching uploadWithKnownSize(basePath, fileName, input, totalSize, message, onProgress)
        }
        val tmp = java.io.File.createTempFile("ruancang-upload-", ".tmp")
        try {
            var counted = 0L
            tmp.outputStream().use { out -> input.copyTo(out) }
            counted = tmp.length()
            java.io.FileInputStream(tmp).use { fileInput ->
                return@runCatching uploadWithKnownSize(basePath, fileName, fileInput, counted, message, onProgress)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun uploadWithKnownSize(
        basePath: String,
        fileName: String,
        input: InputStream,
        totalSize: Long,
        message: String,
        onProgress: (uploaded: Long, total: Long) -> Unit
    ): String {
        val singleThreshold = 90L * 1024 * 1024
        if (totalSize < singleThreshold) {
            // 90MB 以下：直接流式传单个文件，仓库里就是原 APK 文件。
            val url = uploadStreamed("$basePath/$fileName", input, message).getOrThrow()
            onProgress(totalSize, totalSize)
            cleanupStaleChunked(basePath, fileName)
            return url
        }
        // ≥ 90MB：按 10MB 一块切分，全程只用同一块缓冲（v1.0.3 的教训）。
        val chunkSize = 10L * 1024 * 1024
        val buf = ByteArray(chunkSize.toInt())
        var uploaded = 0L
        var partIndex = 1
        val partNames = ArrayList<String>()
        val digests = ArrayList<String>()
        while (true) {
            var len = 0
            while (len < buf.size) {
                val n = input.read(buf, len, buf.size - len)
                if (n < 0) break
                len += n
            }
            if (len == 0) break
            val partName = "part-%03d".format(partIndex)
            uploadStreamed("$basePath/parts/$partName", java.io.ByteArrayInputStream(buf, 0, len), "$message (part $partIndex)").getOrThrow()
            partNames += partName
            digests += sha256Hex(buf, len)
            uploaded += len
            onProgress(uploaded, totalSize)
            partIndex++
            if (len < buf.size) break
        }
        if (partNames.isEmpty()) {
            val url = uploadStreamed("$basePath/$fileName", java.io.ByteArrayInputStream(ByteArray(0)), message).getOrThrow()
            cleanupStaleChunked(basePath, fileName)
            return url
        }
        val manifest = JSONObject().apply {
            put("originalName", fileName)
            put("totalSize", uploaded)
            put("chunkSize", chunkSize)
            put("parts", JSONArray(partNames))
            put("sha256", JSONArray(digests))
            put("note", "用 tools/reassemble.py 或 App 内合并功能按 parts 顺序拼回原文件")
        }
        val manifestUrl = uploadBytes("$basePath/manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8), "$message (manifest)").getOrThrow()
        cleanupStaleSingle(basePath, fileName)
        return manifestUrl
    }

    private fun sha256Hex(bytes: ByteArray, len: Int): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes, 0, len)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
