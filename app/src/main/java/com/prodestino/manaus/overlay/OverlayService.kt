package com.prodestino.manaus.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.prodestino.manaus.MainActivity
import com.prodestino.manaus.R

class OverlayService : Service() {

    companion object {
        // Ações públicas — usadas pelo MainActivity
        const val ACTION_SHOW   = "com.prodestino.manaus.overlay.SHOW"
        const val ACTION_HIDE   = "com.prodestino.manaus.overlay.HIDE"
        const val ACTION_TOGGLE = "com.prodestino.manaus.overlay.TOGGLE"

        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIF_ID = 1001

        /** Helper opcional para iniciar com ação */
        fun start(ctx: Context, action: String) {
            val i = Intent(ctx, OverlayService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private var wm: WindowManager? = null
    private var bubbleView: View? = null
    private var isShown = false

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannelIfNeeded()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showBubble()
            ACTION_HIDE -> hideBubble()
            ACTION_TOGGLE -> if (isShown) hideBubble() else showBubble()
            else -> { /* no-op */ }
        }
        // Mantém o serviço vivo enquanto em FG
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideBubble()
        super.onDestroy()
    }

    // ================= Bubble =================

    private fun showBubble() {
        if (isShown) return
        if (bubbleView == null) {
            // Se você já tiver um layout customizado, troque por R.layout.sua_bolha
            bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble_default, null, false)
            // Clique abre o app
            bubbleView?.setOnClickListener {
                val i = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(i)
            }
            // Clique longo oculta
            bubbleView?.setOnLongClickListener {
                hideBubble()
                true
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 120
        }

        try {
            wm?.addView(bubbleView, lp)
            isShown = true
        } catch (_: Throwable) {
            // Se não tiver permissão de sobreposição, só mantém o serviço em FG
        }
    }

    private fun hideBubble() {
        if (!isShown) return
        try {
            wm?.removeView(bubbleView)
        } catch (_: Throwable) { /* ignore */ }
        isShown = false
    }

    // ================= Notificação FG =================

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "ProDestino — Bolha flutuante",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Mantém o serviço de sobreposição ativo"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_overlay) // <= ícone que vamos criar
            .setContentTitle("ProDestino ativo")
            .setContentText("Toque para abrir o app. Mantenha pressionado na bolha para ocultar.")
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
