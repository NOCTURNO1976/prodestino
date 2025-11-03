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

/**
 * Serviço de localização em 1º plano (ForegroundService).
 * - Coleta posição periodicamente via FusedLocationProviderClient
 * - Envia para a API REST usando o cookie (PHPSESSID) do WebView
 * - Resiliente a falhas de permissão/notification em Android 13/14
 */
class ForegroundLocationService : Service() {

    companion object {
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTI_ID = 1001
        private const val TAG = "FGLocationService"

        fun start(ctx: Context) {
            val i = Intent(ctx, ForegroundLocationService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "startForegroundService falhou: ${t.message}")
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ForegroundLocationService::class.java)) } catch (_: Throwable) {}
        }
    }

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var req: LocationRequest
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()

        // 1) Notificação fixa (obrigatório para foreground de localização)
        createChannel()
        val notification = buildNotification("Rastreamento ativo")
        try {
            // Android 14 pode exigir startForeground logo ao criar
            startForeground(NOTI_ID, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground falhou (notificações/perm.): ${e.message}")
            stopSelf()
            return
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground erro inesperado: ${t.message}")
            stopSelf()
            return
        }

        // 2) Config do provedor de localização
        fused = LocationServices.getFusedLocationProviderClient(this)
        req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L) // alvo 15s
            .setMinUpdateIntervalMillis(10_000L)
            .setWaitForAccurateLocation(true)
            .setMaxUpdateDelayMillis(30_000L)
            .build()

        // 3) Callback de localização
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val body = JSONObject().apply {
                    put("latitude",  loc.latitude)
                    put("longitude", loc.longitude)
                    put("velocidade", if (loc.hasSpeed())  loc.speed.toDouble() else JSONObject.NULL)
                    put("rumo",      if (loc.hasBearing()) Math.round(loc.bearing) else JSONObject.NULL)
                    // timestamps úteis para depurar/conciliar
                    put("provider", loc.provider ?: JSONObject.NULL)
                    put("timestamp", loc.time)
                }
                sendToApi("/api/v1/motoristas/localizacoes.php", body)
                // Para passageiro também, se quiser:
                // sendToApi("/api/v1/passageiros/localizacoes.php", body)
            }
        }

        // 4) Começa a escutar updates (com try/catch robusto)
        tryStartUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Se o sistema recriar, tenta garantir updates
        tryStartUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { callback?.let { fused.removeLocationUpdates(it) } } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tryStartUpdates() {
        try {
            val cb = callback ?: return
            fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Sem permissões de localização — o app continua, só não envia GPS
            Log.e(TAG, "Permissões de localização ausentes: ${e.message}")
        } catch (t: Throwable) {
            Log.e(TAG, "Erro em requestLocationUpdates: ${t.message}")
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
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pro Destino")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Envia JSON para a API com o mesmo cookie do WebView */
    private fun sendToApi(path: String, json: JSONObject) {
        Thread {
            try {
                val base = (BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com/" }).trimEnd('/')
                val url = URL(base + path)

                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 12_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")

                    // Tenta cookie salvo no SharedPreferences (preferido)
                    var cookieToSend: String? = null
                    try {
                        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                        val saved = prefs.getString("web_cookie", null)
                        if (!saved.isNullOrBlank()) cookieToSend = saved
                    } catch (_: Exception) {}

                    // Fallback: CookieManager (pode falhar em bg)
                    if (cookieToSend.isNullOrBlank()) {
                        try {
                            cookieToSend = CookieManager.getInstance().getCookie(base)
                        } catch (_: Exception) {}
                    }

                    if (!cookieToSend.isNullOrBlank()) {
                        setRequestProperty("Cookie", cookieToSend)
                    }
                }

                BufferedOutputStream(conn.outputStream).use { os ->
                    os.write(json.toString().toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "API $path HTTP $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao enviar localização: ${e.message}")
            }
        }.start()
    }
}
