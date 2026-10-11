package me.trinitrix.mirax

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.trinitrix.mirax.host.HostEnvironment
import me.trinitrix.mirax.session.DeviceNameReport
import me.trinitrix.mirax.wifi.WifiRadio
import me.trinitrix.mirax.session.MiraxSession
import me.trinitrix.mirax.session.SystemLocaleReport
import me.trinitrix.mirax.session.ConnectionHistory
import me.trinitrix.mirax.session.ConnectionHistoryFactory

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

        // A new process waits to broadcast. A previous stay does not resume advertising.
        session = MiraxSession(
            initialSettings = SessionPreferences.load(this).copy(advertisingEnabled = false),
            connectionHistory = connectionHistory,
        )
        session.report(SystemLocaleReport(HostEnvironment.isSystemTraditionalChinese()))
        session.report(DeviceNameReport(HostEnvironment.readDeviceName(this)))
        session.report(WifiRadio.report(this))
        HostEnvironment.applyAppLanguage(session.snapshot().appLanguage)
        SessionHost.commit(this)
    }

    companion object {
        lateinit var instance: MiraxApp
            private set
    }
}
