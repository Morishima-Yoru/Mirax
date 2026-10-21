package me.trinitrix.mirax.session

/**
 * Tracks Android pointer IDs that belong to the current picture gesture.
 *
 * @param maxTouchSlots number of touch TLCs in the negotiated descriptor
 * @param inputGeneration current transport generation when input begins
 */
internal class PictureContactState(private val maxTouchSlots: Int, private var inputGeneration: Long = 0L) {
    private val touchSlots = LinkedHashMap<Int, Int>()
    private var penPointerId: Int? = null
    private var penHovering = false
    private var hoverSuppressed = false
    private var ignoreHoverExit = false
    private val endedPointers = mutableSetOf<Int>()
    private val touchPositions = mutableMapOf<Int, Pair<Float, Float>>()
    private var penPosition: Pair<Float, Float>? = null
    private val pendingTouchReleases = mutableSetOf<Int>()
    private var pendingPenRelease = false

    /**
     * Allocate a touch report slot for a newly pressed pointer.
     *
     * @param pointerId Android pointer ID
     * @param insidePicture whether the new contact belongs to the visible picture
     * @param slotLimit negotiated descriptor capacity for this input stream
     */
    fun touchDown(pointerId: Int, insidePicture: Boolean, slotLimit: Int = maxTouchSlots) {
        if (!insidePicture) {
            endedPointers.add(pointerId)
            return
        }
        if (touchSlots.containsKey(pointerId) || pointerId in endedPointers) {
            return
        }
        val used = touchSlots.values.toSet()
        for (slot in 0 until minOf(maxTouchSlots, slotLimit)) {
            if (slot !in used) {
                touchSlots[pointerId] = slot
                return
            }
        }
        endedPointers.add(pointerId)
    }

    /**
     * Find the report slot allocated on DOWN.
     *
     * @param pointerId Android pointer ID
     * @return allocated slot, or null when this pointer never entered the picture
     */
    fun touchSlot(pointerId: Int): Int? = touchSlots[pointerId]

    /**
     * Observe a touch sample and end ownership immediately upon leaving the picture.
     *
     * @param pointerId Android pointer ID
     * @param insidePicture whether the sample is inside the visible, uncovered picture
     * @param localX contact X in picture-view coordinates
     * @param localY contact Y in picture-view coordinates
     * @return its slot while active, or null after termination
     */
    fun touchMove(pointerId: Int, insidePicture: Boolean, localX: Float, localY: Float): Int? {
        val slot = touchSlots[pointerId] ?: return null
        if (!insidePicture) {
            touchSlots.remove(pointerId)
            touchPositions.remove(pointerId)
            endedPointers.add(pointerId)
            pendingTouchReleases.add(slot)
            return null
        }
        touchPositions[pointerId] = localX to localY
        return slot
    }

    /**
     * Forget a lifted touch pointer so a later physical press can use its ID again.
     *
     * @param pointerId Android pointer ID
     */
    fun touchUp(pointerId: Int) {
        touchSlots.remove(pointerId)
        touchPositions.remove(pointerId)
        endedPointers.remove(pointerId)
    }

    /**
     * Start the tip stream for one pen pointer.
     *
     * @param pointerId Android pointer ID
     * @param insidePicture whether the new pen contact belongs to the picture
     */
    fun penDown(pointerId: Int, insidePicture: Boolean) {
        if (penHovering) {
            penHovering = false
            ignoreHoverExit = true
            penPosition = null
        }
        if (!insidePicture) {
            endedPointers.add(pointerId)
        } else if (pointerId !in endedPointers) {
            penPointerId = pointerId
            hoverSuppressed = false
        }
    }

    /**
     * Check whether a pen pointer owns the current tip stream.
     *
     * @param pointerId Android pointer ID
     * @return true if this pointer's tip is still active
     */
    fun penActive(pointerId: Int): Boolean = penPointerId == pointerId

    /**
     * Observe a pen sample and end the tip stream immediately when it leaves the picture.
     *
     * @param pointerId Android pointer ID
     * @param insidePicture whether the sample is inside the visible, uncovered picture
     * @param localX pen X in picture-view coordinates
     * @param localY pen Y in picture-view coordinates
     * @return true while this pointer still owns an active pen tip
     */
    fun penMove(pointerId: Int, insidePicture: Boolean, localX: Float, localY: Float): Boolean {
        if (!penActive(pointerId)) {
            return false
        }
        if (!insidePicture) {
            penPointerId = null
            penPosition = null
            endedPointers.add(pointerId)
            pendingPenRelease = true
            return false
        }
        penPosition = localX to localY
        return true
    }

