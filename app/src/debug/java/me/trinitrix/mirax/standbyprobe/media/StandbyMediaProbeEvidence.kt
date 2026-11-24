package me.trinitrix.mirax.standbyprobe.media

/**
 * Probe-only observed evidence for one media show call.
 *
 * File bytes are transport size only and must not be inferred as process memory.
 *
 * @param contentWidth Decoded content width in px, 0 when unknown.
 * @param contentHeight Decoded content height in px, 0 when unknown.
 * @param sourceBytes Source byte count used for this run.
 * @param decodeMs Wall time spent decoding or preparing in ms.
 * @param deviceModel Device model string for future bounds context.
 * @param sdkInt SDK int for future bounds context.
 * @param detail Codec or runtime note, empty when none.
 */
data class StandbyMediaProbeEvidence(
    val contentWidth: Int,
    val contentHeight: Int,
    val sourceBytes: Int,
    val decodeMs: Long,
    val deviceModel: String,
    val sdkInt: Int,
    val detail: String = ""
)
