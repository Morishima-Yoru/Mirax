package me.trinitrix.mirax.ui.standby.settings

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import me.trinitrix.mirax.data.standby.theme.StandbyLanguage
import me.trinitrix.mirax.data.standby.theme.StandbyLocaleResolver
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import me.trinitrix.mirax.data.standby.theme.StandbyThemeSettings

/**
 * Injected theme state source for the standby settings panel.
 *
 * The panel reads and writes only through this interface; it never touches
 * a DataStore directly. Storage keeps stable identities only.
 */
interface StandbyThemeGateway {
    /** Live theme identity; unknown stored values surface as [StandbyThemeId.MATTER]. */
    val themeFlow: Flow<StandbyThemeId>

    /**
     * Returns the current theme identity.
     *
     * @return current identity, or [StandbyThemeId.MATTER] when unset or unknown.
     */
    suspend fun currentTheme(): StandbyThemeId

    /**
     * Persists a theme choice as its stable identity. A successful return
     * confirms the write; failures and cancellation propagate to the caller.
     *
     * @param id identity to persist.
     */
    suspend fun selectTheme(id: StandbyThemeId)
}

/**
 * Injected language state source for the standby settings panel.
 *
 * Choices persist as stable names with `null` meaning follow-system; display
 * resolution always goes through [StandbyLocaleResolver], never through a
 * stored display string.
 */
interface StandbyLanguageGateway {
    /** Live language choice; `null` means follow-system. */
    val choiceFlow: Flow<StandbyLanguage?>

    /**
     * Returns the current stored choice.
     *
     * @return stored choice, or `null` to follow the system.
     */
    suspend fun currentChoice(): StandbyLanguage?

    /**
     * Persists a language choice; `null` persists as follow-system. A successful
     * return confirms the write; failures and cancellation propagate to the caller.
     *
     * @param language choice to persist, or `null` to follow the system.
     */
    suspend fun selectLanguage(language: StandbyLanguage?)

    /**
     * Resolves the display language for a system locale.
     *
     * @param systemPrimary primary system locale, or `null` when unknown.
     * @return resolved display language via [StandbyLocaleResolver].
     */
    suspend fun resolve(systemPrimary: Locale?): StandbyLanguage {
        return StandbyLocaleResolver.resolve(currentChoice(), systemPrimary)
    }
}

/**
 * [StandbyThemeGateway] backed by the real persisted settings lane.
 *
 * @param settings owned-no-singleton settings store provided by the caller.
 */
class StandbyThemeSettingsGateway(
    private val settings: StandbyThemeSettings,
) : StandbyThemeGateway {
    override val themeFlow: Flow<StandbyThemeId> = settings.themeFlow

    override suspend fun currentTheme(): StandbyThemeId = settings.current()

    override suspend fun selectTheme(id: StandbyThemeId) = settings.setTheme(id)
}

/**
 * In-memory [StandbyThemeGateway] for previews, tests, and host wiring.
 *
 * @param initialRaw stored raw value to start from, or `null` when never set.
 */
class InMemoryStandbyThemeGateway(
    initialRaw: String? = null,
) : StandbyThemeGateway {
    private val state = MutableStateFlow(StandbyThemeId.resolveOrDefault(initialRaw))

    override val themeFlow: Flow<StandbyThemeId> = state

    override suspend fun currentTheme(): StandbyThemeId = state.value

    override suspend fun selectTheme(id: StandbyThemeId) {
        state.value = id
    }
}

/**
 * In-memory [StandbyLanguageGateway] for previews, tests, and host wiring.
 *
 * @param initialChoice stored choice to start from, or `null` to follow the system.
 */
class InMemoryStandbyLanguageGateway(
    initialChoice: StandbyLanguage? = null,
) : StandbyLanguageGateway {
    private val state = MutableStateFlow(initialChoice)

    override val choiceFlow: Flow<StandbyLanguage?> = state

    override suspend fun currentChoice(): StandbyLanguage? = state.value

    override suspend fun selectLanguage(language: StandbyLanguage?) {
        state.value = language
    }
}
