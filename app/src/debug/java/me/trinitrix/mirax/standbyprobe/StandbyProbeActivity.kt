package me.trinitrix.mirax.standbyprobe

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import me.trinitrix.mirax.standbyprobe.render.StandbyRenderProbeView

/**
 * Minimal empty debug host for standby probes.
 *
 * Stays empty so lane 02 can replace content inside `onActivity`.
 * No extras, no static state, no navigation, no release entry.
 */
class StandbyProbeActivity : Activity() {

    /** Empty content root, normally accessible for tests to add or replace views. */
    lateinit var contentRoot: FrameLayout
        private set

    /**
     * Creates the empty probe content and records the activity state.
     * @param savedInstanceState Previously saved activity state, if available.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contentRoot = FrameLayout(this)
        setContentView(contentRoot)
        logState("onCreate")
    }

    /** Records the activity start. */
    override fun onStart() {
        super.onStart()
        logState("onStart")
    }

    /** Records the activity resume. */
    override fun onResume() {
        super.onResume()
        logState("onResume")
    }

    /**
     * Records a configuration change delivered without recreation.
     * @param newConfig Configuration delivered to the activity.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        logState("onConfigurationChanged")
    }

    /** Records the activity pause. */
    override fun onPause() {
        logState("onPause")
        super.onPause()
    }

    /** Stops frame probes and records the activity stop. */
    override fun onStop() {
        super.onStop()
        val count = contentRoot.childCount
        for (i in 0 until count) {
            val child = contentRoot.getChildAt(i)
            if (child is StandbyRenderProbeView) {
                child.stopFrameProbe()
            }
        }
        logState("onStop")
    }

    /** Records the activity destruction. */
    override fun onDestroy() {
        logState("onDestroy")
        super.onDestroy()
    }

    /**
     * Logs lifecycle and configuration state without recording intent data.
     * @param event Lifecycle callback being reported.
     */
    private fun logState(event: String) {
        val configuration = resources.configuration
        Log.i(
            "MiraxStandbyProbe",
            "event=$event instance=${System.identityHashCode(this)} " +
                "isChangingConfigurations=$isChangingConfigurations " +
                "changingConfigurations=0x${Integer.toHexString(changingConfigurations)} " +
                "orientation=${configuration.orientation} densityDpi=${configuration.densityDpi} " +
                "screenWidthDp=${configuration.screenWidthDp} screenHeightDp=${configuration.screenHeightDp} " +
                "uiMode=0x${configuration.uiMode.toString(16)} fontScale=${configuration.fontScale} " +
                "locales=${configuration.locales.toLanguageTags()}",
        )
    }
}
