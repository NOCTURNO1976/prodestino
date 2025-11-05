package com.prodestino.manaus.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.prodestino.manaus.MainActivity
import com.prodestino.manaus.R
import kotlin.math.abs

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
            else -> show()
        }
        return START_STICKY
    }

    private fun show() {
        if (isShowing) return

        // Foreground para estabilidade
        startForeground(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bolha ativa")
                .setContentText("Toque para abrir o app")
                .setSmallIcon(R.mipmap.ic_launcher) // usa o launcher existente
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        )

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_bubble, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // NOT_FOCUSABLE permite interação e não rouba o foco de teclado
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 180
        }

        // Clique: traz o app pra frente
        overlayView?.setOnClickListener {
            try {
                val i = Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                startActivity(i)
            } catch (_: Exception) { }
        }

        // Arrastar: segurar e mover a bolha
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        moved = false
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            moved = true
                        }
                        if (moved) {
                            params.x = startX - dx
                            params.y = startY + dy
                            wm?.updateViewLayout(overlayView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // se não moveu, deixa o clique do setOnClickListener acontecer
                        return moved // true consume (evita clique), false deixa o click rolar
                    }
                }
                return false
            }
        })

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
                    CHANNEL_ID,
                    "Overlay",
                    NotificationManager.IMPORTANCE_MIN
                )
                ch.setShowBadge(false)
                mgr.createNotificationChannel(ch)
            }
        }
    }
}
