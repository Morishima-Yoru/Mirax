package me.trinitrix.mirax

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import me.trinitrix.mirax.session.SessionSnapshot
import kotlin.math.abs

/**
 * Renders the floating ball overlay when the session says to show it.
 * Tap returns to projection; drag onto the lower-half X ends the connection.
 * Does not own product state — only applies [SessionSnapshot.showFloatingBall].
 */
class FloatingBallService : Service() {
    private var windowManager: WindowManager? = null
    private var ballView: View? = null
    private var trashRoot: FrameLayout? = null
    private var trashIcon: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showing = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!OverlayPermission.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureViews()
        return START_STICKY
    }

    override fun onDestroy() {
        removeViews()
        showing = false
        super.onDestroy()
    }

    private fun ensureViews() {
        if (ballView != null) {
            return
        }
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        val ballSize = (56 * density).toInt()
        val trashSize = (72 * density).toInt()

        trashRoot = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0x66000000)
        }
        trashIcon = TextView(this).apply {
            text = "×"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFD32F2F.toInt())
            }
        }
        val lowerHalfTop = resources.displayMetrics.heightPixels / 2
        trashRoot!!.addView(
            trashIcon,
            FrameLayout.LayoutParams(trashSize, trashSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                // Keep the X in the lower half of the screen.
                bottomMargin = (lowerHalfTop * 0.25f).toInt().coerceAtLeast((48 * density).toInt())
            },
        )
        val trashParams = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Gravity.TOP or Gravity.START,
        )
        wm.addView(trashRoot, trashParams)

        val ball = TextView(this).apply {
            text = "M"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF5B9FD4.toInt())
            }
            elevation = 8 * density
        }
        ballParams = overlayParams(ballSize, ballSize, Gravity.TOP or Gravity.START).apply {
            x = (resources.displayMetrics.widthPixels - ballSize - (16 * density).toInt())
            y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        }
        ball.setOnTouchListener { _, event -> handleBallTouch(event) }
        wm.addView(ball, ballParams)
        ballView = ball
    }

    private fun handleBallTouch(event: MotionEvent): Boolean {
        val params = ballParams ?: return false
        val wm = windowManager ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                moved = false
                trashRoot?.visibility = View.VISIBLE
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (abs(dx) > touchSlop() || abs(dy) > touchSlop()) {
                    moved = true
                }
                params.x = startX + dx
                params.y = startY + dy
                wm.updateViewLayout(ballView, params)
                updateTrashHighlight(event.rawX, event.rawY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                trashRoot?.visibility = View.GONE
                if (!moved) {
                    SessionHost.floatingBallTapped(this)
                } else if (isOverTrash(event.rawX, event.rawY)) {
                    SessionHost.endConnection(this)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                trashRoot?.visibility = View.GONE
                return true
            }
        }
        return false
    }

    private fun updateTrashHighlight(rawX: Float, rawY: Float) {
        val over = isOverTrash(rawX, rawY)
        trashIcon?.alpha = if (over) 1f else 0.55f
        trashIcon?.scaleX = if (over) 1.15f else 1f
        trashIcon?.scaleY = if (over) 1.15f else 1f
    }

    private fun isOverTrash(rawX: Float, rawY: Float): Boolean {
        val icon = trashIcon ?: return false
        val loc = IntArray(2)
        icon.getLocationOnScreen(loc)
        val pad = icon.width / 2
        val left = loc[0] - pad
        val top = loc[1] - pad
        val right = loc[0] + icon.width + pad
        val bottom = loc[1] + icon.height + pad
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun touchSlop(): Int =
        (8 * resources.displayMetrics.density).toInt()

    private fun overlayParams(width: Int, height: Int, gravity: Int): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
        }
    }

    private fun removeViews() {
        val wm = windowManager
        ballView?.let { runCatching { wm?.removeView(it) } }
        trashRoot?.let { runCatching { wm?.removeView(it) } }
        ballView = null
        trashRoot = null
        trashIcon = null
        ballParams = null
    }

    companion object {
        @Volatile
        private var showing: Boolean = false

        fun sync(context: Context, snapshot: SessionSnapshot) {
            val app = context.applicationContext
            val want = snapshot.showFloatingBall && OverlayPermission.canDrawOverlays(app)
            val intent = Intent(app, FloatingBallService::class.java)
            if (want) {
                app.startService(intent)
            } else if (showing) {
                app.stopService(intent)
            }
        }
    }
}
