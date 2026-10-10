package me.trinitrix.mirax.session

/**
 * Manages in-memory connection run diagnostics and history buffer.
 */
internal class ConnectionDiagnosticsRepository {
    private var openConnectionRun: OpenConnectionRun? = null
    private val connectionRuns: ArrayDeque<ConnectionRun> = ArrayDeque()

    fun beginRun(remoteHost: String, configuration: List<ConnectionRunFact>) {
        if (openConnectionRun != null) {
            finishRun(
                succeeded = false,
                metadata = listOf(ConnectionRunFact("outcome", "closed by a newer attempt")),
            )
        }
        openConnectionRun = OpenConnectionRun(
            remoteHost = remoteHost,
            startedAtEpochMs = System.currentTimeMillis(),
            configuration = configuration,
        )
    }

    fun appendLog(line: String) {
        val run = openConnectionRun ?: return
        val text = line.trim()
        if (text.isEmpty()) return
        if (run.lines.size >= MAX_CONNECTION_LOG_LINES) {
            run.lines.removeAt(0)
        }
        run.lines.add(text)
    }

    fun finishRun(succeeded: Boolean, metadata: List<ConnectionRunFact>) {
        val run = openConnectionRun ?: return
        openConnectionRun = null
        connectionRuns.addFirst(
            ConnectionRun(
                succeeded = succeeded,
                startedAtEpochMs = run.startedAtEpochMs,
                endedAtEpochMs = System.currentTimeMillis(),
                remoteHost = run.remoteHost,
                metadata = metadata,
                configuration = run.configuration,
                log = run.lines.joinToString("\n"),
            ),
        )
        while (connectionRuns.size > MAX_CONNECTION_RUNS) {
            connectionRuns.removeLast()
        }
    }

    fun currentHandshakeLog(): String = openConnectionRun?.lines?.joinToString("\n").orEmpty()

    fun runs(): List<ConnectionRun> = connectionRuns.toList()

    private class OpenConnectionRun(
        val remoteHost: String,
        val startedAtEpochMs: Long,
        val configuration: List<ConnectionRunFact>,
        val lines: MutableList<String> = mutableListOf(),
    )

    companion object {
        const val MAX_CONNECTION_RUNS: Int = 32
        const val MAX_CONNECTION_LOG_LINES: Int = 200
    }
}
