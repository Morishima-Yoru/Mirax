package me.trinitrix.mirax.session

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide strictly increasing attempt identity generator.
 *
 * A single instance is kept in process scope (not in an Activity) so that
 * configuration recreation cannot reset or replay identities. IDs are positive
 * and strictly increasing, distinguishing attempts even when they reuse the
 * same peer group.
 */
class PrePlayAttemptIdGenerator private constructor() {
    private val counter = AtomicLong(1L)

    /**
     * Returns the next attempt identity.
     *
     * Must be called exactly once per pre-play entry (i.e., each transition
     * into CONNECTING from a non-CONNECTING phase). Thread-safe.
     *
     * @return Positive strictly increasing attempt identity.
     */
    fun next(): PrePlayAttemptId = PrePlayAttemptId(counter.getAndIncrement())

    companion object {
        @Volatile
        private var INSTANCE: PrePlayAttemptIdGenerator? = null

        /**
         * Returns the process-wide generator instance.
         *
         * @return Shared generator.
         */
        fun getInstance(): PrePlayAttemptIdGenerator = INSTANCE ?: synchronized(this) {
            INSTANCE ?: PrePlayAttemptIdGenerator().also { INSTANCE = it }
        }

        /** For test isolation only. */
        internal fun resetForTest() {
            INSTANCE = PrePlayAttemptIdGenerator()
        }
    }
}
