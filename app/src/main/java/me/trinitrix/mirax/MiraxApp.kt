package me.trinitrix.mirax

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.trinitrix.mirax.data.standby.AndroidStandbyMediaValidator
import me.trinitrix.mirax.data.standby.StandbyMediaStore
import me.trinitrix.mirax.data.standby.theme.StandbyThemeMigration
import me.trinitrix.mirax.data.standby.theme.StandbyThemeSettings
import me.trinitrix.mirax.host.HostEnvironment
import me.trinitrix.mirax.host.SessionPlatformBinding
import me.trinitrix.mirax.session.ConnectionHistory
import me.trinitrix.mirax.session.ConnectionHistoryFactory
import me.trinitrix.mirax.session.DeviceNameReport
import me.trinitrix.mirax.session.MiraxSession
import me.trinitrix.mirax.session.SystemLocaleReport
import me.trinitrix.mirax.ui.standby.StandbyMediaOwner
import me.trinitrix.mirax.ui.standby.settings.SessionStandbyLanguageGateway
import me.trinitrix.mirax.ui.standby.settings.StandbyLanguageGateway
import me.trinitrix.mirax.ui.standby.settings.StandbyThemeGateway
import me.trinitrix.mirax.ui.standby.settings.StandbyThemeSettingsGateway

/**
 * Process-scoped application holding the Mirax session for one stay.
 */
class MiraxApp : Application() {
    lateinit var session: MiraxSession
        private set
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private lateinit var connectionHistoryDataStore: DataStore<Preferences>
    private lateinit var connectionHistoryFactory: ConnectionHistoryFactory
    private lateinit var connectionHistory: ConnectionHistory
    private lateinit var standbyPreferences: DataStore<Preferences>
    lateinit var standbyThemeSettings: StandbyThemeSettings
        private set
    lateinit var standbyThemeGateway: StandbyThemeGateway
        private set
    lateinit var standbyMediaStore: StandbyMediaStore
        private set
    internal lateinit var standbyMediaOwner: StandbyMediaOwner
        private set
    lateinit var standbyLanguageGateway: StandbyLanguageGateway
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Deferred init: DataStore needs context from onCreate()
        val dataStoreProperty = preferencesDataStore(name = "connection_history")
        connectionHistoryDataStore = dataStoreProperty.getValue(this, this::connectionHistoryDataStore)
        connectionHistoryFactory = ConnectionHistoryFactory(
            dataStore = connectionHistoryDataStore,
            coroutineScope = coroutineScope,
        )
        connectionHistory = connectionHistoryFactory.create()

        val standbyStoreProperty = preferencesDataStore(
            name = "standby_preferences",
            produceMigrations = { context ->
                listOf(StandbyThemeMigration(context.getSharedPreferences("mirax_session", Context.MODE_PRIVATE)))
            },
        )
        standbyPreferences = standbyStoreProperty.getValue(this, this::standbyPreferences)
        standbyThemeSettings = StandbyThemeSettings(standbyPreferences)
        standbyThemeGateway = StandbyThemeSettingsGateway(standbyThemeSettings)
        val standbyMediaDir = File(filesDir, "standby_media")
        standbyMediaStore = StandbyMediaStore(
            dataStore = standbyPreferences,
            validator = AndroidStandbyMediaValidator(),
            storageDir = standbyMediaDir,
        )
        standbyMediaOwner = StandbyMediaOwner(this, standbyMediaStore, standbyMediaDir)
        standbyLanguageGateway = SessionStandbyLanguageGateway(
            getSharedPreferences("mirax_session", Context.MODE_PRIVATE),
        )

        // A new process waits to broadcast. A previous stay does not resume advertising.
        session = MiraxSession(
            initialSettings = SessionPreferences.load(this).copy(advertisingEnabled = false),
            connectionHistory = connectionHistory,
        )
        session.report(SystemLocaleReport(HostEnvironment.isSystemTraditionalChinese(this)))
        session.report(DeviceNameReport(HostEnvironment.readDeviceName(this)))
        session.report(SessionPlatformBinding.platform.reportWifi(this))
        HostEnvironment.applyAppLanguage(session.snapshot().appLanguage)
        SessionHost.commit(this)
    }

    companion object {
        lateinit var instance: MiraxApp
            private set
    }
}
