package me.trinitrix.mirax.broadcast

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.TileState
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.session.WidgetStatus
import org.junit.Test

/**
 * Behaviour tests for the idle auto-stop schedule seam (issue 10).
 *
 * Seam under test: [BroadcastAutoStopTimer] with an isolated fake scheduler.
 * Each test owns its timer instance; the production [BroadcastAutoStop]
 * singleton is never touched. No sleeps, Android handlers, devices, or WFD calls.
 */
class BroadcastAutoStopTimerTest {

    private class FakeScheduler {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        var cancels = 0

        fun schedule(delayMs: Long, action: () -> Unit) {
            scheduled.add(delayMs to action)
        }

        fun cancel() {
            cancels++
            scheduled.clear()
        }
    }

    private class Harness(initial: SessionSnapshot) {
        val fake = FakeScheduler()
        var current: SessionSnapshot = initial
        var stops = 0
        val timer = BroadcastAutoStopTimer(
            schedule = fake::schedule,
            cancel = fake::cancel,
            snapshotNow = { current },
            stop = { stops++ },
        )
    }

    @Test
    fun zeroClampsToOneMinute() {
        val harness = Harness(idleSnapshot(minutes = 1))
        harness.timer.sync(idleSnapshot(minutes = 0))
        assertThat(harness.fake.scheduled).hasSize(1)
        assertThat(harness.fake.scheduled.single().first).isEqualTo(60_000L)
    }

    @Test
    fun overRangeClampsTo180Minutes() {
        val harness = Harness(idleSnapshot(minutes = 1))
        harness.timer.sync(idleSnapshot(minutes = 181))
        assertThat(harness.fake.scheduled.single().first).isEqualTo(180L * 60_000L)
    }

    @Test
    fun boundaryDelaysAreExact() {
        val harness = Harness(idleSnapshot(minutes = 1))
        harness.timer.sync(idleSnapshot(minutes = 1))
        assertThat(harness.fake.scheduled.single().first).isEqualTo(60_000L)
        harness.timer.sync(idleSnapshot(minutes = 180))
        assertThat(harness.fake.scheduled).hasSize(1)
        assertThat(harness.fake.scheduled.single().first).isEqualTo(180L * 60_000L)
    }

    @Test
    fun repeatSyncKeepsOriginalDeadline() {
        val harness = Harness(idleSnapshot(minutes = 5))
        harness.timer.sync(idleSnapshot(minutes = 5))
        val cancelsAfterFirst = harness.fake.cancels
        harness.timer.sync(idleSnapshot(minutes = 5))
        assertThat(harness.fake.scheduled).hasSize(1)
        assertThat(harness.fake.cancels).isEqualTo(cancelsAfterFirst)
        assertThat(harness.fake.scheduled.single().first).isEqualTo(5L * 60_000L)
    }

    @Test
    fun unrelatedSnapshotChangeKeepsDeadline() {
        val harness = Harness(idleSnapshot(minutes = 5))
        harness.timer.sync(idleSnapshot(minutes = 5))
        val cancelsAfterFirst = harness.fake.cancels
        harness.timer.sync(idleSnapshot(minutes = 5).copy(showFloatingBall = true))
        assertThat(harness.fake.scheduled).hasSize(1)
        assertThat(harness.fake.cancels).isEqualTo(cancelsAfterFirst)
    }

    @Test
    fun changedMinutesReschedule() {
        val harness = Harness(idleSnapshot(minutes = 1))
        harness.timer.sync(idleSnapshot(minutes = 1))
        harness.timer.sync(idleSnapshot(minutes = 2))
        assertThat(harness.fake.scheduled).hasSize(1)
        assertThat(harness.fake.scheduled.single().first).isEqualTo(2L * 60_000L)
    }

    @Test
    fun leavingPhaseCancelsWithoutStop() {
        val harness = Harness(idleSnapshot(minutes = 1))
        harness.timer.sync(idleSnapshot(minutes = 1))
        val stale = harness.fake.scheduled.single().second
        harness.timer.sync(nonIdleSnapshot(ScreenPhase.CONNECTING, minutes = 1))
        assertThat(harness.fake.scheduled).isEmpty()
        harness.current = nonIdleSnapshot(ScreenPhase.CONNECTING, minutes = 1)
        stale()
        assertThat(harness.stops).isEqualTo(0)
    }

    @Test
    fun staleCallbackAfterRescheduleNeverStopsAndKeepsNewDeadline() {
        val harness = Harness(idleSnapshot(minutes = 1))
        harness.timer.sync(idleSnapshot(minutes = 1))
        val stale = harness.fake.scheduled.single().second
        harness.timer.sync(idleSnapshot(minutes = 2))
        harness.current = idleSnapshot(minutes = 2)
        stale()
        assertThat(harness.stops).isEqualTo(0)
        assertThat(harness.fake.scheduled).hasSize(1)
        assertThat(harness.fake.scheduled.single().first).isEqualTo(2L * 60_000L)
        harness.fake.scheduled.single().second()
        assertThat(harness.stops).isEqualTo(1)
    }

