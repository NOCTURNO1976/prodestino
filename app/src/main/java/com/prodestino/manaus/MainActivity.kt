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

    // ===== Launchers de permissão =====
    private val reqForegroundPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Se Fine/Coarse ok e Android 10+, podemos pedir Background (opcional)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fineOk = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarseOk = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fineOk || coarseOk) askBackgroundIfNeeded()
        }
    }

    private val reqBackgroundPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* sem UI extra; serviço/WebView tentam novamente depois */ }

    // Pede as permissões necessárias
    private fun ensureLocationPerms() {
        val needsFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
        val needsCoarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED

        if (needsFine || needsCoarse) {
            reqForegroundPerms.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            // Já tem foreground; se quiser background contínuo, pede depois (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) askBackgroundIfNeeded()
        }
    }

    // Pede BACKGROUND apenas após Fine/Coarse e só em Android 10+
    private fun askBackgroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBg = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBg) {
                reqBackgroundPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        // 1) Permissões e bateria
        ensureLocationPerms()
        askIgnoreBatteryOptimizations()

        // 2) Configura WebView
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setGeolocationEnabled(true) // ESSENCIAL para navigator.geolocation
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.mediaPlaybackRequiresUserGesture = false
        s.userAgentString = s.userAgentString + " ProDestinoWebView/1.0"

        // Cookies (PHPSESSID)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Corrige URLs com ponto final no host
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
        }

        // Autoriza geolocalização automaticamente no seu domínio
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) {
                val allow = origin?.startsWith("https://manaus.prodestino.com") == true
                callback?.invoke(origin, allow, false)
            }
        }

        // 3) Carrega sua URL (sem ponto no final)
        val startUrl = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
        webView.loadUrl(startUrl)

        // 4) Inicia serviço de rastreamento (foreground) ao abrir o app
        ForegroundLocationService.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Se quiser parar o serviço quando fechar o app, descomente:
        // ForegroundLocationService.stop(this)
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // Pede para ignorar otimização de bateria (importante para background)
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
