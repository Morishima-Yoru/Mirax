package me.trinitrix.mirax.session


/**
 * In-memory implementation of [ConnectionHistory] for testing.
 * Does not persist to DataStore.
 */
class TestConnectionHistory : ConnectionHistory {

    private var openRun: OpenConnectionRun? = null
    private val runs: MutableList<ConnectionRun> = mutableListOf()

    private class OpenConnectionRun(
        val remoteHost: String,
        val startedAtEpochMs: Long,
        val configuration: List<ConnectionRunFact>,
        val lines: MutableList<String> = mutableListOf(),
    )

    override fun start(host: String, configuration: List<ConnectionRunFact>) {
        if (openRun != null) {
            finish(succeeded = false, metadata = listOf(ConnectionRunFact("outcome", "closed by a newer attempt")))
        }
        openRun = OpenConnectionRun(
            remoteHost = host,
            startedAtEpochMs = System.currentTimeMillis(),
            configuration = configuration,
        )
    }

    override fun log(line: String) {
        val run = openRun ?: return
        val text = line.trim()
        if (text.isEmpty()) return
        run.lines.add(text)
    }

    override fun finish(succeeded: Boolean, metadata: List<ConnectionRunFact>) {
        val run = openRun ?: return
        openRun = null
        val finishedRun = ConnectionRun(
            succeeded = succeeded,
            startedAtEpochMs = run.startedAtEpochMs,
            endedAtEpochMs = System.currentTimeMillis(),
            remoteHost = run.remoteHost,
            metadata = metadata,
            configuration = run.configuration,
            log = run.lines.joinToString("\n"),
        )
        runs.add(0, finishedRun)
        while (runs.size > 500) {
            runs.removeLast()
        }
    }

    override fun runs(): List<ConnectionRun> = runs.toList()

    override fun activeRun(): ConnectionRun? {
        return openRun?.let {
            ConnectionRun(
                succeeded = false,
                startedAtEpochMs = it.startedAtEpochMs,
                endedAtEpochMs = System.currentTimeMillis(),
                remoteHost = it.remoteHost,
                metadata = emptyList(),
                configuration = it.configuration,
                log = it.lines.joinToString("\n"),
            )
        }
    }

    override fun currentHandshakeLog(): String = openRun?.lines?.joinToString("\n") ?: ""

    /** Clear finished runs immediately in the test store. */
    override fun clear() { runs.clear() }
}
