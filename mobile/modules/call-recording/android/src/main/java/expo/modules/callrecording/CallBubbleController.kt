package expo.modules.callrecording

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * The "quick ball": a small draggable overlay with Pause/Resume and Stop controls, shown while
 * the agent has backgrounded the app (e.g. tapped Home) during an active call recording. Requires
 * SYSTEM_ALERT_WINDOW (Settings.canDrawOverlays) -- CallRecordingModule checks that before ever
 * calling [show].
 *
 * The pause/resume icon and the stop icon are two halves of one small pill. A touch that doesn't
 * move (within touch slop) is treated as a tap on whichever half it landed in; anything else drags
 * the whole pill, since attaching separate click listeners to the child icons would fight with the
 * drag-tracking touch listener on the parent.
 */
class CallBubbleController(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var bubbleView: LinearLayout? = null
    private var pauseIcon: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    var onPauseResumeTapped: (() -> Unit)? = null
    var onStopTapped: (() -> Unit)? = null

    fun show(isPaused: Boolean) {
        if (bubbleView != null) {
            setPaused(isPaused)
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val iconPadding = (16 * context.resources.displayMetrics.density).toInt()

        val pause = ImageView(context).apply {
            setImageResource(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            )
            setColorFilter(Color.WHITE)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        }
        val stop = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(Color.parseColor("#DD1F1F1F"))
            }
            addView(pause)
            addView(stop)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
        }
        layoutParams = params

        attachDragAndTapHandling(container, params, wm)

        wm.addView(container, params)
        bubbleView = container
        pauseIcon = pause
    }

    private fun attachDragAndTapHandling(container: LinearLayout, params: WindowManager.LayoutParams, wm: WindowManager) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        container.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        wm.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (event.x < view.width / 2f) {
                            onPauseResumeTapped?.invoke()
                        } else {
                            onStopTapped?.invoke()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun setPaused(isPaused: Boolean) {
        pauseIcon?.setImageResource(
            if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        )
    }

    fun hide() {
        val view = bubbleView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached.
        }
        bubbleView = null
        pauseIcon = null
        layoutParams = null
    }
}
