package com.prodestino.manaus

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
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
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
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

    // Flags de estado
    private var bootCompletedOnce = false
    private var resumed = false
    private var pageReady = false
    private var serviceStarted = false

    // Callback de conectividade (pra sair do offline.html assim que a rede voltar)
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private val ui = Handler(Looper.getMainLooper())

    // ===== Helpers de permissão =====
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

    // ===== Launchers =====
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

    /** Prossegue somente quando: em primeiro plano + localização OK + (Android 13+) notificação OK */
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
                // Serviço só inicia quando a página real marcar pageReady
                maybeStartService()
            }, 350L)
        }
    }

    // ===== Cookie do WebView → SharedPreferences =====
    private fun saveWebCookie(cookie: String?) {
        if (!cookie.isNullOrBlank()) {
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putString("web_cookie", cookie)
                .apply()
        }
    }

    /** Dispara serviço se (e só se) a página real já carregou e permissões OK */
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

    private fun isOfflineAsset(): Boolean =
        this::webView.isInitialized && (webView.url ?: "").startsWith("file:///android_asset/")

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

        val base = (BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }).trimEnd('/')

        // ==== Client com tratamento de erros e intents externas ====
        webView.webViewClient = object : WebViewClient() {

            private fun showOffline() {
                if (webView.url?.startsWith("file:///android_asset/") == true) return
                webView.loadUrl("file:///android_asset/offline.html")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase() ?: return false

                // 1) Intent URLs: intent://, whatsapp://, market://, tel:, mailto:, etc.
                if (scheme in listOf("intent", "whatsapp", "tel", "mailto", "market")) {
                    try {
                        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        // Fallback genérico
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Throwable) {}
                    } catch (_: Throwable) {}
                    return true
                }

                // 2) http/https normal — deixa seguir; corrige host com ponto final
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
                if (request.isForMainFrame && errorResponse.statusCode >= 400) showOffline()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) showOffline()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showOffline()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Captura cookie da base para o serviço
                try {
                    val cookie = CookieManager.getInstance().getCookie(base)
                    saveWebCookie(cookie)
                } catch (_: Exception) {}

                // Considera "pronto" somente quando for sua URL HTTPS (não assets locais)
                pageReady = url.startsWith(base)
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

        val startUrl = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
        webView.loadUrl(startUrl)
    }

    // ===== Ciclo de vida =====
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Proteção contra print/grav. de tela (e esconde miniatura nos apps recentes)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        ensureForegroundLocationOnce()     // primeiro as permissões
        askIgnoreBatteryOptimizations()    // ajuda a manter no BG

        // Listener de rede: se estiver em offline.html e a internet voltar, recarrega o site
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            netCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isOfflineAsset()) {
                        val startUrl = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
                        webView.post { webView.loadUrl(startUrl) }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(netCb!!)
        } catch (_: Exception) { /* ignora em OEMs que personalizam CM */ }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        proceedIfReady()

        // Se já está no offline.html e o usuário voltou para o app, tenta recarregar
        if (isOfflineAsset()) {
            val startUrl = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
            webView.post { webView.loadUrl(startUrl) }
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onDestroy() {
        super.onDestroy()
        // Desregistrar callback de rede
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

