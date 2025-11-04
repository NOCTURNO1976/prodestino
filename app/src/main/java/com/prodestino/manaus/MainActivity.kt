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
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
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

    // =======================
    // HTML offline inline (sem JS) — mantém a MESMA ORIGEM via baseURL
    // =======================
    private val OFFLINE_HTML: String by lazy {
        """
        <!doctype html>
        <html lang="pt-br">
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Sem conexão</title>
        <meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate, max-age=0">
        <meta http-equiv="Pragma" content="no-cache">
        <meta http-equiv="Expires" content="0">
        <meta http-equiv="Content-Security-Policy"
              content="default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:;
                       navigate-to 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'">
        <style>
          html,body{height:100%}
          body{font-family:system-ui,Arial,sans-serif;margin:0;display:grid;place-items:center;background:#f7f7f7}
          .card{max-width:460px;margin:24px;padding:24px;text-align:center;border-radius:16px;box-shadow:0 8px 30px rgba(0,0,0,.08);background:#fff}
          h1{font-size:18px;margin:0 0 8px}
          p{color:#555;margin:0}
        </style>
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
        } catch (_: Exception) { /* silencioso */ }
    }

    private fun hasFineOrCoarse(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasNotifPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private val reqForegroundPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        maybeStartService()
        proceedIfReady()
    }

    private val reqNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        proceedIfReady()
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

    private fun ensureNotificationPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission()) {
            reqNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun normalizedBase(): String {
        val base = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
        return base.trimEnd('/') + "/"
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
            ensureNotificationPermIfNeeded()
            return
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
    // WebView (offline inline + clients)
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

        val base = normalizedBase()

        webView.webViewClient = object : WebViewClient() {

            private fun showOfflineInline() {
                if (isOfflineShown) return
                isOfflineShown = true
                webView.loadDataWithBaseURL(
                    base,
                    OFFLINE_HTML,
                    "text/html",
                    "UTF-8",
                    null
                )
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
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    vibrate()
                    showOfflineInline()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    vibrate()
                    showOfflineInline()
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                vibrate()
                handler.cancel()
                showOfflineInline()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val target = (lastGoodUrl ?: base)
                safeRecreateWebView(target)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                // Só marca como "página boa" se for do domínio base
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
                val allow = hasFineOrCoarse() && origin?.startsWith(base) == true
                callback?.invoke(origin, allow, false)
                if (!allow && !hasFineOrCoarse()) ensureForegroundLocationOnce()
            }
        }

        val startUrl = lastGoodUrl ?: base
        webView.loadUrl(startUrl)
    }

    private fun showOfflineInline() {
        // helper externo para outros pontos (erros fora do client)
        val base = normalizedBase()
        if (!this::webView.isInitialized) return
        if (isOfflineShown) return
        isOfflineShown = true
        webView.loadDataWithBaseURL(base, OFFLINE_HTML, "text/html", "UTF-8", null)
    }

    private fun recoverFromOfflineOnce() {
        if (!isOffline()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoverAt < 3000L) return
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

        // Reaplica clients
        initWebViewClientsForRecreatedInstance()

        val base = normalizedBase()
        isOfflineShown = false
        webView.loadUrl(if (targetUrl.isNotBlank()) targetUrl else base)
    }

    private fun initWebViewClientsForRecreatedInstance() {
        val base = normalizedBase()

        webView.webViewClient = object : WebViewClient() {

            private fun showOfflineInlineLocal() {
                if (isOfflineShown) return
                isOfflineShown = true
                webView.loadDataWithBaseURL(base, OFFLINE_HTML, "text/html", "UTF-8", null)
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
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    vibrate()
                    showOfflineInlineLocal()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    vibrate()
                    showOfflineInlineLocal()
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                vibrate()
                handler.cancel()
                showOfflineInlineLocal()
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
                val allow = hasFineOrCoarse() && origin?.startsWith(base) == true
                callback?.invoke(origin, allow, false)
                if (!allow && !hasFineOrCoarse()) ensureForegroundLocationOnce()
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

        ensureForegroundLocationOnce()
        askIgnoreBatteryOptimizations()

        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            netCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (this@MainActivity::webView.isInitialized) {
                        webView.setNetworkAvailable(true)
                    }
                    if (isOffline()) {
                        vibrate(30)
                        recoverFromOfflineOnce()
                    }
                }
                override fun onLost(network: Network) {
                    if (this@MainActivity::webView.isInitialized) {
                        webView.setNetworkAvailable(false)
                    }
                }
            }
            cm.registerDefaultNetworkCallback(netCb!!)
        } catch (_: Exception) { /* OEMs que personalizam CM */ }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        proceedIfReady()
        // sem navegação aqui — evitamos colisão com onAvailable()
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
            ensureNotificationPermIfNeeded()
            return
        }
        if (!bootCompletedOnce) {
            bootCompletedOnce = true
            ui.postDelayed({
                initWebViewIfNeeded()
                maybeStartService()
            }, 350L)
        }
    }
}
