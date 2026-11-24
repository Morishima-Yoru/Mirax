package me.trinitrix.mirax.wfd

import android.os.IBinder
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.shell.IMiraxShellService
import org.junit.Test

class OwnerGroupProtocolTest {
    private val epoch = "01234567-89ab-cdef-0123-456789abcdef-1"
    private val up = "V2 $epoch UP 192.168.49.1"

    @Test
    fun socketOldHelperAndUnavailable_failClosedWithoutLegacyEnd() {
        val commands = mutableListOf<String>()
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            "ERR unknown"
        }
        assertThat(adapter.groupSnapshotV2()).isNull()
        val snapshot = requireNotNull(OwnerGroupSnapshot.parse(WfdOwner.HELPER, up))
        assertThat(adapter.endSessionIfEpoch(snapshot)).isFalse()
        assertThat(commands).containsExactly("GROUP_V2", "END_V2 $epoch").inOrder()

        val unavailable = SocketHelperAdapter { null }
        assertThat(unavailable.groupSnapshotV2()).isNull()
        assertThat(unavailable.endSessionIfEpoch(snapshot)).isFalse()
    }

    @Test
    fun socket_guardedCommandRequiresMatchingOwnerAndQueuedReply() {
        val commands = mutableListOf<String>()
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            if (command == "GROUP_V2") up else "QUEUED"
        }
        val snapshot = requireNotNull(adapter.groupSnapshotV2())
        assertThat(adapter.endSessionIfEpoch(snapshot)).isTrue()
        assertThat(adapter.endSessionIfEpoch(snapshot.copy(owner = WfdOwner.SHIZUKU))).isFalse()
        assertThat(adapter.endSessionIfEpoch(snapshot.copy(state = P2pGroupState.Down))).isFalse()
        assertThat(adapter.endSessionIfEpoch(snapshot.copy(epoch = "$epoch\nEND"))).isFalse()
        assertThat(commands).containsExactly("GROUP_V2", "END_V2 $epoch").inOrder()
    }

    @Test
    fun route_rejectsOwnerChangesIncludingAwayAndBack() {
        val commands = mutableListOf<String>()
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            if (command == "GROUP_V2") up else "QUEUED"
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = java.util.concurrent.Executor { it.run() }) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        assertThat(route.snapshot()).isNull()
        route.switchOwner(WfdOwner.HELPER)
        val snapshot = requireNotNull(route.snapshot())
        route.switchOwner(WfdOwner.HELPER)
        assertThat(route.end(snapshot)).isTrue()
        route.switchOwner(WfdOwner.SHIZUKU)
        assertThat(route.end(snapshot)).isFalse()
        route.switchOwner(WfdOwner.HELPER)
        assertThat(route.end(snapshot)).isFalse()
        assertThat(commands).containsExactly("GROUP_V2", "END_V2 $epoch").inOrder()
    }

    @Test(timeout = 5_000)
    fun blockedSnapshot_awayAndBackDiscardsLateReply() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val switched = CountDownLatch(1)
        val result = AtomicReference<OwnerGroupSnapshot?>(null)
        val adapter = SocketHelperAdapter { command ->
            if (command == "GROUP_V2") {
                entered.countDown()
                release.await()
                up
            } else {
                "QUEUED"
            }
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), snapshotTimeoutMs = 3_000L) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        Thread {
            result.set(route.snapshot())
            finished.countDown()
        }.start()
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
            Thread {
                route.switchOwner(WfdOwner.SHIZUKU)
                route.switchOwner(WfdOwner.HELPER)
                switched.countDown()
            }.apply { isDaemon = true }.start()
            assertThat(switched.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(finished.count).isEqualTo(1L)
        } finally {
            release.countDown()
        }
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(result.get()).isNull()
    }

    @Test(timeout = 5_000)
    fun blockedSnapshot_sameOwnerRebindDiscardsLateReply() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val rebound = CountDownLatch(1)
        val result = AtomicReference<OwnerGroupSnapshot?>(null)
        val service = Proxy.newProxyInstance(
            IMiraxShellService::class.java.classLoader,
            arrayOf(IMiraxShellService::class.java),
        ) { _, method, _ ->
            check(method.name == "groupSnapshotV2")
            entered.countDown()
            release.await()
            up
        } as IMiraxShellService
        val adapter = ShizukuOwnerAdapter { service }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), snapshotTimeoutMs = 3_000L) { owner ->
            if (owner == WfdOwner.SHIZUKU) adapter else null
        }
        route.switchOwner(WfdOwner.SHIZUKU)
        Thread {
            result.set(route.snapshot())
            finished.countDown()
        }.start()
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
            Thread {
                route.updateEndpoint(WfdOwner.SHIZUKU) {}
                rebound.countDown()
            }.apply { isDaemon = true }.start()
            assertThat(rebound.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(finished.count).isEqualTo(1L)
        } finally {
            release.countDown()
        }
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(result.get()).isNull()
    }

    @Test
    fun queuedEnd_isDiscardedAfterSwitchBeforeDispatch() {
        val commands = mutableListOf<String>()
        val pending = java.util.ArrayDeque<Runnable>()
        var immediate = true
        val executor = Executor { task -> if (immediate) task.run() else pending.addLast(task) }
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            if (command == "GROUP_V2") up else "QUEUED"
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), worker = executor) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val snapshot = requireNotNull(route.snapshot())
        immediate = false
        assertThat(route.end(snapshot)).isTrue()
        route.switchOwner(WfdOwner.SHIZUKU)
        route.switchOwner(WfdOwner.HELPER)
        pending.removeFirst().run()
        assertThat(commands).containsExactly("GROUP_V2")
        assertThat(route.end(snapshot)).isFalse()
    }

    @Test
    fun localAcceptance_doesNotClaimRemoteQueueOrFallback() {
        val commands = mutableListOf<String>()
        val pending = java.util.ArrayDeque<Runnable>()
        val route = OwnerGroupRoute(
            AtomicReference(WfdOwner.NONE),
            worker = Executor { task -> pending.addLast(task) },
        ) { owner ->
            if (owner != WfdOwner.HELPER) null else SocketHelperAdapter { command ->
                commands.add(command)
                "ERR unknown"
            }
        }
        route.switchOwner(WfdOwner.HELPER)
        val snapshot = requireNotNull(OwnerGroupSnapshot.parse(WfdOwner.HELPER, up)).copy(ownerRevision = 1L)
        assertThat(route.end(snapshot)).isTrue()
        assertThat(commands).isEmpty()
        pending.removeFirst().run()
        assertThat(commands).containsExactly("END_V2 $epoch")
    }

    @Test(timeout = 5_000)
    fun blockedEnd_allowsSwitchAndDoesNotCreateAnotherWorker() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val switched = CountDownLatch(1)
        val busyResult = AtomicReference<Boolean>()
        val staleResult = AtomicReference<Boolean>()
        val commands = mutableListOf<String>()
        val adapter = SocketHelperAdapter { command ->
            commands.add(command)
            if (command == "GROUP_V2") {
                up
            } else {
                entered.countDown()
                release.await()
                exited.countDown()
                "QUEUED"
            }
        }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE)) { owner ->
            if (owner == WfdOwner.HELPER) adapter else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val snapshot = requireNotNull(OwnerGroupSnapshot.parse(WfdOwner.HELPER, up)).copy(ownerRevision = 1L)
        assertThat(route.end(snapshot)).isTrue()
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
            Thread {
                busyResult.set(route.end(snapshot))
                route.switchOwner(WfdOwner.SHIZUKU)
                route.switchOwner(WfdOwner.HELPER)
                staleResult.set(route.end(snapshot))
                switched.countDown()
            }.apply { isDaemon = true }.start()
            assertThat(switched.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(exited.count).isEqualTo(1L)
            assertThat(busyResult.get()).isFalse()
            assertThat(staleResult.get()).isFalse()
        } finally {
            release.countDown()
        }
        assertThat(exited.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(commands).containsExactly("END_V2 $epoch")
    }

    @Test(timeout = 5_000)
    fun committedEnd_keepsCapturedEndpointAcrossReplacement() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val rebound = CountDownLatch(1)
        val newCalls = mutableListOf<String>()
        val oldAdapter = SocketHelperAdapter { command ->
            entered.countDown()
            release.await()
            exited.countDown()
            if (command == "END_V2 $epoch") "QUEUED" else "ERR unknown"
        }
        val newAdapter = SocketHelperAdapter { command ->
            newCalls.add(command)
            "QUEUED"
        }
        var endpoint = oldAdapter
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE)) { owner ->
            if (owner == WfdOwner.HELPER) endpoint else null
        }
        route.switchOwner(WfdOwner.HELPER)
        val snapshot = requireNotNull(OwnerGroupSnapshot.parse(WfdOwner.HELPER, up)).copy(ownerRevision = 1L)
        assertThat(route.end(snapshot)).isTrue()
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
            Thread {
                route.updateEndpoint(WfdOwner.HELPER) { endpoint = newAdapter }
                rebound.countDown()
            }.apply { isDaemon = true }.start()
            assertThat(rebound.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(exited.count).isEqualTo(1L)
        } finally {
            release.countDown()
        }
        assertThat(exited.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(newCalls).isEmpty()
        assertThat(route.end(snapshot)).isFalse()
    }

    @Test(timeout = 5_000)
    fun snapshotTimeout_doesNotReleaseOccupiedBinderSlot() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val result = AtomicReference<OwnerGroupSnapshot?>(null)
        val calls = mutableListOf<String>()
        val service = Proxy.newProxyInstance(
            IMiraxShellService::class.java.classLoader,
            arrayOf(IMiraxShellService::class.java),
        ) { _, method, _ ->
            calls.add(method.name)
            if (method.name == "groupSnapshotV2") {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
                up
            } else {
                error("unexpected Binder call: ${method.name}")
            }
        } as IMiraxShellService
        val adapter = ShizukuOwnerAdapter { service }
        val route = OwnerGroupRoute(AtomicReference(WfdOwner.NONE), snapshotTimeoutMs = 100L) { owner ->
            if (owner == WfdOwner.SHIZUKU) adapter else null
        }
        route.switchOwner(WfdOwner.SHIZUKU)
        val snapshot = requireNotNull(OwnerGroupSnapshot.parse(WfdOwner.SHIZUKU, up)).copy(ownerRevision = 1L)
        Thread {
            result.set(route.snapshot())
            returned.countDown()
        }.start()
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(result.get()).isNull()
        assertThat(route.end(snapshot)).isFalse()
        assertThat(route.snapshot()).isNull()
        assertThat(calls).containsExactly("groupSnapshotV2")
        release.countDown()
    }

    @Test
    fun oldBinderException_neverFallsBackToLegacyEnd() {
        val calls = mutableListOf<String>()
        val service = Proxy.newProxyInstance(
            IMiraxShellService::class.java.classLoader,
            arrayOf(IMiraxShellService::class.java),
        ) { _, method, _ ->
            calls.add(method.name)
            throw UnsupportedOperationException("old binder")
        } as IMiraxShellService
        val adapter = ShizukuOwnerAdapter { service }
        assertThat(adapter.groupSnapshotV2()).isNull()
        val snapshot = requireNotNull(OwnerGroupSnapshot.parse(WfdOwner.SHIZUKU, up))
        assertThat(adapter.endSessionIfEpoch(snapshot)).isFalse()
        assertThat(calls).containsExactly("groupSnapshotV2", "endSessionIfEpoch").inOrder()
    }

    @Test
    fun aidlTransactionIds_remainStableAndAppendV2() {
        val expected = mapOf(
            "advertise" to 1,
            "stopAdvertise" to 2,
            "endSession" to 3,
            "groupState" to 4,
            "wmSize" to 5,
            "forgetAllPairings" to 6,
            "getPairingState" to 7,
            "isListening" to 8,
            "groupSnapshotV2" to 9,
            "endSessionIfEpoch" to 10,
            "destroy" to 16777114,
        )
        expected.forEach { (name, id) ->
            val field = IMiraxShellService.Stub::class.java.getDeclaredField("TRANSACTION_$name")
            field.isAccessible = true
            assertThat(field.getInt(null)).isEqualTo(IBinder.FIRST_CALL_TRANSACTION + id)
        }
    }
}
