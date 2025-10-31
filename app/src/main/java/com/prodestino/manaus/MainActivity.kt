package com.prodestino.manaus

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.*
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webview: WebView

    // File chooser
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private val REQ_PERMS = 1001
    private val REQ_FILE_CHOOSER = 2001

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        webview = findViewById(R.id.webview)

        // === Permissões em tempo de execução (Android 6+) ===
        askRuntimePermissionsIfNeeded()

        // === WebView config ===
        with(webview.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = true
            allowContentAccess = true
        }

        webview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false // mantém a navegação no WebView
            }
        }

        webview.webChromeClient = object : WebChromeClient() {

            // === Geolocalização ===
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // Concede geolocalização ao site (já pedimos ACCESS_FINE_LOCATION no app)
                callback?.invoke(origin, true, false)
            }

            // === WebRTC (câmera/microfone) ===
            override fun onPermissionRequest(request: PermissionRequest?) {
                // O WebView pede cam/mic via WebRTC (getUserMedia). Aqui aceitamos.
                // Você pode validar o origin (request?.origin) para permitir só seu domínio.
                runOnUiThread {
                    try {
                        request?.grant(request.resources)
                    } catch (_: Throwable) {
                        request?.deny()
                    }
                }
            }

            // === <input type="file"> (galeria/câmera) ===
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                // Intent da câmera (gera arquivo temporário via FileProvider)
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                val imageFile = createTempImageFile()
                cameraImageUri = try {
                    FileProvider.getUriForFile(
                        this@MainActivity,
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        imageFile
                    )
                } catch (_: Throwable) { null }

                if (cameraImageUri != null) {
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                    cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    // se não conseguir criar o arquivo, desabilita a opção de câmera
                    cameraIntent.action = Intent.ACTION_PICK
                }

                // Intent de seleção de arquivo (galeria)
                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    // respeita accept mime types se informados pelo site
                    fileChooserParams?.acceptTypes?.let { types ->
                        if (types.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types)
                    }
                }

                val intentArray = arrayOf(cameraIntent)
                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Selecionar arquivo")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
                }

                return try {
                    startActivityForResult(chooser, REQ_FILE_CHOOSER)
                    true
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "Nenhum app de galeria/câmera disponível", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        if (savedInstanceState == null) {
            webview.loadUrl("https://manaus.prodestino.com/")
        }
    }

    private fun askRuntimePermissionsIfNeeded() {
        val notGranted = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), REQ_PERMS)
        }
    }

    private fun createTempImageFile(): File {
        val dir = File(cacheDir, "images").apply { if (!exists()) mkdirs() }
        return File.createTempFile("capture_", ".jpg", dir)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE_CHOOSER) {
            val cb = fileChooserCallback ?: return
            var results: Array<Uri>? = null

            if (resultCode == RESULT_OK) {
                val dataUri = data?.data
                results = when {
                    // retorno da galeria
                    dataUri != null -> arrayOf(dataUri)
                    // retorno da câmera (arquivo salvo no EXTRA_OUTPUT)
                    cameraImageUri != null -> arrayOf(cameraImageUri!!)
                    else -> null
                }
            }
            cb.onReceiveValue(results)
            fileChooserCallback = null
            cameraImageUri = null
        }
    }

    override fun onBackPressed() {
        if (this::webview.isInitialized && webview.canGoBack()) webview.goBack()
        else super.onBackPressed()
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