    @Test
    fun expiryStopsOnce() {
        val harness = Harness(idleSnapshot(minutes = 1))
        harness.timer.sync(idleSnapshot(minutes = 1))
        val fire = harness.fake.scheduled.single().second
        harness.current = idleSnapshot(minutes = 1)
        fire()
        fire()
        assertThat(harness.stops).isEqualTo(1)
    }

    @Test
    fun staleFireAfterExpiryNeverStopsNewArming() {
        val harness = Harness(idleSnapshot(minutes = 1))
        harness.timer.sync(idleSnapshot(minutes = 1))
        val firstFire = harness.fake.scheduled.single().second
        harness.current = idleSnapshot(minutes = 1)
        firstFire()
        assertThat(harness.stops).isEqualTo(1)
        // Fire consumed the arming; re-entry arms a fresh deadline.
        harness.timer.sync(idleSnapshot(minutes = 1))
        assertThat(harness.fake.scheduled).hasSize(1)
        firstFire()
        assertThat(harness.stops).isEqualTo(1)
        harness.fake.scheduled.single().second()
        assertThat(harness.stops).isEqualTo(2)
    }

    @Test
    fun expiryRequiresAdvertisingPhase() {
        val harness = Harness(idleSnapshot(minutes = 1))
        harness.timer.sync(idleSnapshot(minutes = 1))
        val fire = harness.fake.scheduled.single().second
        harness.current = nonIdleSnapshot(ScreenPhase.CONNECTING, minutes = 1)
        fire()
        assertThat(harness.stops).isEqualTo(0)
    }

    @Test
    fun expiryRequiresAdvertisingEnabled() {
        val harness = Harness(idleSnapshot(minutes = 1))
        harness.timer.sync(idleSnapshot(minutes = 1))
        val fire = harness.fake.scheduled.single().second
        harness.current = idleSnapshot(minutes = 1, advertisingEnabled = false)
        fire()
        assertThat(harness.stops).isEqualTo(0)
    }

    @Test
    fun failureEpisodeWithSamePhaseAndMinutesKeepsDeadline() {
        val harness = Harness(idleSnapshot(minutes = 3))
        harness.timer.sync(idleSnapshot(minutes = 3))
        val cancelsAfterFirst = harness.fake.cancels
        // FAILED show/dismiss/expiry carry no phase or minute change.
        harness.timer.sync(idleSnapshot(minutes = 3))
        harness.timer.sync(idleSnapshot(minutes = 3))
        assertThat(harness.fake.scheduled).hasSize(1)
        assertThat(harness.fake.cancels).isEqualTo(cancelsAfterFirst)
        assertThat(harness.fake.scheduled.single().first).isEqualTo(3L * 60_000L)
    }

    @Test
    fun reenteringAdvertisingRearmsFullDelay() {
        val harness = Harness(idleSnapshot(minutes = 4))
        harness.timer.sync(idleSnapshot(minutes = 4))
        assertThat(harness.fake.scheduled.single().first).isEqualTo(4L * 60_000L)
        harness.timer.sync(nonIdleSnapshot(ScreenPhase.CONNECTING, minutes = 4))
        assertThat(harness.fake.scheduled).isEmpty()
        harness.timer.sync(idleSnapshot(minutes = 4))
        assertThat(harness.fake.scheduled).hasSize(1)
        assertThat(harness.fake.scheduled.single().first).isEqualTo(4L * 60_000L)
    }

    private fun idleSnapshot(minutes: Int, advertisingEnabled: Boolean = true): SessionSnapshot {
        return SessionSnapshot(
            phase = ScreenPhase.ADVERTISING,
            wfdOwner = WfdOwner.NONE,
            tileState = TileState.ADVERTISING,
            widgetStatus = WidgetStatus.ADVERTISING,
            advertisingEnabled = advertisingEnabled,
            shouldRequestShizukuPermission = false,
            canReadWmSize = false,
            broadcastAutoStopMinutes = minutes,
        )
    }

    private fun nonIdleSnapshot(phase: ScreenPhase, minutes: Int): SessionSnapshot {
        return SessionSnapshot(
            phase = phase,
            wfdOwner = WfdOwner.NONE,
            tileState = TileState.OFF,
            widgetStatus = WidgetStatus.OFF,
            advertisingEnabled = true,
            shouldRequestShizukuPermission = false,
            canReadWmSize = false,
            broadcastAutoStopMinutes = minutes,
        )
    }
}
