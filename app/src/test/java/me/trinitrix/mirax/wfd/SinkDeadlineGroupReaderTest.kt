package me.trinitrix.mirax.wfd

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import me.trinitrix.mirax.session.WfdOwner
import org.junit.Test

/** The offer deadline must not multiply blocked V2 observation workers. */
class SinkDeadlineGroupReaderTest {
    @Test
    fun continuousTrafficPollsOnMonotonicDueWithOneBoundedRead() {
        val tasks = java.util.ArrayDeque<Runnable>()
        var epoch = "01234567-89ab-cdef-0123-456789abcdef-1"
        val adapter = SocketHelperAdapter { command ->
            if (command == "GROUP_V2") {
                "V2 $epoch UP 192.168.49.1"
            } else {
                "QUEUED"
            }
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = Executor { it.run() }) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val original = checkNotNull(route.snapshot())
        val watch = SinkLiveGroupWatch(Executor { tasks.addLast(it) }, route::snapshot, 500L) { _, err -> throw err }
        repeat(500) { now -> assertThat(watch.poll(now.toLong())).isNull() }
        assertThat(tasks).hasSize(1)
        tasks.removeFirst().run()
        val first = checkNotNull(watch.poll(499L))
        assertThat(compareSinkGroup(original, first.snapshot)).isEqualTo(SinkGroupRelation.SAME)
        assertThat(watch.poll(500L)).isNull()
        repeat(1_000) { now -> assertThat(watch.poll(501L + now)).isNull() }
        assertThat(tasks).hasSize(1)
        route.updateEndpoint(WfdOwner.HELPER) {}
        tasks.removeFirst().run()
        val replacement = checkNotNull(watch.poll(1_501L))
        assertThat(compareSinkGroup(original, replacement.snapshot)).isEqualTo(SinkGroupRelation.SUPERSEDED)
        assertThat(tasks).isEmpty()
        val rebound = checkNotNull(replacement.snapshot)
        epoch = "01234567-89ab-cdef-0123-456789abcdef-2"
        assertThat(watch.poll(1_501L)).isNull()
        tasks.removeFirst().run()
        val newEpoch = checkNotNull(watch.poll(1_502L))
        assertThat(compareSinkGroup(rebound, newEpoch.snapshot)).isEqualTo(SinkGroupRelation.SUPERSEDED)
    }

    @Test
    fun unavailableFinalObservationWaitsForSameOrDownWithoutEndingOrFailing() {
        val original = OwnerGroupSnapshot(
            WfdOwner.HELPER, P2pGroupState.Up("192.168.49.1"),
            "01234567-89ab-cdef-0123-456789abcdef-1", 1L,
        )
        val samples = listOf(null, null, original)
        var index = 0
        var waits = 0
        var ends = 0
        var failures = 0
        val relation = awaitSinkGroupRelation(
            isCurrent = { true },
            observe = {
                assertThat(ends).isEqualTo(0)
                assertThat(failures).isEqualTo(0)
                compareSinkGroup(original, samples[index++])
            },
            wait = { waits++ },
        )
        if (relation == SinkGroupRelation.SAME) {
            ends++
            failures++
        }
        assertThat(waits).isEqualTo(2)
        assertThat(ends).isEqualTo(1)
        assertThat(failures).isEqualTo(1)
        val downSamples = listOf(null, original.copy(state = P2pGroupState.Down))
        var downIndex = 0
        assertThat(awaitSinkGroupRelation(
            { true }, { compareSinkGroup(original, downSamples[downIndex++]) }, {},
        )).isEqualTo(SinkGroupRelation.DOWN)
        assertThat(awaitSinkGroupRelation({ false }, { error("must not observe") }, {}))
            .isEqualTo(SinkGroupRelation.UNKNOWN)
    }

    @Test
    fun timedOutObservationStaysSingleFlightAndLateValueIsNotReturned() {
        val tasks = mutableListOf<Runnable>()
        val failures = mutableListOf<String>()
        val snapshot = OwnerGroupSnapshot(
            WfdOwner.HELPER,
            P2pGroupState.Up("192.168.49.1"),
            "01234567-89ab-cdef-0123-456789abcdef-1",
            1L,
        )
        var reads = 0
        val reader = SinkDeadlineGroupReader(
            executor = Executor { tasks.add(it) },
            read = { reads++; snapshot },
            logFailure = { message, _ -> failures.add(message) },
            awaitResult = { _, _ -> throw TimeoutException() },
        )
        assertThat(reader.snapshot(1_000)).isNull()
        assertThat(reader.snapshot(1_000)).isNull()
        assertThat(tasks).hasSize(1)
        assertThat(reads).isEqualTo(0)
        assertThat(failures).hasSize(1)
        tasks.removeAt(0).run()
        assertThat(reads).isEqualTo(1)
        assertThat(reader.snapshot(1_000)).isNull()
        assertThat(tasks).hasSize(1)
    }
}
