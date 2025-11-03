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

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* sem UI extra */ }

    private fun ensurePerms() {
        val wants = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        // Background só existe a partir do Android 10 (Q)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wants.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        val need = wants.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need) requestPerms.launch(wants.toTypedArray())
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        ensurePerms()
        askIgnoreBatteryOptimizations()

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
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                val allow = origin?.startsWith("https://manaus.prodestino.com") == true
                callback?.invoke(origin, allow, false)
            }
        }

        val startUrl = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
        webView.loadUrl(startUrl)

        // Inicia o serviço de rastreamento quando o app abre
        ForegroundLocationService.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Opcional: manter rodando mesmo ao fechar a Activity (não para o serviço).
        // Se quiser parar quando fechar o app, descomente:
        // ForegroundLocationService.stop(this)
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
        } catch (_: Exception) { /* ignore */ }
    }
}
