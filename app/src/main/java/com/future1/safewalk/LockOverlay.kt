package com.future1.safewalk

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.future1.safewalk.core.Decision
import com.future1.safewalk.core.Level

/**
 * 다른 앱 위에 전체 화면으로 덮는 잠금 화면.
 * 터치를 모두 가로채서 걷는 동안 폰을 쓸 수 없게 한다. 긴급전화 버튼은 항상 남겨 둔다.
 */
class LockOverlay(
    private val context: Context,
    private val onEmergency: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var icon: TextView? = null
    private var message: TextView? = null

    val isShowing get() = root != null

    fun show(d: Decision) {
        if (!Settings.canDrawOverlays(context)) return
        if (root == null) attach()
        val danger = d.level == Level.DANGER
        root?.setBackgroundColor(if (danger) Color.argb(245, 198, 40, 40) else Color.argb(240, 245, 124, 0))
        icon?.text = if (danger) "🚸" else "🚶"
        message?.text = d.message
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun attach() {
        val ctx = context
        fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            isClickable = true   // 아래 앱으로 터치가 전달되지 않게
            isFocusable = true
        }
        icon = TextView(ctx).apply {
            textSize = 96f
            gravity = Gravity.CENTER
        }
        message = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(30f))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }
        val hint = TextView(ctx).apply {
            text = "멈춰 서면 3초 뒤에 풀려요"
            setTextColor(Color.argb(220, 255, 255, 255))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 64)
        }
        val emergency = Button(ctx).apply {
            text = "긴급전화 112 / 119"
            textSize = 18f
            setOnClickListener {
                onEmergency()
                ctx.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        layout.addView(icon)
        layout.addView(message)
        layout.addView(hint)
        layout.addView(emergency)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(layout, params)
        root = layout
    }
}
