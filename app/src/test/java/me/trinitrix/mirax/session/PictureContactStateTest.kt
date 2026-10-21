package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.wfd.UibcPackets
import org.junit.Test

class PictureContactStateTest {
    @Test
    fun touchOutsidePictureReleasesSlotUntilPointerIsLifted() {
        val contacts = PictureContactState(2)
        contacts.touchDown(7, insidePicture = true)

        assertThat(contacts.touchMove(7, insidePicture = true, 10f, 10f)).isEqualTo(0)
        assertThat(contacts.touchMove(7, insidePicture = false, 20f, 10f)).isNull()
        assertThat(contacts.touchMove(7, insidePicture = true, 10f, 10f)).isNull()
        contacts.touchDown(7, insidePicture = true)
        assertThat(contacts.touchSlot(7)).isNull()

        contacts.touchUp(7)
        contacts.touchDown(7, insidePicture = true)
        assertThat(contacts.touchSlot(7)).isEqualTo(0)
    }

    @Test
    fun changingPictureEndsBothToolsUntilTheirPointersAreLifted() {
        val contacts = PictureContactState(2)
        contacts.touchDown(3, insidePicture = true)
        contacts.penDown(4, insidePicture = true)

        contacts.endAll()
        assertThat(contacts.hasContacts()).isFalse()
        assertThat(contacts.touchMove(3, insidePicture = true, 10f, 10f)).isNull()
        assertThat(contacts.penMove(4, insidePicture = true, 20f, 20f)).isFalse()
        contacts.touchDown(3, insidePicture = true)
        contacts.penDown(4, insidePicture = true)
        assertThat(contacts.touchSlot(3)).isNull()
        assertThat(contacts.penActive(4)).isFalse()

        contacts.touchUp(3)
        contacts.penUp(4)
        contacts.touchDown(3, insidePicture = true)
        contacts.penDown(4, insidePicture = true)
        assertThat(contacts.touchSlot(3)).isEqualTo(0)
        assertThat(contacts.penActive(4)).isTrue()
    }

    @Test
    fun penLeavingPictureDoesNotReappearOnMoveWithoutFreshDown() {
        val contacts = PictureContactState(2)
        contacts.penDown(5, insidePicture = true)

        assertThat(contacts.penMove(5, insidePicture = false, 100f, 100f)).isFalse()
        assertThat(contacts.penMove(5, insidePicture = true, 10f, 10f)).isFalse()

        contacts.penUp(5)
        contacts.penDown(5, insidePicture = true)
        assertThat(contacts.penMove(5, insidePicture = true, 10f, 10f)).isTrue()
    }

    @Test
    fun penStartingOutsidePictureIsNotAWindowsContact() {
        val contacts = PictureContactState(2)
        contacts.penDown(5, insidePicture = false)

        assertThat(contacts.penMove(5, insidePicture = true, 10f, 10f)).isFalse()
        contacts.penUp(5)
        contacts.penDown(5, insidePicture = true)
        assertThat(contacts.penMove(5, insidePicture = true, 10f, 10f)).isTrue()
    }

    @Test
    fun movingPhoneControlEndsOnlyTheCoveredFingerAndPen() {
        val contacts = PictureContactState(2)
        contacts.touchDown(1, insidePicture = true)
        contacts.touchDown(2, insidePicture = true)
        contacts.touchMove(1, insidePicture = true, 10f, 10f)
        contacts.touchMove(2, insidePicture = true, 90f, 90f)
        contacts.penDown(3, insidePicture = true)
        contacts.penMove(3, insidePicture = true, 50f, 50f)

        val finger = contacts.endCovered { x, y -> x < 20f && y < 20f }
        assertThat(finger.touchSlots).containsExactly(0)
        assertThat(finger.pen).isFalse()
        assertThat(contacts.touchSlot(1)).isNull()
        assertThat(contacts.touchSlot(2)).isEqualTo(1)
        assertThat(contacts.penActive(3)).isTrue()

        val pen = contacts.endCovered { x, y -> x in 40f..60f && y in 40f..60f }
        assertThat(pen.touchSlots).isEmpty()
        assertThat(pen.pen).isTrue()
        assertThat(contacts.touchSlot(2)).isEqualTo(1)
        assertThat(contacts.penMove(3, insidePicture = true, 50f, 50f)).isFalse()
    }

