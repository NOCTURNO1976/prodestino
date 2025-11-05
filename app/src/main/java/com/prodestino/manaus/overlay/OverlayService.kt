package com.prodestino.manaus.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import androidx.core.app.NotificationCompat
import com.prodestino.manaus.MainActivity
import com.prodestino.manaus.R
import kotlin.math.abs
import kotlin.math.hypot

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
            ACTION_SHOW   -> show()
            ACTION_HIDE   -> hide()
            ACTION_TOGGLE -> if (isShowing) hide() else show()
            else          -> show()
        }
        return START_STICKY
    }

    private fun canDraw(): Boolean =
        if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(this) else true

    private fun show() {
        if (isShowing) return
        if (!canDraw()) { stopSelf(); return }

        // Notificação necessária para serviço em 1º plano
        startForeground(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Atalho rápido")
                .setContentText("Toque na bolha para abrir o app")
                .setSmallIcon(R.mipmap.ic_launcher)
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

        // <<< ÚNICO AJUSTE DE TAMANHO: 56dp x 56dp >>>
        val params = WindowManager.LayoutParams(
            dpToPx(36), // largura fixa da bolha
            dpToPx(36), // altura fixa da bolha
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(16)
            y = dpToPx(160)
        }

        // Clique: traz o app para frente
        overlayView?.setOnClickListener {
            try {
                val i = Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                startActivity(i)
            } catch (_: Exception) {}
        }

        // Arrastar com "segurar e soltar"
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchStartX = 0f
            var touchStartY = 0f

            override fun onTouch(v: View, ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchStartX = ev.rawX
                        touchStartY = ev.rawY
                        return false // permite o clique se não arrastar
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - touchStartX).toInt()
                        val dy = (ev.rawY - touchStartY).toInt()
                        if (abs(dx) > dpToPx(3) || abs(dy) > dpToPx(3)) {
                            params.x = startX - dx // ancorado em RIGHT
                            params.y = startY + dy
                            try { wm?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dist = hypot((ev.rawX - touchStartX), (ev.rawY - touchStartY))
                        return dist > dpToPx(6) // true = tratou como drag (consome)
                    }
                }
                return false
            }
        })

        wm?.addView(overlayView, params)
        isShowing = true
    }

    private fun hide() {
        if (!isShowing) { stopSelf(); return }
        try { wm?.removeView(overlayView) } catch (_: Exception) {}
        overlayView = null
        isShowing = false
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
