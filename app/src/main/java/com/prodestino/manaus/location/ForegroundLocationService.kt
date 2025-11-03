package com.prodestino.manaus.location

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.os.Looper
// CookieManager não é mais obrigatório, mas deixo como fallback opcional
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
 * - Envia para a sua API REST usando o mesmo cookie (PHPSESSID) do WebView
 * - Continua rodando com a tela apagada/minimizado
 * - Iniciado no app e (opcionalmente) após reboot via BootReceiver
 */
class ForegroundLocationService : Service() {

    companion object {
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTI_ID = 1001
        private const val TAG = "FGLocationService"

        /** Inicia o serviço (lida com diferenças de versão) */
        fun start(ctx: Context) {
            val i = Intent(ctx, ForegroundLocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        /** Para o serviço */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ForegroundLocationService::class.java))
        }
    }

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var req: LocationRequest
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()

        // 1) Canal + notificação fixa (obrigatório para foreground service de localização)
        createChannel()
        startForeground(NOTI_ID, buildNotification("Rastreamento ativo"))

        // 2) Configuração do fornecedor de localização (intervalos ajustáveis)
        fused = LocationServices.getFusedLocationProviderClient(this)
        req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L) // intervalo alvo: 15s
            .setMinUpdateIntervalMillis(10_000L)   // mínimo entre updates
            .setWaitForAccurateLocation(true)      // tenta melhorar a precisão quando possível
            .setMaxUpdateDelayMillis(30_000L)      // coalescing para economia (até 30s)
            .build()

        // 3) Callback para cada atualização de localização
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    // Monta payload JSON conforme contrato da sua API
                    val body = JSONObject().apply {
                        put("latitude",  loc.latitude)
                        put("longitude", loc.longitude)
                        put("velocidade", if (loc.hasSpeed())  loc.speed.toDouble() else JSONObject.NULL)
                        put("rumo",      if (loc.hasBearing()) Math.round(loc.bearing) else JSONObject.NULL)
                    }
                    // Envia como MOTORISTA (ajuste se quiser também passageiro)
                    sendToApi("/api/v1/motoristas/localizacoes.php", body)
                    // Para enviar também do passageiro, descomente a linha abaixo:
                    // sendToApi("/api/v1/passageiros/localizacoes.php", body)
                }
            }
        }

        // 4) Inicia a escuta das atualizações
        tryStartUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Se o sistema recriar o serviço, garante que os updates continuem
        tryStartUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove updates para evitar vazamento
        try { callback?.let { fused.removeLocationUpdates(it) } } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Solicita updates ao FusedLocation com tratamento de exceções (permissões) */
    private fun tryStartUpdates() {
        try {
            fused.requestLocationUpdates(req, callback as LocationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissões de localização ausentes: ${e.message}")
            // A Activity (MainActivity) é quem pede os grants. Aqui seguimos vivos, mas sem updates.
        } catch (e: Exception) {
            Log.e(TAG, "Erro requestLocationUpdates: ${e.message}")
        }
    }

    /** Cria o NotificationChannel para Android 8+ */
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

    /** Constrói a notificação persistente do serviço */
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

    /**
     * Envia o JSON para a API usando o cookie da sessão do site (PHPSESSID).
     * 1) Tenta pegar o cookie salvo no SharedPreferences ("app_prefs"."web_cookie")
     * 2) Se não existir, faz fallback para o CookieManager (pode falhar em bg)
     */
    private fun sendToApi(path: String, json: JSONObject) {
        Thread {
            try {
                // Base da URL (vem do BuildConfig ou cai no domínio padrão)
                val base = BuildConfig.BASE_URL.ifBlank { "https://manaus.prodestino.com/" }
                val url = URL(base.trimEnd('/') + path)

                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 12_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")

                    // === Cabeçalho Cookie (PHPSESSID) ===
                    // 1) Primeiro tenta o cookie persistido pelo MainActivity (SharedPreferences)
                    var cookieToSend: String? = null
                    try {
                        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                        val saved = prefs.getString("web_cookie", null)
                        if (!saved.isNullOrBlank()) {
                            cookieToSend = saved
                        }
                    } catch (_: Exception) { /* ignore */ }

                    // 2) Fallback: tenta o CookieManager (pode não funcionar em background)
                    if (cookieToSend.isNullOrBlank()) {
                        try {
                            val cm = CookieManager.getInstance()
                            cookieToSend = cm.getCookie(base.trimEnd('/'))
                        } catch (_: Exception) { /* ignore */ }
                    }

                    // Se achou algum cookie, adiciona o header
                    if (!cookieToSend.isNullOrBlank()) {
                        setRequestProperty("Cookie", cookieToSend)
                    }
                }

                // Corpo JSON
                BufferedOutputStream(conn.outputStream).use { os ->
                    os.write(json.toString().toByteArray(Charsets.UTF_8))
                }

                // Resposta
                val code = conn.responseCode
                if (code !in 200..299) {
                    // Log útil para depurar: 401 geralmente é sessão inválida/expirada
                    Log.w(TAG, "API ${path} HTTP $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao enviar localização: ${e.message}")
            }
        }.start()
    }
}
