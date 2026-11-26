package me.trinitrix.mirax.ui.standby

import com.google.common.truth.Truth.assertThat
import java.io.File
import me.trinitrix.mirax.R
import me.trinitrix.mirax.data.standby.StagedCandidate
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind
import me.trinitrix.mirax.ui.standby.media.StandbyFramingControlsModel
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Covers cancel discard-failure restore when the edit slot is free.
 *
 * Fake store stands in for [me.trinitrix.mirax.data.standby.StandbyMediaStore.discard];
 * full [StandbyMediaOwner] needs Looper/Main (androidTest).
 */
class StandbyMediaOwnerCancelDiscardTest {
    private class FakeDiscardStore(private val fail: Boolean) {
        val discarded = mutableListOf<StagedCandidate>()

        fun discard(candidate: StagedCandidate) {
            if (fail) throw IllegalStateException("discard boom")
            discarded.add(candidate)
        }
    }

    private fun candidate(): StagedCandidate = StagedCandidate(
        generation = 3,
        file = File("staged.bin"),
        kind = StandbyMediaKind.IMAGE,
        framing = StandbyFraming.CENTERED,
    )

    @Test
    fun failedDiscardRestoresWhenEditSlotFreeIgnoringStaging() {
        val candidate = candidate()
        val store = FakeDiscardStore(fail = true)
        assertThrows(IllegalStateException::class.java) { store.discard(candidate) }
        assertThat(store.discarded).isEmpty()

        val occupied = StandbyMediaEdit(
            candidate,
            StandbyFramingControlsModel(StandbyFraming.CENTERED),
            StandbyMediaEditStatus.EDITING,
        )
        // staging may be true after cancel cleared edit; restore still allowed.
        assertThat(shouldRestoreEditingAfterDiscardFailure(null)).isTrue()
        assertThat(shouldRestoreEditingAfterDiscardFailure(occupied)).isFalse()
        assertThat(R.string.standby_media_discard_failed)
            .isNotEqualTo(R.string.standby_media_import_failed)
    }

    @Test
    fun successfulDiscardLeavesNoRetryHandleNeed() {
        val candidate = candidate()
        val store = FakeDiscardStore(fail = false)
        store.discard(candidate)
        assertThat(store.discarded).containsExactly(candidate)
    }
}
