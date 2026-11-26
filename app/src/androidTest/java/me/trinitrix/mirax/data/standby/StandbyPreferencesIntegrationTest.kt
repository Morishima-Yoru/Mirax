package me.trinitrix.mirax.data.standby

import android.content.Context
import android.os.Process
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.trinitrix.mirax.data.standby.theme.StandbyLanguage
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import me.trinitrix.mirax.data.standby.theme.StandbyThemeMigration
import me.trinitrix.mirax.data.standby.theme.StandbyThemeSettings
import me.trinitrix.mirax.standbyprobe.media.StandbyMediaProbeFixtures
import me.trinitrix.mirax.ui.standby.settings.SessionStandbyLanguageGateway
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Real preferences and DataStore checks using isolated test-owned files. */
@RunWith(AndroidJUnit4::class)
class StandbyPreferencesIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = System.nanoTime().toString()

    @Test
    fun migrationPreservesExistingIdentityMediaAndSeparateHistory() = runBlocking {
        val legacy = context.getSharedPreferences("standby-legacy-$suffix", Context.MODE_PRIVATE)
        assertThat(legacy.edit().putString("standby_theme", "MATTER").commit()).isTrue()
        val themeKey = stringPreferencesKey("standby.theme.id")
        val mediaKey = stringPreferencesKey("standby_media_basename")
        val historyKey = stringPreferencesKey("history.record")
        val standbyFile = File(context.cacheDir, "standby-$suffix.preferences_pb")
        val historyFile = File(context.cacheDir, "history-$suffix.preferences_pb")
        val seedJob = SupervisorJob()
        val seed = PreferenceDataStoreFactory.create(scope = CoroutineScope(seedJob + Dispatchers.IO)) { standbyFile }
        seed.edit { it[themeKey] = "future-theme"; it[mediaKey] = "background" }
        seedJob.cancelAndJoin()
        val job = SupervisorJob()
        try {
            val scope = CoroutineScope(job + Dispatchers.IO)
            val standby = PreferenceDataStoreFactory.create(
                migrations = listOf(StandbyThemeMigration(legacy)), scope = scope,
            ) { standbyFile }
            val history = PreferenceDataStoreFactory.create(scope = scope) { historyFile }
            history.edit { it[historyKey] = "history" }
            val settings = StandbyThemeSettings(standby)
            assertThat(settings.current()).isEqualTo(StandbyThemeId.MATTER)
            assertThat(standby.data.first()[themeKey]).isEqualTo("future-theme")
            settings.setTheme(StandbyThemeId.HARDLINE)
            assertThat(standby.data.first()[mediaKey]).isEqualTo("background")
            assertThat(standby.data.first()[historyKey]).isNull()
            assertThat(history.data.first()[historyKey]).isEqualTo("history")
            assertThat(history.data.first()[themeKey]).isNull()
            assertThat(legacy.getString("standby_theme", null)).isEqualTo("MATTER")
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun malformedLegacyRetriesAndLanguageUsesRealSessionPreferenceValues() = runBlocking {
        val legacy = context.getSharedPreferences("standby-malformed-$suffix", Context.MODE_PRIVATE)
        assertThat(legacy.edit().putBoolean("standby_theme", true).commit()).isTrue()
        val job = SupervisorJob()
        try {
            val standby = PreferenceDataStoreFactory.create(
                migrations = listOf(StandbyThemeMigration(legacy)),
                scope = CoroutineScope(job + Dispatchers.IO),
            ) { File(context.cacheDir, "malformed-$suffix.preferences_pb") }
            val settings = StandbyThemeSettings(standby)
            assertThrows(IllegalStateException::class.java) { runBlocking { settings.current() } }
            assertThat(legacy.edit().putString("standby_theme", "MATTER").commit()).isTrue()
            assertThat(settings.current()).isEqualTo(StandbyThemeId.MATTER)
            assertThat(standby.data.first()[stringPreferencesKey("standby.theme.id")]).isEqualTo("matter")

            assertThat(legacy.edit().putString("language_preference", "FUTURE_LANGUAGE").commit()).isTrue()
            val gateway = SessionStandbyLanguageGateway(legacy)
            assertThat(gateway.currentChoice()).isNull()
            assertThat(legacy.getString("language_preference", null)).isEqualTo("FUTURE_LANGUAGE")
            gateway.selectLanguage(StandbyLanguage.TRADITIONAL_CHINESE)
            assertThat(legacy.getString("language_preference", null)).isEqualTo("TRADITIONAL_CHINESE")
            gateway.selectLanguage(null)
            assertThat(legacy.getString("language_preference", null)).isEqualTo("FOLLOW_SYSTEM")
        } finally {
            job.cancelAndJoin()
        }
    }

    /** Verifies clean file-backed scope/store recreation, not process death or crash durability. */
    @Test
    fun committedMediaSurvivesCleanDataStoreRecreation() {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "standby-store-reopen-").toFile()
        var firstJob: Job? = null
        var secondJob: Job? = null
        var bodyJob: Job? = null
        var primaryFailure: Throwable? = null
        var restoreInterrupt = false

        /** @param error Keeps the exact first failure; cleanup failures remain supplemental. */
        fun retainFailure(error: Throwable) {
            restoreInterrupt = Thread.interrupted() || error is InterruptedException || restoreInterrupt
            val primary = primaryFailure
            if (primary == null) primaryFailure = error
            else if (primary !== error) primary.addSuppressed(error)
        }

        /** @param job Owned work to cancel and finitely join in a scope independent of a cancelled body. */
        fun cancelAndAwait(job: Job?): Boolean {
            if (job == null) return true
            try {
                job.cancel()
            } catch (error: Throwable) {
                retainFailure(error)
            }
            return try {
                runBlocking { withTimeout(10_000L) { job.join() } }
                true
            } catch (error: Throwable) {
                retainFailure(error)
                false
            }
        }

        try {
            val writingJob = SupervisorJob()
            firstJob = writingJob
            val reopeningJob = SupervisorJob()
            secondJob = reopeningJob
            runBlocking {
                bodyJob = checkNotNull(coroutineContext[Job])
                val metadata = File(root, "metadata.preferences_pb")
                val mediaDir = File(root, "media")
                val preferences = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(writingJob + Dispatchers.IO),
                ) { metadata }
                val store = StandbyMediaStore(preferences, AndroidStandbyMediaValidator(), mediaDir)
                assertThat(store.snapshot()).isEqualTo(StandbySnapshot.NeverSet)
                val bytes = Base64.decode(StandbyMediaProbeFixtures.SMALL_PNG_BASE64, Base64.DEFAULT)
                val framing = StandbyFraming(0.3f, 0.7f, 1.8f)
                val staged = store.stageContent({ ByteArrayInputStream(bytes) }, framing)
                assertThat(staged).isInstanceOf(StandbyStageResult.Staged::class.java)
                val candidate = (staged as StandbyStageResult.Staged).candidate
                val result = store.confirm(candidate, framing)
                assertThat(result).isInstanceOf(StandbyConfirmResult.Committed::class.java)
                val committed = (result as StandbyConfirmResult.Committed).active
                assertThat(committed.file).isEqualTo(candidate.file)
                assertThat(committed.kind).isEqualTo(StandbyMediaKind.IMAGE)
                assertThat(committed.framing).isEqualTo(framing)
                assertThat(committed.generation).isEqualTo(candidate.generation)
                assertThat(committed.generation).isGreaterThan(0L)
                val committedFile = checkNotNull(committed.file).canonicalFile
                assertThat(committedFile.parentFile).isEqualTo(mediaDir.canonicalFile)
                assertThat(committedFile.isFile).isTrue()
                assertThat(committedFile.readBytes()).isEqualTo(bytes)
                assertThat(metadata.isFile).isTrue()
                assertThat(metadata.length()).isGreaterThan(0L)

                withTimeout(10_000L) { writingJob.cancelAndJoin() }
                val reopenedPreferences = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(reopeningJob + Dispatchers.IO),
                ) { metadata }
                val reopenedStore = StandbyMediaStore(reopenedPreferences, AndroidStandbyMediaValidator(), mediaDir)
                val snapshot = reopenedStore.snapshot()
                assertThat(snapshot).isInstanceOf(StandbySnapshot.Available::class.java)
                val reopened = (snapshot as StandbySnapshot.Available).active
                assertThat(reopened).isEqualTo(committed)
                val reopenedFile = checkNotNull(reopened.file).canonicalFile
                assertThat(reopenedFile).isEqualTo(committedFile)
                assertThat(reopenedFile.isFile).isTrue()
                assertThat(reopenedFile.readBytes()).isEqualTo(bytes)
            }
        } catch (error: Throwable) {
            retainFailure(error)
        } finally {
            restoreInterrupt = Thread.interrupted() || restoreInterrupt
            try {
                // Store publication can outlive an interrupted runBlocking; settle that body before deleting its files.
                val bodyJoined = cancelAndAwait(bodyJob)
                val firstJoined = cancelAndAwait(firstJob)
                val secondJoined = cancelAndAwait(secondJob)
                if (bodyJoined && firstJoined && secondJoined) {
                    try {
                        check(root.deleteRecursively()) { "Cannot delete owned DataStore reopen fixture" }
                    } catch (error: Throwable) {
                        retainFailure(error)
                    }
                }
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt()
            }
        }
        primaryFailure?.let { throw it }
    }

    /** Publishes and closes one intentionally retained fixture; only the host may establish the process boundary. */
    @Test
    fun publishCommittedMediaForProcessRestart() {
        requireRestartSelector("publishCommittedMediaForProcessRestart")
        checkRestartWitnessParser()
        val png = Base64.decode(StandbyMediaProbeFixtures.SMALL_PNG_BASE64, Base64.DEFAULT)
        val work = RestartWork()
        var root: File? = null
        var committed: StandbyActive? = null
        try {
            val basename = "standby-store-restart-${UUID.randomUUID().toString().replace("-", "")}"
            val ownedRoot = Files.createDirectory(File(restartParent(), basename).toPath()).toFile()
            root = ownedRoot
            runBlocking {
                work.bodyJob = checkNotNull(coroutineContext[Job])
                val media = File(ownedRoot, "media")
                val metadata = File(ownedRoot, "metadata.preferences_pb")
                val preferences = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(work.storeJob + Dispatchers.IO),
                ) { metadata }
                val store = StandbyMediaStore(preferences, AndroidStandbyMediaValidator(), media)
                check(store.snapshot() == StandbySnapshot.NeverSet)
                val framing = StandbyFraming(0.3f, 0.7f, 1.8f)
                val staged = store.stageContent({ ByteArrayInputStream(png) }, framing) as StandbyStageResult.Staged
                val result = store.confirm(staged.candidate, framing) as StandbyConfirmResult.Committed
                val active = result.active
                check(active.file == staged.candidate.file && active.kind == StandbyMediaKind.IMAGE)
                check(active.generation > 0 && active.generation == staged.candidate.generation)
                check(active.framing == framing)
                check(checkNotNull(active.file).canonicalFile.parentFile == media.canonicalFile)
                committed = active
            }
        } catch (error: Throwable) {
            work.retainFailure(error)
        } finally {
            work.finish()
        }
        val active = checkNotNull(committed)
        val asset = checkNotNull(active.file)
        val actualBytes = readRestartBytes(asset, png.size)
        check(actualBytes.contentEquals(png)) { "Committed PNG differs" }
        val witness = RestartWitness(checkNotNull(root).name, asset.name, active.generation,
            checkNotNull(active.kind), active.framing, restartSha256(actualBytes), Process.myPid())
        val checkedRoot = checkedRestartRoot(witness)
        check(asset.canonicalFile == File(File(checkedRoot, "media"), witness.asset).canonicalFile)
        val line = witness.readyLine()
        checkRestartInterrupt()
        Log.i("STANDBY_STORE_RESTART", line)
    }

    /** Reads only the frozen producer identity in a new process, then disposes only its closed validated fixture. */
    @Test
    fun committedMediaSurvivesProcessRestart() {
        requireRestartSelector("committedMediaSurvivesProcessRestart")
        checkRestartWitnessParser()
        val arguments = InstrumentationRegistry.getArguments()
        val fields = listOf("root", "asset", "generation", "kind", "centerX", "centerY", "zoom", "sha256", "pid")
            .associateWith { arguments.getString("standby_store_restart_$it") }
        val expected = parseRestartWitness(fields)
        check(Process.myPid() != expected.pid) { "Consumer must not reuse the producer PID" }
        val root = checkedRestartRoot(expected)
        val png = Base64.decode(StandbyMediaProbeFixtures.SMALL_PNG_BASE64, Base64.DEFAULT)
        val work = RestartWork()
        try {
            runBlocking {
                work.bodyJob = checkNotNull(coroutineContext[Job])
                val metadata = File(root, "metadata.preferences_pb")
                val media = File(root, "media")
                val preferences = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(work.storeJob + Dispatchers.IO),
                ) { metadata }
                val store = StandbyMediaStore(preferences, AndroidStandbyMediaValidator(), media)
                val snapshot = store.snapshot() as StandbySnapshot.Available
                val asset = File(media, expected.asset).canonicalFile
                check(snapshot.active == StandbyActive(asset, expected.kind, expected.framing, expected.generation)) {
                    "Reopened active tuple differs from the producer witness"
                }
                check(checkNotNull(snapshot.active.file).canonicalFile == asset)
            }
        } catch (error: Throwable) {
            work.retainFailure(error)
        } finally {
            work.finish()
        }
        val closedRoot = checkedRestartRoot(expected)
        val bytes = readRestartBytes(File(File(closedRoot, "media"), expected.asset), png.size)
        check(bytes.contentEquals(png)) { "Reopened PNG differs" }
        check(restartSha256(bytes) == expected.sha256) { "Reopened digest differs from the producer witness" }
        deleteRestartRoot(expected)
    }

    /** Immutable host expectations; these fields never write application metadata. */
    private data class RestartWitness(
        val root: String,
        val asset: String,
        val generation: Long,
        val kind: StandbyMediaKind,
        val framing: StandbyFraming,
        val sha256: String,
        val pid: Int,
    ) {
        init {
            require(Regex("standby-store-restart-[0-9a-f]{32}").matches(root)) { "Invalid restart root" }
            require(generation > 0 && Regex("standby_${generation}_[0-9a-f]{16}\\.bin").matches(asset)) {
                "Invalid restart asset identity"
            }
            require(kind == StandbyMediaKind.IMAGE && pid > 0) { "Invalid restart kind/PID" }
            require(Regex("[0-9a-f]{64}").matches(sha256)) { "Invalid restart digest" }
        }

        /** @return The only bounded producer record, containing basenames and actual tuple values only. */
        fun readyLine(): String {
            val line = "phase=READY root=$root asset=$asset generation=$generation kind=${kind.name} " +
                "centerX=${framing.centerX} centerY=${framing.centerY} zoom=${framing.zoom} sha256=$sha256 pid=$pid"
            check(line.length <= 512) { "Restart witness exceeds its bound" }
            return line
        }
    }

    /** @param fields Nine raw namespaced argument values. @return Strict frozen producer expectations. */
    private fun parseRestartWitness(fields: Map<String, String?>): RestartWitness {
        fun field(name: String): String = requireNotNull(fields[name]) { "Missing restart field $name" }
        fun number(name: String): Float {
            val text = field(name)
            require(text.length in 1..32) { "Unbounded restart framing" }
            val value = requireNotNull(text.toFloatOrNull()) { "Invalid restart framing" }
            require(value.isFinite() && value.toString() == text) { "Noncanonical restart framing" }
            return value
        }
        val generation = field("generation")
        val pid = field("pid")
        require(Regex("[1-9][0-9]{0,18}").matches(generation)) { "Invalid restart generation" }
        require(Regex("[1-9][0-9]{0,9}").matches(pid)) { "Invalid restart PID" }
        require(field("kind") == "IMAGE") { "Invalid restart kind" }
        return RestartWitness(field("root"), field("asset"), requireNotNull(generation.toLongOrNull()),
            StandbyMediaKind.IMAGE, StandbyFraming(number("centerX"), number("centerY"), number("zoom")),
            field("sha256"), requireNotNull(pid.toIntOrNull()))
    }

    /** Exercises the same parser using pure inputs before either phase accesses its fixture namespace. */
    private fun checkRestartWitnessParser() {
        val valid = mapOf("root" to "standby-store-restart-${"a".repeat(32)}",
            "asset" to "standby_7_${"b".repeat(16)}.bin", "generation" to "7", "kind" to "IMAGE",
            "centerX" to "0.3", "centerY" to "0.7", "zoom" to "1.8", "sha256" to "c".repeat(64), "pid" to "1234")
        check(parseRestartWitness(valid).readyLine().length <= 512)
        val invalid = listOf(valid - "asset", valid + ("root" to "../${valid.getValue("root")}"),
            valid + ("asset" to "${valid.getValue("asset")}\\escape"), valid + ("zoom" to "NaN"),
            valid + ("centerX" to "1.1"), valid + ("pid" to "0"))
        invalid.forEach { fields -> assertThrows(IllegalArgumentException::class.java) { parseRestartWitness(fields) } }
    }

    /** @param method One exact new phase, never a class-wide selector authorization. */
    private fun requireRestartSelector(method: String) {
        check(InstrumentationRegistry.getArguments().getString("class") ==
            "me.trinitrix.mirax.data.standby.StandbyPreferencesIntegrationTest#$method") {
            "Restart phase requires its one exact selector"
        }
    }

    /** @return Existing non-link parent verified against target filesDir, without a missing-directory fallback. */
    private fun restartParent(): File {
        // Check existence before the filesDir accessor can create a missing parent.
        val parent = File(context.dataDir, "files")
        check(Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Invalid target files directory" }
        val canonical = parent.canonicalFile
        check(canonical == context.filesDir.canonicalFile) { "Unexpected target files directory" }
        return canonical
    }

    /** @param witness Parsed or actual producer identity. @return Only the complete four-node non-link fixture tree. */
    private fun checkedRestartRoot(witness: RestartWitness): File {
        val parent = restartParent()
        val root = File(parent, witness.root)
        val media = File(root, "media")
        val metadata = File(root, "metadata.preferences_pb")
        val asset = File(media, witness.asset)
        check(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Restart root missing or linked" }
        check(root.canonicalFile.parentFile == parent) { "Restart root escaped target files directory" }
        val expected = setOf(root.toPath(), media.toPath(), metadata.toPath(), asset.toPath())
        var seen = 0
        Files.walk(root.toPath()).use { paths ->
            paths.forEach { path ->
                check(path in expected) { "Unexpected restart fixture entry" }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                check(!attributes.isSymbolicLink) { "Linked restart fixture entry" }
                check(path.toFile().canonicalFile == path.toFile().absoluteFile) { "Escaping restart fixture entry" }
                if (path == root.toPath() || path == media.toPath()) check(attributes.isDirectory)
                else check(attributes.isRegularFile && attributes.size() > 0L)
                seen += 1
            }
        }
        check(seen == expected.size) { "Incomplete restart fixture tree" }
        return root
    }

    /** @param file Known fixture asset. @param size Exact trusted PNG length. @return Bounded actual bytes. */
    private fun readRestartBytes(file: File, size: Int): ByteArray {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() == size.toLong()) {
            "Restart asset has unexpected type/length"
        }
        return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(bytes, offset, size - offset)
                check(count > 0) { "Restart asset truncated" }
                offset += count
            }
            check(input.read() == -1) { "Restart asset grew" }
            bytes
        }
    }

    /** @param bytes Actual bounded asset bytes. @return The stable lowercase SHA-256 representation. */
    private fun restartSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Fails without clearing an interrupt or announcing a successful handoff/disposal. */
    private fun checkRestartInterrupt() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Restart phase interrupted; fixture retained")
    }

    /** Deletes only revalidated known nodes; any failure leaves remaining entries and propagates without repair. */
    private fun deleteRestartRoot(witness: RestartWitness) {
        val root = checkedRestartRoot(witness)
        val media = File(root, "media")
        val paths = listOf(File(media, witness.asset).toPath(), File(root, "metadata.preferences_pb").toPath(),
            media.toPath(), root.toPath())
        paths.forEach { path ->
            checkRestartInterrupt()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(!attributes.isSymbolicLink && path.toFile().canonicalFile == path.toFile().absoluteFile)
            if (path == media.toPath() || path == root.toPath()) check(attributes.isDirectory)
            else check(attributes.isRegularFile)
            Files.delete(path)
        }
        check(!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Restart root deletion incomplete" }
    }

    /** Shared only by the two new phases; failure never authorizes file disposal or a READY record. */
    private class RestartWork {
        val storeJob = SupervisorJob()
        var bodyJob: Job? = null
        private var primaryFailure: Throwable? = null
        private var restoreInterrupt = false

        /** @param error Original body or supplemental cleanup failure, retained without wrapping. */
        fun retainFailure(error: Throwable) {
            restoreInterrupt = Thread.interrupted() || error is InterruptedException || restoreInterrupt
            val primary = primaryFailure
            if (primary == null) primaryFailure = error
            else if (primary !== error) primary.addSuppressed(error)
        }

        /** Cancels and finitely joins all owned work before returning successfully; never inspects moving job state. */
        fun finish() {
            if (Thread.interrupted()) retainFailure(InterruptedException("Restart phase interrupted"))
            val joined = try {
                val bodyJoined = cancelAndAwait(bodyJob)
                val storeJoined = cancelAndAwait(storeJob)
                bodyJoined && storeJoined
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt()
            }
            primaryFailure?.let { throw it }
            check(joined) { "Restart work unsettled; fixture retained" }
        }

        /** @param job Existing owned job. @return Only a captured successful finite join permits success. */
        private fun cancelAndAwait(job: Job?): Boolean {
            if (job == null) return true
            try {
                job.cancel()
            } catch (error: Throwable) {
                retainFailure(error)
            }
            return try {
                runBlocking { withTimeout(10_000L) { job.join() } }
                true
            } catch (error: Throwable) {
                retainFailure(error)
                false
            }
        }
    }
}
