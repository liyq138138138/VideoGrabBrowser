package com.videograb.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Central download manager that handles all video downloads.
 *
 * Storage strategy:
 * - Android 10+ (API 29+): Uses MediaStore API only
 * - Android 9 and below: Uses direct file access with WRITE_EXTERNAL_STORAGE
 */
class DownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "DownloadManager"
        private const val DOWNLOAD_DIR = "VideoGrab"

        /**
         * Android 10+ gets MediaStore-managed storage.
         * We use the relative path "Download/VideoGrab/" for easy access.
         */
        private val USE_MEDIA_STORE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    data class DownloadTask(
        val id: String = UUID.randomUUID().toString(),
        val url: String,
        val sourceUrl: String,
        val fileName: String,
        val type: VideoType,
        var status: DownloadStatus = DownloadStatus.QUEUED,
        var progress: Int = 0,
        var downloadedBytes: Long = 0,
        var totalBytes: Long = 0,
        var error: String? = null,
        var outputPath: String? = null,
        var outputUri: Uri? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    enum class DownloadStatus {
        QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeTasks = ConcurrentHashMap<String, Any>()
    private val tasks = mutableListOf<DownloadTask>()
    private var listeners = mutableListOf<(List<DownloadTask>) -> Unit>()

    /**
     * For Android 9 and below: fallback to legacy file storage.
     */
    private val legacyDownloadsDir: File
        get() {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_DIR
            )
            dir.mkdirs()
            return dir
        }

    /**
     * For Android 10+: internal cache for temp files during download,
     * then moved to MediaStore when complete.
     */
    private val cacheDir: File
        get() {
            val dir = File(context.cacheDir, "download_parts")
            dir.mkdirs()
            return dir
        }

    fun addListener(listener: (List<DownloadTask>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (List<DownloadTask>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val snapshot = tasks.toList()
        listeners.forEach { it(snapshot) }
    }

    fun getTasks(): List<DownloadTask> = tasks.toList()

    fun download(video: DetectedVideo) {
        val task = DownloadTask(
            url = video.url,
            sourceUrl = video.sourceUrl,
            fileName = generateFileName(video),
            type = video.type,
            status = DownloadStatus.QUEUED
        )

        tasks.add(task)
        notifyListeners()

        when (video.type) {
            VideoType.HLS, VideoType.DASH -> startM3U8Download(task)
            VideoType.DIRECT, VideoType.TS_SEGMENT, VideoType.M4S_SEGMENT -> startDirectDownload(task)
        }
    }

    private fun generateFileName(video: DetectedVideo): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())

        val urlPath = try {
            val path = java.net.URI(video.url).path
            path.substringAfterLast("/").substringBefore("?")
                .substringBefore(".")
                .take(30)
                .ifEmpty { "video" }
        } catch (_: Exception) { "video" }

        val title = video.title.takeIf { it.isNotBlank() }
            ?.let { sanitizeFileName(it) } ?: urlPath
        return "${title}_$timestamp"
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
    }

    // ====================================================================
    // Direct URL download (MP4, WebM, etc.)
    // ====================================================================

    private fun startDirectDownload(task: DownloadTask) {
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index < 0) return
        tasks[index] = task.copy(status = DownloadStatus.DOWNLOADING)
        notifyListeners()

        val ext = getExtension(task.url)

        val downloader = DirectDownloader(
            videoUrl = task.url,
            outputFile = getTempFile(task.fileName, ext),
            onProgress = { percent, downloaded, total ->
                updateTaskProgress(task.id, percent, downloaded, total)
            },
            onComplete = { tempFile ->
                saveToPermanentStorage(task.id, tempFile, ext)
            },
            onError = { error ->
                setTaskFailed(task.id, error)
            }
        )

        activeTasks[task.id] = downloader
        downloader.start()
    }

    // ====================================================================
    // HLS / M3U8 stream download
    // ====================================================================

    private fun startM3U8Download(task: DownloadTask) {
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index < 0) return
        tasks[index] = task.copy(status = DownloadStatus.DOWNLOADING)
        notifyListeners()

        val downloader = M3U8Downloader(
            m3u8Url = task.url,
            outputDir = cacheDir,
            fileName = task.fileName,
            onProgress = { percent, downloaded, total ->
                updateTaskProgress(task.id, percent, downloaded.toLong(), total.toLong())
            },
            onComplete = { tsFile ->
                if (tsFile != null && tsFile.exists()) {
                    saveToPermanentStorage(task.id, tsFile, "ts")
                } else {
                    setTaskFailed(task.id, "输出文件无效")
                }
            },
            onError = { error ->
                setTaskFailed(task.id, error)
            }
        )

        activeTasks[task.id] = downloader
        downloader.start()
    }

    // ====================================================================
    // Progress / state management
    // ====================================================================

    private fun updateTaskProgress(id: String, percent: Int, downloaded: Long, total: Long) {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            tasks[idx] = tasks[idx].copy(
                progress = percent,
                downloadedBytes = downloaded,
                totalBytes = total
            )
            notifyListeners()
        }
    }

    private fun setTaskFailed(id: String, error: String) {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            tasks[idx] = tasks[idx].copy(
                status = DownloadStatus.FAILED,
                error = error
            )
            notifyListeners()
        }
    }

    // ====================================================================
    // Permanent storage (MediaStore for Android 10+, legacy for below)
    // ====================================================================

    /**
     * Save completed file to permanent storage.
     * On Android 10+ (Q), this uses MediaStore API.
     * On older devices, it copies to Download/VideoGrab/.
     */
    private fun saveToPermanentStorage(taskId: String, tempFile: File, ext: String) {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index < 0) return

        val task = tasks[index]
        val displayName = "${task.fileName}.$ext"
        val mimeType = getMimeType(displayName)

        try {
            if (USE_MEDIA_STORE) {
                // ===== Android 10+ MediaStore API =====
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Download/$DOWNLOAD_DIR")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val itemUri = context.contentResolver.insert(collectionUri, contentValues)

                if (itemUri != null) {
                    // Write file content via ContentResolver
                    context.contentResolver.openOutputStream(itemUri)?.use { outputStream ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(outputStream)
                        }
                    }

                    // Mark as not pending
                    val updateValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                        put(MediaStore.Video.Media.SIZE, tempFile.length())
                    }
                    context.contentResolver.update(itemUri, updateValues, null, null)

                    // Cleanup temp file
                    tempFile.delete()

                    tasks[index] = tasks[index].copy(
                        status = DownloadStatus.COMPLETED,
                        progress = 100,
                        outputPath = itemUri.toString(),
                        outputUri = itemUri,
                        totalBytes = tempFile.length()
                    )
                } else {
                    tasks[index] = tasks[index].copy(
                        status = DownloadStatus.FAILED,
                        error = "存储空间写入失败"
                    )
                }
            } else {
                // ===== Android 9 and below: legacy file storage =====
                val destFile = File(legacyDownloadsDir, displayName)
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()

                // Register in MediaStore for older Android too
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Video.Media.DATA, destFile.absolutePath)
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Video.Media.SIZE, destFile.length())
                }
                try {
                    context.contentResolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues
                    )
                } catch (_: Exception) { }

                tasks[index] = tasks[index].copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    outputPath = destFile.absolutePath,
                    totalBytes = destFile.length()
                )
            }

            notifyListeners()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save permanent file", e)
            tasks[index] = tasks[index].copy(
                status = DownloadStatus.FAILED,
                error = "保存文件失败: ${e.localizedMessage}"
            )
            notifyListeners()
        }
    }

    // ====================================================================
    // Cancellation & cleanup
    // ====================================================================

    fun cancelTask(taskId: String) {
        val cancelTarget = activeTasks[taskId]
        when (cancelTarget) {
            is M3U8Downloader -> cancelTarget.cancel()
            is DirectDownloader -> cancelTarget.cancel()
        }
        activeTasks.remove(taskId)

        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx >= 0) {
            tasks[idx] = tasks[idx].copy(status = DownloadStatus.CANCELLED)
            notifyListeners()
        }
    }

    fun clearCompleted() {
        tasks.removeAll {
            it.status in listOf(
                DownloadStatus.COMPLETED,
                DownloadStatus.CANCELLED,
                DownloadStatus.FAILED
            )
        }
        notifyListeners()
    }

    // ====================================================================
    // Utilities
    // ====================================================================

    private fun getTempFile(baseName: String, ext: String): File {
        return File(cacheDir, "${baseName}_tmp.$ext")
    }

    private fun getExtension(url: String): String {
        val path = url.substringBefore("?").substringAfterLast("/")
        val ext = path.substringAfterLast(".", "mp4")
        return ext.takeIf { it.length in 2..5 } ?: "mp4"
    }

    private fun getMimeType(fileName: String): String {
        val ext = MimeTypeMap.getFileExtensionFromUrl(fileName)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/mp4"
    }
}
