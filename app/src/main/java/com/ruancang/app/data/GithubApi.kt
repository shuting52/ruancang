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
 *  - < 10 MB：一次流式上传，原文件名不变；
 *  - ≥ 10 MB：按 10 MB 一块切分，每块流式 PUT 为 .part 文件，
 *    并附一份 manifest.json（原文件名、总大小、分块数、每块 SHA-256），
 *    下载方用 tools/reassemble.py 或 App 内“合并分块”拼回原文件。
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
        // v1.0.3：块从 45MB 降到 10MB，且全程只用同一块缓冲：
        // 读满一块 -> 流式上传这一块 -> 再复用同一块读下一块。
        // 旧版同时持有首块 + 当前块 + 下一块（3 × 45MB），再加 Base64 大字符串，
        // 是这次 OOM 的直接原因。
        val chunkSize = 10L * 1024 * 1024
        val buf = ByteArray(chunkSize.toInt())
        var uploaded = 0L
        var partIndex = 1
        val partNames = ArrayList<String>()
        val digests = ArrayList<String>()
        var first = true
        while (true) {
            var len = 0
            while (len < buf.size) {
                val n = input.read(buf, len, buf.size - len)
                if (n < 0) break
                len += n
            }
            if (len == 0) break
            val isLast = len < buf.size
            if (first && isLast) {
                // 小文件：流在第一块内结束，直接传原文件，不分块。
                val url = uploadStreamed("$basePath/$fileName", java.io.ByteArrayInputStream(buf, 0, len), message).getOrThrow()
                uploaded = len.toLong()
                onProgress(uploaded, totalSize)
                return@runCatching url
            }
            first = false
            val partName = "part-%03d".format(partIndex)
            uploadStreamed("$basePath/parts/$partName", java.io.ByteArrayInputStream(buf, 0, len), "$message (part $partIndex)").getOrThrow()
            partNames += partName
            digests += sha256Hex(buf, len)
            uploaded += len
            onProgress(uploaded, totalSize)
            partIndex++
            if (isLast) break
        }
        if (partNames.isEmpty()) {
            // 空文件：传一个 0 字节原文件。
            return@runCatching uploadStreamed("$basePath/$fileName", java.io.ByteArrayInputStream(ByteArray(0)), message).getOrThrow()
        }
        val manifest = JSONObject().apply {
            put("originalName", fileName)
            put("totalSize", uploaded)
            put("chunkSize", chunkSize)
            put("parts", JSONArray(partNames))
            put("sha256", JSONArray(digests))
            put("note", "用 tools/reassemble.py 或 App 内合并功能按 parts 顺序拼回原文件")
        }
        uploadBytes("$basePath/manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8), "$message (manifest)").getOrThrow()
    }

    private fun sha256Hex(bytes: ByteArray, len: Int): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes, 0, len)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
