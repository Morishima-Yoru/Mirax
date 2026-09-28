package me.trinitrix.mirax

import android.app.Application
import me.trinitrix.mirax.session.MiraxSession

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
    }

    companion object {
        lateinit var instance: MiraxApp
            private set
    }
}
