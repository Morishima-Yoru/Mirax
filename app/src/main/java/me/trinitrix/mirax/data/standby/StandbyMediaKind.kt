package me.trinitrix.mirax.data.standby

/**
 * Kind of the single active standby custom media slot.
 *
 * Needed to choose playback behaviour (static still versus looping visual)
 * without sniffing file content at render time.
 */
enum class StandbyMediaKind {
    /** Static still image. */
    IMAGE,
    /** Looping graphics interchange image. */
    GIF,
    /** Muted looping video. */
    VIDEO,
}
