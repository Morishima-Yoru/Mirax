package me.trinitrix.mirax.ui.standby.settings

import android.content.SharedPreferences
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.trinitrix.mirax.data.standby.theme.StandbyLanguage

/**
 * A failed language write.
 *
 * @param uncertain true when the previous durable value could not be restored.
 * @param cause original commit failure.
 */
class LanguagePersistenceException(
    val uncertain: Boolean,
    cause: Exception,
) : IOException("Language preference could not be confirmed", cause)

/**
 * The existing session language preference, committed before publishing a choice.
 * Use the process-scoped instance; a cancelled caller must reload before rebinding.
 */
class SessionStandbyLanguageGateway internal constructor(
    private val readRaw: () -> String?,
    private val writeRaw: (String?) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StandbyLanguageGateway {
    /** Uses the existing `mirax_session` shared preferences. */
    constructor(preferences: SharedPreferences) : this(
        readRaw = { preferences.getString(KEY_LANGUAGE, null) },
        writeRaw = { preferences.edit().putString(KEY_LANGUAGE, it).commit() },
    )

    override val choiceFlow: Flow<StandbyLanguage?> = flow {
        currentChoice()
        emitAll(changes.map { it.getOrThrow() })
    }.distinctUntilChanged()

    override suspend fun currentChoice(): StandbyLanguage? = withContext(ioDispatcher) {
        writeLock.withLock {
            uncertainFailure?.let { throw it }
            val raw = try {
                readRaw()
            } catch (failure: Exception) {
                if (failure !is CancellationException) Log.e(TAG, "Cannot read language preference", failure)
                throw failure
            }
            val choice = StandbySettingsPanelModel.parseLanguage(raw)
            changes.tryEmit(Result.success(choice))
            choice
        }
    }

    override suspend fun selectLanguage(language: StandbyLanguage?) = withContext(ioDispatcher) {
        writeLock.withLock {
            val previous = try {
                readRaw()
            } catch (failure: Exception) {
                if (failure !is CancellationException) Log.e(TAG, "Cannot read language preference", failure)
                throw failure
            }
            val wasUncertain = uncertainFailure != null
            try {
                if (!writeRaw(language?.name ?: StandbySettingsPanelModel.FOLLOW_SYSTEM_RAW)) {
                    throw IOException("SharedPreferences language commit returned false")
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.e(TAG, "Language preference commit failed", failure)
                try {
                    readRaw()
                } catch (readbackFailure: Exception) {
                    Log.e(TAG, "Cannot read language preference after failed commit", readbackFailure)
                    failure.addSuppressed(readbackFailure)
                }
                val restored = try {
                    writeRaw(previous) && readRaw() == previous
                } catch (restoreFailure: Exception) {
                    Log.e(TAG, "Language preference restore failed", restoreFailure)
                    failure.addSuppressed(restoreFailure)
                    false
                }
                if (!restored) {
                    val restoreFailure = IOException("SharedPreferences language restore was not confirmed")
                    Log.e(TAG, "Language preference result is uncertain", restoreFailure)
                    failure.addSuppressed(restoreFailure)
                }
                val error = LanguagePersistenceException(wasUncertain || !restored, failure)
                if (error.uncertain) {
                    uncertainFailure = error
                    changes.tryEmit(Result.failure(error))
                }
                throw error
            }
            uncertainFailure = null
            changes.tryEmit(Result.success(language))
            Unit
        }
    }

    companion object {
        private const val KEY_LANGUAGE = "language_preference"
        private const val TAG = "StandbyLanguageGateway"
        private val writeLock = Mutex()
        private val changes = MutableSharedFlow<Result<StandbyLanguage?>>(replay = 1)
        private var uncertainFailure: LanguagePersistenceException? = null
    }
}
