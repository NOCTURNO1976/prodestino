package com.prodestino.manaus.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

        private const val PREFS = "overlay_prefs"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_HAS_POS = "has_pos"
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private val sysEvents = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_CONFIGURATION_CHANGED,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    // Garante que a view permanece anexada e com bounds corretos
                    ensureAttached()
                    clampInsideScreen()
                }
            }
        }
    }

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

        // Ouve eventos que costumam "descolar" overlays em alguns OEMs
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(sysEvents, f)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(sysEvents) } catch (_: Exception) {}
        hideBubble()
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
        if (bubbleView != null) { ensureAttached(); return }

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val sizePx = dp(56f) // Layout está fixo em 56dp

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            // Flags mais estáveis para hit-test
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.RGBA_8888
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (prefs.getBoolean(KEY_HAS_POS, false)) {
                x = prefs.getInt(KEY_X, 24)
                y = prefs.getInt(KEY_Y, 200)
            } else {
                val (w, _) = getWindowSize()
                x = max(0, w - sizePx - dp(24f)) // começa no canto direito com margem
                y = dp(200f)
            }
        }

        val icon = bubbleView!!.findViewById<ImageView>(R.id.bubbleIcon)
        icon.isHapticFeedbackEnabled = false
        bubbleView!!.isHapticFeedbackEnabled = false
        bubbleView!!.isLongClickable = false
        bubbleView!!.isClickable = true

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        bubbleView!!.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var downX = 0f
            private var downY = 0f
            private var dragged = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                try {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params!!.x
                            initialY = params!!.y
                            downX = e.rawX
                            downY = e.rawY
                            dragged = false
                            return true // sempre consome
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (e.rawX - downX).toInt()
                            val dy = (e.rawY - downY).toInt()
                            if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                                dragged = true
                            }
                            if (dragged) {
                                var newX = initialX + dx
                                var newY = initialY + dy
                                val (sw, sh) = getWindowSize()
                                val half = params!!.width / 2
                                newX = min(max(newX, 0), max(0, sw - params!!.width))
                                newY = min(max(newY, 0), max(0, sh - params!!.height))
                                params!!.x = newX
                                params!!.y = newY
                                updateViewSafe()
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (!dragged && e.actionMasked == MotionEvent.ACTION_UP) {
                                // Clique curto → abre o app
                                bringAppToFrontAndHide()
                                return true
                            }
                            if (dragged) {
                                // Snap para a borda mais próxima e salva
                                val (sw, _) = getWindowSize()
                                val centerX = params!!.x + params!!.width / 2
                                params!!.x = if (centerX < sw / 2) 0 else max(0, sw - params!!.width)
                                updateViewSafe()
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean(KEY_HAS_POS, true)
                                    .putInt(KEY_X, params!!.x)
                                    .putInt(KEY_Y, params!!.y)
                                    .apply()
                                return true
                            }
                            return true
                        }
                    }
                } catch (_: Throwable) {
                    ensureAttached()
                }
                return true
            }
        })

        addViewSafe()
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

    /* ---------- Helpers de robustez ---------- */

    private fun ensureAttached() {
        if (bubbleView == null || params == null) return
        try {
            // Se não estiver anexada, add de novo
            addViewSafe()
        } catch (_: Exception) {
            // recria do zero
            hideBubble()
            showBubble()
        }
    }

    private fun addViewSafe() {
        try {
            if (bubbleView?.windowToken == null) {
                windowManager?.addView(bubbleView, params)
            } else {
                updateViewSafe()
            }
        } catch (_: IllegalStateException) {
            // Tenta remover e adicionar novamente
            try { windowManager?.removeView(bubbleView) } catch (_: Exception) {}
            try { windowManager?.addView(bubbleView, params) } catch (_: Exception) {}
        } catch (_: IllegalArgumentException) {
            // Token inválido → recria
            try { windowManager?.removeViewImmediate(bubbleView) } catch (_: Exception) {}
            try { windowManager?.addView(bubbleView, params) } catch (_: Exception) {}
        }
    }

    private fun updateViewSafe() {
        try { windowManager?.updateViewLayout(bubbleView, params) }
        catch (_: IllegalArgumentException) { addViewSafe() }
        catch (_: IllegalStateException) { addViewSafe() }
    }

    private fun clampInsideScreen() {
        if (params == null) return
        val (sw, sh) = getWindowSize()
        params!!.x = min(max(params!!.x, 0), max(0, sw - params!!.width))
        params!!.y = min(max(params!!.y, 0), max(0, sh - params!!.height))
        updateViewSafe()
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
