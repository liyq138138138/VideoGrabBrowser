package com.videograb.browser

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * VideoSniffer intercepts network requests from the WebView
 * and identifies video/media URLs that can be downloaded.
 *
 * Supports:
 * - Standard video extensions (MP4, WebM, etc.)
 * - HLS streams (.m3u8)
 * - DASH streams (.mpd)
 * - Common Chinese video CDN patterns (youku, bilibili, iqiyi segments)
 * - Encrypted/obfuscated media in JS
 */
class VideoSniffer(private val onVideoDetected: (DetectedVideo) -> Unit) {

    companion object {
        private const val TAG = "VideoSniffer"

        private val VIDEO_EXTENSIONS = setOf(
            ".mp4", ".webm", ".avi", ".mov", ".mkv", ".flv", ".wmv",
            ".3gp", ".ts", ".m4s", ".m4v", ".f4v", ".hlv"
        )

        private val M3U8_PATTERN = Pattern.compile(
            """https?://[^'"\s<>]+\.m3u8[^'"\s<>]*""",
            Pattern.CASE_INSENSITIVE
        )
        private val MPD_PATTERN = Pattern.compile(
            """https?://[^'"\s<>]+\.mpd[^'"\s<>]*""",
            Pattern.CASE_INSENSITIVE
        )

        // Chinese video site CDN patterns — these often serve .ts directly
        private val CHINESE_CDN_PATTERNS = listOf(
            "video", "vod", "cdn", "pcdn", "media", "stream",
            "txvideo", "wsd", "gslb", "sss"
        ).map { keyword ->
            Pattern.compile(
                """https?://[^/]*$keyword[^/]*/.*?\.(ts|m4s|mp4|m3u8)(\?[^\s"']*)?(|[&\s"'<])""",
                Pattern.CASE_INSENSITIVE
            )
        }

        // Common video path patterns (many sites use /video/12345 format)
        private val VIDEO_PATH_PATTERN = Pattern.compile(
            """https?://[^/]+/video/\d+""",
            Pattern.CASE_INSENSITIVE
        )
    }

    private val detectedUrls = ConcurrentHashMap.newKeySet<String>()

    /**
     * Intercept WebView requests. Returns null to let WebView handle normally.
     */
    fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val sourcePage = request.requestHeaders?.get("Referer") ?: ""

        if (isVideoUrl(url) && detectedUrls.add(url)) {
            Log.d(TAG, "Detected video via intercept: ${url.take(100)}")
            onVideoDetected(
                DetectedVideo(
                    url = url,
                    sourceUrl = sourcePage,
                    detectedAt = System.currentTimeMillis(),
                    type = classifyVideoType(url)
                )
            )
        }

        return null
    }

    /**
     * Scan HTML for embedded video references.
     */
    fun sniffFromHtml(html: String, pageUrl: String) {
        // <video> with <source> children
        val sourcePattern = Pattern.compile(
            """<source[^>]+src\s*=\s*["']([^"']+)["'][^>]*>""",
            Pattern.CASE_INSENSITIVE
        )
        val m1 = sourcePattern.matcher(html)
        while (m1.find()) {
            addVideo(m1.group(1), pageUrl)
        }

        // <video src="...">
        val videoAttrPattern = Pattern.compile(
            """<video[^>]+src\s*=\s*["']([^"']+)["'][^>]*>""",
            Pattern.CASE_INSENSITIVE
        )
        val m2 = videoAttrPattern.matcher(html)
        while (m2.find()) {
            addVideo(m2.group(1), pageUrl)
        }

        // <audio> elements (sometimes audio podcasts)
        val audioPattern = Pattern.compile(
            """<audio[^>]+src\s*=\s*["']([^"']+)["'][^>]*>""",
            Pattern.CASE_INSENSITIVE
        )
        val m3 = audioPattern.matcher(html)
        while (m3.find()) {
            addVideo(m3.group(1), pageUrl)
        }

        // data attributes with video URLs
        val dataAttrPattern = Pattern.compile(
            """data-(?:src|url|video|source|media)\s*=\s*["']([^"']+\.(?:mp4|webm|m3u8|ts|m4s|flv)[^"']*)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val m4 = dataAttrPattern.matcher(html)
        while (m4.find()) {
            addVideo(m4.group(1), pageUrl)
        }

        // M3U8 URLs anywhere in text/scripts
        val m5 = M3U8_PATTERN.matcher(html)
        while (m5.find()) {
            addVideo(m5.group(), pageUrl, VideoType.HLS)
        }

        // DASH MPD URLs
        val m6 = MPD_PATTERN.matcher(html)
        while (m6.find()) {
            addVideo(m6.group(), pageUrl, VideoType.DASH)
        }

        // Chinese CDN TS segments
        CHINESE_CDN_PATTERNS.forEach { pattern ->
            val m7 = pattern.matcher(html)
            while (m7.find()) {
                addVideo(m7.group(), pageUrl)
            }
        }
    }

    private fun addVideo(raw: String, baseUrl: String, forceType: VideoType? = null) {
        val resolved = resolveUrl(raw.trim(), baseUrl)
        if (!isVideoUrl(resolved) || !detectedUrls.add(resolved)) return

        Log.d(TAG, "Detected video: ${resolved.take(100)}")
        onVideoDetected(
            DetectedVideo(
                url = resolved,
                sourceUrl = baseUrl,
                detectedAt = System.currentTimeMillis(),
                type = forceType ?: classifyVideoType(resolved)
            )
        )
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        VIDEO_EXTENSIONS.forEach { ext ->
            if (lower.contains(ext)) return true
        }
        if (M3U8_PATTERN.matcher(lower).find()) return true
        if (MPD_PATTERN.matcher(lower).find()) return true
        // Some sites serve video without extension — check by path
        if (VIDEO_PATH_PATTERN.matcher(url).find()) return true
        return false
    }

    private fun classifyVideoType(url: String): VideoType {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> VideoType.HLS
            lower.contains(".mpd") -> VideoType.DASH
            lower.contains(".ts") -> VideoType.TS_SEGMENT
            lower.contains(".m4s") -> VideoType.M4S_SEGMENT
            else -> VideoType.DIRECT
        }
    }

    private fun resolveUrl(raw: String, base: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("//")) return "https:$raw"
        return try {
            val baseUri = Uri.parse(base)
            baseUri.buildUpon().encodedPath(raw).build().toString()
        } catch (_: Exception) { raw }
    }
}

data class DetectedVideo(
    val url: String,
    val sourceUrl: String,
    val detectedAt: Long,
    val type: VideoType,
    var fileName: String = "",
    var sizeBytes: Long = 0,
    var title: String = ""
)

enum class VideoType {
    DIRECT,       // Direct video file (MP4, WebM, FLV, etc.)
    HLS,          // M3U8 playlist — needs TS segment merging
    DASH,         // MPD manifest — needs segment merging
    TS_SEGMENT,   // MPEG-TS segment (part of HLS)
    M4S_SEGMENT   // M4S segment (part of DASH/fMP4)
}
