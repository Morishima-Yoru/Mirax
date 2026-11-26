package me.trinitrix.mirax.ui.standby.framing

import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.ui.standby.media.StandbyFrameGeometry
import me.trinitrix.mirax.ui.standby.media.StandbyFramingControlsModel

/** Applies editor input to the existing framing model and guards commit requests. */
internal class StandbyFramingEditorGesture(
    private val model: StandbyFramingControlsModel,
    commitPending: Boolean = false,
) {
    private enum class State { EDITING, BUSY, CONFIRMED, CANCELED }

    private var state = if (commitPending) State.BUSY else State.EDITING

    val visible: StandbyFraming get() = model.visible()
    val isEditing: Boolean get() = state == State.EDITING
    val isBusy: Boolean get() = state == State.BUSY
    val isConfirmed: Boolean get() = state == State.CONFIRMED
    val isCanceled: Boolean get() = state == State.CANCELED

    /** Returns whether the decoded content and viewport define a usable source window. */
    fun hasGeometry(contentW: Float, contentH: Float, viewW: Float, viewH: Float): Boolean =
        StandbyFrameGeometry.computeSrc(contentW, contentH, viewW, viewH, visible) != null

    /** Returns whether the supplied finger movement would move the visible source window. */
    fun canPan(
        dx: Float,
        dy: Float,
        contentW: Float,
        contentH: Float,
        viewW: Float,
        viewH: Float,
    ): Boolean = isEditing && panTarget(dx, dy, contentW, contentH, viewW, viewH) != null

    /** Applies a finger movement in viewport pixels, or returns null if it changes nothing. */
    fun pan(
        dx: Float,
        dy: Float,
        contentW: Float,
        contentH: Float,
        viewW: Float,
        viewH: Float,
    ): StandbyFraming? {
        if (!isEditing) return null
        val target = panTarget(dx, dy, contentW, contentH, viewW, viewH) ?: return null
        repeat(4) {
            val current = visible
            if (current.centerX == target.first && current.centerY == target.second) return current
            model.pan(target.first - current.centerX, target.second - current.centerY)
        }
        return visible
    }

    /** Applies a finite zoom request through the existing model. */
    fun zoom(requested: Float): StandbyFraming? {
        if (!isEditing || !requested.isFinite()) return null
        val clamped = requested.coerceIn(
            StandbyFramingControlsModel.MIN_ZOOM,
            StandbyFramingControlsModel.MAX_ZOOM,
        )
        if (clamped == visible.zoom) return null
        return model.zoom(clamped)
    }

    /** Starts one commit request without changing the committed baseline. */
    fun requestCommit(onRequest: (StandbyFraming) -> Unit): Boolean {
        if (!isEditing) return false
        state = State.BUSY
        onRequest(visible)
        return true
    }

    /** Publishes the draft only after the host reports a successful commit. */
    fun completeCommit(): Boolean {
        if (!isBusy) return false
        model.confirm()
        state = State.CONFIRMED
        return true
    }

    /** Allows retry or cancellation after the host reports a definite failure. */
    fun failCommit(): Boolean {
        if (!isBusy) return false
        state = State.EDITING
        return true
    }

    /** Restores the baseline and invokes the host once, unless a commit is pending. */
    fun cancel(onCanceled: (StandbyFraming) -> Unit): Boolean {
        if (!isEditing) return false
        state = State.CANCELED
        onCanceled(model.cancel())
        return true
    }

    private fun panTarget(
        dx: Float,
        dy: Float,
        contentW: Float,
        contentH: Float,
        viewW: Float,
        viewH: Float,
    ): Pair<Float, Float>? {
        if (!dx.isFinite() || !dy.isFinite()) return null
        val current = visible
        val src = StandbyFrameGeometry.computeSrc(contentW, contentH, viewW, viewH, current)
            ?: return null
        val x = axisTarget(current.centerX, src.left, src.right, contentW, viewW, dx)
        val y = axisTarget(current.centerY, src.top, src.bottom, contentH, viewH, dy)
        if (!x.isFinite() || !y.isFinite() || (x == current.centerX && y == current.centerY)) {
            return null
        }
        return x to y
    }

    private fun axisTarget(
        current: Float,
        start: Float,
        end: Float,
        content: Float,
        viewport: Float,
        delta: Float,
    ): Float {
        val window = (end - start) / content
        if (delta == 0f || window >= 1f) return current
        val half = window / 2f
        val center = start / content + half
        val target = (center - delta / viewport * window).coerceIn(half, 1f - half)
        return if (target == center) current else target
    }
}
