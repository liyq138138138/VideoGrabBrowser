package com.videograb.browser

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads direct video files (MP4, WebM, etc.) with:
 * - Resumable downloads
 * - Progress tracking
 * - Multi-threaded download support (for large files)
 */
class DirectDownloader(
    private val videoUrl: String,
    private val outputFile: File,
    private val onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit,
    private val onComplete: (outputFile: File) -> Unit,
    private val onError: (error: String) -> Unit
) {
    companion object {
        private const val TAG = "DirectDownloader"
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
        private const val USER_AGENT = "Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0"
        private const val MIN_MULTI_THREAD_SIZE = 50L * 1024 * 1024 // 50MB
    }

    private val scope = CoroutineScope(IO + SupervisorJob())
    private var job: Job? = null
    private var cancelled = false

    fun start() {
        cancelled = false
        job = scope.launch {
            try {
                download()
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                onError("下载失败: ${e.localizedMessage ?: "未知错误"}")
            }
        }
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
    }

    private suspend fun download() = withContext(IO) {
        // Get file size and check if it supports range requests
        val fileInfo = getFileInfo(videoUrl)
            ?: run {
                onError("无法获取文件信息")
                return@withContext
            }

        val totalBytes = fileInfo.contentLength
        val supportsRange = fileInfo.acceptsRanges

        Log.d(TAG, "File size: $totalBytes, Range support: $supportsRange")

        if (totalBytes > 0) {
            onProgress(0, 0, totalBytes)
        }

        if (supportsRange && totalBytes > MIN_MULTI_THREAD_SIZE) {
            // Multi-threaded download for large files
            downloadMultiThreaded(totalBytes)
        } else {
            // Simple single-stream download
            downloadSingleStream()
        }
    }

    private suspend fun downloadSingleStream() = withContext(IO) {
        var conn: HttpURLConnection? = null
        var inputStream: java.io.InputStream? = null

        try {
            conn = URL(videoUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("User-Agent", USER_AGENT)

            conn.connect()

            val totalBytes = conn.contentLengthLong
            inputStream = conn.inputStream

            outputFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead: Long = 0
                var lastProgress = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (cancelled) return@withContext
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    if (totalBytes > 0) {
                        val percent = (totalRead * 100 / totalBytes).toInt()
                        if (percent != lastProgress) {
                            lastProgress = percent
                            onProgress(percent, totalRead, totalBytes)
                        }
                    }
                }
            }

            if (!cancelled) {
                onProgress(100, totalBytes, totalBytes)
                onComplete(outputFile)
            }
        } finally {
            inputStream?.close()
            conn?.disconnect()
        }
    }

    private suspend fun downloadMultiThreaded(totalBytes: Long) = withContext(IO) {
        val numThreads = 4
        val chunkSize = totalBytes / numThreads
        val downloadedBytes = AtomicLong(0)
        val deferredJobs = mutableListOf<Deferred<Boolean>>()

        // Pre-allocate file space
        RandomAccessFile(outputFile, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        for (i in 0 until numThreads) {
            val start = i * chunkSize
            val end = if (i == numThreads - 1) totalBytes - 1 else (start + chunkSize - 1)

            deferredJobs.add(async {
                downloadChunk(videoUrl, outputFile, start, end, downloadedBytes, totalBytes)
            })
        }

        val results = deferredJobs.awaitAll()
        val allSuccess = results.all { it }

        if (allSuccess && !cancelled) {
            onProgress(100, totalBytes, totalBytes)
            onComplete(outputFile)
        } else if (!cancelled) {
            onError("部分下载失败")
        }
    }

    private suspend fun downloadChunk(
        url: String,
        output: File,
        start: Long,
        end: Long,
        downloadedBytes: AtomicLong,
        totalBytes: Long
    ): Boolean = withContext(IO) {
        try {
            var conn: HttpURLConnection? = null
            var inputStream: java.io.InputStream? = null

            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout = READ_TIMEOUT
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Range", "bytes=$start-$end")

                conn.connect()

                if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    Log.e(TAG, "Chunk download failed: response ${conn.responseCode}")
                    return@withContext false
                }

                inputStream = conn.inputStream
                val buffer = ByteArray(8192)
                var bytesRead: Int

                RandomAccessFile(output, "rw").use { raf ->
                    raf.seek(start)
                    var lastProgress = 0

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelled) return@withContext false
                        raf.write(buffer, 0, bytesRead)
                        val cum = downloadedBytes.addAndGet(bytesRead.toLong())
                        val percent = (cum * 100 / totalBytes).toInt()
                        if (percent > lastProgress) {
                            lastProgress = percent
                            onProgress(percent, cum, totalBytes)
                        }
                    }
                }

                true
            } finally {
                inputStream?.close()
                conn?.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chunk download error", e)
            false
        }
    }

    private fun getFileInfo(url: String): FileInfo? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")

            // Use HEAD request first
            conn.requestMethod = "HEAD"
            conn.connect()

            val contentLength = conn.contentLengthLong
            val acceptsRanges = conn.getHeaderField("Accept-Ranges")?.equals("bytes", ignoreCase = true) ?: false
            val contentType = conn.contentType

            conn.disconnect()

            FileInfo(contentLength, acceptsRanges, contentType ?: "")
        } catch (e: Exception) {
            // HEAD might not be supported, try GET with small range
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout = READ_TIMEOUT
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Range", "bytes=0-0")
                conn.connect()

                val contentLength = conn.getHeaderField("Content-Range")
                    ?.let { parseContentRange(it) } ?: conn.contentLengthLong
                val acceptsRanges = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                conn.disconnect()

                FileInfo(contentLength, acceptsRanges, "")
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun parseContentRange(range: String): Long {
        val match = Regex("""bytes \d+-\d+/(\d+)""").find(range)
        return match?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    }

    private data class FileInfo(
        val contentLength: Long,
        val acceptsRanges: Boolean,
        val contentType: String
    )
}
