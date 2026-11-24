package me.trinitrix.mirax.broadcast

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.AdvertisingKeepAlivePolicy
import org.junit.Test

/**
 * Unit tests verifying that AdvertisingKeepAlivePolicy always satisfies
 * the Android startForegroundService contract to prevent ForegroundServiceDidNotStartInTimeException.
 */
class AdvertisingKeepAlivePolicyTest {

    @Test
    fun whenAdvertisingEnabled_startsForegroundAndRemainsRunning() {
        val action = AdvertisingKeepAlivePolicy.decide(advertisingEnabled = true)
        assertThat(action).isEqualTo(AdvertisingKeepAlivePolicy.Action.START_FOREGROUND_AND_REMAIN)
    }

    @Test
    fun whenAdvertisingDisabled_stillStartsForegroundBeforeStopping() {
        // When broadcast fails (e.g. no privilege path, BeaconFailed), onStartCommand
        // must still enter foreground before calling stopSelf to fulfill Android's contract.
        val action = AdvertisingKeepAlivePolicy.decide(advertisingEnabled = false)
        assertThat(action).isEqualTo(AdvertisingKeepAlivePolicy.Action.START_FOREGROUND_AND_STOP)
    }
}
