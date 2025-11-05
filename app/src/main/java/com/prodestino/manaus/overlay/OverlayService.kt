package com.prodestino.manaus.overlay

import android.app.*
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
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.prodestino.manaus.R

/**
 * Serviço mínimo de sobreposição (bolha).
 * Seguro: não faz nada se a permissão de overlay não estiver concedida.
 * Sobe em foreground para evitar crashes no Android 8+.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.prodestino.manaus.overlay.SHOW"
        const val ACTION_HIDE = "com.prodestino.manaus.overlay.HIDE"

        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTI_ID = 2001
    }

    private var wm: WindowManager? = null
    private var bubbleView: View? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val noti = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name) // use um ícone existente do seu projeto
            .setContentTitle("Atalho em execução")
            .setContentText("Toque para retornar ao app.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTI_ID, noti)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showBubbleIfAllowed()
            ACTION_HIDE -> hideBubble()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideBubble()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "ProDestino – Sobreposição",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun showBubbleIfAllowed() {
        if (!hasOverlayPermission()) return
        if (bubbleView != null) return

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Um pequeno ponto/bolha (layout mínimo)
        val frame = FrameLayout(this).apply {
            // toque retorna ao app (experiência básica)
            setOnClickListener {
                val i = packageManager.getLaunchIntentForPackage(packageName)
                i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(i)
            }
            // aparência simples (círculo)
            setBackgroundResource(android.R.drawable.presence_online)
            alpha = 0.9f
        }

        val size = (48f * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 120
        }

        wm?.addView(frame, params)
        bubbleView = frame
    }

    private fun hideBubble() {
        val w = wm
        val v = bubbleView
        if (w != null && v != null) {
            try { w.removeView(v) } catch (_: Throwable) {}
        }
        bubbleView = null
    }
}