    @Test
    fun disableAndReenableWithoutMotionDoesNotResurrectOldPointers() {
        val contacts = PictureContactState(2, inputGeneration = 10)
        contacts.touchDown(1, insidePicture = true)
        contacts.penDown(2, insidePicture = true)

        assertThat(contacts.observeInputGeneration(12)).isTrue()
        assertThat(contacts.touchMove(1, insidePicture = true, 10f, 10f)).isNull()
        assertThat(contacts.penMove(2, insidePicture = true, 10f, 10f)).isFalse()

        contacts.touchUp(1)
        contacts.penUp(2)
        contacts.touchDown(1, insidePicture = true)
        contacts.penDown(2, insidePicture = true)
        assertThat(contacts.touchSlot(1)).isEqualTo(0)
        assertThat(contacts.penActive(2)).isTrue()
    }

    @Test
    fun hoverLeavingPictureDoesNotRestartUntilHoverExit() {
        val contacts = PictureContactState(2)
        assertThat(contacts.penHover(true, 10f, 10f)).isTrue()
        assertThat(contacts.penHover(false, 100f, 100f)).isFalse()
        assertThat(contacts.takeOutsideReleases().pen).isTrue()
        assertThat(contacts.penHover(true, 10f, 10f)).isFalse()

        contacts.penHoverExit()
        assertThat(contacts.penHover(true, 10f, 10f)).isTrue()
        assertThat(contacts.hasPenHover()).isTrue()
    }

    @Test
    fun hoverExitAfterPenDownDoesNotReleaseActivePenTip() {
        val contacts = PictureContactState(2)
        contacts.penHover(true, 10f, 10f)
        contacts.penDown(9, insidePicture = true)

        assertThat(contacts.penHoverExit()).isFalse()
        assertThat(contacts.penActive(9)).isTrue()
    }

    @Test
    fun penUpLeavesInRangeHoverOwnedUntilPictureEnds() {
        val contacts = PictureContactState(2)
        contacts.penDown(9, insidePicture = true)
        contacts.penMove(9, insidePicture = true, 40f, 50f)
        contacts.penUp(9)
        contacts.finishGesture()

        assertThat(contacts.hasContacts()).isFalse()
        assertThat(contacts.hasPenHover()).isTrue()
        assertThat(contacts.observeInputGeneration(1)).isTrue()
        assertThat(contacts.hasPenHover()).isFalse()
    }

    @Test
    fun phoneControlCoveringPenHoverEndsOnlyThatHover() {
        val contacts = PictureContactState(2)
        contacts.touchDown(1, insidePicture = true)
        contacts.touchMove(1, true, 10f, 10f)
        contacts.penHover(true, 80f, 80f)

        val ended = contacts.endCovered { x, y -> x > 50f && y > 50f }
        assertThat(ended.touchSlots).isEmpty()
        assertThat(ended.pen).isTrue()
        assertThat(contacts.hasPenHover()).isFalse()
        assertThat(contacts.touchSlot(1)).isEqualTo(0)
        assertThat(contacts.penHover(true, 80f, 80f)).isFalse()
        contacts.penHoverExit()
        assertThat(contacts.penHover(true, 80f, 80f, entering = true)).isTrue()
    }

    @Test
    fun cancelAllowsFreshAndroidGestureToReuseSamePointerId() {
        val contacts = PictureContactState(2)
        contacts.touchDown(0, insidePicture = true)
        contacts.penDown(1, insidePicture = true)

        contacts.cancelAll()
        contacts.touchDown(0, insidePicture = true)
        contacts.penDown(1, insidePicture = true)

        assertThat(contacts.touchSlot(0)).isEqualTo(0)
        assertThat(contacts.penActive(1)).isTrue()
    }

