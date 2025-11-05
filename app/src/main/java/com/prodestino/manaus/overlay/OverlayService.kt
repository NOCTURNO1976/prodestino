package com.prodestino.manaus.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.prodestino.manaus.MainActivity
import com.prodestino.manaus.R

class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.prodestino.manaus.overlay.SHOW"
        const val ACTION_HIDE = "com.prodestino.manaus.overlay.HIDE"
        const val ACTION_TOGGLE = "com.prodestino.manaus.overlay.TOGGLE"

        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIF_ID = 1002
    }

    private var wm: WindowManager? = null
    private var overlayView: View? = null
    private var isShowing = false

    override fun onCreate() {
        super.onCreate()
        createNotifChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> show()
            ACTION_HIDE -> hide()
            ACTION_TOGGLE -> if (isShowing) hide() else show()
            else -> { /* no-op: não força FG em ações desconhecidas */ }
        }
        return START_STICKY
    }

    private fun show() {
        if (isShowing) return

        // Sem permissão de sobreposição? Encerra silenciosamente.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        // PendingIntent para abrir o app ao tocar na notificação
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Notificação/FG
        startForeground(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bolha ativa")
                .setContentText("Toque para abrir o app")
                .setSmallIcon(R.mipmap.ic_launcher) // ícone garantido
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(contentIntent)
                .build()
        )

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Tenta inflar o layout; se não existir, usa fallback simples
        overlayView = try {
            LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        } catch (_: Exception) {
            // Fallback: bolha mínima com o ícone do app
            val iv = android.widget.ImageView(this)
            iv.setImageResource(R.mipmap.ic_launcher)
            iv
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 180
        }

        try {
            wm?.addView(overlayView, params)
            isShowing = true
        } catch (_: SecurityException) {
            // OEM barrou mesmo com permissão → encerra limpo
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (_: Exception) {
            // Qualquer outro erro ao adicionar a view
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun hide() {
        if (!isShowing) return
        try { wm?.removeView(overlayView) } catch (_: Exception) {}
        overlayView = null
        isShowing = false
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotifChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Overlay",
                    NotificationManager.IMPORTANCE_MIN
                ).apply { setShowBadge(false) }
                mgr.createNotificationChannel(ch)
            }
        }
    }
}
