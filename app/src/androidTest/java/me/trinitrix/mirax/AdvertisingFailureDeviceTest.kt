package me.trinitrix.mirax

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.TileState
import me.trinitrix.mirax.session.WidgetStatus
import me.trinitrix.mirax.wfd.UibcTouchChannel
import me.trinitrix.mirax.wfd.WfdOwnerBridge
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an orchestrator-owned device and an external Windows cast; ordinary suites skip it. */
@RunWith(AndroidJUnit4::class)
class AdvertisingFailureDeviceTest {
    /** Injects a beacon failure during real playback and verifies the Android host's final state. */
    @Test(timeout = 90_000)
    fun beaconFailure_during_external_playback_persists_off_and_closes_input(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("reliabilityTestMode") == "advertisingFailure")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        try {
            instrumentation.runOnMainSync {
                SessionHost.refreshPrivilegeAndSurfaces(context)
            }
            // This scenario tests failure during playback, not cold user-service startup.
            withTimeout(20_000) {
                while (WfdOwnerBridge.groupState() == null) delay(100)
            }
            instrumentation.runOnMainSync {
                val started = SessionHost.setAdvertising(context, true)
                assertThat(started.advertisingEnabled).isTrue()
                assertThat(SessionPreferences.load(context).advertisingEnabled).isTrue()
            }
            Log.i("MiraxAdvertisingDevice", "WAITING_FOR_EXTERNAL_CAST")
            withTimeout(60_000) {
                var playing = false
                while (!playing) {
                    instrumentation.runOnMainSync {
                        playing = MiraxApp.instance.session.snapshot().showPicture
                    }
                    if (!playing) delay(100)
                }
            }
            Log.i("MiraxAdvertisingDevice", "EXTERNAL_PLAYBACK_READY")
            delay(1_500)
            instrumentation.runOnMainSync {
                val playing = MiraxApp.instance.session.snapshot()
                assertThat(playing.showPicture).isTrue()
                assertThat(playing.advertisingEnabled).isTrue()
                assertThat(UibcTouchChannel.canForwardInput()).isTrue()
                val failed = SessionHost.dispatchConnectionEvent(context, SessionAction.BeaconFailed)
                assertThat(failed.advertisingEnabled).isFalse()
                assertThat(failed.tileState).isEqualTo(TileState.OFF)
                assertThat(failed.widgetStatus).isEqualTo(WidgetStatus.OFF)
                assertThat(failed.wfdAdvertise).isNull()
                assertThat(failed.showPicture).isFalse()
                assertThat(failed.showFloatingBall).isFalse()
                assertThat(SessionPreferences.load(context).advertisingEnabled).isFalse()
                val repeated = SessionHost.commit(context)
                assertThat(repeated.advertisingEnabled).isFalse()
                assertThat(repeated.showPicture).isFalse()
            }
            withTimeout(5_000) {
                while (UibcTouchChannel.canForwardInput()) delay(50)
            }
            instrumentation.waitForIdleSync()
            Log.i("MiraxAdvertisingDevice", "PASS finalOff=true persistedOff=true pictureClosed=true inputClosed=true")
        } finally {
            instrumentation.runOnMainSync { SessionHost.setAdvertising(context, false) }
        }
    }
}
