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
        // Depois de lidar com localização, checa/solicita notificação e inicia o serviço
        ensureNotificationPermThenStartService()
    }

    private val reqBackgroundPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* sem UI extra; serviço/WebView tentam novamente depois */
        // Em qualquer caso, garanta que o serviço possa iniciar se as notificações estiverem ok
        ensureNotificationPermThenStartService()
    }

    // --- Launcher para pedir a permissão de NOTIFICAÇÃO (Android 13+) ---
    private val reqNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Se concedido, podemos iniciar o serviço com segurança
        if (granted) {
            ForegroundLocationService.start(this)
        }
        // Se negar, seguimos sem serviço em 2º plano (WebView continua em 1º plano)
    }

    // Pede POST_NOTIFICATIONS em Android 13+ antes de iniciar o serviço
    private fun ensureNotificationPermThenStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val has = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!has) {
                reqNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        // Versões < 13 ou já tem permissão -> inicia
        ForegroundLocationService.start(this)
    }

    // Pede as permissões necessárias (Fine/Coarse e, opcionalmente, Background)
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
            // E garante a partida do serviço (checa notificação)
            ensureNotificationPermThenStartService()
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

    // ===== Helper: salva cookie (PHPSESSID) do WebView no SharedPreferences =====
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

        // Corrige URLs com ponto final no host + captura cookie ao concluir o carregamento
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
                // Pega e persiste os cookies do domínio base (inclui PHPSESSID)
                try {
                    val base = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com" }.trimEnd('/')
                    val cookie = CookieManager.getInstance().getCookie(base)
                    saveWebCookie(cookie)
                } catch (_: Exception) { /* ignore */ }
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
        // OBS: o serviço só é iniciado após as checagens de permissão (ver ensureLocationPerms/ensureNotificationPermThenStartService)
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
