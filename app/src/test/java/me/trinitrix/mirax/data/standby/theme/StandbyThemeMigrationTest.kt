package me.trinitrix.mirax.data.standby.theme

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Migration policy and DataStore initialization checks. */
class StandbyThemeMigrationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun existingKnownAndUnknownValuesWinWithoutReadingLegacy() = runBlocking {
        for (raw in listOf("landscape", "future-theme", "")) {
            val original = mutablePreferencesOf(StandbyThemeSettings.KEY_THEME_ID to raw)
            val migration = StandbyThemeMigration { error("legacy must not be read") }
            val migrated = migration.migrate(original)
            assertThat(migrated[StandbyThemeSettings.KEY_THEME_ID]).isEqualTo(raw)
            assertThat(migrated[StandbyThemeMigration.KEY_MIGRATED]).isTrue()
            assertThat(migration.shouldMigrate(migrated)).isFalse()
        }
    }

    @Test
    fun legacyMatterRawAndAbsentValuesMigrateWithoutInventingAChoice() = runBlocking {
        for ((legacy, expected) in listOf("MATTER" to "matter", "future-theme" to "future-theme", "" to "", null to null)) {
            val migrated = StandbyThemeMigration { legacy }.migrate(emptyPreferences())
            assertThat(migrated[StandbyThemeSettings.KEY_THEME_ID]).isEqualTo(expected)
            assertThat(migrated[StandbyThemeMigration.KEY_MIGRATED]).isTrue()
        }
    }

    @Test
    fun booleanMarkersRetainValidSemanticsAndExistingThemePriority() = runBlocking {
        var legacyReads = 0
        val migration = StandbyThemeMigration { legacyReads++; "MATTER" }
        val mediaKey = stringPreferencesKey("standby_media_basename")
        assertThat(migration.shouldMigrate(emptyPreferences())).isTrue()
        for (marker in listOf(false, true)) {
            val original = mutablePreferencesOf(
                StandbyThemeMigration.KEY_MIGRATED to marker,
                StandbyThemeSettings.KEY_THEME_ID to "future-theme",
                mediaKey to "background",
            )
            assertThat(migration.shouldMigrate(original)).isEqualTo(!marker)
            val migrated = migration.migrate(original)
            assertThat(migrated[StandbyThemeMigration.KEY_MIGRATED]).isTrue()
            assertThat(migrated[StandbyThemeSettings.KEY_THEME_ID]).isEqualTo("future-theme")
            assertThat(migrated[mediaKey]).isEqualTo("background")
            assertThat(original[StandbyThemeMigration.KEY_MIGRATED]).isEqualTo(marker)
        }
        assertThat(legacyReads).isEqualTo(0)
    }

    @Test
    fun malformedLegacyAndMarkerFailWithoutMutatingInput() = runBlocking {
        val original = emptyPreferences()
        val legacyMigration = StandbyThemeMigration { throw ClassCastException("legacy is not a string") }
        val legacyFailure = assertThrows(IllegalStateException::class.java) {
            runBlocking { legacyMigration.migrate(original) }
        }
        assertThat(legacyFailure.cause).isInstanceOf(ClassCastException::class.java)
        assertThat(original[StandbyThemeMigration.KEY_MIGRATED]).isNull()

        var legacyReads = 0
        val migration = StandbyThemeMigration { legacyReads++; "MATTER" }
        val markerName = "standby.theme.legacy_migrated"
        val mediaKey = stringPreferencesKey("standby_media_basename")
        val malformed = listOf(
            mutablePreferencesOf(
                stringPreferencesKey(markerName) to "wrong-type",
                StandbyThemeSettings.KEY_THEME_ID to "future-theme",
                mediaKey to "background",
            ),
            mutablePreferencesOf(
                intPreferencesKey(markerName) to 42,
                StandbyThemeSettings.KEY_THEME_ID to "future-theme",
                mediaKey to "background",
            ),
            mutablePreferencesOf(stringPreferencesKey(markerName) to "wrong-type", mediaKey to "background"),
        )
        for (data in malformed) {
            val before = data.asMap().toMap()
            val shouldFailure = assertThrows(IllegalStateException::class.java) {
                runBlocking { migration.shouldMigrate(data) }
            }
            val migrateFailure = assertThrows(IllegalStateException::class.java) {
                runBlocking { migration.migrate(data) }
            }
            assertThat(shouldFailure.cause).isInstanceOf(ClassCastException::class.java)
            assertThat(migrateFailure.cause).isInstanceOf(ClassCastException::class.java)
            assertThat(data.asMap()).isEqualTo(before)
        }
        assertThat(legacyReads).isEqualTo(0)
    }

    @Test
    fun failedInitializationRetriesWithoutAFalseMarkerAndKeepsOtherStoresSeparate() = runBlocking {
        val markers = mutableListOf<Boolean?>()
        var failing = true
        val delegate = StandbyThemeMigration {
            if (failing) throw IOException("temporary failure")
            "MATTER"
        }
        val migration = object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences): Boolean {
                markers.add(currentData[StandbyThemeMigration.KEY_MIGRATED])
                return delegate.shouldMigrate(currentData)
            }
            override suspend fun migrate(currentData: Preferences): Preferences = delegate.migrate(currentData)
            override suspend fun cleanUp() = delegate.cleanUp()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val standby = PreferenceDataStoreFactory.create(migrations = listOf(migration), scope = scope) {
                File(folder.root, "standby.preferences_pb")
            }
            val history = PreferenceDataStoreFactory.create(scope = scope) {
                File(folder.root, "history.preferences_pb")
            }
            val historyKey = stringPreferencesKey("history.record")
            val mediaKey = stringPreferencesKey("standby_media_basename")
            history.edit { it[historyKey] = "history" }
            val settings = StandbyThemeSettings(standby)
            assertThrows(IllegalStateException::class.java) { runBlocking { settings.current() } }
            assertThrows(IllegalStateException::class.java) { runBlocking { settings.setTheme(StandbyThemeId.HARDLINE) } }
            failing = false
            assertThat(settings.current()).isEqualTo(StandbyThemeId.MATTER)
            assertThat(markers).containsExactly(null, null, null).inOrder()
            standby.edit { it[mediaKey] = "background" }
            settings.setTheme(StandbyThemeId.HARDLINE)
            assertThat(standby.data.first()[mediaKey]).isEqualTo("background")
            assertThat(standby.data.first()[historyKey]).isNull()
            assertThat(history.data.first()[historyKey]).isEqualTo("history")
            assertThat(history.data.first()[mediaKey]).isNull()
        } finally {
            scope.cancel()
        }
    }
}
