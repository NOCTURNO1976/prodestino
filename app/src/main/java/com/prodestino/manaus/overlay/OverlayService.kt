package com.prodestino.manaus.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.prodestino.manaus.MainActivity
import com.prodestino.manaus.R

class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.prodestino.manaus.overlay.SHOW"
        const val ACTION_HIDE = "com.prodestino.manaus.overlay.HIDE"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIF_ID = 4201
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle("Atalho flutuante ativo")
                .setContentText("Toque no botão para voltar ao Pro Destino")
                .setOngoing(true)
                .build()
        )
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showBubble()
            ACTION_HIDE -> hideBubble()
            else -> { /* noop */ }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubble() {
        if (bubbleView != null) return

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null)

        // Parâmetros da janela (overlay)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
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
            y = 200
        }

        val iv = bubbleView!!.findViewById<ImageView>(R.id.bubbleIcon)

        // Arrastar
        bubbleView!!.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params!!.x
                        initialY = params!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params!!.x = initialX - (event.rawX - initialTouchX).toInt()
                        params!!.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(bubbleView, params)
                        return true
                    }
                }
                return false
            }
        })

        // Clique → trazer app pra frente e ocultar a bolha
        iv.setOnClickListener {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                         Intent.FLAG_ACTIVITY_SINGLE_TOP or
                         Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(i)
            hideBubble()
        }

        windowManager?.addView(bubbleView, params)
    }

    private fun hideBubble() {
        bubbleView?.let {
            windowManager?.removeView(it)
        }
        bubbleView = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Atalho Flutuante",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Serviço do botão flutuante" }
                )
            }
        }
    }
}
