package me.trinitrix.mirax.data.standby

import java.io.File

/**
 * Real decoding contract for a staged standby candidate.
 *
 * Implementations must attempt a true decode of [candidate] and return its
 * media kind, or `null` when the bytes are not usable. There is no
 * always-valid default; callers must supply genuine decoding at integration
 * time, while slice tests may fake this seam.
 *
 * The store invokes [inspect] on its injected IO dispatcher, never on the
 * caller thread, so real decoding must be safe to run there.
 *
 * @param candidate store-owned staged file to inspect.
 * @return media kind for usable content, else `null`.
 */
fun interface StandbyMediaValidator {
    suspend fun inspect(candidate: File): StandbyMediaKind?
}
