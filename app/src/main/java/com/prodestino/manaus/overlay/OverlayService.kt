package com.prodestino.manaus.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.prodestino.manaus.MainActivity
import com.prodestino.manaus.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.prodestino.manaus.overlay.SHOW"
        const val ACTION_HIDE = "com.prodestino.manaus.overlay.HIDE"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIF_ID = 4201

        // Preferências (posição)
        private const val PREFS = "overlay_prefs"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_HAS_POS = "has_pos"
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
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Atalho flutuante ativo")
                .setContentText("Toque para voltar ao Pro Destino")
                .setOngoing(true)
                .build()
        )
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showBubble()
            ACTION_HIDE -> hideBubble()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubble() {
        if (bubbleView != null) return

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // Tamanho fixo (56dp) conforme o layout
        val sizePx = dp(56f)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Restaura posição se existir, senão top-end
            if (prefs.getBoolean(KEY_HAS_POS, false)) {
                x = prefs.getInt(KEY_X, 24)
                y = prefs.getInt(KEY_Y, 200)
                gravity = Gravity.TOP or Gravity.START
            } else {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 200
            }
        }

        val icon = bubbleView!!.findViewById<ImageView>(R.id.bubbleIcon)

        // Controle de toque único (decide click vs drag)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        bubbleView!!.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var downX = 0f
            private var downY = 0f
            private var dragged = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params!!.x
                        initialY = params!!.y
                        downX = e.rawX
                        downY = e.rawY
                        dragged = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            dragged = true
                        }
                        if (dragged) {
                            val (screenW, screenH) = getWindowSize()
                            val half = params!!.width / 2
                            val statusBar = 0 // ajuste se quiser reservar topo

                            var newX = initialX + dx
                            var newY = initialY + dy

                            newX = min(max(newX, -screenW + half), screenW - half)
                            newY = min(max(newY, statusBar), screenH - params!!.height)

                            params!!.x = newX
                            params!!.y = newY
                            params!!.gravity = Gravity.TOP or Gravity.START
                            windowManager?.updateViewLayout(bubbleView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragged) {
                            // Tratamos como clique: abrir o app e esconder a bolha
                            bringAppToFrontAndHide()
                            return true
                        }

                        // Se arrastou: snap na borda e salvar posição
                        val (screenW, _) = getWindowSize()
                        val centerX = params!!.x + params!!.width / 2
                        val toLeft = centerX < screenW / 2
                        params!!.x = if (toLeft) 0 else screenW - params!!.width
                        windowManager?.updateViewLayout(bubbleView, params)

                        prefs.edit()
                            .putBoolean(KEY_HAS_POS, true)
                            .putInt(KEY_X, params!!.x)
                            .putInt(KEY_Y, params!!.y)
                            .apply()
                        return true
                    }
                }
                return false
            }
        })

        // Evita conflito de click duplicado: remove OnClickListener — ACTION_UP já trata clique.
        icon.setOnClickListener(null)

        windowManager?.addView(bubbleView, params)
    }

    private fun bringAppToFrontAndHide() {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        startActivity(i)
        hideBubble()
    }

    private fun hideBubble() {
        bubbleView?.let { v ->
            try { windowManager?.removeView(v) } catch (_: Exception) {}
        }
        bubbleView = null
    }

    private fun dp(v: Float): Int {
        val dm = resources.displayMetrics
        return (v * dm.density).toInt()
    }

    private fun getWindowSize(): Pair<Int, Int> {
        val wm = windowManager ?: return 1080 to 1920
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
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
