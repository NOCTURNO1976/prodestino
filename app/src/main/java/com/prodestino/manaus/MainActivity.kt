package com.prodestino.manaus

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.prodestino.manaus.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null

    private val askPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* noop */ }

    private val openFiles = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val cb = pendingFilePathCallback
        pendingFilePathCallback = null
        cb?.onReceiveValue(uris?.toTypedArray() ?: emptyArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (BuildConfig.WEBVIEW_DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        askPerms.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ))

        setupWebView()
        binding.webView.loadUrl("https://manaus.prodestino.com/")
    }

    private fun setupWebView() = with(binding.webView) {
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                view.loadUrl(req.url.toString()); return true
            }
        }
        webChromeClient = object : WebChromeClient() {

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                val ok = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
                callback.invoke(origin, ok, false)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val allow = request.resources.all {
                    when (it) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasPerm(Manifest.permission.RECORD_AUDIO)
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasPerm(Manifest.permission.CAMERA)
                        else -> true
                    }
                }
                if (allow) request.grant(request.resources) else request.deny()
