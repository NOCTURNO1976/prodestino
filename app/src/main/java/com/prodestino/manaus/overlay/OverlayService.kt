package com.prodestino.manaus.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import androidx.core.app.NotificationCompat
import com.prodestino.manaus.R

class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.prodestino.manaus.overlay.SHOW"
        const val ACTION_HIDE = "com.prodestino.manaus.overlay.HIDE"
        const val ACTION_TOGGLE = "com.prodestino.manaus.overlay.TOGGLE" // <— adicionado

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
            ACTION_TOGGLE -> if (isShowing) hide() else show()  // <— trata toggle
            else -> show()
        }
        return START_STICKY
    }

    private fun show() {
        if (isShowing) return
        startForeground(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bolha ativa")
                .setContentText("Toque para abrir o app")
                .setSmallIcon(R.mipmap.ic_launcher) // <— corrige ícone inexistente
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        )

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)

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
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 24; params.y = 180

        wm?.addView(overlayView, params)
        isShowing = true
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
                    CHANNEL_ID, "Overlay",
                    NotificationManager.IMPORTANCE_MIN
                )
                ch.setShowBadge(false)
                mgr.createNotificationChannel(ch)
            }
        }
    }
}
