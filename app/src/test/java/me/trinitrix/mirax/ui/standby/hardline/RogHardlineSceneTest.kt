package me.trinitrix.mirax.ui.standby.hardline

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.ui.standby.StandbyViewportScale
import org.junit.Test
import kotlin.math.abs
import android.graphics.Typeface

/**
 * Unit tests for RogHardlineScene verifying ROG stream physics and quadratic acceleration
 * according to Prototype Theme E (design/big-standby-e-scene.js and verify-d-streams.cjs).
 */
class RogHardlineSceneTest {

    @Test
    fun sceneInitializesWithFourEdgeStreams() {
        val scene = RogHardlineScene(statusCopy = testStatusCopy())
        assertThat(scene.streams).hasSize(4)
        val names = scene.streams.map { it.name }
        assertThat(names).containsExactly(
            "upper-top",
            "upper-bottom",
            "lower-top",
            "lower-bottom",
        )
    }

    @Test
    fun updateAdvancesPhysicsWithQuadraticAcceleration() {
        // Deterministic random generator
        val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        val stream = scene.streams.first()
        // Gap is 0.12 + 0.5 * 0.78 = 0.51s
        // Duration is 0.95 + 0.5 * 0.65 = 1.275s

        // Advance through gap
        scene.update(0.55)
        assertThat(stream.inShot).isTrue()
        assertThat(stream.progress).isGreaterThan(0.0)
        assertThat(stream.progress).isLessThan(1.0)

        // Verify quadratic position acceleration: position == progress^2
        val expectedPosition = stream.progress * stream.progress
        assertThat(abs(stream.position - expectedPosition)).isLessThan(1e-9)

        // Verify alpha envelope
        assertThat(stream.alpha).isAtLeast(0.0)
        assertThat(stream.alpha).isAtMost(1.0)
    }

    @Test
    fun completedShotReschedulesStream() {
        val scene = RogHardlineScene(random = { 0.0 }, statusCopy = testStatusCopy())
        // Advance past gap + duration (0.12 + 0.95 = 1.07s)
        scene.update(2.0)
        // Stream should have completed and rescheduled for a fresh cycle
        for (stream in scene.streams) {
            assertThat(stream.elapsedSeconds).isAtLeast(0.0)
            assertThat(stream.gapSeconds).isGreaterThan(0.1)
            assertThat(stream.durationSeconds).isGreaterThan(0.9)
        }
    }

    @Test
    fun nonPositiveOrNonFiniteDeltaIsSafelyIgnored() {
        val scene = RogHardlineScene(statusCopy = testStatusCopy())
        val before = scene.streams.map { it.elapsedSeconds }
        scene.update(-1.0)
        scene.update(Double.NaN)
        scene.update(Double.POSITIVE_INFINITY)
        val after = scene.streams.map { it.elapsedSeconds }
        assertThat(after).isEqualTo(before)
    }

