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
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.ServiceWorkerWebSettings
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
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    // =======================
    // Campos de estado
    // =======================
    private lateinit var webView: WebView
    private var bootCompletedOnce = false
    private var resumed = false
    private var pageReady = false
    private var serviceStarted = false

    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastRecoverAt = 0L
    private var isOfflineShown = false

    private val ui = Handler(Looper.getMainLooper())

    private var lastGoodUrl: String?
        get() = getSharedPreferences("app_prefs", MODE_PRIVATE).getString("last_good_url", null)
        set(v) { getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putString("last_good_url", v).apply() }

    // Pedido pendente de getUserMedia vindo do WebView
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

    // =======================
    // HTML offline (fallback apenas)
    // =======================
    private val OFFLINE_FALLBACK_HTML: String by lazy {
        """
        <!doctype html>
        <html lang="pt-br">
        <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sem conexão</title>
        <style>html,body{height:100%}body{font-family:system-ui,Arial,sans-serif;margin:0;display:grid;place-items:center;background:#f7f7f7}
        .card{max-width:460px;margin:24px;padding:24px;text-align:center;border-radius:16px;box-shadow:0 8px 30px rgba(0,0,0,.08);background:#fff}
        h1{font-size:18px;margin:0 0 8px}p{color:#555;margin:0}</style>
        <div class="card" role="status" aria-live="polite">
          <h1>Sem conexão</h1>
          <p>Volte ao app quando a internet estiver disponível.</p>
        </div>
        </html>
        """.trimIndent()
    }

    // =======================
    // Utilitários
    // =======================
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

    private fun hasNotifPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    // Launchers de permissão
    private val reqForegroundPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        maybeStartService()
        proceedIfReady()
    }

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
        if (!hasMic()) need += Manifest.permission.RECORD_AUDIO
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
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putString("web_cookie", cookie)
                .apply()
        }
    }

    private fun flushCookies() {
        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
    }

    // =======================
    // Serviço (só quando página real estiver pronta)
    // =======================
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

    // =======================
    // WebView
    // =======================
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

        // Bloqueio de Service Worker
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                val swc = ServiceWorkerController.getInstance()
                val sws: ServiceWorkerWebSettings = swc.serviceWorkerWebSettings
                sws.setAllowContentAccess(false)
                sws.setAllowFileAccess(false)
                sws.setBlockNetworkLoads(true)
                swc.setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                })
            }
        } catch (_: Throwable) { }

        val base = normalizedBase()

        webView.webViewClient = object : WebViewClient() {

            private fun showOfflineScreen() {
                if (isOfflineShown) return
                isOfflineShown = true
                // Carrega o arquivo físico de assets
                try {
                    webView.loadUrl("file:///android_asset/offline.html")
                } catch (_: Throwable) {
                    // Fallback absoluto se o arquivo não existir
                    webView.loadDataWithBaseURL(base, OFFLINE_FALLBACK_HTML, "text/html", "UTF-8", null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase() ?: return false

                if (scheme in listOf("intent", "whatsapp", "tel", "mailto", "market")) {
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
                            .build()
                            .toString()
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
                    try {
                        val cookie = CookieManager.getInstance().getCookie(base)
                        saveWebCookie(cookie)
                    } catch (_: Exception) {}
                    maybeStartService()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            // Geolocalização HTML5
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                val allow = hasFineOrCoarse() && originMatchesBase(origin)
                callback?.invoke(origin, allow, false)
                if (!allow && !hasFineOrCoarse()) ensureForegroundLocationOnce()
            }

            // getUserMedia (câmera/microfone)
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
                if (needsAudio && !hasMic()) need += Manifest.permission.RECORD_AUDIO
                if (need.isNotEmpty()) reqCamMicPerms.launch(need.toTypedArray()) else { request.deny(); pendingWebPermission = null }
            }

            // File chooser para <input type="file">
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
                }
                val chooser = Intent.createChooser(intent, "Selecionar arquivo")
                fileChooserLauncher.launch(chooser)
                return true
            }
        }

        val startUrl = lastGoodUrl ?: base
        webView.loadUrl(startUrl)
    }

    private fun showOfflineInline() {
        // Mantido como ponte para chamadas existentes: delega ao assets
        if (!this::webView.isInitialized) return
        if (isOfflineShown) return
        isOfflineShown = true
        try {
            webView.loadUrl("file:///android_asset/offline.html")
        } catch (_: Throwable) {
            val base = normalizedBase()
            webView.loadDataWithBaseURL(base, OFFLINE_FALLBACK_HTML, "text/html", "UTF-8", null)
        }
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

        // Reaplica bloqueio do Service Worker
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                val swc = ServiceWorkerController.getInstance()
                val sws: ServiceWorkerWebSettings = swc.serviceWorkerWebSettings
                sws.setAllowContentAccess(false)
                sws.setAllowFileAccess(false)
                sws.setBlockNetworkLoads(true)
                swc.setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                })
            }
        } catch (_: Throwable) { }

        initWebViewClientsForRecreatedInstance()

        val base = normalizedBase()
        isOfflineShown = false
        webView.loadUrl(if (targetUrl.isNotBlank()) targetUrl else base)
    }

    // === ÚNICA definição (mantida) ===
    private fun initWebViewClientsForRecreatedInstance() {
        val base = normalizedBase()

        webView.webViewClient = object : WebViewClient() {
            private fun showOfflineLocal() {
                if (isOfflineShown) return
                isOfflineShown = true
                try {
                    webView.loadUrl("file:///android_asset/offline.html")
                } catch (_: Throwable) {
                    webView.loadDataWithBaseURL(base, OFFLINE_FALLBACK_HTML, "text/html", "UTF-8", null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme in listOf("intent", "whatsapp", "tel", "mailto", "market")) {
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
                            .build()
                            .toString()
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
                    try {
                        val cookie = CookieManager.getInstance().getCookie(base)
                        saveWebCookie(cookie)
                    } catch (_: Exception) {}
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
                if (needsAudio && !hasMic()) need += Manifest.permission.RECORD_AUDIO
                if (need.isNotEmpty()) reqCamMicPerms.launch(need.toTypedArray()) else { request.deny(); pendingWebPermission = null }
            }
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
                }
                val chooser = Intent.createChooser(intent, "Selecionar arquivo")
                fileChooserLauncher.launch(chooser)
                return true
            }
        }
    }

    // =======================
    // Lifecycle
    // =======================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Protege contra prints/previews
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // Inicializa WebView já no boot (independente das permissões)
        initWebViewIfNeeded()

        // Pede permissões conforme necessidade
        ensureForegroundLocationOnce()
        ensureCamMicOnce()
        ensureNotificationPermIfNeeded()

        askIgnoreBatteryOptimizations()

        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            netCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isOffline()) {
                        vibrate(30)
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
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onStop() {
        super.onStop()
        flushCookies()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            netCb?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    // =======================
    // Outros helpers
    // =======================
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

    /** Prossegue quando: em primeiro plano + localização OK + (13+) notificação OK */
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
}
