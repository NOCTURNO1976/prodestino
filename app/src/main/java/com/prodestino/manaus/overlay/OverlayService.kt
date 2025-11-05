package com.prodestino.manaus.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.view.ViewConfiguration
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.prodestino.manaus.R

class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.prodestino.manaus.overlay.SHOW"
        const val ACTION_HIDE = "com.prodestino.manaus.overlay.HIDE"
        const val ACTION_TOGGLE = "com.prodestino.manaus.overlay.TOGGLE"

        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIF_ID = 1002

        private const val PREFS = "overlay_prefs"
        private const val KEY_HAS_POS = "has_pos"
        private const val KEY_POS_X = "pos_x"
        private const val KEY_POS_Y = "pos_y"
    }

    private var wm: WindowManager? = null
    private var overlayView: View? = null
    private var isShowing = false

    private lateinit var lp: WindowManager.LayoutParams
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

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

    private fun show() {
        if (isShowing) return

        // Se não tiver permissão de sobreposição, tenta abrir a tela e encerra
        if (!Settings.canDrawOverlays(this)) {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            stopSelf()
            return
        }

        // Notificação mínima (foreground para estabilidade)
        val pi = packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            PendingIntent.getActivity(
                this, 0, it,
                if (Build.VERSION.SDK_INT >= 31)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bolha ativa")
            .setContentText("Toque para abrir o app")
            .setSmallIcon(R.mipmap.ic_launcher) // usa ícone existente
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()

        startForeground(NOTIF_ID, notif)

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START

        // Posição inicial (ou restaurada)
        if (prefs.getBoolean(KEY_HAS_POS, false)) {
            lp.x = prefs.getInt(KEY_POS_X, 24)
            lp.y = prefs.getInt(KEY_POS_Y, 180)
        } else {
            lp.x = 24
            lp.y = 180
        }

        // Listener de clique → abre o app e esconde a bolha
        overlayView?.setOnClickListener {
            try {
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(launch)
            } catch (_: Exception) {}
            // opcional: esconder após abrir o app
            hide()
        }

        // Arrastar / soltar com snap-to-edge e clamp de tela
        installDragToMove(overlayView!!)

        wm?.addView(overlayView, lp)
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

    // ========= Drag & Snap =========

    private fun installDragToMove(view: View) {
        val vc = ViewConfiguration.get(this)
        val touchSlop = vc.scaledTouchSlop

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var consumedClick = false

        view.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    consumedClick = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()

                    if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        clampToScreen(lp, view)
                        wm?.updateViewLayout(view, lp)
                        consumedClick = true
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        // snap para a lateral mais próxima
                        val screenW = getScreenWidth()
                        val halfBubble = (view.width / 2)
                        lp.x = if ((lp.x + halfBubble) < (screenW / 2)) 0 else screenW - view.width
                        clampToScreen(lp, view)
                        wm?.updateViewLayout(view, lp)
                        savePos(lp.x, lp.y)
                    }
                    // Se não arrastou, deixa o OnClickListener cuidar (não consumir aqui)
                    !consumedClick
                }
                else -> false
            }
        }
    }

    private fun clampToScreen(lp: WindowManager.LayoutParams, v: View) {
        val sw = getScreenWidth()
        val sh = getScreenHeight()
        val w = v.width.coerceAtLeast(dp(56))  // fallback
        val h = v.height.coerceAtLeast(dp(56))
        lp.x = lp.x.coerceIn(0, (sw - w).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (sh - h).coerceAtLeast(0))
    }

    private fun savePos(x: Int, y: Int) {
        prefs.edit()
            .putBoolean(KEY_HAS_POS, true)
            .putInt(KEY_POS_X, x)
            .putInt(KEY_POS_Y, y)
            .apply()
    }

    private fun getScreenWidth(): Int {
        val wm = wm ?: return resources.displayMetrics.widthPixels
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.widthPixels
        }
    }

    private fun getScreenHeight(): Int {
        val wm = wm ?: return resources.displayMetrics.heightPixels
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.height()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.heightPixels
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
