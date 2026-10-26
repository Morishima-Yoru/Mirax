package me.trinitrix.mirax.wfd

import java.net.Socket

/**
 * Serializes RTP receiver replacement and owner-scoped cleanup on one monitor.
 *
 * @param stopReceiver closes the current RTP socket and stops its audio player.
 */
internal class RtpSessionOwner(private val stopReceiver: () -> Unit) {
    private var activeOwner: Socket? = null

    /**
     * Replaces the receiver only while the requesting generation remains current.
     *
     * @param owner RTSP socket identity; this boundary never opens or closes it.
     * @param isCurrent generation predicate evaluated inside the lifecycle monitor.
     * @param startReceiver starts this session's receiver and audio input.
     * @return false for a stale generation, otherwise true after invoking the callback.
     */
    @Synchronized
    fun start(owner: Socket, isCurrent: () -> Boolean, startReceiver: () -> Unit): Boolean {
        if (!isCurrent()) return false
        stop()
        activeOwner = owner
        startReceiver()
        return true
    }

    /**
     * Releases this owner's receiver/audio, or unconditionally stops for controller shutdown.
     *
     * @param expectedOwner requesting RTSP identity, or null for explicit shutdown.
     */
    @Synchronized
    fun stop(expectedOwner: Socket? = null) {
        if (expectedOwner != null && activeOwner !== expectedOwner) return
        stopReceiver()
        activeOwner = null
    }
}
