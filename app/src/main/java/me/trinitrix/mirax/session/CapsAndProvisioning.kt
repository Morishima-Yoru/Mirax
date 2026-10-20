package me.trinitrix.mirax.session

/**
 * Encapsulates video mode selection, custom mode ordering, bitrate caps,
 * and one-time wm-size preferred mode provisioning.
 */
internal class CapsAndProvisioning(
    initialPreferredMode: VideoMode? = null,
    initialCheckedStandardModes: Set<VideoMode> = StandardVideoModes.DEFAULT_CHECKED,
    initialMaxVideoBitrateBps: Long = StandardVideoModes.BITRATE_CAP_BPS,
    initialProvisioningConsumed: Boolean = false,
    initialCustomModes: List<VideoMode> = emptyList(),
    initialAutoAddWmSizeOnConnect: Boolean = true,
) {
    var preferredMode: VideoMode? = initialPreferredMode
        private set

    var checkedStandardModes: Set<VideoMode> = initialCheckedStandardModes.toSet()
        private set

    var maxVideoBitrateBps: Long = StandardVideoModes.clampMaxBitrateBps(initialMaxVideoBitrateBps)
        private set

    var provisioningConsumed: Boolean = initialProvisioningConsumed || initialPreferredMode != null
        private set

    var customModes: List<VideoMode> = distinctModes(
        if (initialCustomModes.isNotEmpty()) initialCustomModes else listOfNotNull(initialPreferredMode),
    )
        private set

    var autoAddWmSizeOnConnect: Boolean = initialAutoAddWmSizeOnConnect
        private set

    var provisioningReadRequested: Boolean = false

    fun commitPreferredModeText(text: String) {
        val fallbackRefresh = preferredMode?.refreshHz ?: 60
        when (val result = PreferredModeCorrection.parse(text, fallbackRefresh)) {
            PreferredModeCorrection.ParseResult.Cleared -> {
                if (preferredMode == null) return
                preferredMode = null
                consumeProvisioning()
            }
            PreferredModeCorrection.ParseResult.Unparseable -> Unit
            is PreferredModeCorrection.ParseResult.Accepted -> {
                preferredMode = result.mode
                consumeProvisioning()
            }
        }
    }

    fun setStandardModeChecked(mode: VideoMode, checked: Boolean) {
        val catalog = StandardVideoModes.catalog(maxVideoBitrateBps)
        if (mode !in catalog) return
        checkedStandardModes = if (checked) {
            checkedStandardModes + mode
        } else {
            checkedStandardModes - mode
        }
    }

    fun setStandardModesChecked(modes: Collection<VideoMode>, checked: Boolean) {
        val catalog = StandardVideoModes.catalog(maxVideoBitrateBps)
        val known = modes.filter { it in catalog }.toSet()
        if (known.isEmpty()) return
        checkedStandardModes = if (checked) {
            checkedStandardModes + known
        } else {
            checkedStandardModes - known
        }
    }

    fun setMaxVideoBitrateBps(bps: Long) {
        maxVideoBitrateBps = StandardVideoModes.clampMaxBitrateBps(bps)
    }

    fun setAutoAddWmSizeOnConnect(enabled: Boolean) {
        autoAddWmSizeOnConnect = enabled
    }

    fun addCustomMode(width: Int, height: Int, refreshHz: Int) {
        val mode = PreferredModeCorrection.correct(width, height, refreshHz) ?: return
        if (mode in customModes) return
        customModes = customModes + mode
    }

    fun removeCustomMode(mode: VideoMode) {
        customModes = customModes.filter { it != mode }
        if (preferredMode == mode) {
            preferredMode = null
        }
    }

    fun moveCustomMode(from: Int, to: Int) {
        if (from !in customModes.indices || to !in customModes.indices || from == to) return
        val next = customModes.toMutableList()
        val moved = next.removeAt(from)
        next.add(to, moved)
        customModes = next
    }

    fun useThisScreen(reading: WmSizeReading, pictureRotationDegrees: Int) {
        consumeProvisioning()
        val refresh = preferredMode?.refreshHz ?: 60
        val (width, height) = visiblePictureAxes(reading.chosenWidth, reading.chosenHeight, pictureRotationDegrees)
        PreferredModeCorrection.correct(width, height, refresh)?.let { preferredMode = it }
    }

    fun applyProvisioningWmSize(reading: WmSizeReading, pictureRotationDegrees: Int): Boolean {
        if (provisioningConsumed || preferredMode != null) {
            consumeProvisioning()
            return false
        }
        val (width, height) = visiblePictureAxes(reading.chosenWidth, reading.chosenHeight, pictureRotationDegrees)
        PreferredModeCorrection.correct(width, height, 60)?.let { preferredMode = it }
        consumeProvisioning()
        return true
    }

    fun consumeProvisioning() {
        provisioningConsumed = true
        provisioningReadRequested = false
    }

    fun resolveNextAdvertisementModes(): Set<VideoMode> {
        val checked = checkedStandardModes.intersect(
            StandardVideoModes.catalog(maxVideoBitrateBps).toSet(),
        )
        return checked + customModes.toSet() + listOfNotNull(preferredMode).toSet()
    }

    companion object {
        fun visiblePictureAxes(baseWidth: Int, baseHeight: Int, rotationDegrees: Int): Pair<Int, Int> {
            return when (rotationDegrees) {
                90, 270 -> baseHeight to baseWidth
                else -> baseWidth to baseHeight
            }
        }

        fun distinctModes(modes: List<VideoMode>): List<VideoMode> {
            val seen = LinkedHashSet<VideoMode>()
            val ordered = ArrayList<VideoMode>(modes.size)
            for (mode in modes) {
                if (seen.add(mode)) {
                    ordered.add(mode)
                }
            }
            return ordered
        }
    }
}
