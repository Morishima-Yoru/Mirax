package me.trinitrix.mirax

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.wfd.SinkConnectionController
import java.lang.ref.WeakReference

/**
 * Fullscreen Miracast picture. Aspect-fit with black letterboxing — no stretch,
 * no crop-to-fill. Picture taps and long-presses do nothing (no settings).
 *
 * System Back and the bottom handle are decided by [me.trinitrix.mirax.session.MiraxSession];
 * this activity only renders [SessionSnapshot] outputs and forwards actions.
 */
class PictureActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var stage: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var handleRoot: LinearLayout
    private lateinit var handleBar: View
    private lateinit var handlePanel: LinearLayout
    private lateinit var handleEndButton: TextView
    private lateinit var handleResolutionValue: TextView
    private lateinit var handleRefreshValue: TextView
    private var videoW: Int = 0
    private var videoH: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = WeakReference(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        stage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
            // Taps and long-presses must not open settings.
            isClickable = true
            isLongClickable = true
            setOnClickListener { /* intentionally empty */ }
            setOnLongClickListener { true }
        }
        stage.addView(surfaceView)
        stage.setOnClickListener { /* intentionally empty */ }
        stage.setOnLongClickListener { true }

        buildHandle()
        stage.addView(
            handleRoot,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )
        setContentView(stage)

        surfaceView.holder.addCallback(this)
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }

        SinkConnectionController.decoder.onFormat = { w, h, _ ->
            runOnUiThread {
                videoW = w
                videoH = h
                fitSurface()
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    applySession(SessionHost.onSystemBack(this@PictureActivity))
                }
            },
        )

        hideSystemBars()
        applySession(MiraxApp.instance.session.snapshot())
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Projection is foreground again — hide the ball without ending the connection.
        val session = MiraxApp.instance.session
        if (session.snapshot().showFloatingBall) {
            applySession(SessionHost.openedMiraxDashboard(this))
        } else {
            applySession(session.snapshot())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Home / Recents leave. Opening Mirax's own dashboard is reported from MainActivity.
        if (!isChangingConfigurations && !MainActivity.isShowing()) {
            SessionHost.leftProjectionToHome(this)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || isFinishing) {
            return
        }
        // Fallback when onUserLeaveHint did not run (some OEM home gestures).
        if (!MainActivity.isShowing()) {
            val snap = MiraxApp.instance.session.snapshot()
            if (snap.phase == ScreenPhase.CONNECTED && !snap.showFloatingBall) {
                SessionHost.leftProjectionToHome(this)
            }
        }
    }

    override fun onDestroy() {
        if (showing?.get() === this) {
            showing = null
        }
        SinkConnectionController.decoder.attachSurface(null)
        SinkConnectionController.decoder.onFormat = null
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        SinkConnectionController.decoder.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        SinkConnectionController.decoder.attachSurface(holder.surface)
        fitSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        SinkConnectionController.decoder.attachSurface(null)
    }

    private fun applySession(snapshot: SessionSnapshot) {
        if (!snapshot.showPicture) {
            finish()
            return
        }
        renderHandle(snapshot)
    }

    private fun buildHandle() {
        fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()

        handleRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        ViewCompat.setOnApplyWindowInsetsListener(handleRoot) { view, insets ->
            val nav = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(dp(16), dp(8), dp(16), dp(8) + nav.bottom)
            insets
        }

        handleBar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(0xCCE8EAED.toInt())
            }
            isClickable = true
            setOnClickListener {
                applySession(SessionHost.toggleBottomHandleExpanded(this@PictureActivity))
            }
        }

        handlePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0xE61C1F26.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
            }
        }

        handleEndButton = TextView(this).apply {
            text = getString(R.string.handle_end_connection)
            setTextColor(getColor(R.color.mirax_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isClickable = true
            setOnClickListener {
                applySession(SessionHost.endConnection(this@PictureActivity))
            }
        }
        handlePanel.addView(handleEndButton)

        handlePanel.addView(mutedLabel(getString(R.string.handle_resolution_label), topPadDp = 12))
        handleResolutionValue = TextView(this).apply {
            setTextColor(getColor(R.color.mirax_on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        handlePanel.addView(handleResolutionValue)

        handlePanel.addView(mutedLabel(getString(R.string.handle_refresh_label), topPadDp = 12))
        handleRefreshValue = TextView(this).apply {
            setTextColor(getColor(R.color.mirax_on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        handlePanel.addView(handleRefreshValue)

        handleRoot.addView(handleBar)
        handleRoot.addView(handlePanel)
    }

    private fun mutedLabel(text: String, topPadDp: Int): TextView {
        val topPad = (topPadDp * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.mirax_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, topPad, 0, 0)
        }
    }

    private fun renderHandle(snapshot: SessionSnapshot) {
        if (!snapshot.showBottomHandle) {
            handleRoot.visibility = View.GONE
            handlePanel.visibility = View.GONE
            return
        }
        handleRoot.visibility = View.VISIBLE
        handleResolutionValue.text = snapshot.handleResolutionText
        val refresh = snapshot.handleRefreshRateHz
        handleRefreshValue.text = if (refresh != null) {
            getString(R.string.handle_refresh_value, refresh)
        } else {
            ""
        }
        handlePanel.visibility = if (snapshot.bottomHandleExpanded) View.VISIBLE else View.GONE
        ViewCompat.requestApplyInsets(handleRoot)
    }

    private fun fitSurface() {
        if (videoW <= 0 || videoH <= 0 || stage.width <= 0 || stage.height <= 0) {
            return
        }
        val scale = minOf(stage.width / videoW.toFloat(), stage.height / videoH.toFloat())
        val width = maxOf(2, Math.round(videoW * scale))
        val height = maxOf(2, Math.round(videoH * scale))
        surfaceView.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        surfaceView.holder.setFixedSize(videoW, videoH)
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        @Volatile
        private var showing: WeakReference<PictureActivity>? = null

        fun finishIfShowing() {
            showing?.get()?.finish()
            showing = null
        }
    }
}
