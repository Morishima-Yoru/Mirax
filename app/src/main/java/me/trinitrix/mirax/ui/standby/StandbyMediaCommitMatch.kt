package me.trinitrix.mirax.ui.standby

import me.trinitrix.mirax.data.standby.StagedCandidate
import me.trinitrix.mirax.data.standby.StandbyActive
import me.trinitrix.mirax.data.standby.StandbyFraming

/** Identifies only the requested candidate, generation, kind and committed framing. */
internal fun isExpectedCommit(
    candidate: StagedCandidate,
    framing: StandbyFraming,
    active: StandbyActive,
): Boolean = active.file == candidate.file && active.generation == candidate.generation &&
    active.kind == candidate.kind && active.framing == framing
