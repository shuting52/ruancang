package com.ruancang.app.data

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 自更新下载器：把用户发来的下载代码（Flutter + 原生双通道那套）
 * 移植成纯 Kotlin，不依赖 Flutter 通道，核心思路原样保留：
 *
 *  - 探针先问总长：GET + Range bytes=0-0，看 206 / Content-Range；
 *    服务端不认 Range（回 200 整条）时退回单条顺序下载，不按偏移写坏文件；
 *  - 大文件分段：4MB 一段，多条连接共享游标认领下一段（谁空谁领，
 *    不是每条 lane 固定守一块，避免最慢那条拖住尾巴）；
 *  - 段内断点续传：一段中途断了，从已收字节处续，不是从段头重来；
 *    连续 4 次一字节都没收到、或累计 40 次尝试才判该段失败；
 *  - 跨次断点续传：已下完的段号写进同目录的 .segments 文件，
 *    下次打开更新弹窗重下时跳过已完的段，不用从头再来；
 *  - 完整性：收满 total 字节才算成功，少一个字节都报失败，不把残件交给安装器。
 *
 * 没移植的部分（说清楚，不装作有）：Flutter 那套后台保活前台服务
 * 和通知栏进度没有移植——更新弹窗开着时下载在前台协程跑，
 * 弹窗关了/应用被杀了，下次点更新会按段续传，不会丢已下的段。
 */
