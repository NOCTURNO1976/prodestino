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
import com.google.android.gms.location.*
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

        private const val PREFS = "pd_prefs"
        private const val KEY_ROLE = "role"         // "driver" | "passenger" | "unknown"
        private const val KEY_FAILS = "auth_fails"  // contador de 401/403
        private const val ROLE_UNKNOWN = "unknown"
        private const val ROLE_DRIVER = "driver"
        private const val ROLE_PASSENGER = "passenger"
        private const val AUTH_FAIL_LIMIT = 5
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(ch)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        val req = LocationRequest.Builder(15_000L) // 15s
            .setMinUpdateIntervalMillis(5_000L)
            .setMaxUpdateDelayMillis(30_000L)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) handleLocation(loc)
            }
        }

        fused.requestLocationUpdates(req, callback as LocationCallback, mainLooper)
    }

    private fun stopLocationUpdates() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }

    // ======= Autodetect + cache =======
    private fun getRole(): String {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        return sp.getString(KEY_ROLE, ROLE_UNKNOWN) ?: ROLE_UNKNOWN
    }
    private fun setRole(role: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ROLE, role).apply()
        // zera falhas ao fixar papel
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_FAILS, 0).apply()
    }
    private fun incAuthFail() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val cur = sp.getInt(KEY_FAILS, 0) + 1
        sp.edit().putInt(KEY_FAILS, cur).apply()
        if (cur >= AUTH_FAIL_LIMIT) {
            // Perdeu sessão: zera papel para reaprender
            sp.edit().putString(KEY_ROLE, ROLE_UNKNOWN).putInt(KEY_FAILS, 0).apply()
        }
    }
    private fun resetAuthFail() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_FAILS, 0).apply()
    }
    // ================================

    private fun handleLocation(loc: Location) {
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 9999.0
        if (accuracy > 150.0) return // filtro anti-ruído simples

        val body = FormBody.Builder()
            .add("latitude", loc.latitude.toString())
            .add("longitude", loc.longitude.toString())
            .add("timestamp", (System.currentTimeMillis() / 1000L).toString())
            .add("accuracy", accuracy.toString())
            .add("speed", (if (loc.hasSpeed()) loc.speed else 0f).toString())
            .add("bearing", (if (loc.hasBearing()) loc.bearing else 0f).toString())
            .build()

        val role = getRole()
        when (role) {
            ROLE_DRIVER -> postTo(BuildConfig.LOCATION_POST_URL_DRIVER, body, expectHeader = "X-Debug-Motorista")
            ROLE_PASSENGER -> postTo(BuildConfig.LOCATION_POST_URL_PASSENGER, body, expectHeader = "X-Debug-Passageiro")
            else -> autodetectAndPost(body)
        }
    }

    private fun autodetectAndPost(body: FormBody) {
        // 1) Tenta motorista
        val okDriver = postTo(
            BuildConfig.LOCATION_POST_URL_DRIVER,
            body,
            expectHeader = "X-Debug-Motorista",
            tryFixRole = true
        )
        if (okDriver) return

        // 2) Tenta passageiro
        postTo(
            BuildConfig.LOCATION_POST_URL_PASSENGER,
            body,
            expectHeader = "X-Debug-Passageiro",
            tryFixRole = true
        )
    }

    /**
     * Faz o POST para url; se expectHeader estiver presente e >0, considera reconhecido.
     * Se tryFixRole=true e reconhecido, fixa o papel correspondente.
     *
     * Retorna true se houve sucesso e (quando aplicável) reconhecimento do papel.
     */
    private fun postTo(
        url: String,
        body: FormBody,
        expectHeader: String,
        tryFixRole: Boolean = false
    ): Boolean {
        val cookieHeader = getSessionCookieHeader(url)
        val req = Request.Builder()
            .url(url)
            .post(body)
            .apply { if (cookieHeader != null) header("Cookie", cookieHeader) }
            .header("Accept", "application/json")
            .build()

        // duas tentativas com backoff
        var attempt = 0
        var backoffMs = 500L
        while (attempt <= 2) {
            try {
                http.newCall(req).execute().use { resp ->
                    val code = resp.code
                    if (code == 401 || code == 403) {
                        incAuthFail(); return false
                    }
                    if (resp.isSuccessful) {
                        resetAuthFail()
                        val hdr = resp.header(expectHeader)?.trim()
                        val hdrOk = hdr?.toIntOrNull()?.let { it > 0 } ?: false
                        if (tryFixRole && hdrOk) {
                            if (expectHeader == "X-Debug-Motorista") setRole(ROLE_DRIVER)
                            if (expectHeader == "X-Debug-Passageiro") setRole(ROLE_PASSENGER)
                        }
                        // Sucesso (mesmo que header não venha, consideramos entregue)
                        return true
                    }
                }
            } catch (_: Throwable) {
                // ignora; fará retry
            }
            attempt++
            if (attempt <= 2) {
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) {}
                backoffMs = max(backoffMs * 3 / 2, 800L)
            }
        }
        return false
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
