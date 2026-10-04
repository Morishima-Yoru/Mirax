package me.trinitrix.mirax

import android.app.Application
import me.trinitrix.mirax.host.HostEnvironment
import me.trinitrix.mirax.session.DeviceNameReport
import me.trinitrix.mirax.wifi.WifiRadio
import me.trinitrix.mirax.session.MiraxSession
import me.trinitrix.mirax.session.SystemLocaleReport

/**
 * Process-scoped application holding the Mirax session for one stay.
 */
class MiraxApp : Application() {
    lateinit var session: MiraxSession
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // A new process waits to broadcast. A previous stay does not resume advertising.
        session = MiraxSession(SessionPreferences.load(this).copy(advertisingEnabled = false))
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
