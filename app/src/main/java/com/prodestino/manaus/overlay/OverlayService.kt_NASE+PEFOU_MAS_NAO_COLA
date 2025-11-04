package com.prodestino.manaus.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
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

    private val ui = Handler()

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

        val sizePx = dp(56f) // tamanho fixo (layout está 56dp)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            // NOT_FOCUSABLE evita roubar foco; NOT_TOUCH_MODAL deixa o resto da tela interagir normal
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Use SEMPRE TOP|START para consistência do (x,y)
            gravity = Gravity.TOP or Gravity.START
            if (prefs.getBoolean(KEY_HAS_POS, false)) {
                x = prefs.getInt(KEY_X, 24)
                y = prefs.getInt(KEY_Y, 200)
            } else {
                // posição inicial: canto superior direito com margem
                val (w, _) = getWindowSize()
                x = max(0, w - sizePx - dp(24f))
                y = dp(200f)
            }
        }

        val icon = bubbleView!!.findViewById<ImageView>(R.id.bubbleIcon)
        icon.isHapticFeedbackEnabled = false
        bubbleView!!.isHapticFeedbackEnabled = false
        bubbleView!!.isLongClickable = false
        bubbleView!!.isClickable = true

        val vc = ViewConfiguration.get(this)
        val touchSlop = vc.scaledTouchSlop / 2  // mais sensível que o padrão
        val dragArmMs = 120L                     // segura 120ms e entra em modo drag

        bubbleView!!.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var downX = 0f
            private var downY = 0f
            private var dragged = false
            private var pointerId = -1
            private val enterDrag = Runnable {
                // força entrar em modo drag se o dedo ficou apoiado
                dragged = true
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pointerId = e.getPointerId(0)
                        initialX = params!!.x
                        initialY = params!!.y
                        downX = e.rawX
                        downY = e.rawY
                        dragged = false
                        // agenda entrar em drag após 120ms mesmo com pouco movimento
                        ui.postDelayed(enterDrag, dragArmMs)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // só considera o pointer principal
                        val idx = e.findPointerIndex(pointerId)
                        if (idx < 0) return false

                        val rawX = e.getRawX(idx)
                        val rawY = e.getRawY(idx)
                        val dx = (rawX - downX).toInt()
                        val dy = (rawY - downY).toInt()

                        if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            dragged = true
                            // ao entrar em drag, cancela o clique programado
                            ui.removeCallbacks(enterDrag)
                        }

                        if (dragged) {
                            val (screenW, screenH) = getWindowSize()
                            val half = params!!.width / 2
                            val statusBar = 0

                            var newX = initialX + dx
                            var newY = initialY + dy

                            newX = min(max(newX, -screenW + half), screenW - half)
                            newY = min(max(newY, statusBar), screenH - params!!.height)

                            params!!.x = newX
                            params!!.y = newY
                            try { windowManager?.updateViewLayout(bubbleView, params) } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        ui.removeCallbacks(enterDrag)

                        if (!dragged && e.actionMasked == MotionEvent.ACTION_UP) {
                            // toque curto sem arraste -> abre app
                            bringAppToFrontAndHide()
                            return true
                        }

                        if (dragged) {
                            // snap para a borda e salva
                            val (screenW, _) = getWindowSize()
                            val centerX = params!!.x + params!!.width / 2
                            val toLeft = centerX < screenW / 2
                            params!!.x = if (toLeft) 0 else screenW - params!!.width
                            try { windowManager?.updateViewLayout(bubbleView, params) } catch (_: Exception) {}

                            prefs.edit()
                                .putBoolean(KEY_HAS_POS, true)
                                .putInt(KEY_X, params!!.x)
                                .putInt(KEY_Y, params!!.y)
                                .apply()
                            return true
                        }
                        return true
                    }
                }
                return false
            }
        })

        // Evita clique duplicado (tudo é tratado no onTouch)
        icon.setOnClickListener(null)

        try { windowManager?.addView(bubbleView, params) } catch (_: Exception) {}
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
