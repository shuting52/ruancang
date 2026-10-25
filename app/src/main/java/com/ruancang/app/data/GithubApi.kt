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
 *  - < 45 MB：一次 Contents API PUT 直传；
 *  - ≥ 45 MB：按 45 MB 一块切分，每块单独 PUT 为 .part 文件，
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
        // 先把第一块读出来，才能判断是直传还是分块；第一块也参与后续上传，不丢数据。
        val chunkSize = 45L * 1024 * 1024
        val first = ByteArray(chunkSize.toInt())
        var firstLen = 0
        var n: Int
        var uploaded = 0L
        // 只读满第一块（或读到流结束）
        while (firstLen < first.size) {
            n = input.read(first, firstLen, first.size - firstLen)
            if (n < 0) break
            firstLen += n
        }
        val isSingle = firstLen < first.size // 流在第一块内结束 = 小文件
        if (isSingle) {
            val bytes = first.copyOf(firstLen)
            uploaded = bytes.size.toLong()
            onProgress(uploaded, totalSize)
            return@runCatching uploadBytes("$basePath/$fileName", bytes, message).getOrThrow()
        }
        // 大文件：第一块 + 后续块，全部写成 .part 文件
        val partNames = ArrayList<String>()
        var partIndex = 1
        var current = first.copyOf(firstLen)
        var currentLen = firstLen
        val digests = ArrayList<String>()
        while (true) {
            val partName = "part-%03d".format(partIndex)
            val partPath = "$basePath/parts/$partName"
            uploadBytes(partPath, current.copyOf(currentLen), "$message (part $partIndex)").getOrThrow()
            partNames += partName
            digests += sha256Hex(current, currentLen)
            uploaded += currentLen
            onProgress(uploaded, totalSize)
            // 读下一块
            val buf = ByteArray(chunkSize.toInt())
            var len = 0
            while (len < buf.size) {
                n = input.read(buf, len, buf.size - len)
                if (n < 0) break
                len += n
            }
            if (len == 0) break
            current = buf
            currentLen = len
            partIndex++
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
