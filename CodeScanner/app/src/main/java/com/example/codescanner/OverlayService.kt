package com.example.codescanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var button: Button? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(42, buildNotification())
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addButton()
    }

    private fun buildNotification(): Notification {
        val channelId = "overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Плавающая кнопка", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val b = Notification.Builder(this, channelId)
            .setContentTitle("CodeScanner")
            .setContentText("Кнопка Старт/Стоп активна")
            .setSmallIcon(android.R.drawable.ic_media_play)
        return b.build()
    }

    private fun addButton() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        // Правый край экрана, по вертикали примерно по центру — подальше от
        // клавиатуры, поля ввода и кнопок окна обмена. Кнопку можно перетащить пальцем.
        val m = resources.displayMetrics
        p.gravity = Gravity.TOP or Gravity.START
        p.x = m.widthPixels - 220
        p.y = m.heightPixels / 2
        params = p

        val btn = Button(this).apply {
            text = "СТАРТ"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#E8CC2E"))
            alpha = 0.9f
        }
        button = btn

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false

        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    p.x = startX + dx
                    p.y = startY + dy
                    runCatching { wm.updateViewLayout(btn, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggle()
                    true
                }
                else -> false
            }
        }
        wm.addView(btn, p)
    }

    private fun toggle() {
        val svc = ClickerService.instance
        if (svc == null) {
            Toast.makeText(this, "Включите службу в спец. возможностях", Toast.LENGTH_LONG).show()
            return
        }
        if (svc.isRunning()) {
            svc.stop()
            button?.text = "СТАРТ"
            button?.setBackgroundColor(Color.parseColor("#E8CC2E"))
        } else {
            val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val code = prefs.getLong("code", 331075708L)
            val count = prefs.getInt("count", 100)
            val delay = prefs.getLong("delay", 1500L)
            svc.start(code, count, delay)
            button?.text = "СТОП"
            button?.setBackgroundColor(Color.parseColor("#E5392E"))
        }
    }

    override fun onDestroy() {
        button?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }
}
