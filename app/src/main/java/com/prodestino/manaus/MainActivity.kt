package com.prodestino.manaus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
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

    // Serviço só inicia quando a página real estiver pronta
    private var pageReady = false
    private var serviceStarted = false

    private val ui = Handler(Looper.getMainLooper())

    // ===== Helpers de permissão =====
    private fun hasFineOrCoarse(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasNotifPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    // ===== Launchers =====
    private val reqForegroundPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Depois que o usuário responde, apenas reavaliamos condições
        maybeStartService()
        proceedIfReady()
    }

    private val reqNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        proceedIfReady()
    }

    // Pede SOMENTE localização foreground (evita travas)
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

    // Pede POST_NOTIFICATIONS apenas quando já temos localização OK
    private fun ensureNotificationPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission()) {
            reqNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Centro do fluxo: só avança quando:
     *  - activity em primeiro plano,
     *  - localização foreground OK,
     *  - (Android 13+) notificação OK.
     */
    private fun proceedIfReady() {
        if (!resumed) return
        if (!hasFineOrCoarse()) return
        if (!hasNotifPermission()) {
            ensureNotificationPermIfNeeded()
            return
        }

        if (!bootCompletedOnce) {
            bootCompletedOnce = true
            // Pequeno atraso impede crashes em alguns OEMs
            ui.postDelayed({
                initWebViewIfNeeded()
                // NÃO iniciamos o serviço aqui — quem dispara é maybeStartService()
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

    // Dispara o serviço se (e só se) a página real já carregou e as permissões exigidas existem
    private fun maybeStartService() {
        if (serviceStarted) return
        if (!pageReady) return
        if (!hasFineOrCoarse()) return
        // No Android 13+, garanta permissão de notificação ANTES do serviço
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission()) {
            ensureNotificationPermIfNeeded()
            return
        }
        try {
            ForegroundLocationService.start(this)
            serviceStarted = true
        } catch (_: Throwable) {
            // Se algum OEM bloquear, tenta 1s depois
            ui.postDelayed({
                try { ForegroundLocationService.start(this); serviceStarted = true } catch (_: Throwable) {}
            }, 1000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebViewIfNeeded() {
        if (this::webView.isInitialized && webView.url != null) return

        webView = WebView(this)
        setContentView(webView)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setGeolocationEnabled(true) // necessário para navigator.geolocation
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.mediaPlaybackRequiresUserGesture = false
        s.userAgentString = s.userAgentString + " ProDestinoWebView/1.0"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // ==== BLOCO COM TRATAMENTO DE ERROS (offline/404/SSL) ====
        webView.webViewClient = object : WebViewClient() {

            private fun showOffline() {
                // Evita loop se já estamos na página local
                if (webView.url?.startsWith("file:///android_asset/") == true) return
                webView.loadUrl("file:///android_asset/offline.html")
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val host = uri.host ?: return false
                // Corrige hosts terminando com ponto
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

            // HTTP 4xx/5xx (apenas no frame principal)
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    showOffline()
                }
            }

            // Falhas de rede (sem internet, timeout, DNS…)
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) showOffline()
            }

            // Erros SSL (cert inválido, data/hora errada…)
            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.cancel()
                showOffline()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                // Captura cookie da base para o serviço
                try {
                    val base = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }.trimEnd('/')
                    val cookie = CookieManager.getInstance().getCookie(base)
                    saveWebCookie(cookie)
                } catch (_: Exception) { /* ignore */ }

                // Considera "pronto" somente quando for sua URL HTTPS (não os assets locais)
                pageReady = url.startsWith("https://manaus.prodestino.com")
                maybeStartService()
            }
        }
        // ==== FIM DO BLOCO DE TRATAMENTO DE ERROS ====

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) {
                // Só autoriza se o app já tem FINE/COARSE e o origin é o seu domínio
                val allow = hasFineOrCoarse() && origin?.startsWith("https://manaus.prodestino.com") == true
                callback?.invoke(origin, allow, false)
                if (!allow && !hasFineOrCoarse()) {
                    ensureForegroundLocationOnce()
                }
            }
        }

        val startUrl = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
        webView.loadUrl(startUrl)
    }

    // ===== Ciclo de vida =====
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Primeiro as permissões; o WebView vem depois
        ensureForegroundLocationOnce()
        askIgnoreBatteryOptimizations()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        proceedIfReady()

        // Se estivermos na tela offline local e a activity voltou, tente recarregar o site
        if (this::webView.isInitialized) {
            val u = webView.url ?: ""
            if (u.startsWith("file:///android_asset/")) {
                val startUrl = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
                webView.post { webView.loadUrl(startUrl) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    // Esconde barra de navegação (imersivo)
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
                (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    // Pede para ignorar otimização de bateria (ajuda no background)
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
        } catch (_: Exception) { /* ignore */ }
    }
}
