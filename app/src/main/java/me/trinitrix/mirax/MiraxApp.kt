package me.trinitrix.mirax

import android.app.Application
import me.trinitrix.mirax.session.DeviceNameReport
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
        session = MiraxSession(SessionPreferences.load(this))
        session.report(SystemLocaleReport(HostEnvironment.isSystemTraditionalChinese()))
        session.report(DeviceNameReport(HostEnvironment.readDeviceName(this)))
        HostEnvironment.applyAppLanguage(session.snapshot().appLanguage)
    }

    companion object {
        lateinit var instance: MiraxApp
            private set
    }
}
