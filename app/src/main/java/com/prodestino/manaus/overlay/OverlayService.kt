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

        // Preferências
        private const val PREFS = "overlay_prefs"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_HAS_POS = "has_pos"
        private const val KEY_SIZE_DP = "size_dp"

        // Tamanhos (dp)
        private const val SIZE_S = 40
        private const val SIZE_M = 44  // default
        private const val SIZE_L = 56
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
                .setSmallIcon(R.mipmap.ic_launcher) // ícone do app
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

        // Lê tamanho salvo (dp); default M
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sizeDp = prefs.getInt(KEY_SIZE_DP, SIZE_M)
        val sizePx = dp(sizeDp.toFloat())

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Posição inicial: restaurar se existir, senão top-end
            if (prefs.getBoolean(KEY_HAS_POS, false)) {
                x = prefs.getInt(KEY_X, 24)
                y = prefs.getInt(KEY_Y, 200)
                gravity = Gravity.TOP or Gravity.START // x/y absolutos
            } else {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 200
            }
        }

        val icon = bubbleView!!.findViewById<ImageView>(R.id.bubbleIcon)

        // Clique: trazer app pra frente
        icon.setOnClickListener {
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

        // Long-press: alterna tamanho (S -> M -> L -> S)
        icon.setOnLongClickListener {
            val next = when (prefs.getInt(KEY_SIZE_DP, SIZE_M)) {
                SIZE_S -> SIZE_M
                SIZE_M -> SIZE_L
                else -> SIZE_S
            }
            prefs.edit().putInt(KEY_SIZE_DP, next).apply()
            // Atualiza tamanho em tempo real mantendo posição
            resizeBubble(next)
            true
        }

        // Arrastar com limites + snap-to-edge e salvar posição
        bubbleView!!.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var downX = 0f
            private var downY = 0f
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params!!.x
                        initialY = params!!.y
                        downX = e.rawX
                        downY = e.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (abs(dx) > 2 || abs(dy) > 2) moved = true

                        val (screenW, screenH) = getWindowSize()
                        val half = params!!.width / 2
                        val statusBar = 0 // ajuste fino se quiser reservar área superior

                        var newX = initialX + dx
                        var newY = initialY + dy

                        // mantém dentro da tela
                        newX = min(max(newX, -screenW + half), screenW - half)
                        newY = min(max(newY, statusBar), screenH - params!!.height)

                        params!!.x = newX
                        params!!.y = newY
                        params!!.gravity = Gravity.TOP or Gravity.START
                        windowManager?.updateViewLayout(bubbleView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // pequeno movimento = tratar como click normal (OnClickListener cuidará)
                        if (!moved) return false

                        // Snap para a borda mais próxima e salva posição
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

        windowManager?.addView(bubbleView, params)
    }

    private fun hideBubble() {
        bubbleView?.let { v ->
            try { windowManager?.removeView(v) } catch (_: Exception) {}
        }
        bubbleView = null
    }

    private fun resizeBubble(sizeDp: Int) {
        if (bubbleView == null || params == null) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val newPx = dp(sizeDp.toFloat())
        params!!.width = newPx
        params!!.height = newPx
        windowManager?.updateViewLayout(bubbleView, params)
        prefs.edit().putInt(KEY_SIZE_DP, sizeDp).apply()
    }

    private fun dp(v: Float): Int {
        val dm = resources.displayMetrics
        return (v * dm.density).toInt()
    }

    private fun getWindowSize(): Pair<Int, Int> {
        val wm = windowManager ?: return 1080 to 1920
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            (b.width()) to (b.height())
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
