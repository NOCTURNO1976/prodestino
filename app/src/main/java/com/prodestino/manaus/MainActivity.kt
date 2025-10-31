package com.prodestino.manaus

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webview: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        webview = findViewById(R.id.webview)

        // Configura WebView
        with(webview.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // Se for usar HTTP em dev, depois ativamos networkSecurityConfig no Manifest.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // Mantém navegação dentro do WebView
                return false
            }
        }

        webview.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // Concede geolocalização ao WebView (já temos permissões no Manifest)
                callback?.invoke(origin, true, false)
            }

            // Camera/microfone via WebRTC: permissões já no Manifest; o Android 12+ pedirá runtime no navegador.
            // Para upload/chooser avançado, podemos adicionar onShowFileChooser depois.
        }

        // URL inicial
        if (savedInstanceState == null) {
            webview.loadUrl("https://manaus.prodestino.com/")
        }
    }

    override fun onBackPressed() {
        if (this::webview.isInitialized && webview.canGoBack()) {
            webview.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::webview.isInitialized) webview.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (this::webview.isInitialized) webview.restoreState(savedInstanceState)
    }
}
