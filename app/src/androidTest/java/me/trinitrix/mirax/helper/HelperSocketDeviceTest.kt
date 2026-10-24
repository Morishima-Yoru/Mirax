package me.trinitrix.mirax.helper

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.host.HelperClient
import me.trinitrix.mirax.host.HelperUnavailableException
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises Android LocalSocket only when the orchestrator selects an isolated or live helper. */
@RunWith(AndroidJUnit4::class)
class HelperSocketDeviceTest {
    /** Requires no helper: the real JNI connect error must not block a Shizuku-only hand-off. */
    @Test(timeout = 5_000)
    fun absentListener_is_classified_at_connect_before_any_command() {
        assumeTrue(testMode() == "isolated")
        assertThrows(HelperUnavailableException::class.java) {
            HelperClient.exchange("UID", timeoutMs = 1_000)
        }
    }

    /** Requires no privileged helper: a stalled reply must expire and the next exchange succeed. */
    @Test(timeout = 10_000)
    fun stalledReply_expires_and_next_exchange_succeeds() {
        assumeTrue(testMode() == "isolated")
        LocalServerSocket(Helper.SOCKET_NAME).use { server ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val served = worker.submit {
                    server.accept().use { client ->
                        client.soTimeout = 2_000
                        assertThat(client.inputStream.bufferedReader().readLine()).isEqualTo("UID")
                        // Wait for the production client's deadline to close this unresponsive exchange.
                        while (client.inputStream.read() != -1) Unit
                    }
                    server.accept().use { client ->
                        client.soTimeout = 2_000
                        assertThat(client.inputStream.bufferedReader().readLine()).isEqualTo("UID")
                        client.outputStream.write("2000\n".toByteArray(Charsets.UTF_8))
                        client.outputStream.flush()
                    }
                }
                val started = SystemClock.elapsedRealtime()
                assertThrows(IOException::class.java) { HelperClient.exchange("UID", timeoutMs = 250) }
                val elapsed = SystemClock.elapsedRealtime() - started
                assertThat(elapsed).isLessThan(1_500L)
                assertThat(HelperClient.exchange("UID", timeoutMs = 2_000)).isEqualTo("2000")
                served.get(3, TimeUnit.SECONDS)
                Log.i("MiraxHelperDevice", "isolated deadline elapsedMs=$elapsed uid=${Process.myUid()}")
            } finally {
                server.close()
                worker.shutdownNow()
            }
        }
    }

    /** Requires the reviewed privileged helper: partial frames cannot starve a healthy UID query. */
    @Test(timeout = 15_000)
    fun partialClients_expire_without_starving_UID() {
        assumeTrue(testMode() == "live")
        val stalled = List(2) { LocalSocket() }
        try {
            stalled.forEach { client ->
                client.connect(LocalSocketAddress(Helper.SOCKET_NAME))
                client.soTimeout = Helper.COMMAND_TIMEOUT_MS + 2_000
                client.outputStream.write("UI".toByteArray(Charsets.UTF_8))
                client.outputStream.flush()
            }
            val started = SystemClock.elapsedRealtime()
            val uid = HelperClient.exchange("UID", timeoutMs = 1_000).toInt()
            assertThat(uid).isAnyOf(0, 2000)
            assertThat(SystemClock.elapsedRealtime() - started).isLessThan(1_500L)
            stalled.forEach { client -> assertThat(client.inputStream.read()).isEqualTo(-1) }
            assertThat(SystemClock.elapsedRealtime() - started).isLessThan(7_000L)
            assertThat(HelperClient.exchange("UID", timeoutMs = 1_000)).isEqualTo(uid.toString())
            Log.i("MiraxHelperDevice", "live clientUid=${Process.myUid()} serverUid=$uid partialClients=2 expired")
        } finally {
            stalled.forEach(LocalSocket::close)
        }
    }

    /** @return explicit orchestrator mode; ordinary device suites skip these process-sensitive probes. */
    private fun testMode(): String? =
        InstrumentationRegistry.getArguments().getString("helperTestMode")
}
