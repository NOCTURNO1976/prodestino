package com.prodestino.manaus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.prodestino.manaus.location.ForegroundLocationService

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Flags de estado para evitar loops
    private var bootCompletedOnce = false
    private var resumed = false

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
        // Depois que o usuário responde, tentamos avançar
        proceedIfReady()
    }

    private val reqNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Depois que o usuário responde, tentamos avançar
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
     *  - activity em primeiro plano (resumed == true),
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
            // Pequeno atraso impede crashes em alguns OEMs (serviço iniciado cedo demais)
            ui.postDelayed({
                initWebViewIfNeeded()
                startTrackingServiceSafely()
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

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
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

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                try {
                    val base = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }.trimEnd('/')
                    val cookie = CookieManager.getInstance().getCookie(base)
                    saveWebCookie(cookie)
                } catch (_: Exception) { /* ignore */ }
            }
        }

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

    private fun startTrackingServiceSafely() {
        try {
            // IMPORTANTE: só iniciar com a Activity em foreground e após o pequeno delay
            ForegroundLocationService.start(this)
        } catch (_: Throwable) {
            // Falha em OEM? Tenta de novo depois de 1s.
            ui.postDelayed({
                try { ForegroundLocationService.start(this) } catch (_: Throwable) {}
            }, 1000L)
        }
    }

    // ===== Ciclo de vida =====
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Não carrega a WebView ainda; primeiro resolvemos as permissões
        ensureForegroundLocationOnce()
        askIgnoreBatteryOptimizations()
        // Se o usuário já tinha concedido antes, pode seguir assim que o onResume sinalizar foreground
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        proceedIfReady()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
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
        } catch (_: Exception) { /* ignore */ }
    }
}
