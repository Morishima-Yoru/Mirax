package me.trinitrix.mirax.session

/**
 * Render a connection run as a standalone HTML document.
 *
 * @param run Connection run to render.
 * @param active Whether the run is still ongoing.
 * @return HTML containing the escaped run details and log.
 */
internal fun connectionRunHtml(run: ConnectionRun, active: Boolean = false): String = buildString {
    append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Connection run</title></head><body>")
    append("<h1>Connection run</h1><p>Source host: ")
    append(escapeHtml(run.remoteHost))
    append("</p><p>Started (epoch ms): ")
    append(run.startedAtEpochMs)
    append(if (active) "</p><p>Captured (epoch ms): " else "</p><p>Ended (epoch ms): ")
    append(run.endedAtEpochMs)
    append("</p><p>Outcome: ")
    append(if (active) "Active" else if (run.succeeded) "Succeeded" else "Failed")
    append("</p>")
    for ((heading, facts) in listOf("Metadata" to run.metadata, "Configuration" to run.configuration)) {
        append("<h2>").append(heading).append("</h2><ul>")
        for (fact in facts) {
            append("<li>").append(escapeHtml(fact.label)).append(": ")
                .append(escapeHtml(fact.value)).append("</li>")
        }
        append("</ul>")
    }
    append("<h2>Log</h2><pre>")
    append(escapeHtml(run.log))
    append("</pre></body></html>")
}

/**
 * Escape text for an HTML text node, including quoted and apostrophe characters.
 *
 * @param text Untrusted text to escape.
 * @return HTML-safe text.
 */
private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
