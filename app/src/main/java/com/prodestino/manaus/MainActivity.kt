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

    // Evita executar duas vezes a sequência de “tudo pronto”
    private var bootCompletedOnce = false

    // ========= Helpers de permissão =========
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

    // ========= Launchers =========
    private val reqForegroundPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Quando o usuário responde à permissão de localização (Fine/Coarse), seguimos o fluxo
        proceedIfReady()
    }

    private val reqNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Após resposta de notificação, seguimos o fluxo
        proceedIfReady()
    }

    // Pede SOMENTE localização em 1º plano (evita loop/ANR em vários OEMs)
    private fun ensureForegroundLocation() {
        if (!hasFineOrCoarse()) {
            reqForegroundPerms.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    // Android 13+: pedir notificação só quando já tiver localização OK
    private fun ensureNotificationPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission()) {
            reqNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Chamada central: só avança quando (1) localização OK e (2) notificação OK (13+)
    private fun proceedIfReady() {
        if (!hasFineOrCoarse()) {
            // Ainda sem localização — não avança
            return
        }
        if (!hasNotifPermission()) {
            // Pede notificação e aguarda callback
            ensureNotificationPermIfNeeded()
            return
        }
        // Tudo pronto uma única vez
        if (!bootCompletedOnce) {
            bootCompletedOnce = true
            initWebViewAndStartService()
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // 1) Primeiro: pedir apenas localização em 1º plano
        ensureForegroundLocation()
        // 2) Sugerir ignorar otimização de bateria (não é permissão)
        askIgnoreBatteryOptimizations()
        // 3) Caso o usuário já tenha concedido antes, continuar o fluxo
        proceedIfReady()
    }

    /**
     * Só é chamado quando já temos:
     *  - Localização em 1º plano OK
     *  - (Android 13+) Notificação OK
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebViewAndStartService() {
        // Configura WebView
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setGeolocationEnabled(true) // necessário pro navigator.geolocation
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
                // Persiste cookie da sessão para o serviço
                try {
                    val base = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }.trimEnd('/')
                    val cookie = CookieManager.getInstance().getCookie(base)
                    saveWebCookie(cookie)
                } catch (_: Exception) { }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) {
                // Só autoriza o WebView se o app JÁ tem permissão do Android.
                val allow = hasFineOrCoarse() && origin?.startsWith("https://manaus.prodestino.com") == true
                callback?.invoke(origin, allow, false)
                // Se o usuário revogar a permissão em runtime, tenta pedir de novo
                if (!allow) ensureForegroundLocation()
            }
        }

        val startUrl = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
        webView.loadUrl(startUrl)

        // Inicia serviço DEPOIS que tudo está ok; com try/catch para OEMs chatos
        try {
            ForegroundLocationService.start(this)
        } catch (_: Exception) { /* em caso extremo, o WebView já coleta em 1º plano */ }

        // Dica: a permissão “Permitir o tempo todo” (BACKGROUND) pode ser oferecida depois,
        // via uma tela do app apontando para Configurações, sem travar o fluxo inicial.
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
        } catch (_: Exception) { }
    }
}
