package com.prodestino.manaus.location

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.os.Looper
import android.webkit.CookieManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.prodestino.manaus.BuildConfig
import com.prodestino.manaus.MainActivity
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ForegroundLocationService : Service() {

    companion object {
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTI_ID = 1001
        private const val TAG = "FGLocationService"

        fun start(ctx: Context) {
            val i = Intent(ctx, ForegroundLocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ForegroundLocationService::class.java))
        }
    }

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var req: LocationRequest
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNotification("Rastreamento ativo"))

        fused = LocationServices.getFusedLocationProviderClient(this)
        req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L) // 15s
            .setMinUpdateIntervalMillis(10_000L)
            .setWaitForAccurateLocation(true)
            .setMaxUpdateDelayMillis(30_000L)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    // Monta JSON
                    val body = JSONObject().apply {
                        put("latitude",  loc.latitude)
                        put("longitude", loc.longitude)
                        put("velocidade", if (loc.hasSpeed()) loc.speed.toDouble() else JSONObject.NULL)
                        put("rumo", if (loc.hasBearing()) Math.round(loc.bearing) else JSONObject.NULL)
                    }
                    // Tenta enviar como MOTORISTA
                    sendToApi("/api/v1/motoristas/localizacoes.php", body)
                    // Se você quiser também passageiro, descomente a linha abaixo:
                    // sendToApi("/api/v1/passageiros/localizacoes.php", body)
                }
            }
        }

        tryStartUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        tryStartUpdates() // garante retomada se o sistema recriar
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { callback?.let { fused.removeLocationUpdates(it) } } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tryStartUpdates() {
        try {
            fused.requestLocationUpdates(req, callback as LocationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissões de localização ausentes: ${e.message}")
            // Sem permissões — service continua, mas sem updates. A Activity deve pedir os grants.
        } catch (e: Exception) {
            Log.e(TAG, "Erro requestLocationUpdates: ${e.message}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Rastreamento",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Localização em tempo real"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pro Destino")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun sendToApi(path: String, json: JSONObject) {
        Thread {
            try {
                val base = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com/" }
                val url = URL(base.trimEnd('/') + path)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 12_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    // Leva o cookie do WebView (PHPSESSID) para manter a sessão do motorista
                    try {
                        val cm = CookieManager.getInstance()
                        val cookie = cm.getCookie(base.trimEnd('/'))
                        if (!cookie.isNullOrBlank()) {
                            setRequestProperty("Cookie", cookie)
                        }
                    } catch (_: Exception) {}
                }
                BufferedOutputStream(conn.outputStream).use { os ->
                    os.write(json.toString().toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "API ${path} HTTP $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao enviar localização: ${e.message}")
            }
        }.start()
    }
}
