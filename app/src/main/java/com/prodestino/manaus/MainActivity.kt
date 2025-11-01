// app/src/main/java/com/prodestino/manaus/MainActivity.kt
package com.prodestino.manaus

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    private val allowedHosts = setOf(
        Uri.parse(BuildConfig.BASE_URL).host,  // sidaf.online
        "www.sidaf.online"
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔒 Anti-print: bloqueia screenshot e gravação de tela (app inteiro)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 🧱 Imersivo: esconde status bar + barra de navegação (voltam por gesto)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowInsetsControllerCompat(window, binding.root)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val wv = binding.webview
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (BuildConfig.WEBVIEW_DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val h = request.url.host ?: return false
                // abre dentro do app se for o mesmo domínio; externos vão para o navegador
                return if (allowedHosts.contains(h)) {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
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
                fileCallback?.onReceiveValue(emptyArray()) // limpa qualquer callback anterior
                fileCallback = filePathCallback

                // Intent para escolher arquivo da galeria
                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                }

                // Intent para câmera (foto)
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

        // pede permissões Android (uma vez)
        askPerms.launch(arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))

        // carrega o site
        wv.loadUrl(BuildConfig.BASE_URL)
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

    // Mantém as barras ocultas quando o app volta ao foco (imersivo)
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
