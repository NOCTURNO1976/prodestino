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

    // Flag do estado de offline (como agora não usamos file://, controlamos por flag)
    private var isOfflineShown = false

    // Última URL boa para voltar exatamente onde estava
    private var lastGoodUrl: String?
        get() = getSharedPreferences("app_prefs", MODE_PRIVATE).getString("last_good_url", null)
        set(v) { getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putString("last_good_url", v).apply() }

    private val ui = Handler(Looper.getMainLooper())

    // HTML offline inerte (sem JS), não expõe rota e mantém mesma origem via baseURL
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

    /** BASE_URL normalizada com barra final (evita redirect extra e melhora sessão no 1º request) */
    private fun normalizedBase(): String {
        val base = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }
        return base.trimEnd('/') + "/"
    }

    /** Está em offline? (usamos flag, já que não mudamos de origem mais) */
    private fun isOffline(): Boolean = isOfflineShown

    /** Mostra off**
