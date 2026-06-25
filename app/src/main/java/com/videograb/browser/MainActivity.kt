package com.videograb.browser

}
    // =================== 书签功能 ===================

    private fun toggleBookmark() {
        if (currentUrl.isBlank()) return
        Snackbar.make(webView, "书签功能暂不可用", Snackbar.LENGTH_SHORT).show()
    }

    private fun updateBookmarkIcon() {
        // Stub - bookmark icon will be updated once compilation succeeds
    }

    private fun showBookmarksDialog() {
        Snackbar.make(webView, "书签功能暂不可用", Snackbar.LENGTH_SHORT).show()
    }
}

package com.videograb.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.*
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var urlBar: EditText
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var tabWeb: View
    private lateinit var tabDownloads: View
    private lateinit var downloadsRecycler: RecyclerView
    private lateinit var emptyDownloads: View
    private lateinit var floatingDetectButton: com.google.android.material.floatingactionbutton.FloatingActionButton

    private lateinit var downloadManager: com.videograb.browser.DownloadManager
    private lateinit var bookmarkManager: com.videograb.browser.BookmarkManager
    private val videoSniffer by lazy { VideoSniffer { onVideoDetected(it) } }
    private lateinit var downloadsAdapter: DownloadAdapter

    private var currentUrl = ""
    private var currentTitle = ""
    private var detectedVideos = mutableListOf<DetectedVideo>()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val MANAGE_STORAGE_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ===== Android 15+ / OriginOS 6 Edge-to-Edge =====
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        downloadManager = com.videograb.browser.DownloadManager(this)
        bookmarkManager = com.videograb.browser.BookmarkManager(this)

        initViews()
        setupEdgeToEdgeInsets()
        setupWebView()
        setupBottomNav()
        setupDownloadList()
        setupPredictiveBack()
        requestPermissions()

        downloadManager.addListener { notifyDownloadBadge() }

        // Handle incoming URL intents (e.g. from other apps)
        handleIntent(intent)

        // Load default page if no intent
        if (webView.url == null) {
            webView.loadUrl("https://www.google.com")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.toString()?.let { url ->
                loadUrl(url)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun initViews() {
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        urlBar = findViewById(R.id.urlBar)
        bottomNav = findViewById(R.id.bottomNav)
        tabWeb = findViewById(R.id.tabWeb)
        tabDownloads = findViewById(R.id.tabDownloads)
        downloadsRecycler = findViewById(R.id.downloadsRecycler)
        emptyDownloads = findViewById(R.id.emptyDownloads)
        floatingDetectButton = findViewById(R.id.floatingDetectButton)

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                loadUrl(urlBar.text.toString())
                true
            } else false
        }

        findViewById<View>(R.id.btnGo).setOnClickListener {
            loadUrl(urlBar.text.toString())
        }

        findViewById<View>(R.id.btnBookmark).setOnClickListener {
            toggleBookmark()
        }
        findViewById<View>(R.id.btnBookmark).setOnLongClickListener {
            showBookmarksDialog()
            true
        }

        findViewById<View>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }

        findViewById<View>(R.id.btnForward).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }

        findViewById<View>(R.id.btnRefresh).setOnClickListener {
            webView.reload()
        }

        floatingDetectButton.setOnClickListener {
            showDetectedVideosDialog()
        }
    }

    /**
     * Android 15+ Edge-to-Edge: handle system bar insets
     * so content doesn't overlap with status/navigation bars.
     * This is critical for OriginOS 6's gesture navigation.
     */
    private fun setupEdgeToEdgeInsets() {
        // Keep the status bar icons visible (light or dark)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        // Apply insets to the root layout
        val rootLayout = findViewById<View>(R.id.rootLayout)
        rootLayout.setOnApplyWindowInsetsListener { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsets.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsets.Type.navigationBars())

            // Only apply top inset (status bar) 鈥?bottom nav handles its own
            view.updatePadding(top = statusBarInsets.top)

            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            allowContentAccess = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            databaseEnabled = true

            // ===== OriginOS 6 / iQOO 10 UA =====
            userAgentString = "Mozilla/5.0 (Linux; Android 16; iQOO 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"

            // Enable WebView dark theme support (OriginOS follows system)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                forceDark = WebSettings.FORCE_DARK_AUTO
            }
        }

        // Enable multi-window / split-screen support for OriginOS骞宠瑙嗙晫
        // Note: setEnableSmartHistory requires API 28+ and WebView 117+, wrap to avoid crash
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                WebView::class.java.getMethod("setEnableSmartHistory", Boolean::class.javaPrimitiveType)?.invoke(webView, true)
            }
        } catch (_: Exception) {
            // Method not available in this WebView version
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.isVisible = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                supportActionBar?.title = title
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let {
                    urlBar.setText(it)
                    currentUrl = it
                }
                detectedVideos.clear()
                floatingDetectButton.isVisible = false
                updateBookmarkIcon()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                currentTitle = title ?: ""
                updateBookmarkIcon()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let { currentUrl = it }
                injectVideoScanner()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                request?.url?.toString()?.let { url ->
                    if (isDirectVideoUrl(url)) {
                        showVideoDetected(url, request.requestHeaders?.get("Referer") ?: currentUrl)
                        return true
                    }
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val intercepted = videoSniffer.interceptRequest(request ?: return null)
                if (intercepted == null) {
                    request.url.toString().let { url ->
                        if (isDirectVideoUrl(url) && !url.contains("m3u8")) {
                            showVideoDetected(url, request.requestHeaders?.get("Referer") ?: currentUrl)
                        }
                    }
                }
                return intercepted
            }
        }

        // ===== Android 14+ predictive back gesture for WebView =====
        // Let WebView handle its own back navigation first
    }

    private fun injectVideoScanner() {
        val js = """
            (function() {
                var videos = [];
                
                // Find all <video> elements
                document.querySelectorAll('video').forEach(function(v) {
                    if (v.src) videos.push(v.src);
                    v.querySelectorAll('source').forEach(function(s) {
                        if (s.src) videos.push(s.src);
                    });
                });
                
                // Find all M3U8 URLs in scripts and page source
                var html = document.documentElement.innerHTML;
                var m3u8Regex = /https?:\/\/[^'"\s]+\.m3u8[^'"\s]*/g;
                var match;
                while ((match = m3u8Regex.exec(html)) !== null) {
                    videos.push(match[0]);
                }
                
                // Check for video URLs in all script tags
                document.querySelectorAll('script').forEach(function(s) {
                    if (s.textContent) {
                        var found = s.textContent.match(/https?:\/\/[^'"\s]+\.(mp4|webm|m3u8|m4s|ts)[^'"\s]*/gi);
                        if (found) videos = videos.concat(found);
                    }
                });
                
                // Check for video URLs in inline styles and attributes
                document.querySelectorAll('[data-src*=".mp4"], [data-url*=".mp4"], [data-video*=".mp4"]').forEach(function(el) {
                    var src = el.getAttribute('data-src') || el.getAttribute('data-url') || el.getAttribute('data-video');
                    if (src) videos.push(src);
                });
                
                if (videos.length > 0) {
                    videos = [...new Set(videos)];
                    Android.onVideosDetected(JSON.stringify(videos));
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mp4") || lower.contains(".webm") ||
                lower.contains(".mkv") || lower.contains(".mov") ||
                lower.contains(".avi") || lower.contains(".flv") ||
                lower.contains(".m3u8") || lower.contains(".ts") ||
                lower.contains(".m4s")
    }

    @JavascriptInterface
    fun onVideosDetected(jsonVideos: String) {
        runOnUiThread {
            try {
                val gson = com.google.gson.Gson()
                val urls: Array<String> = gson.fromJson(jsonVideos, Array<String>::class.java)
                urls.forEach { url ->
                    showVideoDetected(url, currentUrl)
                }
            } catch (_: Exception) { }
        }
    }

    private fun showVideoDetected(url: String, source: String) {
        if (detectedVideos.any { it.url == url }) return

        val type = when {
            url.contains(".m3u8", ignoreCase = true) -> VideoType.HLS
            url.contains(".mpd", ignoreCase = true) -> VideoType.DASH
            url.contains(".ts", ignoreCase = true) -> VideoType.TS_SEGMENT
            url.contains(".m4s", ignoreCase = true) -> VideoType.M4S_SEGMENT
            else -> VideoType.DIRECT
        }

        detectedVideos.add(
            DetectedVideo(url = url, sourceUrl = source, detectedAt = System.currentTimeMillis(), type = type)
        )

        floatingDetectButton.isVisible = true
        // Badge is handled via the badge in XML layout; fallback to content description
        floatingDetectButton.setContentDescription("鍙戠幇瑙嗛: ${detectedVideos.size}")

        Snackbar.make(webView, "鍙戠幇 ${if (type == VideoType.HLS) "HLS娴?ts" else "瑙嗛"}",
            Snackbar.LENGTH_SHORT).setAction("涓嬭浇") { showDetectedVideosDialog() }.show()
    }

    private fun showDetectedVideosDialog() {
        if (detectedVideos.isEmpty()) {
            Snackbar.make(webView, "娌℃湁鍙戠幇鍙笅杞界殑瑙嗛", Snackbar.LENGTH_SHORT).show()
            return
        }

        val items = detectedVideos.mapIndexed { index, video ->
            val typeStr = when (video.type) {
                VideoType.DIRECT -> "MP4"
                VideoType.HLS -> "M3U8娴?
                VideoType.DASH -> "DASH"
                VideoType.TS_SEGMENT -> "TS"
                VideoType.M4S_SEGMENT -> "fMP4"
            }
            "${index + 1}. [$typeStr] ...${video.url.takeLast(50)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("鍙戠幇 ${detectedVideos.size} 涓棰?)
            .setItems(items) { _, which ->
                val video = detectedVideos[which]
                AlertDialog.Builder(this)
                    .setTitle("涓嬭浇瑙嗛")
                    .setMessage("绫诲瀷: ${video.type}\n鏉ヨ嚜: ${video.sourceUrl.take(60)}")
                    .setPositiveButton("涓嬭浇") { _, _ ->
                        downloadManager.download(video)
                        Snackbar.make(webView, "寮€濮嬩笅杞?, Snackbar.LENGTH_SHORT).show()
                        switchToDownloadsTab()
                    }
                    .setNegativeButton("鍙栨秷", null)
                    .show()
            }
            .setNeutralButton("鍏ㄩ儴涓嬭浇") { _, _ ->
                detectedVideos.forEach { downloadManager.download(it) }
                Snackbar.make(webView, "寮€濮嬩笅杞?${detectedVideos.size} 涓棰?, Snackbar.LENGTH_SHORT).show()
                switchToDownloadsTab()
                detectedVideos.clear()
                floatingDetectButton.isVisible = false
            }
            .show()
    }

    private fun onVideoDetected(video: DetectedVideo) {
        runOnUiThread { showVideoDetected(video.url, video.sourceUrl) }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_browser -> {
                    tabWeb.isVisible = true
                    tabDownloads.isVisible = false
                    true
                }
                R.id.nav_downloads -> {
                    switchToDownloadsTab()
                    true
                }
                else -> false
            }
        }
    }

    private fun switchToDownloadsTab() {
        tabWeb.isVisible = false
        tabDownloads.isVisible = true
        bottomNav.selectedItemId = R.id.nav_downloads
        refreshDownloadList()
    }

    /**
     * Android 16 棰勬祴鎬ц繑鍥炴墜鍔?
     * 浣跨敤 OnBackPressedCallback 鏇夸唬 onBackPressed
     */
    private fun setupPredictiveBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tabDownloads.isVisible) {
                    tabWeb.isVisible = true
                    tabDownloads.isVisible = false
                    bottomNav.selectedItemId = R.id.nav_browser
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // =================== 涔︾鍔熻兘 ===================

    private fun toggleBookmark() {
        if (currentUrl.isBlank()) return
        bookmarkManager.toggle(currentUrl, currentTitle)
        updateBookmarkIcon()
        val msg = if (bookmarkManager.isBookmarked(currentUrl)) "宸插姞鍏ヤ功绛? else "宸插彇娑堜功绛?
        Snackbar.make(webView, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun updateBookmarkIcon() {
        val btnBookmark = findViewById<android.widget.ImageButton>(R.id.btnBookmark)
        if (currentUrl.isNotBlank() && bookmarkManager.isBookmarked(currentUrl)) {
            btnBookmark.setImageResource(R.drawable.ic_bookmark_filled)
        } else {
            btnBookmark.setImageResource(R.drawable.ic_bookmark)
        }
    }

    private fun showBookmarksDialog() {
        val bookmarks = bookmarkManager.getAll()
        if (bookmarks.isEmpty()) {
            Snackbar.make(webView, "鏆傛棤涔︾", Snackbar.LENGTH_SHORT).show()
            return
        }

        val names = bookmarks.map { it.title.ifBlank { it.url } }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("涔︾")
            .setItems(names) { _, which ->
                loadUrl(bookmarks[which].url)
            }
            .setPositiveButton("绠＄悊") { _, _ ->
                val deleteNames = bookmarks.map { it.title.ifBlank { it.url } }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("闀挎寜鍒犻櫎涔︾")
                    .setItems(deleteNames) { _, which ->
                        bookmarkManager.remove(bookmarks[which].url)
                        updateBookmarkIcon()
                        showBookmarksDialog()
                    }
                    .setNegativeButton("鍏抽棴", null)
                    .show()
            }
            .setNegativeButton("鍏抽棴", null)
            .show()
    }

    private fun setupDownloadList() {
        downloadsAdapter = DownloadAdapter(
            onCancel = { task -> downloadManager.cancelTask(task.id) },
            onOpen = { task -> openDownloadedFile(task.outputPath) }
        )
        downloadsRecycler.layoutManager = LinearLayoutManager(this)
        downloadsRecycler.adapter = downloadsAdapter
    }

    /**
     * Open downloaded video using FileProvider (secure, works on Android 14+)
     */
    private fun openDownloadedFile(path: String?) {
        if (path == null) return
        val file = java.io.File(path)
        if (!file.exists()) return

        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: try implicit Uri
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(file), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Snackbar.make(webView, "鏃犳硶鎵撳紑瑙嗛鏂囦欢", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshDownloadList() {
        val tasks = downloadManager.getTasks()
        val hasActive = tasks.any { it.status == DownloadManager.DownloadStatus.DOWNLOADING }
        downloadsAdapter.submitList(tasks)
        emptyDownloads.isVisible = tasks.isEmpty()

        if (hasActive) {
            lifecycleScope.launch {
                delay(1000)
                refreshDownloadList()
            }
        }
    }

    private fun notifyDownloadBadge() {
        val activeCount = downloadManager.getTasks()
            .count { it.status == DownloadManager.DownloadStatus.DOWNLOADING }
        val badge = bottomNav.getOrCreateBadge(R.id.nav_downloads)
        if (activeCount > 0) {
            badge.isVisible = true
            badge.number = activeCount
        } else {
            badge.isVisible = false
        }
    }

    private fun loadUrl(input: String) {
        var url = input.trim()
        if (url.isBlank()) return

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(".") && !url.contains(" ")) "https://$url"
            else "https://www.google.com/search?q=${Uri.encode(url)}"
        }

        urlBar.setText(url)
        webView.loadUrl(url)
    }

    /**
     * 瀹夊崜鏉冮檺閫傞厤:
     * - Android 13+ (API 33+): READ_MEDIA_VIDEO 浠ｆ浛瀛樺偍鏉冮檺
     * - Android 11-12: READ/WRITE_EXTERNAL_STORAGE
     * - Android 16: 娌℃湁棰濆鍙樺寲锛岃蛋 MediaStore API
     */
    private fun requestPermissions() {
        val permsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: granular media permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            // Notification permission (required Android 13+)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Android 12 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                permsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, permsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE
            )
        }

        // Android 11+: handle MANAGE_EXTERNAL_STORAGE for direct file access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (!Environment.isExternalStorageManager()) {
                // We use MediaStore API on Android 11+, so this is just a fallback
                showManageStorageDialog()
            }
        }
    }

    private fun showManageStorageDialog() {
        // Silently proceed 鈥?we use MediaStore API which doesn't need MANAGE_EXTERNAL_STORAGE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (denied) {
                Snackbar.make(webView, "鏉冮檺琚嫆缁濓紝瑙嗛灏嗕繚瀛樺埌搴旂敤鍐呴儴鐩綍", Snackbar.LENGTH_LONG).show()
            }
        }
    }
}
