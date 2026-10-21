package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConnectionRunHtmlTest {
    @Test
    fun rendersAllFieldsAndEscapesUntrustedText() {
        val run = ConnectionRun(
            succeeded = true,
            startedAtEpochMs = 123L,
            endedAtEpochMs = 456L,
            remoteHost = "host<&\"'",
            metadata = listOf(ConnectionRunFact("<script>", "a&b\"'")),
            configuration = listOf(ConnectionRunFact("mode", "<fast> & ready")),
            log = "first <script>alert('x')</script>\nsecond & \"quoted\"",
        )

        val html = connectionRunHtml(run)

        assertThat(html).startsWith("<!doctype html><html")
        assertThat(html).endsWith("</body></html>")
        assertThat(html).contains("Source host: host&lt;&amp;&quot;&#39;")
        assertThat(html).contains("Started (epoch ms): 123")
        assertThat(html).contains("Ended (epoch ms): 456")
        assertThat(html).contains("Outcome: Succeeded")
        assertThat(html).contains("<h2>Metadata</h2><ul><li>&lt;script&gt;: a&amp;b&quot;&#39;</li></ul>")
        assertThat(html).contains("<h2>Configuration</h2><ul><li>mode: &lt;fast&gt; &amp; ready</li></ul>")
        assertThat(html).contains("<pre>first &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;\nsecond &amp; &quot;quoted&quot;</pre>")
        assertThat(html).doesNotContain("<script>")
        assertThat(connectionRunHtml(run.copy(succeeded = false))).contains("Outcome: Failed")
        assertThat(connectionRunHtml(run, active = true)).contains("Outcome: Active")
        assertThat(connectionRunHtml(run, active = true)).contains("Captured (epoch ms): 456")
    }
}
