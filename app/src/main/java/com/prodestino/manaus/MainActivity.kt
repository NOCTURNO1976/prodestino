package com.prodestino.manaus

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.prodestino.manaus.location.ForegroundLocationService
import com.prodestino.manaus.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    // ===== Estado geral =====
    private lateinit var webView: WebView
    private var bootCompletedOnce = false
    private var resumed = false
    private var pageReady = false
    private var serviceStarted = false

    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastRecoverAt = 0L
    private var isOfflineShown = false

    private val ui = Handler(Looper.getMainLooper())

    // Permissões pendentes do getUserMedia
    private var pendingWebPermission: PermissionRequest? = null

    // File chooser callback
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val uri: Uri? = data?.data
            if (uri != null) callback.onReceiveValue(arrayOf(uri)) else callback.onReceiveValue(null)
        } else {
            callback.onReceiveValue(null)
        }
    }

    // Página offline (fallback interno, caso asset falhe)
    private val OFFLINE_FALLBACK_HTML: String by lazy {
        """
        <!doctype html><html lang="pt-br"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sem conexão</title>
        <style>html,body{height:100%}body{font-family:system-ui,Arial,sans-serif;margin:0;display:grid;place-items:center;background:#f7f7f7}
        .card{max-width:460px;margin:24px;padding:24px;text-align:center;border-radius:16px;box-shadow:0 8px 30px rgba(0,0,0,.08);background:#fff}
        h1{font-size:18px;margin:0 0 8px}p{color:#555;margin:0}</style>
        <div class="card" role="status" aria-live="polite">
          <h1>Sem conexão</h1><p>Volte ao app quando a internet estiver disponível.</p>
        </div></html>
        """.trimIndent()
    }

    // ===== Preferências simples =====
    private val uiPrefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    private var lastGoodUrl: String?
        get() = uiPrefs.getString("last_good_url", null)
        set(v) { uiPrefs.edit().putString("last_good_url", v).apply() }

    private fun isBubbleEnabled(): Boolean =
        uiPrefs.getBoolean("bubble_enabled", false)

    private fun setBubbleEnabled(enabled: Boolean) {
        uiPrefs.edit().putBoolean("bubble_enabled", enabled).apply()
    }

    // ===== Bridge exposto ao JavaScript =====
    private inner class WebBridge(private val ctx: Context) {

        /** Liga/Desliga a bolha (serviço de overlay) */
        @JavascriptInterface
        fun overlay(mode: String?) {
            val m = (mode ?: "").lowercase()
            when (m) {
                "on" -> {
                    setBubbleEnabled(true)
                    if (hasOverlayPermissionInternal()) {
                        startOverlayService(OverlayService.ACTION_SHOW)
                    } else {
                        requestOverlayPermissionIfNeeded()
                    }
                }
                "off" -> {
                    setBubbleEnabled(false)
                    startOverlayService(OverlayService.ACTION_HIDE)
                }
                else -> { // toggle
                    val newState = !isBubbleEnabled()
                    setBubbleEnabled(newState)
                    if (newState) {
                        if (hasOverlayPermissionInternal()) {
                            startOverlayService(OverlayService.ACTION_SHOW)
                        } else {
                            requestOverlayPermissionIfNeeded()
                        }
                    } else {
                        startOverlayService(OverlayService.ACTION_HIDE)
                    }
                }
            }
        }

        /** Abre a tela do sistema para conceder a permissão de sobreposição */
        @JavascriptInterface
        fun ensureOverlayPermission() {
            requestOverlayPermissionIfNeeded()
        }

        /** Retorna se o app já pode desenhar sobre outros apps */
        @JavascriptInterface
        fun hasOverlayPermission(): Boolean = hasOverlayPermissionInternal()

        /** Estado atual desejado (persistido) da bolha */
        @JavascriptInterface
        fun isBubbleEnabled(): Boolean = this@MainActivity.isBubbleEnabled()
    }

    // ===== Utilitários de permissão/efeitos =====
    private fun vibrate(ms: Long = 60) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(ms)
                }
            }
        } catch (_: Exception) { }
    }

    private fun hasFineOrCoarse(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

    private fun hasOverlayPermissionInternal(): Boolean =
        Settings.canDrawOverlays(this)

    private fun requestOverlayPermissionIfNeeded() {
        if (!hasOverlayPermissionInternal()) {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    /**
     * Inicia o serviço da bolha com segurança:
     * - SHOW/TOGGLE: usa startForegroundService (Android O+)
     * - HIDE: usa startService (para não forçar startForeground sem necessidade)
     */
    private fun startOverlayService(action: String) {
        val i = Intent(this, OverlayService::class.java).apply { this.action = action }
        val isShow = action == OverlayService.ACTION_SHOW || action == OverlayService.ACTION_TOGGLE
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (isShow) startForegroundService(i) else startService(i)
            } else {
                startService(i)
            }
        } catch (_: Exception) { /* evita crash em OEMs mais restritos */ }
    }

    private val reqForegroundPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { maybeStartService(); proceedIfReady() }

    private val reqNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { proceedIfReady() }

    private val reqCamMicPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val grantedCamera = results[Manifest.permission.CAMERA] == true
        val grantedMic = results[Manifest.permission.RECORD_AUDIO] == true
        pendingWebPermission?.let { req ->
            val needsVideo = req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            val needsAudio = req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            val okVideo = !needsVideo || grantedCamera
            val okAudio = !needsAudio || grantedMic
            if (okVideo && okAudio) req.grant(req.resources) else req.deny()
            pendingWebPermission = null
        }
    }

    private fun ensureForegroundLocationOnce() {
        if (!hasFineOrCoarse()) {
            reqForegroundPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun ensureCamMicOnce() {
        val need = mutableListOf<String>()
        if (!hasCamera()) need += Manifest.permission.CAMERA
        if (!hasMic())    need += Manifest.permission.RECORD_AUDIO
        if (need.isNotEmpty()) reqCamMicPerms.launch(need.toTypedArray())
    }

    private fun ensureNotificationPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission()) {
            reqNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun normalizedBase(): String {
        val base = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
        return base.trimEnd('/') + "/"
    }

    private fun originMatchesBase(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return false
        val base = normalizedBase().removeSuffix("/")
        return try {
            val o = Uri.parse(origin)
            val b = Uri.parse(base)
            val oHost = (o.host ?: "").lowercase()
            val bHost = (b.host ?: "").lowercase()
            val oScheme = (o.scheme ?: "https").lowercase()
            val bScheme = (b.scheme ?: "https").lowercase()
            (oHost == bHost) && (oScheme == bScheme)
        } catch (_: Exception) { false }
    }

    private fun isOffline(): Boolean = isOfflineShown

    private fun saveWebCookie(cookie: String?) {
        if (!cookie.isNullOrBlank()) {
            uiPrefs.edit().putString("web_cookie", cookie).apply()
        }
    }

    private fun flushCookies() {
        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
    }

    private fun maybeStartService() {
        if (serviceStarted) return
        if (!pageReady) return
        if (!hasFineOrCoarse()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission()) {
            ensureNotificationPermIfNeeded(); return
        }
        try {
            ForegroundLocationService.start(this)
            serviceStarted = true
        } catch (_: Throwable) {
            ui.postDelayed({
                try { ForegroundLocationService.start(this); serviceStarted = true } catch (_: Throwable) {}
            }, 1000L)
        }
    }

    // ===== WebView (sem Service Worker) =====
    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebViewIfNeeded() {
        if (this::webView.isInitialized && webView.url != null) return

        webView = WebView(this)
        setContentView(webView)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setGeolocationEnabled(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.mediaPlaybackRequiresUserGesture = false
        s.userAgentString = s.userAgentString + " ProDestinoWebView/1.0"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Bridge JS → Android
        webView.addJavascriptInterface(WebBridge(this), "Android")

        val base = normalizedBase()

        webView.webViewClient = object : WebViewClient() {
            private fun showOfflineScreen() {
                if (isOfflineShown) return
                isOfflineShown = true
                try { webView.loadUrl("file:///android_asset/offline.html") }
                catch (_: Throwable) { webView.loadDataWithBaseURL(base, OFFLINE_FALLBACK_HTML, "text/html", "UTF-8", null) }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase() ?: return false

                if (scheme in listOf("intent","whatsapp","tel","mailto","market")) {
                    try {
                        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Throwable) {}
                    } catch (_: Throwable) {}
                    return true
                }

                if (scheme == "http" || scheme == "https") {
                    val host = uri.host ?: return false
                    if (host.endsWith(".")) {
                        val fixed = Uri.Builder()
                            .scheme(uri.scheme ?: "https")
                            .encodedAuthority(host.trimEnd('.'))
                            .encodedPath(uri.encodedPath)
                            .encodedQuery(uri.encodedQuery)
                            .build().toString()
                        view.loadUrl(fixed)
                        return true
                    }
                    return false
                }
                return false
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) { vibrate(); showOfflineScreen() }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) { vibrate(); showOfflineScreen() }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                vibrate(); handler.cancel(); showOfflineScreen()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val target = (lastGoodUrl ?: base)
                safeRecreateWebView(target)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val isGood = url.startsWith(base)
                if (isGood) {
                    pageReady = true
                    isOfflineShown = false
                    lastGoodUrl = url
                    try { saveWebCookie(CookieManager.getInstance().getCookie(base)) } catch (_: Exception) {}
                    maybeStartService()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                val allow = hasFineOrCoarse() && originMatchesBase(origin)
                callback?.invoke(origin, allow, false)
                if (!allow && !hasFineOrCoarse()) ensureForegroundLocationOnce()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val originOk = originMatchesBase(request.origin?.toString())
                val resources = request.resources ?: emptyArray()
                val needsVideo = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val needsAudio = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!originOk) { request.deny(); return }
                val haveEverything = (!needsVideo || hasCamera()) && (!needsAudio || hasMic())
                if (haveEverything) { request.grant(resources); return }
                pendingWebPermission = request
                val need = mutableListOf<String>()
                if (needsVideo && !hasCamera()) need += Manifest.permission.CAMERA
                if (needsAudio && !hasMic())    need += Manifest.permission.RECORD_AUDIO
                if (need.isNotEmpty()) reqCamMicPerms.launch(need.toTypedArray()) else { request.deny(); pendingWebPermission = null }
            }

            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*","application/pdf"))
                }
                val chooser = Intent.createChooser(intent, "Selecionar arquivo")
                fileChooserLauncher.launch(chooser)
                return true
            }
        }

        val startUrl = lastGoodUrl ?: base
        webView.loadUrl(startUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun safeRecreateWebView(targetUrl: String) {
        try {
            if (this::webView.isInitialized) {
                try { (webView.parent as? android.view.ViewGroup)?.removeView(webView) } catch (_: Exception) {}
                try { webView.destroy() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        webView = WebView(this)
        setContentView(webView)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setGeolocationEnabled(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.mediaPlaybackRequiresUserGesture = false
        s.userAgentString = s.userAgentString + " ProDestinoWebView/1.0"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Bridge JS → Android
        webView.addJavascriptInterface(WebBridge(this), "Android")

        initWebViewClientsForRecreatedInstance()

        val base = normalizedBase()
        isOfflineShown = false
        webView.loadUrl(if (targetUrl.isNotBlank()) targetUrl else base)
    }

    private fun initWebViewClientsForRecreatedInstance() {
        val base = normalizedBase()

        webView.webViewClient = object : WebViewClient() {
            private fun showOfflineLocal() {
                if (isOfflineShown) return
                isOfflineShown = true
                try { webView.loadUrl("file:///android_asset/offline.html") }
                catch (_: Throwable) { webView.loadDataWithBaseURL(base, OFFLINE_FALLBACK_HTML, "text/html", "UTF-8", null) }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme in listOf("intent","whatsapp","tel","mailto","market")) {
                    try {
                        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Throwable) {}
                    } catch (_: Throwable) {}
                    return true
                }
                if (scheme == "http" || scheme == "https") {
                    val host = uri.host ?: return false
                    if (host.endsWith(".")) {
                        val fixed = Uri.Builder()
                            .scheme(uri.scheme ?: "https")
                            .encodedAuthority(host.trimEnd('.'))
                            .encodedPath(uri.encodedPath)
                            .encodedQuery(uri.encodedQuery)
                            .build().toString()
                        view.loadUrl(fixed)
                        return true
                    }
                    return false
                }
                return false
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) { vibrate(); showOfflineLocal() }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) { vibrate(); showOfflineLocal() }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                vibrate(); handler.cancel(); showOfflineLocal()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val target = (lastGoodUrl ?: base)
                safeRecreateWebView(target)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val isGood = url.startsWith(base)
                if (isGood) {
                    pageReady = true
                    isOfflineShown = false
                    lastGoodUrl = url
                    try { saveWebCookie(CookieManager.getInstance().getCookie(base)) } catch (_: Exception) {}
                    maybeStartService()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                val allow = hasFineOrCoarse() && originMatchesBase(origin)
                callback?.invoke(origin, allow, false)
                if (!allow && !hasFineOrCoarse()) ensureForegroundLocationOnce()
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                val originOk = originMatchesBase(request.origin?.toString())
                val resources = request.resources ?: emptyArray()
                val needsVideo = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val needsAudio = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!originOk) { request.deny(); return }
                val haveEverything = (!needsVideo || hasCamera()) && (!needsAudio || hasMic())
                if (haveEverything) { request.grant(resources); return }
                pendingWebPermission = request
                val need = mutableListOf<String>()
                if (needsVideo && !hasCamera()) need += Manifest.permission.CAMERA
                if (needsAudio && !hasMic())    need += Manifest.permission.RECORD_AUDIO
                if (need.isNotEmpty()) reqCamMicPerms.launch(need.toTypedArray()) else { request.deny(); pendingWebPermission = null }
            }
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*","application/pdf"))
                }
                val chooser = Intent.createChooser(intent, "Selecionar arquivo")
                fileChooserLauncher.launch(chooser)
                return true
            }
        }
    }

    // ===== Lifecycle =====
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bloqueia screenshots/previews
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        initWebViewIfNeeded()

        ensureForegroundLocationOnce()
        ensureCamMicOnce()
        ensureNotificationPermIfNeeded()
        askIgnoreBatteryOptimizations()

        // Listener de rede para recuperar da offline.html
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            netCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isOffline()) {
                        ui.postDelayed({ recoverFromOfflineOnce() }, 500L)
                    }
                }
            }
            cm.registerDefaultNetworkCallback(netCb!!)
        } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        proceedIfReady()

        // App visível → esconde a bolha
        startOverlayService(OverlayService.ACTION_HIDE)

        // Se a permissão foi revogada, também desligamos a preferência
        if (!hasOverlayPermissionInternal() && isBubbleEnabled()) {
            setBubbleEnabled(false)
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onStop() {
        super.onStop()
        flushCookies()
        // App em 2º plano → mostra a bolha somente se habilitada e com permissão
        if (isBubbleEnabled() && hasOverlayPermissionInternal()) {
            startOverlayService(OverlayService.ACTION_SHOW)
        } else {
            startOverlayService(OverlayService.ACTION_HIDE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            netCb?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // ===== Helpers =====
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { c ->
                c.hide(android.view.WindowInsets.Type.navigationBars())
                c.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun askIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val pkg = packageName
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$pkg")
                }
                startActivity(i)
            }
        } catch (_: Exception) { }
    }

    /** Prossegue quando: app visível + localização OK + (13+) notificação OK */
    private fun proceedIfReady() {
        if (!resumed) return
        if (!hasFineOrCoarse()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission()) {
            ensureNotificationPermIfNeeded(); return
        }
        if (!bootCompletedOnce) {
            bootCompletedOnce = true
            ui.postDelayed({ maybeStartService() }, 250L)
        }
    }

    private fun showOfflineInline() {
        if (!this::webView.isInitialized) return
        if (isOfflineShown) return
        isOfflineShown = true
        val base = normalizedBase()
        try { webView.loadUrl("file:///android_asset/offline.html") }
        catch (_: Throwable) { webView.loadDataWithBaseURL(base, OFFLINE_FALLBACK_HTML, "text/html", "UTF-8", null) }
    }

    private fun recoverFromOfflineOnce() {
        if (!isOffline()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoverAt < 6000L) return
        lastRecoverAt = now
        val target = (lastGoodUrl ?: normalizedBase())
        webView.post {
            try {
                isOfflineShown = false
                webView.loadUrl(target)
            } catch (_: Throwable) {
                safeRecreateWebView(target)
            }
        }
    }
}