    /**
     * Observe a hovering pen until it exits or a phone control covers it.
     *
     * @param insidePicture whether the sample is inside the visible, uncovered picture
     * @param localX hover X in picture-view coordinates
     * @param localY hover Y in picture-view coordinates
     * @param entering whether Android started a new hover stream
     * @return true while the picture still owns the hover stream
     */
    fun penHover(insidePicture: Boolean, localX: Float, localY: Float, entering: Boolean = false): Boolean {
        if (entering) {
            hoverSuppressed = false
            ignoreHoverExit = false
        }
        if (!insidePicture) {
            if (penHovering) {
                pendingPenRelease = true
            }
            penHovering = false
            hoverSuppressed = true
            penPosition = null
            return false
        }
        if (hoverSuppressed) {
            return false
        }
        penHovering = true
        penPosition = localX to localY
        return true
    }

    /**
     * End a hover stream and send its final out-of-range report once.
     *
     * @return true if the caller must send a terminal pen report
     */
    fun penHoverExit(): Boolean {
        if (ignoreHoverExit) {
            ignoreHoverExit = false
            return false
        }
        val wasHovering = penHovering
        penHovering = false
        hoverSuppressed = false
        if (penPointerId == null) {
            penPosition = null
        }
        return wasHovering
    }

    /**
     * End only the active contacts covered by a phone control.
     *
     * @param contains whether a local picture-view coordinate is covered
     * @return finger slots and pen stream requiring terminal reports
     */
    fun endCovered(contains: (Float, Float) -> Boolean): CoveredContacts {
        val covered = touchPositions.filterValues { (x, y) -> contains(x, y) }.keys
        val released = covered.mapNotNull { pointerId ->
            touchPositions.remove(pointerId)
            endedPointers.add(pointerId)
            touchSlots.remove(pointerId)
        }.toSet()
        val penCovered = penPosition?.let { (x, y) -> contains(x, y) } == true
        if (penCovered) {
            penPointerId?.let(endedPointers::add)
            penPointerId = null
            penPosition = null
            penHovering = false
            hoverSuppressed = true
        }
        return CoveredContacts(released, penCovered)
    }

    /**
     * Report slots and pen contact ended by a phone control or picture boundary.
     *
     * @param touchSlots touch TLCs requiring terminal reports
     * @param pen whether the pen requires a terminal report
     */
    data class CoveredContacts(val touchSlots: Set<Int>, val pen: Boolean)

    /**
     * Consume the one-time reports needed for contacts that left the visible picture.
     *
     * @return slots and pen stream to release on the shared HIDC socket
     */
    fun takeOutsideReleases(): CoveredContacts {
        val releases = CoveredContacts(pendingTouchReleases.toSet(), pendingPenRelease)
        pendingTouchReleases.clear()
        pendingPenRelease = false
        return releases
    }

    /**
     * Forget a lifted pen pointer while retaining its in-range hover ownership.
     *
     * @param pointerId Android pointer ID
     */
    fun penUp(pointerId: Int) {
        if (penPointerId == pointerId) {
            penPointerId = null
            penHovering = true
            ignoreHoverExit = false
        }
        endedPointers.remove(pointerId)
    }

    /** @return whether a pressed finger or pen still belongs to the picture. */
    fun hasContacts(): Boolean = touchSlots.isNotEmpty() || penPointerId != null

    /** @return whether the picture still owns the pen hover stream. */
    fun hasPenHover(): Boolean = penHovering

    /**
     * Notice a channel lifecycle transition, including disable then enable without input events.
     *
     * @param generation current transport generation
     * @return whether the old generation contained active picture contacts or pen hover
     */
    fun observeInputGeneration(generation: Long): Boolean {
        if (inputGeneration == generation) {
            return false
        }
        inputGeneration = generation
        val hadContacts = hasContacts() || penHovering
        endAll()
        return hadContacts
    }

    /** End all current contacts when the picture changes or stops accepting input. */
    fun endAll() {
        endedPointers.addAll(touchSlots.keys)
        penPointerId?.let(endedPointers::add)
        touchSlots.clear()
        touchPositions.clear()
        penPointerId = null
        penHovering = false
        hoverSuppressed = true
        penPosition = null
        pendingTouchReleases.clear()
        pendingPenRelease = false
    }

    /** End a cancelled Android gesture. */
    fun cancelAll() {
        endAll()
        endedPointers.clear()
    }

    /** Complete an Android gesture while retaining a pen still hovering in range. */
    fun finishGesture() {
        endedPointers.clear()
    }
}
