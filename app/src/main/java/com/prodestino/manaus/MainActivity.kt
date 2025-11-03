package com.prodestino.manaus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    // ===== Launchers =====
    private val reqForegroundPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Após a resposta de localização (FINE/COARSE), verificamos notificações e só então iniciamos o serviço.
        ensureNotificationPermThenStartService()
    }

    private val reqNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ForegroundLocationService.start(this)
        // Se negar, seguimos sem serviço em 2º plano; o WebView ainda funciona em 1º plano.
    }

    // ===== Utils de permissão =====
    private fun hasFineOrCoarse(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun ensureForegroundLocationOnce() {
        // Pede SOMENTE FINE/COARSE. NÃO pedimos BACKGROUND aqui (evita loop/crash em OEMs).
        if (!hasFineOrCoarse()) {
            reqForegroundPerms.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            // Já tem localização em 1º plano; segue para checar notificação e iniciar serviço.
            ensureNotificationPermThenStartService()
        }
    }

    private fun ensureNotificationPermThenStartService() {
        // Android 13+ exige POST_NOTIFICATIONS para o ForegroundService mostrar notificação
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val has = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!has) {
                reqNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        // Só inicia o serviço se já tiver pelo menos FINE/COARSE (senão o serviço falha e o app insiste em pedir)
        if (hasFineOrCoarse()) {
            ForegroundLocationService.start(this)
        }
    }

    // ===== Cookie do WebView → SharedPreferences (para o serviço usar a sessão) =====
    private fun saveWebCookie(cookie: String?) {
        if (!cookie.isNullOrBlank()) {
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putString("web_cookie", cookie)
                .apply()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // 1) Primeiro pedimos SOMENTE localização em 1º plano; depois, notificações; só então iniciamos o serviço.
        ensureForegroundLocationOnce()

        // 2) Sugerimos ignorar otimização de bateria (não é permissão, abre tela de sistema)
        askIgnoreBatteryOptimizations()

        // 3) Configura WebView
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
                } catch (_: Exception) {}
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, cb: GeolocationPermissions.Callback?) {
                val allow = origin?.startsWith("https://manaus.prodestino.com") == true
                cb?.invoke(origin, allow, false)
            }
        }

        val startUrl = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
        webView.loadUrl(startUrl)
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
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
