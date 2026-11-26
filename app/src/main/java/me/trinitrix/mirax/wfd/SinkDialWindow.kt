package me.trinitrix.mirax.wfd

import java.net.Socket
import java.net.SocketTimeoutException
import me.trinitrix.mirax.session.TcpEstablishmentTimeout

/** Result of a real TCP dial window. */
internal sealed interface SinkDialResult {
    data class Connected(val socket: Socket) : SinkDialResult
    data class NoConnection(val timeout: TcpEstablishmentTimeout? = null) : SinkDialResult
}

/** Tracks only real TCP connect timeouts within one dial window. */
internal class SinkDialWindow {
    private var consecutiveConnectTimeouts = 0

    /** Records an exception thrown specifically by Socket.connect. */
    fun connectFailed(error: Exception) {
        consecutiveConnectTimeouts = if (error is SocketTimeoutException) consecutiveConnectTimeouts + 1 else 0
    }

    fun otherFailure() {
        consecutiveConnectTimeouts = 0
    }

    /** Returns the real dial result, with evidence only for a proven current timeout. */
    fun finish(expired: Boolean, current: Boolean, observationKnown: Boolean): SinkDialResult.NoConnection =
        SinkDialResult.NoConnection(
            if (expired && current && observationKnown && consecutiveConnectTimeouts >= 2) {
                TcpEstablishmentTimeout
            } else {
                null
            },
        )
}
