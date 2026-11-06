package app.webgen.template

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.http.SslError
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Template WebView app (single Activity, framework-only, no AndroidX).
 *
 * On startup it reads assets/site_config.json:
 *   {"url": "https://...", "name": "...",
 *    "templateVersion": 4,                    // template version baked in
 *    "notifications": true,                   // ask POST_NOTIFICATIONS once
 *    "permissions": {"microphone": true, "camera": false,
 *                    "location": false, "notifications": true},
 *    "updateFeedUrl": "https://..."}          // optional update feed
 * and shows the URL in a full-screen WebView.
 * The generator patches site_config.json, the manifest package/label
 * literals, the launcher icon PNGs and (for disabled capabilities) the
 * permission name strings to produce a stand-alone site app.
 *
 * Navigation rules (v1.1, auth-friendly):
 * - http/https navigations ALWAYS stay inside the WebView, regardless of
 *   host, so cross-host auth flows keep their cookies in-app.
 * - Non-http(s) schemes go to external apps by kind; intent:// is
 *   resolved via Intent.parseUri with browser_fallback_url support.
 * - window.open popups are shown in a dialog WebView with the same rules.
 *
 * Capabilities (v1.2):
 * - WebChromeClient.onPermissionRequest bridges the site's mic/camera
 *   requests to Android runtime permissions (RECORD_AUDIO / CAMERA).
 * - Geolocation prompts are bridged to ACCESS_FINE_LOCATION.
 * - File chooser (<input type="file">) is implemented, with a camera
 *   capture option when CAMERA is granted.
 * - "notifications": true in the config asks POST_NOTIFICATIONS once at
 *   startup. This is for app-side notices (e.g. the update prompt);
 *   Web Push (site push notifications) is NOT supported by WebView.
 * - If the config carries updateFeedUrl, the app fetches it once at
 *   startup; when the feed's templateVersion is newer than the baked-in
 *   one it tells the user to regenerate the app with アプリカ.
 * UX (v4): teal top progress bar, a splash overlay (app icon + name)
 * while the first page loads, pull-to-refresh, and a friendly error
 * overlay with a retry button for main-frame network / SSL errors.
 *
 * Note: some providers (notably Google OAuth) block embedded WebView
 * sign-in by policy; no in-app rule can fix that. See SPEC.md section 5.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var splashOverlay: LinearLayout
    private lateinit var errorOverlay: LinearLayout
    private var pageError = false
    private var startUrl: String = ""
    private var popupWebView: WebView? = null
    private var popupDialog: Dialog? = null

    private lateinit var siteConfig: JSONObject

    private var touchDownY = 0f
    private var pullReloadArmed = false

    // Bridges between WebChromeClient callbacks and runtime permissions.
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        siteConfig = readSiteConfig()
        startUrl = siteConfig.optString("url", "https://example.com")
        title = siteConfig.optString("name", "")

        val root = FrameLayout(this)
        webView = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(BRAND_TEAL)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(-1, 6))
        errorOverlay = buildErrorOverlay()
        root.addView(errorOverlay, FrameLayout.LayoutParams(-1, -1))
        splashOverlay = buildSplashOverlay()
        root.addView(splashOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // Dark mode (template v5): on Android 13+, let the WebView
        // algorithmically darken pages when the device is in dark
        // mode. Sites with their own prefers-color-scheme support
        // follow the device setting through the WebView itself.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                webView.settings.setAlgorithmicDarkeningAllowed(true)
            } catch (e: Exception) {
                // older WebView providers may not support it
            }
        }
        applyWebSettings(webView)
        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = chromeClient

        // Simple pull-to-refresh: drag down while at the very top.
        webView.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownY = ev.y
                    pullReloadArmed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pullReloadArmed && v.scrollY == 0 && ev.y - touchDownY > 220f) {
                        pullReloadArmed = false
                        webView.reload()
                    }
                }
            }
            false
        }

        if (savedInstanceState == null) {
            webView.loadUrl(startUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }

        maybeRequestNotificationPermission()
        checkForTemplateUpdate()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyWebSettings(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // Popup (window.open) support, used by OAuth-style auth flows.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            // Geolocation API (bridged to runtime permission below).
            setGeolocationEnabled(true)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = handleNavigation(view, request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (view === webView) {
                    pageError = false
                    errorOverlay.visibility = View.GONE
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Persist session cookies (login state) promptly.
                CookieManager.getInstance().flush()
                if (view === webView) {
                    splashOverlay.visibility = View.GONE
                    if (!pageError) errorOverlay.visibility = View.GONE
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (view === webView && request.isForMainFrame) {
                    showError()
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                // Never proceed on a certificate error; show the retry
                // screen instead of a raw browser error page.
                handler.cancel()
                if (view === webView) showError()
            }
        }
    }

    private fun showError() {
        pageError = true
        splashOverlay.visibility = View.GONE
        errorOverlay.visibility = View.VISIBLE
    }

    private fun appIconDrawable(): android.graphics.drawable.Drawable? {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }

    /** Full-screen splash shown until the first page finishes loading. */
    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun brandBg(): Int =
        if (isNightMode()) 0xFF0C1B1A.toInt() else BRAND_BG

    private fun inkColor(): Int =
        if (isNightMode()) 0xFFE8F4F1.toInt() else INK

    private fun inkSubColor(): Int =
        if (isNightMode()) 0xFF9DB8B3.toInt() else INK_SUB

    private fun buildSplashOverlay(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(brandBg())
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(appIconDrawable())
                val size = (96 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            addView(icon)
            addView(TextView(this@MainActivity).apply {
                text = siteConfig.optString("name", "")
                textSize = 20f
                setTextColor(inkColor())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, (14 * density).toInt(), 0, 0)
            })
            addView(ProgressBar(this@MainActivity).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(BRAND_TEAL)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (18 * density).toInt() }
            })
        }
    }

    /** Friendly error screen with retry, for main-frame load failures. */
    private fun buildErrorOverlay(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(brandBg())
            visibility = View.GONE
            setPadding(
                (32 * density).toInt(), 0, (32 * density).toInt(), 0
            )
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(appIconDrawable())
                val size = (72 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            addView(icon)
            addView(TextView(this@MainActivity).apply {
                text = "ページを読み込めませんでした"
                textSize = 17f
                setTextColor(inkColor())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, (16 * density).toInt(), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = "通信環境を確認して、もう一度お試しください。"
                textSize = 13f
                setTextColor(inkSubColor())
                gravity = Gravity.CENTER
                setPadding(0, (8 * density).toInt(), 0, 0)
            })
            addView(Button(this@MainActivity).apply {
                text = "再読み込み"
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(BRAND_TEAL)
                setOnClickListener {
                    errorOverlay.visibility = View.GONE
                    pageError = false
                    if (webView.url.isNullOrEmpty()) {
                        webView.loadUrl(startUrl)
                    } else {
                        webView.reload()
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (20 * density).toInt() }
            })
        }
    }

    /**
     * Central navigation rule, shared by the main WebView and popups.
     * Returns true when the navigation has been handled (or swallowed)
     * and the WebView must NOT load it itself.
     */
    private fun handleNavigation(view: WebView, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return when {
            // All http/https stays in the WebView, host does not matter,
            // so cross-host auth redirects keep their cookies in-app.
            scheme == "http" || scheme == "https" -> false

            // Schemes the WebView itself renders; never dispatch outside.
            scheme in INTERNAL_SCHEMES -> false

            scheme == "intent" -> {
                handleIntentScheme(view, uri)
                true
            }

            // tel:, mailto:, sms:, geo:, and any other custom scheme:
            // hand to an external app; if none handles it, ignore quietly
            // (do not let the WebView show an error page for it).
            else -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    // No handler installed: swallow silently.
                }
                true
            }
        }
    }

    private fun handleIntentScheme(view: WebView, uri: Uri) {
        val intent = try {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // App not installed etc.: fall back to the web URL if given.
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (!fallback.isNullOrEmpty()) {
                view.loadUrl(fallback)
            }
        }
    }

    // ------------------------------------------------------------------
    // WebChromeClient: progress, popups, and the v1.2 capability bridges
    // ------------------------------------------------------------------

    private val chromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view === webView) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            closePopup()
            val popup = WebView(this@MainActivity)
            applyWebSettings(popup)
            popup.webViewClient = createWebViewClient()
            popup.webChromeClient = this

            val dialog = Dialog(this@MainActivity)
            dialog.setContentView(
                popup,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            dialog.setOnDismissListener {
                if (popupWebView === popup) {
                    popupWebView = null
                    popupDialog = null
                }
                popup.destroy()
            }
            popupWebView = popup
            popupDialog = dialog
            dialog.show()
            val metrics = resources.displayMetrics
            dialog.window?.setLayout(
                (metrics.widthPixels * 0.96f).toInt(),
                (metrics.heightPixels * 0.92f).toInt()
            )

            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window === popupWebView) {
                closePopup()
            } else {
                super.onCloseWindow(window)
            }
        }

        /**
         * The site asks for mic/camera (getUserMedia). Bridge each web
         * resource to its Android runtime permission: ask the user for
         * whatever is missing, then grant/deny accordingly. Resources
         * that need no Android permission are granted as-is.
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            val missing = mutableListOf<String>()
            for (res in request.resources) {
                val androidPerm = androidPermissionFor(res) ?: continue
                if (!isGranted(androidPerm) && androidPerm !in missing) {
                    missing.add(androidPerm)
                }
            }
            if (missing.isEmpty()) {
                request.grant(request.resources)
                return
            }
            // A second request supersedes a still-pending one.
            pendingPermissionRequest?.deny()
            pendingPermissionRequest = request
            requestPermissions(missing.toTypedArray(), REQ_WEB_PERMISSION)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingPermissionRequest === request) {
                pendingPermissionRequest = null
            }
        }

        /** The site asks for geolocation. Bridge to ACCESS_FINE_LOCATION. */
        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            if (isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                callback.invoke(origin, true, false)
                return
            }
            pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
            pendingGeoOrigin = origin
            pendingGeoCallback = callback
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_GEOLOCATION
            )
        }

        override fun onGeolocationPermissionsHidePrompt() {
            pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }

        /**
         * <input type="file"> support. Uses the params' own intent (accept
         * types honored) and adds a camera capture option when CAMERA is
         * granted and a capture target could be prepared.
         */
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback
            cameraImageUri = null

            val cameraIntent = buildCameraIntent()
            if (fileChooserParams.isCaptureEnabled && cameraIntent != null) {
                // The page explicitly wants a capture (e.g. capture="user").
                @Suppress("DEPRECATION")
                startActivityForResult(cameraIntent, REQ_FILE_CHOOSER)
                return true
            }

            val contentIntent = try {
                fileChooserParams.createIntent()
            } catch (e: Exception) {
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
            }
            if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            val chooser = Intent.createChooser(contentIntent, "ファイルを選択")
            if (cameraIntent != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(chooser, REQ_FILE_CHOOSER)
                true
            } catch (e: Exception) {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = null
                false
            }
        }
    }

    /** IMAGE_CAPTURE intent with a MediaStore output Uri; null if unavailable. */
    private fun buildCameraIntent(): Intent? {
        if (!isGranted(Manifest.permission.CAMERA)) return null
        val uri = try {
            val values = ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "webgen_" + System.currentTimeMillis() + ".jpg"
                )
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return null
        cameraImageUri = uri
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun androidPermissionFor(webResource: String): String? {
        return when (webResource) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                Manifest.permission.RECORD_AUDIO
            PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                Manifest.permission.CAMERA
            else -> null // MIDI / protected media: no Android permission needed
        }
    }

    private fun isGranted(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------------
    // Runtime permission results
    // ------------------------------------------------------------------

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_WEB_PERMISSION -> {
                val request = pendingPermissionRequest ?: return
                pendingPermissionRequest = null
                // Re-check the live grant state (covers partial grants).
                val granted = request.resources.filter { res ->
                    val p = androidPermissionFor(res)
                    p == null || isGranted(p)
                }
                if (granted.isEmpty()) {
                    request.deny()
                } else {
                    request.grant(granted.toTypedArray())
                }
            }
            REQ_GEOLOCATION -> {
                val callback = pendingGeoCallback ?: return
                val origin = pendingGeoOrigin
                pendingGeoCallback = null
                pendingGeoOrigin = null
                callback.invoke(
                    origin,
                    isGranted(Manifest.permission.ACCESS_FINE_LOCATION), false
                )
            }
            REQ_NOTIFICATIONS -> {
                // Nothing to do: the grant state is what matters, and the
                // system records it. This request was app-side only.
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE_CHOOSER) return
        val callback = filePathCallback ?: return
        filePathCallback = null
        var results: Array<Uri>? = null
        if (resultCode == Activity.RESULT_OK) {
            val clip = data?.clipData
            val single = data?.data
            results = when {
                clip != null -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                single != null -> arrayOf(single)
                cameraImageUri != null -> arrayOf(cameraImageUri!!)
                else -> null
            }
        }
        cameraImageUri = null
        callback.onReceiveValue(results)
    }

    // ------------------------------------------------------------------
    // Notifications (app-side only) & template update check
    // ------------------------------------------------------------------

    /**
     * If the generator enabled notifications for this app, ask for
     * POST_NOTIFICATIONS once at startup (Android 13+). Web Push from the
     * site itself is not supported by WebView; this permission exists so
     * the APP can post local notices (like the update prompt below).
     */
    private fun maybeRequestNotificationPermission() {
        if (!siteConfig.optBoolean("notifications", false)) return
        if (Build.VERSION.SDK_INT < 33) return // install-time granted before 13
        if (!isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS
            )
        }
    }

    /**
     * Fetches updateFeedUrl once (background, silent on failure). If the
     * feed reports a templateVersion newer than the one baked into this
     * app's config, prompt the user to regenerate with アプリカ.
     */
    private fun checkForTemplateUpdate() {
        val feedUrl = siteConfig.optString("updateFeedUrl", "")
        if (feedUrl.isEmpty()) return
        val current = siteConfig.optInt("templateVersion", 0)
        if (current <= 0) return
        thread {
            val latest = try {
                val conn = URL(feedUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "WebGenTemplateUpdateCheck")
                val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                parseLatestTemplateVersion(JSONObject(text))
            } catch (e: Exception) {
                null
            }
            if (latest != null && latest > current && !isFinishing) {
                runOnUiThread { showTemplateUpdateDialog(feedUrl) }
            }
        }
    }

    /**
     * Feed JSON format: {"templateVersion": N} (preferred), or a GitHub
     * release object whose tag_name looks like "template-vN" / "template-N".
     */
    private fun parseLatestTemplateVersion(json: JSONObject): Int? {
        val direct = json.optInt("templateVersion", -1)
        if (direct > 0) return direct
        val tag = json.optString("tag_name", "")
        val m = Regex("template[-_]?v?(\\d+)", RegexOption.IGNORE_CASE).find(tag)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun showTemplateUpdateDialog(feedUrl: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("新しいバージョンがあります")
            .setMessage("このアプリの新しいバージョンが用意できます。アプリカで再生成してください。")
            .setPositiveButton("アプリカを開く") { _, _ ->
                val launch = packageManager.getLaunchIntentForPackage(GENERATOR_PACKAGE)
                if (launch != null) {
                    startActivity(launch)
                } else {
                    // Generator not installed: open the releases page instead.
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasesPageUrl(feedUrl))))
                    } catch (e: Exception) {
                        // nowhere to go; dialog dismissal is enough
                    }
                }
            }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun releasesPageUrl(feedUrl: String): String {
        val m = Regex("api\\.github\\.com/repos/([^/]+)/([^/]+)").find(feedUrl)
        return if (m != null) {
            "https://github.com/${m.groupValues[1]}/${m.groupValues[2]}/releases"
        } else {
            feedUrl
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private fun closePopup() {
        val dialog = popupDialog
        if (dialog != null && dialog.isShowing) {
            dialog.dismiss() // dismiss listener destroys the popup WebView
        } else {
            popupWebView?.destroy()
            popupWebView = null
            popupDialog = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val popup = popupWebView
        if (popupDialog?.isShowing == true && popup != null) {
            if (popup.canGoBack()) {
                popup.goBack()
            } else {
                closePopup()
            }
            return
        }
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        closePopup()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun readSiteConfig(): JSONObject {
        return try {
            val text = assets.open("site_config.json").bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    companion object {
        // UI accents shared with the generator's brand palette.
        private const val BRAND_TEAL = 0xFF0E9594.toInt()
        private const val BRAND_BG = 0xFFF2F8F7.toInt()
        private const val INK = 0xFF173A3A.toInt()
        private const val INK_SUB = 0xFF57726F.toInt()

        /** Schemes rendered/handled by the WebView itself, never sent out. */
        private val INTERNAL_SCHEMES = setOf(
            "about", "data", "blob", "file", "content", "javascript", "view-source"
        )

        /** The generator app that can regenerate this site app. */
        private const val GENERATOR_PACKAGE = "app.webgen.generator"

        private const val REQ_WEB_PERMISSION = 1001
        private const val REQ_GEOLOCATION = 1002
        private const val REQ_FILE_CHOOSER = 1003
        private const val REQ_NOTIFICATIONS = 1004
    }
}
