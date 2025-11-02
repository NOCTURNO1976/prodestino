package com.prodestino.manaus.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class LocationForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "pd_location_channel"
        const val CHANNEL_NAME = "Rastreamento em andamento"
        const val NOTIF_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        // Notificação simples; depois você pode personalizar texto/ícone
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pro Destino")
            .setContentText("Rastreamento de localização ativo")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
        // IMPORTANTE: ainda não iniciamos coleta de localização aqui.
        // Isso evita mexer no seu código atual. Depois plugamos o FusedLocationProvider.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Mantém o serviço ativo. Quando formos integrar a coleta, faremos aqui.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(ch)
        }
    }
}
