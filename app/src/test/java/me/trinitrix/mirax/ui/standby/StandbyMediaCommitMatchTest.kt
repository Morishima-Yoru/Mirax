package me.trinitrix.mirax.ui.standby

import com.google.common.truth.Truth.assertThat
import java.io.File
import me.trinitrix.mirax.data.standby.StagedCandidate
import me.trinitrix.mirax.data.standby.StandbyActive
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind
import org.junit.Test

/** Prevents an unknown result from being resolved using a different active asset. */
class StandbyMediaCommitMatchTest {
    @Test
    fun recoveryRequiresOriginalFileGenerationKindAndFraming() {
        val candidate = StagedCandidate(7, File("candidate.bin"), StandbyMediaKind.GIF, StandbyFraming.CENTERED)
        val draft = StandbyFraming(0.4f, 0.6f, 1.5f)
        val active = StandbyActive(candidate.file, candidate.kind, draft, candidate.generation)
        assertThat(isExpectedCommit(candidate, draft, active)).isTrue()
        assertThat(isExpectedCommit(candidate, draft, active.copy(file = File("other.bin")))).isFalse()
        assertThat(isExpectedCommit(candidate, draft, active.copy(generation = 8))).isFalse()
        assertThat(isExpectedCommit(candidate, draft, active.copy(kind = StandbyMediaKind.VIDEO))).isFalse()
        assertThat(isExpectedCommit(candidate, draft, active.copy(framing = StandbyFraming.CENTERED))).isFalse()
    }
}
