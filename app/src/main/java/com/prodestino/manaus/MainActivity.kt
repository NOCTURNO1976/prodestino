// app/src/main/java/com/prodestino/manaus/MainActivity.kt
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
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.prodestino.manaus.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== Imports do WorkManager (vigilante silencioso) =====
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    private val allowedHosts = setOfNotNull(
        Uri.parse(BuildConfig.BASE_URL).host,
        "manaus.prodestino.com"
    )

    // Apenas CÂMERA/ÁUDIO aqui. Localização fica no fluxo 2-passos.
    private val askPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ok */ }

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
    private val homeUrl by lazy { BuildConfig.BASE_URL }

    // ====== Orquestrador de permissões (localização/notificação) ======
    private val reqFineCoarseLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineOk = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseOk = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineOk || coarseOk) {
            requestBackgroundIfNeeded()
            requestPostNotificationsIfNeeded()
            maybeStartLocationService()
        }
    }

    private val reqBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Inicia serviço mesmo que ainda não tenha “Sempre permitir”.
        maybeStartLocationService()
    }

    private val reqNotifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* opcional */ }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                reqNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestBackgroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                reqBackgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    private fun requestFineCoarseIfNeeded() {
        val needFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
        val needCoarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED

        if (needFine || needCoarse) {
            reqFineCoarseLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            // Já tem “enquanto em uso”
            requestBackgroundIfNeeded()
            requestPostNotificationsIfNeeded()
            maybeStartLocationService()
        }
    }

    private fun maybeStartLocationService() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            val it = Intent(this, com.prodestino.manaus.bg.LocationForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it)
            } else {
                startService(it)
            }
        }
    }
    // ====== Fim orquestrador ======

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Anti-print
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ======= AQUI ENTRA O AGENDAMENTO (WorkManager) =======
        val req = PeriodicWorkRequestBuilder<com.prodestino.manaus.bg.EnsureServiceWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // roda só com rede
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "pd_ensure_service",
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
        // ======= FIM DO AGENDAMENTO =======

        // Imersivo
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
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        if (BuildConfig.WEBVIEW_DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        wv.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val h = request.url.host ?: return false
                return if (allowedHosts.contains(h)) {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    view.loadUrl("file:///android_asset/offline.html")
                    scheduleAutoRetry()
                }
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    view.loadUrl("file:///android_asset/offline.html")
                    scheduleAutoRetry()
                }
            }

            override fun onReceivedSslError(
                view: WebView, handler: SslErrorHandler, error: SslError
            ) {
                handler.cancel()
                view.loadUrl("file:///android_asset/offline.html")
                scheduleAutoRetry()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    val host = request?.origin?.host
                    if (host != null && allowedHosts.contains(host)) {
                        request.grant(request.resources)
                    } else request?.deny()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) {
                val host = origin?.let { Uri.parse(it).host }
                callback?.invoke(origin, host != null && allowedHosts.contains(host), false)
            }

            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(emptyArray())
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

        // Volta automático quando a rede reaparece
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

        // Pede apenas CÂMERA/ÁUDIO aqui (mantém seu fluxo).
        askPerms.launch(arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ))

        // Fluxo 2-passos de localização (uso → background) + notificações
        requestFineCoarseIfNeeded()

        wv.loadUrl(homeUrl)
    }

    private fun scheduleAutoRetry() {
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val insets = WindowInsetsControllerCompat(window, binding.root)
            insets.hide(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        // Reforça que o serviço esteja ativo quando a Activity voltar
        maybeStartLocationService()
    }
}
