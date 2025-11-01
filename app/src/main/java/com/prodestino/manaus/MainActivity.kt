// app/src/main/java/com/prodestino/manaus/MainActivity.kt
package com.prodestino.manaus

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.prodestino.manaus.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    // Domínios que o WebView pode manter "dentro do app"
    private val allowedHosts = setOfNotNull(
        Uri.parse(BuildConfig.BASE_URL).host,       // ex.: manaus.prodestino.com
        "manaus.prodestino.com"
    )

    private val askPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* resultado não bloqueia o fluxo do WebView */ }

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val results: Array<Uri>? = when {
            result.resultCode != RESULT_OK -> null
            data == null && cameraUri != null -> arrayOf(cameraUri!!)
            data?.data != null -> arrayOf(data.data!!)
            data?.clipData != null -> {
                val count = data.clipData!!.itemCount
                Array(count) { i -> data.clipData!!.getItemAt(i).uri }
            }
            else -> null
        }
        fileCallback?.onReceiveValue(results ?: emptyArray())
        fileCallback = null
        cameraUri = null
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val homeUrl by lazy { BuildConfig.BASE_URL } // mantenha BASE_URL com seu path inicial

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔒 Anti-print: bloqueia screenshot e gravação de tela
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 🧱 Imersivo
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowInsetsControllerCompat(window, binding.root)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val wv = binding.webview
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        // Cookies de sessão (PHP)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val h = request.url.host ?: return false
                return if (allowedHosts.contains(h)) {
                    // abre dentro do app
                    false
                } else {
                    // externos → navegador
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                }
            }

            // Intercepta erros genéricos (DNS/timeout/aborted/etc.)
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    view.loadUrl("file:///android_asset/offline.html")
                    scheduleAutoRetry()
                }
            }

            // Intercepta HTTP 4xx/5xx
            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    view.loadUrl("file:///android_asset/offline.html")
                    scheduleAutoRetry()
                }
            }

            // Intercepta problemas de SSL sem expor a URL
            override fun onReceivedSslError(
                view: WebView, handler: SslErrorHandler, error: SslError
            ) {
                handler.cancel()
                view.loadUrl("file:///android_asset/offline.html")
                scheduleAutoRetry()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            // Permissão de câmera/microfone (getUserMedia) — só para o domínio permitido
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    val host = request?.origin?.host
                    if (host != null && allowedHosts.contains(host)) {
                        request.grant(request.resources)
                    } else {
                        request?.deny()
                    }
                }
            }

            // Geolocalização — autoriza só se vier do domínio permitido
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) {
                val host = origin?.let { Uri.parse(it).host }
                callback?.invoke(origin, host != null && allowedHosts.contains(host), false)
            }

            // <input type="file">
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(emptyArray()) // limpa callback anterior
                fileCallback = filePathCallback

                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                }

                val photoFile = createTempImageFile()
                cameraUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    photoFile
                )
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Selecionar arquivo")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                }

                return try {
                    pickFile.launch(chooser); true
                } catch (_: ActivityNotFoundException) {
                    fileCallback?.onReceiveValue(emptyArray()); fileCallback = null
                    false
                }
            }
        }

        // Observa rede para tentar voltar automaticamente da tela offline
        val cmgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cmgr.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (wv.url?.startsWith("file:///android_asset/offline.html") == true) {
                        wv.loadUrl(homeUrl)
                    }
                }
            }
        })

        // Pede permissões Android (uma vez)
        askPerms.launch(arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))

        // Carrega a home
        wv.loadUrl(homeUrl)
    }

    private fun scheduleAutoRetry() {
        // pequeno debounce para não “martelar” a cada erro
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                binding.webview.loadUrl(homeUrl)
            }
        }, 3500L)
    }

    private fun createTempImageFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(cacheDir, "camera_cache").apply { mkdirs() }
        return File.createTempFile("JPEG_${ts}_", ".jpg", dir)
    }

    override fun onBackPressed() {
        val wv = binding.webview
        if (wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }

    // Mantém imersivo ao voltar foco
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val insets = WindowInsetsControllerCompat(window, binding.root)
            insets.hide(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
