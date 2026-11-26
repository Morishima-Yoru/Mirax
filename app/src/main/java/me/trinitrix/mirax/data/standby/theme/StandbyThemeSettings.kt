package me.trinitrix.mirax.data.standby.theme

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Persisted standby theme identity.
 *
 * Storage holds only [StandbyThemeId.storageValue], never a display string.
 * Absent records read as [StandbyThemeId.MATTER], the first-launch default.
 * Storage and migration failures propagate. Unknown raw values also read as
 * [StandbyThemeId.MATTER] with a warning log, and the stored raw is left
 * untouched until the next explicit [setTheme], so an unknown value from a
 * newer version is never silently erased by an old reader.
 *
 * @param dataStore caller-supplied preferences store; this lane creates no singletons.
 * @param ioDispatcher dispatcher for DataStore IO.
 */
class StandbyThemeSettings(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Live theme identity; absent or unknown values do not cause a write. */
    val themeFlow: Flow<StandbyThemeId> =
        dataStore.data
            .map { prefs -> resolveWithLog(prefs[KEY_THEME_ID]) }
            .catch { failure ->
                if (failure !is CancellationException) {
                    Log.e(TAG, "Cannot read standby theme", failure)
                }
                throw failure
            }

    /**
     * Reads the current theme identity once.
     *
     * @return stored identity, or [StandbyThemeId.MATTER] when absent or unknown.
     */
    suspend fun current(): StandbyThemeId = withContext(ioDispatcher) { themeFlow.first() }

    /**
     * Persists [id] as its stable storage value.
     *
     * @param id identity to store.
     */
    suspend fun setTheme(id: StandbyThemeId) {
        try {
            dataStore.edit { prefs ->
                prefs[KEY_THEME_ID] = id.storageValue
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(TAG, "Cannot persist standby theme", failure)
            throw failure
        }
    }

    private fun resolveWithLog(raw: String?): StandbyThemeId {
        val parsed = StandbyThemeId.fromStorage(raw)
        if (raw != null && parsed == null) {
            // shortcut: log without the raw value, upgrade to sampled logging if noisy.
            Log.w(TAG, "Unknown standby theme; using MATTER fallback")
        }
        return parsed ?: StandbyThemeId.MATTER
    }

    companion object {
        internal val KEY_THEME_ID = stringPreferencesKey("standby.theme.id")
        private const val TAG = "StandbyThemeSettings"
    }
}