    @Test
    fun endingFingerOutsideLeavesPenAndOtherFingerActive() {
        val contacts = PictureContactState(3)
        contacts.touchDown(1, insidePicture = true)
        contacts.touchDown(2, insidePicture = true)
        contacts.penDown(3, insidePicture = true)
        contacts.touchMove(1, true, 10f, 10f)
        contacts.touchMove(2, true, 50f, 50f)
        contacts.penMove(3, true, 90f, 90f)

        assertThat(contacts.touchMove(1, false, 100f, 100f)).isNull()
        assertThat(contacts.takeOutsideReleases().touchSlots).containsExactly(0)
        assertThat(contacts.takeOutsideReleases().touchSlots).isEmpty()
        assertThat(contacts.touchSlot(2)).isEqualTo(1)
        assertThat(contacts.penActive(3)).isTrue()
    }

    @Test
    fun noFreeSlotStillCannotEnterWithoutFreshDown() {
        val contacts = PictureContactState(1)
        contacts.touchDown(1, insidePicture = true)
        contacts.touchDown(2, insidePicture = true)
        assertThat(contacts.touchSlot(2)).isNull()

        contacts.touchUp(1)
        assertThat(contacts.touchMove(2, true, 10f, 10f)).isNull()
        contacts.touchUp(2)
        contacts.touchDown(2, insidePicture = true)
        assertThat(contacts.touchSlot(2)).isEqualTo(0)
    }

    @Test
    fun elevenFingersRequireLiftBeforeAnOverflowPointerCanUseAFreedSlot() {
        val contacts = PictureContactState(UibcPackets.MAX_CONTACTS)
        repeat(UibcPackets.MAX_CONTACTS) { pointerId ->
            contacts.touchDown(pointerId, insidePicture = true)
            assertThat(contacts.touchSlot(pointerId)).isEqualTo(pointerId)
        }
        val overflowPointerId = UibcPackets.MAX_CONTACTS
        contacts.touchDown(overflowPointerId, insidePicture = true)
        assertThat(contacts.touchSlot(overflowPointerId)).isNull()

        contacts.touchUp(0)
        assertThat(contacts.touchMove(overflowPointerId, true, 10f, 10f)).isNull()
        contacts.touchDown(overflowPointerId, insidePicture = true)
        assertThat(contacts.touchSlot(overflowPointerId)).isNull()

        contacts.touchUp(overflowPointerId)
        contacts.touchDown(overflowPointerId, insidePicture = true)
        assertThat(contacts.touchSlot(overflowPointerId)).isEqualTo(0)
    }

    @Test
    fun diagnosticTwoContactDescriptorNeverAllocatesAnUnreportedThirdSlot() {
        val contacts = PictureContactState(UibcPackets.MAX_CONTACTS)
        repeat(3) { pointerId ->
            contacts.touchDown(pointerId, insidePicture = true, slotLimit = 2)
        }
        assertThat(contacts.touchSlot(0)).isEqualTo(0)
        assertThat(contacts.touchSlot(1)).isEqualTo(1)
        assertThat(contacts.touchSlot(2)).isNull()
        contacts.touchUp(0)
        assertThat(contacts.touchMove(2, true, 10f, 10f)).isNull()
        contacts.touchUp(2)
        contacts.touchDown(2, insidePicture = true, slotLimit = 2)
        assertThat(contacts.touchSlot(2)).isEqualTo(0)
    }

    @Test
    fun endingCoveredContactDoesNotReleaseAnotherContact() {
        val contacts = PictureContactState(2)
        contacts.touchDown(1, insidePicture = true)
        contacts.touchDown(2, insidePicture = true)
        contacts.touchMove(1, true, 10f, 10f)
        contacts.touchMove(2, true, 90f, 90f)
        val released = contacts.endCovered { x, y -> x < 50f && y < 50f }

        assertThat(released.touchSlots).containsExactly(0)
        assertThat(contacts.touchSlot(1)).isNull()
        assertThat(contacts.touchSlot(2)).isEqualTo(1)
    }
}
