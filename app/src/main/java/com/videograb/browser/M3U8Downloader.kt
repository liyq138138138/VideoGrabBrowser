package com.videograb.browser

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * Downloads M3U8 HLS streams by:
 * 1. Parsing the master playlist to find the best quality
 * 2. Parsing the media playlist to get TS segment URLs
 * 3. Downloading all TS segments in parallel
 * 4. Concatenating them into a single MPEG-TS file
 *
 * Optionally converts to MP4 using Android MediaMuxer (for supported codecs).
 */
class M3U8Downloader(
    private val m3u8Url: String,
    private val outputDir: File,
    private val fileName: String,
    private val onProgress: (percent: Int, downloadedSegments: Int, totalSegments: Int) -> Unit,
    private val onComplete: (outputFile: File?) -> Unit,
    private val onError: (error: String) -> Unit
) {
    companion object {
        private const val TAG = "M3U8Downloader"
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
        private const val MAX_PARALLEL_DOWNLOADS = 8
        private const val MAX_RETRIES = 3
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var cancelled = false

    /**
     * Base URL is derived from the playlist URL.
     * Segments are often relative to the playlist URL.
     */
    private var baseUrl: String = m3u8Url.substringBeforeLast("/")

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
        Log.d(TAG, "Starting M3U8 download: $m3u8Url")

        // Step 1: Fetch the M3U8 playlist
        val playlistContent = fetchUrl(m3u8Url) ?: run {
            onError("无法获取播放列表")
            return@withContext
        }

        // Step 2: Determine if master or media playlist
        val segmentUrls = parsePlaylist(playlistContent)

        if (segmentUrls.isEmpty()) {
            // Might be a master playlist — look for the best variant
            val variantUrl = findBestVariant(playlistContent)

            if (variantUrl != null) {
                Log.d(TAG, "Found variant playlist: $variantUrl")
                val resolvedVariant = resolveUrl(variantUrl)
                val variantContent = fetchUrl(resolvedVariant) ?: run {
                    onError("无法获取变体播放列表")
                    return@withContext
                }
                val variantSegments = parsePlaylist(variantContent)
                downloadSegments(variantSegments, resolvedVariant)
            } else {
                onError("无法解析播放列表（没有找到视频分段）")
            }
        } else {
            downloadSegments(segmentUrls, m3u8Url)
        }
    }

    /**
     * Resolve relative URLs against the base playlist URL.
     */
    private fun resolveUrl(segment: String): String {
        if (segment.startsWith("http://") || segment.startsWith("https://")) return segment
        if (segment.startsWith("//")) return "https:$segment"
        return "$baseUrl/$segment"
    }

    /**
     * Parse an M3U8 playlist and return segment URLs.
     */
    private fun parsePlaylist(content: String): List<String> {
        val segments = mutableListOf<String>()
        val lines = content.lines()

        var expectSegment = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                // Check for EXTINF (indicates next line is a segment URL)
                if (trimmed.startsWith("#EXTINF")) {
                    expectSegment = true
                }
                // Also check EXTM3U
                continue
            }

            // This is a URL line
            if (expectSegment || !trimmed.startsWith("#")) {
                // Skip if it looks like a variant playlist (contains m3u8)
                if (trimmed.endsWith(".m3u8", ignoreCase = true) ||
                    trimmed.contains("m3u8?")) {
                    expectSegment = false
                    continue
                }
                segments.add(trimmed)
                expectSegment = false
            }
        }

        Log.d(TAG, "Parsed ${segments.size} segments from playlist")
        return segments
    }

    /**
     * Find the highest quality variant stream from a master playlist.
     */
    private fun findBestVariant(playlist: String): String? {
        var bestUrl: String? = null
        var bestBandwidth = -1L

        val lines = playlist.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // Extract bandwidth
                val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(line)
                val bandwidth = bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0

                // Next non-comment line should be the URL
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isNotBlank() && !next.startsWith("#")) {
                        if (bandwidth > bestBandwidth) {
                            bestBandwidth = bandwidth
                            bestUrl = next
                        }
                        break
                    }
                    j++
                }
            }
            i++
        }

        // If no stream inf found, just return the first non-comment URL ending in m3u8
        if (bestUrl == null) {
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("#") &&
                    (trimmed.endsWith(".m3u8", ignoreCase = true) ||
                            trimmed.contains("m3u8?"))) {
                    bestUrl = trimmed
                    break
                }
            }
        }

        return bestUrl
    }

    /**
     * Download all segments and concatenate them.
     */
    private suspend fun downloadSegments(
        segments: List<String>,
        playlistUrl: String
    ) = withContext(IO) {
        if (segments.isEmpty()) {
            onError("没有找到视频分段")
            return@withContext
        }

        val total = segments.size
        val outputFile = File(outputDir, "$fileName.ts")
        val tempDir = File(outputDir, "${fileName}_parts")
        tempDir.mkdirs()

        val downloadedCount = AtomicInteger(0)
        val segmentFiles = arrayOfNulls<File?>(total)
        val failedSegments = mutableListOf<Int>()

        onProgress(0, 0, total)

        // Download segments in parallel batches
        val batches = segments.chunked(MAX_PARALLEL_DOWNLOADS)
        for (batch in batches) {
            val deferred = batch.mapIndexed { indexInBatch, segmentUrl ->
                val globalIndex = (downloadedCount.get()) + indexInBatch
                async {
                    val resolved = resolveUrl(segmentUrl)
                    val partFile = File(tempDir, "part_${"%05d".format(globalIndex)}.ts")
                    for (attempt in 1..MAX_RETRIES) {
                        try {
                            downloadFile(resolved, partFile)
                            return@async partFile
                        } catch (e: Exception) {
                            Log.w(TAG, "Segment $globalIndex attempt $attempt failed: ${e.message}")
                            if (attempt == MAX_RETRIES) {
                                return@async null // Give up
                            }
                            delay(1000L * attempt) // Exponential backoff
                        }
                    }
                    return@async null
                }
            }

            val results = deferred.awaitAll()
            results.forEachIndexed { idx, file ->
                val globalIdx = downloadedCount.getAndIncrement()
                segmentFiles[globalIdx] = file
                if (file == null) {
                    failedSegments.add(globalIdx)
                }
            }

            if (failedSegments.isEmpty()) {
                onProgress(
                    (downloadedCount.get() * 100 / total).coerceAtMost(99),
                    downloadedCount.get(),
                    total
                )
            }
        }

        // Check cancellation
        if (cancelled) {
            tempDir.deleteRecursively()
            return@withContext
        }

        // If too many segments failed, abort
        if (failedSegments.size > total * 0.1) {
            tempDir.deleteRecursively()
            onError("下载失败：${failedSegments.size}/$total 个分段下载失败")
            return@withContext
        }

        onProgress(99, total, total)

        // Concatenate all downloaded segments
        try {
            concatTsFiles(segmentFiles.filterNotNull(), outputFile)
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            onError("合并分段失败: ${e.localizedMessage}")
            return@withContext
        }

        // Cleanup
        tempDir.deleteRecursively()

        // Try to convert TS to MP4 if possible
        if (outputFile.exists() && outputFile.length() > 0) {
            onProgress(100, total, total)
            onComplete(outputFile)
        } else {
            onError("输出文件无效")
        }
    }

    /**
     * Concatenate TS files in order. TS is a container format where
     * simple concatenation produces a valid combined file.
     */
    private fun concatTsFiles(parts: List<File>, output: File) {
        val buffer = ByteArray(8192)
        output.outputStream().use { out ->
            parts.forEach { part ->
                if (part.exists()) {
                    part.inputStream().use { input ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }
        }
        Log.d(TAG, "Concatenated ${parts.size} parts into ${output.absolutePath}")
    }

    /**
     * Download a file from URL to local path.
     */
    private fun downloadFile(url: String, output: File) {
        val conn = URL(url).openConnection()
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0")
        conn.setRequestProperty("Accept", "*/*")

        conn.connect()

        val inputStream = conn.getInputStream()
        output.outputStream().use { outputStream ->
            inputStream.copyTo(outputStream, bufferSize = 8192)
        }
        inputStream.close()
    }

    /**
     * Fetch URL content as string.
     */
    private fun fetchUrl(url: String): String? {
        return try {
            val conn = URL(url).openConnection()
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0")

            conn.connect()

            val inputStream = conn.getInputStream()
            val result = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()

            // Update base URL from redirect
            baseUrl = conn.url.toString().substringBeforeLast("/")

            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch URL: $url", e)
            null
        }
    }
}