    /** Raw status timestamps retain each phase independently of capped decorative updates. */
    @Test
    fun statusProgress_resumesEachPhaseWithoutCountingOtherPhaseUpdates() {
        val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        scene.update(0.5)
        assertThat(statusTimes(scene).all { it == 0.0 }).isTrue()
        scene.setState(ScreenPhase.READY, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 0L)
        assertThat(statusProgress(scene)).isEqualTo(0.0)
        scene.advanceStatusTo(2000L)
        assertThat(statusProgress(scene)).isWithin(1e-9).of(2.0 / 148.0)
        scene.setState(ScreenPhase.ARMING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 2000L)
        assertThat(statusProgress(scene)).isEqualTo(0.0)
        scene.advanceStatusTo(3250L)
        assertThat(statusProgress(scene)).isWithin(1e-9).of(1.25 / 68.0)
        scene.setState(ScreenPhase.READY, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 3250L)
        assertThat(statusProgress(scene)).isWithin(1e-9).of(2.0 / 148.0)
        scene.advanceStatusTo(3750L)
        scene.setState(ScreenPhase.ARMING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 3750L)
        assertThat(statusProgress(scene)).isWithin(1e-9).of(1.25 / 68.0)
        scene.setState(ScreenPhase.FROZEN, "", false, RogHardlineScene.StatusMode.RUNNING, 3750L)
        assertThat(statusProgress(scene)).isEqualTo(0.0)
        scene.advanceStatusTo(4500L)
        assertThat(statusProgress(scene)).isWithin(1e-9).of(0.75 / 112.0)
        scene.setState(ScreenPhase.CONNECTING, "", false, RogHardlineScene.StatusMode.RUNNING, 4500L)
        assertThat(statusProgress(scene)).isEqualTo(0.0)
        scene.advanceStatusTo(4750L)
        assertThat(statusProgress(scene)).isWithin(1e-9).of(0.25 / 84.0)
        scene.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 4750L)
        assertThat(statusProgress(scene)).isWithin(1e-9).of(2.5 / 148.0)
    }

    /** Equal durations do not merge Wi-Fi/waiting; every genuine failure selects one shared track. */
    @Test
    fun statusProgress_separatesEqualDurationsAndSharesVisualFailureTrack() {
        val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        scene.setState(ScreenPhase.ADVERTISING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 0L)
        scene.advanceStatusTo(1500L)
        scene.setState(ScreenPhase.WIFI_PAUSED, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 1500L)
        assertThat(statusProgress(scene)).isEqualTo(0.0)
        scene.advanceStatusTo(3750L)
        scene.setState(ScreenPhase.WIFI_PAUSED, "Studio", true, RogHardlineScene.StatusMode.RUNNING, 3750L)
        assertThat(statusProgress(scene)).isEqualTo(0.0)
        scene.advanceStatusTo(4500L)
        scene.setState(ScreenPhase.CONNECTING, "", true, RogHardlineScene.StatusMode.RUNNING, 4500L)
        assertThat(statusProgress(scene)).isWithin(1e-9).of(0.75 / 140.0)
        scene.advanceStatusTo(5750L)
        for (phase in ScreenPhase.entries) {
            scene.setState(phase, "", true, RogHardlineScene.StatusMode.RUNNING, 5750L)
            assertThat(statusProgress(scene)).isWithin(1e-9).of(2.0 / 140.0)
            assertThat(scene.statusText).isEqualTo("Connection failed · Press Win + K to retry")
            assertThat(scene.statusAlert).isTrue()
        }
        scene.setState(ScreenPhase.WIFI_PAUSED, "", false, RogHardlineScene.StatusMode.RUNNING, 5750L)
        assertThat(statusProgress(scene)).isWithin(1e-9).of(2.25 / 144.0)
        scene.setState(ScreenPhase.ADVERTISING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 5750L)
        assertThat(statusProgress(scene)).isWithin(1e-9).of(1.5 / 144.0)
        scene.setState(ScreenPhase.FROZEN, "", false, RogHardlineScene.StatusMode.RUNNING, 5750L)
        assertThat(statusProgress(scene)).isEqualTo(0.0)
        scene.setState(ScreenPhase.CONNECTED, "", false, RogHardlineScene.StatusMode.RUNNING, 5750L)
        assertThat(scene.statusText).isNull()
        assertThat(statusProgress(scene)).isEqualTo(0.0)
    }

    /** Raw identity is always refreshed; unknown names suppress copy, not the selected waiting clock. */
    @Test
    fun statusProgress_preservesWaitingTimeAcrossNamesAndRepeatedState() {
        val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        var now = 0L
        scene.setState(ScreenPhase.ADVERTISING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
        now = 500L
        scene.advanceStatusTo(now)
        val name = "  <Desk & \"TV\" / 客廳>  "
        repeat(3) {
            scene.setState(ScreenPhase.ADVERTISING, name, false, RogHardlineScene.StatusMode.RUNNING, now)
            assertThat(scene.statusText).isEqualTo("Windows: press Win + K · Choose $name")
            assertThat(statusProgress(scene)).isWithin(1e-9).of(0.5 / 144.0)
        }
        for (unknown in listOf("", "   ", "\t\n")) {
            scene.setState(ScreenPhase.ADVERTISING, unknown, false, RogHardlineScene.StatusMode.RUNNING, now)
            assertThat(scene.statusText).isNull()
            now += 250L
            scene.advanceStatusTo(now)
        }
        assertThat(statusProgress(scene)).isWithin(1e-9).of(1.25 / 144.0)
        scene.setState(ScreenPhase.FROZEN, "", false, RogHardlineScene.StatusMode.RUNNING, now)
        now += 500L
        scene.advanceStatusTo(now)
        assertThat(statusProgress(scene)).isWithin(1e-9).of(0.5 / 112.0)
        scene.setState(ScreenPhase.ADVERTISING, name, false, RogHardlineScene.StatusMode.RUNNING, now)
        assertThat(scene.statusText).isEqualTo("Windows: press Win + K · Choose $name")
        assertThat(statusProgress(scene)).isWithin(1e-9).of(1.25 / 144.0)
    }

    /** Invalid decorative deltas touch no status slot; model physics retains its 5s/0.05s protections. */
    @Test
    fun statusProgress_ignoresInvalidDeltasAndRetainsCapAndSubstepPhysics() {
        val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        var now = 0L
        for (phase in ScreenPhase.entries) {
            scene.setState(phase, "Studio", false, RogHardlineScene.StatusMode.RUNNING, now)
            now += 250L
            scene.advanceStatusTo(now)
            scene.update(0.25)
        }
        scene.setState(ScreenPhase.READY, "", true, RogHardlineScene.StatusMode.RUNNING, now)
        scene.advanceStatusTo(now + 125L)
        scene.update(0.125)
        val before = statusTimes(scene)
        assertThat(before).hasSize(8)
        for (track in listOf("READY", "ARMING", "WIFI_PAUSED", "FROZEN", "WAITING", "CONNECTING", "PROJECTING")) {
            assertThat(statusScalar(scene, track, "elapsedSeconds")).isWithin(1e-9).of(0.25)
        }
        assertThat(statusScalar(scene, "FAILURE", "elapsedSeconds")).isWithin(1e-9).of(0.125)
        val traces = scene.streams.map { it.copy() }
        for (delta in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) scene.update(delta)
        assertThat(statusTimes(scene)).isEqualTo(before)
        assertThat(scene.streams).isEqualTo(traces)
        val capped = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        val stepped = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        capped.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 0L)
        stepped.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 0L)
        capped.update(Double.MAX_VALUE)
        repeat(100) { stepped.update(0.05) }
        assertThat(statusProgress(capped)).isEqualTo(0.0)
        capped.advanceStatusTo(5000L)
        stepped.advanceStatusTo(5000L)
        assertThat(statusProgress(capped)).isWithin(1e-9).of(5.0 / 148.0)
        assertThat(statusProgress(capped)).isWithin(1e-9).of(statusProgress(stepped))
        for ((actual, expected) in capped.streams.zip(stepped.streams)) {
            assertThat(actual.inShot).isEqualTo(expected.inShot)
            assertThat(actual.gapSeconds).isEqualTo(expected.gapSeconds)
            assertThat(actual.durationSeconds).isEqualTo(expected.durationSeconds)
            assertThat(actual.elapsedSeconds).isWithin(1e-9).of(expected.elapsedSeconds)
            assertThat(actual.progress).isWithin(1e-9).of(expected.progress)
            assertThat(actual.position).isWithin(1e-9).of(expected.position)
            assertThat(actual.alpha).isWithin(1e-9).of(expected.alpha)
        }
        repeat(30) { capped.update(5.0) }
        capped.advanceStatusTo(155000L)
        assertThat(statusProgress(capped)).isWithin(1e-9).of(7.0 / 148.0)
        capped.setState(ScreenPhase.WIFI_PAUSED, "", false, RogHardlineScene.StatusMode.RUNNING, 155000L)
        assertThat(statusProgress(capped)).isEqualTo(0.0)
    }

    /** Old intervals are attributed at state boundaries, including a valid zero anchor and regressing input. */
    @Test
    fun statusTimeline_chargesOldPhaseAtBoundaryWithoutRegressionCredit() {
        val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        scene.setState(ScreenPhase.READY, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 1000L)
        scene.advanceStatusTo(1040L)
        scene.setState(ScreenPhase.ARMING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 1060L)
        scene.advanceStatusTo(1100L)
        assertThat(statusScalar(scene, "READY", "elapsedSeconds")).isWithin(1e-9).of(0.06)
        assertThat(statusScalar(scene, "ARMING", "elapsedSeconds")).isWithin(1e-9).of(0.04)
        val alpha = statusScalar(scene, "ARMING", "alpha")
        scene.advanceStatusTo(1020L)
        scene.setState(ScreenPhase.ARMING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 1020L)
        assertThat(statusScalar(scene, "ARMING", "alpha")).isEqualTo(alpha)
        scene.advanceStatusTo(1160L)
        assertThat(statusScalar(scene, "ARMING", "elapsedSeconds")).isWithin(1e-9).of(0.10)
        scene.advanceStatusTo(1240L)
        assertThat(statusScalar(scene, "ARMING", "alpha")).isEqualTo(1.0)
        val zero = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        zero.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 0L)
        zero.advanceStatusTo(40L)
        assertThat(statusScalar(zero, "READY", "elapsedSeconds")).isWithin(1e-9).of(0.04)
    }

    /** More than two retained slots survive interruption; repeated targets do not extend the original epoch. */
    @Test
    fun statusTransitions_retainTripleLayersAndSameTargetCompletion() {
        val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        scene.setState(ScreenPhase.READY, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 0L)
        assertThat(statusScalar(scene, "READY", "alpha")).isEqualTo(1.0)
        scene.setState(ScreenPhase.ARMING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 100L)
        assertThat(statusScalar(scene, "ARMING", "alpha")).isEqualTo(0.0)
        scene.advanceStatusTo(190L)
        assertThat(statusScalar(scene, "READY", "alpha") + statusScalar(scene, "ARMING", "alpha")).isWithin(1e-9).of(1.0)
        scene.setState(ScreenPhase.FROZEN, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 190L)
        scene.advanceStatusTo(200L)
        for (track in listOf("READY", "ARMING", "FROZEN")) assertThat(statusScalar(scene, track, "alpha")).isGreaterThan(0.0)
        scene.setState(ScreenPhase.FROZEN, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 280L)
        scene.advanceStatusTo(370L)
        assertThat(statusScalar(scene, "FROZEN", "alpha")).isEqualTo(1.0)
        assertThat(statusScalar(scene, "READY", "alpha")).isEqualTo(0.0)
        assertThat(statusScalar(scene, "ARMING", "alpha")).isEqualTo(0.0)
    }

    /** Opacity may finish before delayed visibility; a same-target event must not restart either deadline. */
    @Test
    fun statusTransitions_shortenReversalButKeepFullVisibilityDelay() {
        val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        scene.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 0L)
        scene.setState(ScreenPhase.ARMING, "", false, RogHardlineScene.StatusMode.RUNNING, 100L)
        scene.advanceStatusTo(160L)
        val ready = statusScalar(scene, "READY", "alpha")
        val arming = statusScalar(scene, "ARMING", "alpha")
        scene.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 160L)
        assertThat(statusScalar(scene, "READY", "alpha")).isEqualTo(ready)
        assertThat(statusScalar(scene, "ARMING", "alpha")).isEqualTo(arming)
        assertThat(statusScalar(scene, "ARMING", "durationMillis")).isGreaterThan(0.0)
        assertThat(statusScalar(scene, "ARMING", "durationMillis")).isLessThan(180.0)
        scene.setState(ScreenPhase.READY, "", false, RogHardlineScene.StatusMode.RUNNING, 200L)
        scene.advanceStatusTo(300L)
        assertThat(statusScalar(scene, "ARMING", "alpha")).isEqualTo(0.0)
        assertThat(statusVisible(scene, "ARMING")).isTrue()
        scene.advanceStatusTo(339L)
        assertThat(statusVisible(scene, "ARMING")).isTrue()
        scene.advanceStatusTo(340L)
        assertThat(statusVisible(scene, "ARMING")).isFalse()
    }

    /** Media pauses only marquee; explicit stop snaps; removal resets status without touching simulation. */
    @Test
    fun statusModes_distinguishMediaStopAndRemovalWithoutResettingPhysics() {
        val scene = RogHardlineScene(random = { 0.5 }, statusCopy = testStatusCopy())
        scene.setState(ScreenPhase.READY, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 0L)
        scene.update(0.5)
        val traces = scene.streams.map { it.copy() }
        scene.setState(ScreenPhase.ARMING, "Studio", false, RogHardlineScene.StatusMode.RUNNING, 1000L)
        scene.setStatusMode(RogHardlineScene.StatusMode.MEDIA_PAUSED, 1045L)
        scene.advanceStatusTo(1445L)
        assertThat(statusScalar(scene, "ARMING", "elapsedSeconds")).isWithin(1e-9).of(0.045)
        assertThat(statusScalar(scene, "ARMING", "alpha")).isEqualTo(1.0)
        scene.setState(ScreenPhase.FROZEN, "Studio", false, RogHardlineScene.StatusMode.STOPPED, 1445L)
        assertThat(statusScalar(scene, "FROZEN", "alpha")).isEqualTo(1.0)
        assertThat(statusScalar(scene, "ARMING", "alpha")).isEqualTo(0.0)
        scene.setStatusMode(RogHardlineScene.StatusMode.RUNNING, 2000L)
        scene.advanceStatusTo(2200L)
        assertThat(statusScalar(scene, "FROZEN", "elapsedSeconds")).isWithin(1e-9).of(0.2)
        scene.setStatusMode(RogHardlineScene.StatusMode.REMOVED, 2200L)
        assertThat(statusTimes(scene).all { it == 0.0 }).isTrue()
        scene.setStatusMode(RogHardlineScene.StatusMode.RUNNING, 2800L)
        assertThat(statusScalar(scene, "FROZEN", "alpha")).isEqualTo(1.0)
        scene.advanceStatusTo(3000L)
        assertThat(statusScalar(scene, "FROZEN", "elapsedSeconds")).isWithin(1e-9).of(0.2)
        assertThat(scene.streams).isEqualTo(traces)
        val elapsed = RogHardlineScene::class.java.getDeclaredField("activeElapsedSeconds").apply { isAccessible = true }.getDouble(scene)
        assertThat(elapsed).isWithin(1e-9).of(0.5)
    }

    /** @return Private visibility only, used to distinguish the full deadline from shortened opacity. */
    private fun statusVisible(scene: RogHardlineScene, track: String): Boolean {
        val layers = RogHardlineScene::class.java.getDeclaredField("statusLayers").apply { isAccessible = true }.get(scene) as Array<*>
        val layer = requireNotNull(layers.single {
            val value = requireNotNull(it)
            (value.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(value) as Enum<*>).name == track
        })
        return layer.javaClass.getDeclaredField("visible").apply { isAccessible = true }.getBoolean(layer)
    }

    /** @return Frozen counters in DOM order, without a production diagnostic API. */
    private fun statusTimes(scene: RogHardlineScene): List<Double> =
        (RogHardlineScene::class.java.getDeclaredField("statusLayers").apply { isAccessible = true }.get(scene) as Array<*>).map {
            val layer = requireNotNull(it)
            layer.javaClass.getDeclaredField("elapsedSeconds").apply { isAccessible = true }.getDouble(layer)
        }

    /** @return A private scalar of a named fixed slot; expectations never query the production easing helper. */
    private fun statusScalar(scene: RogHardlineScene, track: String, property: String): Double {
        val layers = RogHardlineScene::class.java.getDeclaredField("statusLayers").apply { isAccessible = true }.get(scene) as Array<*>
        val layer = requireNotNull(layers.single {
            val value = requireNotNull(it)
            (value.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(value) as Enum<*>).name == track
        })
        return layer.javaClass.getDeclaredField(property).apply { isAccessible = true }.getDouble(layer)
    }

    /** @return Selected phase progress using the immutable duration consumed by the painter. */
    private fun statusProgress(scene: RogHardlineScene): Double {
        val track = RogHardlineScene::class.java.getDeclaredField("statusTrack").apply { isAccessible = true }.get(scene) as Enum<*>
        val duration = track.javaClass.getDeclaredField("durationSeconds").apply { isAccessible = true }.getDouble(track)
        return (statusScalar(scene, track.name, "elapsedSeconds") % duration) / duration
    }

    @Test
    fun statusRowFollowsEachRealPhase() {
        val scene = RogHardlineScene(statusCopy = testStatusCopy())
        val expected = mapOf(
            ScreenPhase.READY to "Press the broadcast button to start receiving",
            ScreenPhase.ARMING to "Starting · Not ready",
            ScreenPhase.WIFI_PAUSED to "Wi-Fi is off · Please turn on Wi-Fi to broadcast",
            ScreenPhase.FROZEN to "Receiver unavailable · Check settings",
            ScreenPhase.ADVERTISING to "Windows: press Win + K · Choose MIRAX · 客廳",
            ScreenPhase.CONNECTING to "Connecting · Please wait",
        )
        for ((phase, text) in expected) {
            scene.setState(phase, "MIRAX · 客廳", hasFailure = false, mode = RogHardlineScene.StatusMode.RUNNING, nowUptimeMillis = 0L)
            assertThat(scene.statusText).isEqualTo(text)
            if (phase != ScreenPhase.ADVERTISING) {
                for (receiverName in listOf("", "   ")) {
                    scene.setState(phase, receiverName, hasFailure = false, mode = RogHardlineScene.StatusMode.RUNNING, nowUptimeMillis = 0L)
                    assertThat(scene.statusText).isEqualTo(text)
                }
            }
        }
    }

    @Test
    fun advertisingStatusInterpolatesRealReceiverName() {
        val scene = RogHardlineScene(statusCopy = testStatusCopy())
        for (receiverName in listOf("", "   ", "\t\n")) {
            scene.setState(ScreenPhase.ADVERTISING, "MIRAX · Study", hasFailure = false, mode = RogHardlineScene.StatusMode.RUNNING, nowUptimeMillis = 0L)
            assertThat(scene.statusText).isEqualTo("Windows: press Win + K · Choose MIRAX · Study")
            scene.setState(ScreenPhase.ADVERTISING, receiverName, hasFailure = false, mode = RogHardlineScene.StatusMode.RUNNING, nowUptimeMillis = 0L)
            assertThat(scene.statusText).isNull()
            assertThat(scene.statusAlert).isFalse()
        }
        val realName = "  MIRAX · Study  "
        scene.setState(ScreenPhase.ADVERTISING, realName, hasFailure = false, mode = RogHardlineScene.StatusMode.RUNNING, nowUptimeMillis = 0L)
        assertThat(scene.statusText).isEqualTo("Windows: press Win + K · Choose $realName")
    }

    @Test
    fun failureOverridesPhaseWithRetryCopy() {
        val scene = RogHardlineScene(statusCopy = testStatusCopy())
        for (phase in ScreenPhase.entries) {
            for (receiverName in listOf("MIRAX", "", "   ")) {
                scene.setState(phase, receiverName, hasFailure = true, mode = RogHardlineScene.StatusMode.RUNNING, nowUptimeMillis = 0L)
                assertThat(scene.statusText).isEqualTo("Connection failed · Press Win + K to retry")
                assertThat(scene.statusAlert).isTrue()
            }
        }
    }

    @Test
    fun statusTurnsAlertOnFailureOrFrozenOnly() {
        val scene = RogHardlineScene(statusCopy = testStatusCopy())
        scene.setState(ScreenPhase.ADVERTISING, "MIRAX", hasFailure = false, mode = RogHardlineScene.StatusMode.RUNNING, nowUptimeMillis = 0L)
        assertThat(scene.statusAlert).isFalse()

        scene.setState(ScreenPhase.FROZEN, "MIRAX", hasFailure = false, mode = RogHardlineScene.StatusMode.RUNNING, nowUptimeMillis = 0L)
        assertThat(scene.statusAlert).isTrue()

        scene.setState(ScreenPhase.READY, "MIRAX", hasFailure = true, mode = RogHardlineScene.StatusMode.RUNNING, nowUptimeMillis = 0L)
        assertThat(scene.statusAlert).isTrue()
    }

    @Test
    fun statusRowIsHiddenWhileProjecting() {
        val scene = RogHardlineScene(statusCopy = testStatusCopy())
        scene.setState(ScreenPhase.CONNECTED, "MIRAX", hasFailure = false, mode = RogHardlineScene.StatusMode.RUNNING, nowUptimeMillis = 0L)
        assertThat(scene.statusText).isNull()
        assertThat(scene.statusAlert).isFalse()
    }

    @Test
    fun statusRowUsesPrototypeBoldTypographyAndSize() {
        val scene = RogHardlineScene(statusCopy = testStatusCopy())
        assertThat(scene.statusTypefaceStyle).isEqualTo(Typeface.BOLD)
        assertThat(RogHardlineScene.STATUS_TEXT_SIZE).isWithin(1e-3f).of(14.667f)
    }

    @Test
    fun channelBandsMatchPrototypeV37HeightsAndOffsets() {
        val viewport = StandbyViewportScale(2400, 1080, density = 1.5f, cssPixels = true)
        val channelSize = 842.4f
        val upper = RogHardlineScene.upperChannel(viewport, channelSize)
        val lower = RogHardlineScene.lowerChannel(viewport, channelSize)

        assertThat(RogHardlineScene.UPPER_HEIGHT_PX).isEqualTo(80f)
        assertThat(RogHardlineScene.LOWER_HEIGHT_PX).isEqualTo(52f)
        assertThat(upper.pxHeight).isWithin(1e-3f).of(120f)
        assertThat(lower.pxHeight).isWithin(1e-3f).of(78f)
        assertThat(RogHardlineScene.TRACE_WIDTH_PX).isEqualTo(1f)
        assertThat(RogHardlineScene.CHANNEL_SPAN_FACTOR).isEqualTo(2f)
        assertThat(upper.offset).isWithin(1e-3f).of(-0.59f * channelSize)
        assertThat(lower.offset)
            .isWithin(1e-3f)
            .of(0.59f * channelSize - 24f)
        assertThat(upper.bottom - upper.top).isWithin(1e-3f).of(upper.pxHeight)
    }

    @Test
    fun centeredRowsKeepBreathingMarginInsideEachBand() {
        // 16 + 3 + 16 + 3 + 24 inside 80px, 16 + 3 + 16 inside 52px.
        assertThat(RogHardlineScene.UPPER_CONTENT_PX)
            .isLessThan(RogHardlineScene.UPPER_HEIGHT_PX)
        assertThat(RogHardlineScene.LOWER_CONTENT_PX)
            .isLessThan(RogHardlineScene.LOWER_HEIGHT_PX)
    }

    @Test
    fun scanTracesTravelOnlyAcrossVisibleBandIntersection() {
        val originX = 2400f * RogHardlineScene.ORIGIN_X_FRACTION
        val originY = 1080f * RogHardlineScene.ORIGIN_Y_FRACTION
        val viewport = StandbyViewportScale(2400, 1080, density = 1.5f, cssPixels = true)
        val channelSize = minOf(
            2400f * RogHardlineScene.CHANNEL_WIDTH_FRACTION,
            1080f * RogHardlineScene.CHANNEL_HEIGHT_FRACTION,
        )
        val upper = RogHardlineScene.upperChannel(viewport, channelSize)
        val topStart = RogHardlineScene.scanStart(originX, originY, 2400f, 1080f, upper.top)
        val topEnd = RogHardlineScene.scanEnd(originX, originY, 2400f, 1080f, upper.top)

        assertThat(topEnd).isGreaterThan(topStart)
        // Travel covers the visible span plus one trace length: the trace enters
        // at scanStart and clears scanEnd by one trace length at position 1.
        val traceLength = 72f
        val travel = topEnd - topStart + traceLength
        assertThat(RogHardlineScene.traceHeadX(topStart, topEnd, traceLength, 1.0))
            .isWithin(1e-3f)
            .of(topEnd + traceLength)
        assertThat(RogHardlineScene.traceHeadX(topStart, topEnd, traceLength, 0.0))
            .isWithin(1e-3f)
            .of(topStart)
        assertThat(
            RogHardlineScene.traceHeadX(topStart, topEnd, traceLength, 1.0) -
                RogHardlineScene.traceHeadX(topStart, topEnd, traceLength, 0.0),
        ).isWithin(1e-3f).of(travel)

        // The bottom edge keeps its own intersection and never inverts the sweep.
        val bottomStart = RogHardlineScene.scanStart(originX, originY, 2400f, 1080f, upper.bottom)
        val bottomEnd = RogHardlineScene.scanEnd(originX, originY, 2400f, 1080f, upper.bottom)
        assertThat(bottomEnd).isGreaterThan(bottomStart)
        assertThat(bottomStart).isGreaterThan(topStart)
        assertThat(bottomEnd).isGreaterThan(topEnd)
    }

    @Test
    fun refreshStatusCopy_updatesActiveAndCachedCopiesWithoutResettingTracks() {
        val enCopy = testStatusCopy()
        val zhCopy = RogHardlineScene.StatusCopy(
            ready = "按下廣播按鈕以開始接收",
            arming = "啟動中 · 尚未就緒",
            wifiPaused = "Wi-Fi 已關閉 · 請開啟 Wi-Fi 以廣播",
            frozen = "接收不可用 · 請檢查設定",
            connecting = "正在連接 · 請稍候",
            failure = "連接未成功 · 按 Win + K 重試",
            formatAdvertising = { name -> "Windows 按 Win + K，選擇「$name」" },
        )
        val prePhase = RogHardlineScene(random = { 0.5 }, statusCopy = enCopy)
        prePhase.refreshStatusCopy(zhCopy)
        assertThat(prePhase.statusText).isNull()
        assertThat(statusTimes(prePhase).all { it == 0.0 }).isTrue()

        val scene = RogHardlineScene(random = { 0.5 }, statusCopy = enCopy)
        scene.setState(ScreenPhase.ADVERTISING, "100% 客廳", false, RogHardlineScene.StatusMode.RUNNING, 0L)
        scene.advanceStatusTo(40L)
        scene.setState(ScreenPhase.READY, "100% 客廳", false, RogHardlineScene.StatusMode.RUNNING, 40L)
        scene.advanceStatusTo(130L)
        val waitingElapsed = statusScalar(scene, "WAITING", "elapsedSeconds")
        val readyElapsed = statusScalar(scene, "READY", "elapsedSeconds")
        val waitingAlpha = statusScalar(scene, "WAITING", "alpha")
        val readyAlpha = statusScalar(scene, "READY", "alpha")

        scene.refreshStatusCopy(zhCopy)

        assertThat(scene.statusText).isEqualTo("按下廣播按鈕以開始接收")
        assertThat(statusScalar(scene, "WAITING", "elapsedSeconds")).isEqualTo(waitingElapsed)
        assertThat(statusScalar(scene, "READY", "elapsedSeconds")).isEqualTo(readyElapsed)
        assertThat(statusScalar(scene, "WAITING", "alpha")).isEqualTo(waitingAlpha)
        assertThat(statusScalar(scene, "READY", "alpha")).isEqualTo(readyAlpha)
        assertThat(layerText(scene, "WAITING")).isEqualTo("Windows 按 Win + K，選擇「100% 客廳」")
        assertThat(layerText(scene, "READY")).isEqualTo("按下廣播按鈕以開始接收")

        scene.setState(ScreenPhase.ADVERTISING, "   ", false, RogHardlineScene.StatusMode.RUNNING, 250L)
        scene.refreshStatusCopy(enCopy)
        assertThat(scene.statusText).isNull()
        assertThat(layerText(scene, "WAITING")).isNull()
    }

    private fun layerText(scene: RogHardlineScene, track: String): String? {
        val layers = RogHardlineScene::class.java.getDeclaredField("statusLayers").apply { isAccessible = true }.get(scene) as Array<*>
        val layer = requireNotNull(layers.single {
            val value = requireNotNull(it)
            (value.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(value) as Enum<*>).name == track
        })
        val runs = layer.javaClass.getDeclaredField("runs").apply { isAccessible = true }.get(layer) as List<*>
        return runs.firstOrNull()?.let { run ->
            run.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(run) as String
        }
    }

    private fun testStatusCopy(): RogHardlineScene.StatusCopy = RogHardlineScene.StatusCopy(
        ready = "Press the broadcast button to start receiving",
        arming = "Starting · Not ready",
        wifiPaused = "Wi-Fi is off · Please turn on Wi-Fi to broadcast",
        frozen = "Receiver unavailable · Check settings",
        connecting = "Connecting · Please wait",
        failure = "Connection failed · Press Win + K to retry",
        formatAdvertising = { name -> "Windows: press Win + K · Choose $name" },
    )
}
