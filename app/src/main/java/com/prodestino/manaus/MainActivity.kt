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

    private lateinit var webView: WebView

    // Estado
    private var bootCompletedOnce = false
    private var resumed = false
    private var pageReady = false
    private var serviceStarted = false

    // Conectividade
    private var netCb: ConnectivityManager.NetworkCallback? = null

    // Anti navegação duplicada na saída do offline
    private var lastRecoverAt = 0L

    // Última URL boa (para não “perder sessão” voltando sempre à raiz)
    private var lastGoodUrl: String?
        get() = getSharedPreferences("app_prefs", MODE_PRIVATE).getString("last_good_url", null)
        set(v) { getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putString("last_good_url", v).apply() }

    private val ui = Handler(Looper.getMainLooper())

    // ===== Vibração =====
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

    // ===== Permissões =====
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
        maybeStartService(); proceedIfReady()
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

    /** Só prossegue quando: em primeiro plano + localização OK + (13+) notificação OK */
    private fun proceedIfReady() {
        if (!resumed) return
        if (!hasFineOrCoarse()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission()) {
            ensureNotificationPermIfNeeded(); return
        }
        if (!bootCompletedOnce) {
            bootCompletedOnce = true
            ui.postDelayed({
                initWebViewIfNeeded()
                maybeStartService()
            }, 350L)
        }
    }

    // ===== Cookies =====
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

    /** Dispara serviço se (e só se) a página real já carregou e permissões OK */
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

    private fun isOfflineAsset(): Boolean =
        this::webView.isInitialized && (webView.url ?: "").startsWith("file:///android_asset/")

    /** BASE_URL normalizada com barra final */
    private fun normalizedBase(): String {
        val base = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
        return base.trimEnd('/') + "/"
    }

    /** Sair do offline apenas 1x por janela (evita colisões com múltiplos sinais de rede) */
    private fun recoverFromOfflineOnce() {
        if (!isOfflineAsset()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoverAt < 3000L) return
        lastRecoverAt = now

        val target = (lastGoodUrl ?: normalizedBase())
        webView.post {
            try {
                webView.loadUrl(target)
            } catch (_: Throwable) {
                // Se o renderer estiver caído, reconstroi a WebView e tenta de novo
                safeRecreateWebView(target)
            }
        }
    }

    // ===== WebView bootstrap =====
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

        // Cookies
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val base = normalizedBase()

        webView.webViewClient = object : WebViewClient() {

            private fun showOffline() {
                if (webView.url?.startsWith("file:///android_asset/") == true) return
                webView.loadUrl("file:///android_asset/offline.html")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase() ?: return false

                // intents externas
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

                // http/https normal; corrige host com ponto final
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
                    showOffline()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    vibrate()
                    showOffline()
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                vibrate()
                handler.cancel()
                showOffline()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // O renderer caiu (acontece muito quando volta a rede). Não derruba o app.
                // Recria WebView com segurança e volta para a última página boa (ou base).
                val target = (lastGoodUrl ?: base)
                safeRecreateWebView(target)
                return true // indicamos que tratamos o crash
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                try {
                    val cookie = CookieManager.getInstance().getCookie(base)
                    saveWebCookie(cookie)
                } catch (_: Exception) {}

                // Marca página boa e grava URL boa se for do domínio base
                val isGood = url.startsWith(base)
                pageReady = isGood
                if (isGood) {
                    lastGoodUrl = url
                }
                maybeStartService()
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

    // Recria a WebView de forma segura após crash do renderer
    @SuppressLint("SetJavaScriptEnabled")
    private fun safeRecreateWebView(targetUrl: String) {
        try {
            // Remove a antiga com segurança
            if (this::webView.isInitialized) {
                try { (webView.parent as? android.view.ViewGroup)?.removeView(webView) } catch (_: Exception) {}
                try { webView.destroy() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Recria do zero e reaplica as mesmas configs/clients
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

        // Vai para a última boa (ou base)
        val base = normalizedBase()
        webView.loadUrl(if (targetUrl.isNotBlank()) targetUrl else base)
    }

    // Extrai a parte dos clients para reaplicar na recriação
    private fun initWebViewClientsForRecreatedInstance() {
        val base = normalizedBase()

        webView.webViewClient = object : WebViewClient() {
            private fun showOffline() {
                if (webView.url?.startsWith("file:///android_asset/") == true) return
                webView.loadUrl("file:///android_asset/offline.html")
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
                    vibrate(); showOffline()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    vibrate(); showOffline()
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                vibrate(); handler.cancel(); showOffline()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val target = (lastGoodUrl ?: base)
                safeRecreateWebView(target)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                try {
                    val cookie = CookieManager.getInstance().getCookie(base)
                    saveWebCookie(cookie)
                } catch (_: Exception) {}
                val isGood = url.startsWith(base)
                pageReady = isGood
                if (isGood) lastGoodUrl = url
                maybeStartService()
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

    // ===== Ciclo de vida =====
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Proteção contra captura de tela e previews
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
                    // Avise a WebView que a rede está OK (reduz glitches internos do motor)
                    if (this@MainActivity::webView.isInitialized) {
                        webView.setNetworkAvailable(true)
                    }
                    if (isOfflineAsset()) {
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
        } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        proceedIfReady()
        // IMPORTANTE: não force navegação aqui (evita colisão com onAvailable)
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
}