object SelfDownloader {
    private const val SEGMENT_BYTES = 4L * 1024 * 1024
    private const val LANES = 4
    private const val STALL_LIMIT = 4
    private const val ATTEMPT_LIMIT = 40

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "ruancang-app")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        return conn
    }

    private class Probe(val size: Long, val rangeIgnored: Boolean)

    private fun probe(url: String): Probe {
        val conn = open(url).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-0")
        }
        try {
            val code = conn.responseCode
            return when (code) {
                206 -> {
                    val total = conn.getHeaderField("Content-Range")
                        ?.substringAfterLast('/')?.toLongOrNull() ?: 0L
                    Probe(total, false)
                }
                200 -> Probe(conn.contentLengthLong, true)
                else -> throw IllegalStateException("下载地址 HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * @param onProgress 已收字节 / 总字节（总字节未知时 total 传 -1）
     * @param isCancelled 每段开始前查一次，用户点取消即停，已完段保留可续传
     */
    fun download(
        url: String,
        dest: File,
        onProgress: (received: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): File {
        val probe = probe(url)
        val total = probe.size
        dest.parentFile?.mkdirs()
        val sidecar = File(dest.parentFile, dest.name + ".segments")

        if (total <= 0 || probe.rangeIgnored) {
            // 总长未知，或服务端不认 Range：单条顺序下载（可整体重试 3 次）。
            // 这种情况下没有可续的断点，失败即删残件重来，不装作断点续传。
            var last: Exception? = null
            repeat(3) { attempt ->
                if (isCancelled()) throw InterruptedException("用户取消下载")
                try {
                    val conn = open(url)
                    try {
                        if (conn.responseCode !in 200..299) {
                            throw IllegalStateException("下载地址 HTTP ${conn.responseCode}")
                        }
                        var received = 0L
                        conn.inputStream.use { input ->
                            dest.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    received += n
                                    onProgress(received, total)
                                    if (isCancelled()) throw InterruptedException("用户取消下载")
                                }
                            }
                        }
                        if (total > 0 && received < total) {
                            throw IllegalStateException("下载不完整 $received/$total")
                        }
                        sidecar.delete()
                        return dest
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                    if (attempt < 2) Thread.sleep(500L * (attempt + 1))
                }
            }
            dest.delete()
            throw last ?: IllegalStateException("下载失败")
        }

        // 分段下载：预分配 + 共享游标 + 已完段记录。
        val done = mutableSetOf<Long>()
        if (sidecar.exists()) {
            sidecar.readLines().mapNotNull { it.toLongOrNull() }.forEach { done += it }
        }
        RandomAccessFile(dest, "rw").use { it.setLength(total) }
        val segmentCount = (total + SEGMENT_BYTES - 1) / SEGMENT_BYTES
        val received = AtomicLong(done.size * SEGMENT_BYTES.coerceAtMost(total))
        // 已完段若含最后一小段，上面会多算，纠正为实际已完字节：
        var doneBytes = 0L
        for (i in done) {
            if (i < segmentCount) {
                doneBytes += minOf(SEGMENT_BYTES, total - i * SEGMENT_BYTES)
            }
        }
        received.set(doneBytes)
        onProgress(received.get(), total)

        val cursor = AtomicLong(0)
        // 跳过已完段：游标从 0 认领，遇到已完段直接记完成不下载。
        val pool = Executors.newFixedThreadPool(LANES)
        val latch = CountDownLatch(LANES)
        val failed = AtomicBoolean(false)
        var failure: Throwable? = null
        val lock = Any()
        try {
            repeat(LANES) {
                pool.execute {
                    try {
                        while (true) {
                            if (isCancelled()) throw InterruptedException("用户取消下载")
                            if (failed.get()) break
                            val seg = cursor.getAndIncrement()
                            if (seg >= segmentCount) break
                            if (seg in done) continue
                            val start = seg * SEGMENT_BYTES
                            val end = minOf(total, start + SEGMENT_BYTES) - 1
                            var offset = start
                            var attempts = 0
                            var stalls = 0
                            while (offset <= end) {
                                try {
                                    val got = downloadRange(url, dest, offset, end)
                                    if (got == 0L) {
                                        stalls++
                                        attempts++
                                        if (stalls >= STALL_LIMIT || attempts >= ATTEMPT_LIMIT) {
                                            throw IllegalStateException("第 ${seg + 1} 段收不动：$offset-$end")
                                        }
                                        Thread.sleep(minOf(200L shl (stalls - 1).coerceIn(0, 4), 2000L))
                                        continue
                                    }
                                    received.addAndGet(got)
                                    onProgress(received.get(), total)
                                    offset += got
                                    stalls = 0
                                } catch (e: InterruptedException) {
                                    throw e
                                } catch (e: Exception) {
                                    attempts++
                                    // 已收部分已落盘，下次从 offset 续；只有连续无进展才放弃。
                                    if (attempts >= ATTEMPT_LIMIT) throw e
                                    Thread.sleep(500L)
                                }
                                if (isCancelled()) throw InterruptedException("用户取消下载")
                            }
                            synchronized(lock) {
                                done += seg
                                sidecar.writeText(done.sorted().joinToString("\n"))
                            }
                        }
                    } catch (e: Throwable) {
                        if (e !is InterruptedException) {
                            synchronized(lock) { if (failure == null) failure = e }
                            failed.set(true)
                        } else {
                            failed.set(true)
                            synchronized(lock) { if (failure == null) failure = e }
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
        } finally {
            pool.shutdown()
        }
        failure?.let { throw it }
        if (isCancelled()) throw InterruptedException("用户取消下载")
        if (received.get() < total) {
            throw IllegalStateException("下载不完整 ${received.get()}/$total")
        }
        sidecar.delete()
        return dest
    }

    /** 收一个 Range 区间，seek 写到对应偏移，返回实际收到的字节数。 */
    private fun downloadRange(url: String, dest: File, start: Long, end: Long): Long {
        val conn = open(url).apply {
            setRequestProperty("Range", "bytes=$start-$end")
        }
        try {
            val code = conn.responseCode
            if (code != 206) {
                // 区间正好是整条文件时服务端回 200 也是对的，其余 200 一律不按偏移写。
                val whole = code == 200 && start == 0L && conn.contentLengthLong == end + 1
                if (!whole) throw IllegalStateException("分段下载被拒 $start-$end（HTTP $code）")
            }
            if (code == 206) {
                val declared = conn.getHeaderField("Content-Range")
                    ?.substringAfter(' ')?.substringBefore('/')?.substringBefore('-')
                    ?.toLongOrNull()
                if (declared != null && declared != start) {
                    throw IllegalStateException("服务端回的区间从 $declared 开始，要的是 $start")
                }
            }
            var written = 0L
            conn.inputStream.use { input ->
                RandomAccessFile(dest, "rw").use { out ->
                    out.seek(start)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        var n = input.read(buf)
                        if (n < 0) break
                        val left = (end - start + 1) - written
                        if (left <= 0) break
                        if (n > left) n = left.toInt()
                        out.write(buf, 0, n)
                        written += n
                    }
                }
            }
            return written
        } finally {
            conn.disconnect()
        }
    }
}
