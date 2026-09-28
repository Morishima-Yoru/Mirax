package me.trinitrix.mirax

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.wfd.SinkConnectionController
import java.lang.ref.WeakReference

/**
 * Fullscreen Miracast picture. Aspect-fit with black letterboxing — no stretch,
 * no crop-to-fill. Picture taps and long-presses do nothing (no settings).
 *
 * Issue #8 owns first/second Back toast and the bottom handle; this activity
 * only finishes when the session leaves CONNECTED.
 */
class PictureActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var stage: FrameLayout
    private lateinit var surfaceView: SurfaceView
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
                    // Issue #8 owns the double-Back confirm. Until then, ignore Back
                    // so a single press does not tear down the stream.
                }
            },
        )

        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        val phase = MiraxApp.instance.session.snapshot().phase
        if (phase != ScreenPhase.CONNECTED) {
            finish()
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
