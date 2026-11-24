package me.trinitrix.mirax.data.standby.theme

/**
 * Stable standby theme identity persisted across restarts and language changes.
 *
 * Only [storageValue] is ever written to storage. Display labels live in
 * localized resources and must never be persisted.
 */
enum class StandbyThemeId(val storageValue: String) {
    /** Ambient mountain and mist landscape motion. */
    LANDSCAPE("landscape"),

    /** Particle field; first-launch default theme. */
    MATTER("matter"),

    /** Geometry and stream lines. */
    HARDLINE("hardline");

    companion object {
        /**
         * Parses a stored value back to an identity.
         *
         * @param raw raw stored string, or `null` when never set.
         * @return matching identity, or `null` for absent and unknown values.
         */
        fun fromStorage(raw: String?): StandbyThemeId? {
            return entries.firstOrNull { it.storageValue == raw }
        }

        /**
         * Parses a stored value, falling back to [MATTER] when absent or unknown.
         *
         * @param raw raw stored string, or `null` when never set.
         * @return matching identity, or [MATTER] as the launch default.
         */
        fun resolveOrDefault(raw: String?): StandbyThemeId {
            return fromStorage(raw) ?: MATTER
        }
    }
}
