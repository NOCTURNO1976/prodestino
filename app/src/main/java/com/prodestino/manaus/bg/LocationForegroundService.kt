// app/src/main/java/com/prodestino/manaus/bg/LocationForegroundService.kt
package com.prodestino.manaus.bg

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.prodestino.manaus.BuildConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.max

class LocationForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "pd_location_channel"
        const val CHANNEL_NAME = "Rastreamento em andamento"
        const val NOTIF_ID = 1001
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pro Destino")
            .setContentText("Rastreamento de localização ativo")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            )
            nm?.createNotificationChannel(ch)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        // Intervalos equilibrados: ajuste conforme sua necessidade real
        val req: LocationRequest = LocationRequest.Builder(15_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setMaxUpdateDelayMillis(30_000L)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) sendLocation(loc)
            }
        }

        fused.requestLocationUpdates(req, callback as LocationCallback, mainLooper)
    }

    private fun stopLocationUpdates() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }

    private fun sendLocation(loc: Location) {
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 9999.0
        if (accuracy > 150.0) return  // filtro anti-ruído simples

        // === Payload com os MESMOS campos que seu backend já usa ===
        // Ajuste os nomes abaixo se seu endpoint espera chaves diferentes.
        val body = FormBody.Builder()
            .add("latitude", loc.latitude.toString())
            .add("longitude", loc.longitude.toString())
            .add("timestamp", (System.currentTimeMillis() / 1000L).toString()) // epoch (s) — ajuste se necessário
            // opcionais (se o backend aceitar, mantém; senão, remova)
            .add("accuracy", accuracy.toString())
            .add("speed", (if (loc.hasSpeed()) loc.speed else 0f).toString())        // m/s
            .add("bearing", (if (loc.hasBearing()) loc.bearing else 0f).toString())  // graus
            .build()

        val cookieHeader = getSessionCookieHeader(BuildConfig.LOCATION_POST_URL)

        val req = Request.Builder()
            .url(BuildConfig.LOCATION_POST_URL)
            .post(body)
            .apply { if (cookieHeader != null) header("Cookie", cookieHeader) }
            .header("Accept", "application/json")
            .build()

        sendWithRetry(req, 2)
    }

    private fun sendWithRetry(request: Request, retries: Int) {
        var attempt = 0
        var backoffMs = 500L
        while (attempt <= retries) {
            try {
                http.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) return
                }
            } catch (_: Throwable) { /* retry */ }
            attempt++
            if (attempt <= retries) {
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) {}
                backoffMs = max(backoffMs * 3 / 2, 800L)
            }
        }
        // Após retries, mantém serviço vivo e segue — sem travar
    }

    private fun getSessionCookieHeader(postUrl: String): String? {
        return try {
            val uri = Uri.parse(postUrl)
            val domain = "${uri.scheme}://${uri.host}"
            CookieManager.getInstance().getCookie(domain)
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("unused")
    private fun getBatteryPct(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) level else -1
        } else -1
    }
}
