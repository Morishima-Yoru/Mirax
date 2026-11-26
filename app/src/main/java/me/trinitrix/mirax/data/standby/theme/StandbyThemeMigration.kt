package me.trinitrix.mirax.data.standby.theme

import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CancellationException

/** Migrates the legacy theme without deleting its backup. */
class StandbyThemeMigration internal constructor(
    private val readLegacyTheme: () -> String?,
) : DataMigration<Preferences> {
    /** Creates a migration from the existing session preferences. */
    constructor(preferences: SharedPreferences) : this({ preferences.getString("standby_theme", null) })

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = migrationStep {
        currentData[KEY_MIGRATED]?.not() ?: true
    }

    override suspend fun migrate(currentData: Preferences): Preferences = migrationStep {
        val needsMarkerUpdate = currentData[KEY_MIGRATED]?.not() ?: true
        val migrated = currentData.toMutablePreferences()
        if (!currentData.contains(StandbyThemeSettings.KEY_THEME_ID)) {
            val legacy = readLegacyTheme()
            if (legacy != null) {
                migrated[StandbyThemeSettings.KEY_THEME_ID] =
                    if (legacy == "MATTER") StandbyThemeId.MATTER.storageValue else legacy
            }
        }
        if (needsMarkerUpdate) {
            migrated[KEY_MIGRATED] = true
        }
        migrated
    }

    override suspend fun cleanUp() = Unit

    private inline fun <T> migrationStep(block: () -> T): T {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(TAG, "Standby theme migration failed; initialization will retry", failure)
            throw IllegalStateException("Standby theme migration failed", failure)
        }
    }

    companion object {
        internal val KEY_MIGRATED = booleanPreferencesKey("standby.theme.legacy_migrated")
        private const val TAG = "StandbyThemeMigration"
    }
}
