package me.trinitrix.mirax.ui.standby.media

import me.trinitrix.mirax.data.standby.StandbyFraming

/**
 * Pure framing edit state machine with preview, confirm, and cancel.
 *
 * Holds the committed baseline plus an optional preview draft. Pan and zoom
 * edits apply to the draft only; cancel restores the prior framing and
 * confirm publishes the draft. Fully JVM testable with no Android types.
 *
 * @param committed initially committed framing.
 */
class StandbyFramingControlsModel(
    committed: StandbyFraming = StandbyFraming.CENTERED,
) {
    companion object {
        /** Smallest zoom factor; below this aspect fill degenerates. */
        const val MIN_ZOOM: Float = 1.0f

        /** Largest zoom factor; above this the window becomes unusable. */
        const val MAX_ZOOM: Float = 4.0f

        /** Largest normalized pan step per operation. */
        const val MAX_PAN_STEP: Float = 0.25f
    }

    /** Last confirmed framing. */
    var committed: StandbyFraming = committed
        private set

    private var draft: StandbyFraming? = null

    /** True while a preview draft is active. */
    val isPreviewing: Boolean
        get() = draft != null

    /**
     * Currently visible framing: draft during preview, else committed.
     *
     * @return visible framing.
     */
    fun visible(): StandbyFraming {
        return draft ?: committed
    }

    /**
     * Starts a preview draft from the committed baseline.
     */
    fun startPreview() {
        if (draft == null) {
            draft = committed
        }
    }

    /**
     * Pans the draft centre by a clamped delta.
     *
     * No draft means preview starts implicitly from the baseline.
     * Non-finite deltas are ignored without starting a preview.
     *
     * @param deltaX normalized horizontal delta.
     * @param deltaY normalized vertical delta.
     * @return visible framing after the pan.
     */
    fun pan(deltaX: Float, deltaY: Float): StandbyFraming {
        if (!deltaX.isFinite() || !deltaY.isFinite()) {
            return draft ?: committed
        }
        val current = draft ?: committed.copy().also { draft = it }
        val stepX = deltaX.coerceIn(-MAX_PAN_STEP, MAX_PAN_STEP)
        val stepY = deltaY.coerceIn(-MAX_PAN_STEP, MAX_PAN_STEP)
        val next = current.copy(
            centerX = (current.centerX + stepX).coerceIn(0f, 1f),
            centerY = (current.centerY + stepY).coerceIn(0f, 1f),
        )
        draft = next
        return next
    }

    /**
     * Sets the draft zoom, clamped to [MIN_ZOOM] and [MAX_ZOOM].
     *
     * @param zoom requested zoom factor.
     * @return visible framing after the zoom.
     */
    fun zoom(zoom: Float): StandbyFraming {
        val current = draft ?: committed.copy().also { draft = it }
        val clamped = when {
            !zoom.isFinite() -> current.zoom
            else -> zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        }
        val next = current.copy(zoom = clamped)
        draft = next
        return next
    }

    /**
     * Confirms the draft and publishes it.
     *
     * @return committed framing after confirmation.
     */
    fun confirm(): StandbyFraming {
        val pending = draft
        if (pending != null) {
            committed = pending
            draft = null
        }
        return committed
    }

    /**
     * Cancels the draft and restores the prior framing.
     *
     * @return committed framing, unchanged.
     */
    fun cancel(): StandbyFraming {
        draft = null
        return committed
    }
}
