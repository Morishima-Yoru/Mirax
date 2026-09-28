package me.trinitrix.mirax

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import me.trinitrix.mirax.databinding.ActivityMainBinding
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionEffect
import me.trinitrix.mirax.session.SessionSnapshot

/**
 * Renders [me.trinitrix.mirax.session.MiraxSession] output and forwards user actions.
 *
 * Does not own product state; privilege probing is delegated to [PrivilegeProbe].
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var updatingSwitch = false

    private val autoWaitRunnable = object : Runnable {
        override fun run() {
            val session = MiraxApp.instance.session
            session.handle(SessionAction.AutoWait)
            refreshPrivilege()
            render(session.snapshot())
            if (session.snapshot().phase == ScreenPhase.FROZEN) {
                handler.postDelayed(this, AUTO_WAIT_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.waitingRetry.setOnClickListener {
            onRetry()
        }

        binding.advertisingSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSwitch) {
                return@setOnCheckedChangeListener
            }
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetAdvertising(isChecked))
            SessionPreferences.saveAdvertising(this, session.snapshot().advertisingEnabled)
            render(session.snapshot())
        }

        val session = MiraxApp.instance.session
        refreshPrivilege()
        session.handle(SessionAction.OpenApp)
        maybeRequestShizuku(session.snapshot())
        render(session.snapshot())
    }

    override fun onStart() {
        super.onStart()
        refreshPrivilege()
        render(MiraxApp.instance.session.snapshot())
        scheduleAutoWaitIfFrozen()
    }

    override fun onStop() {
        handler.removeCallbacks(autoWaitRunnable)
        super.onStop()
    }

    private fun onRetry() {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.Retry)
        refreshPrivilege()
        render(session.snapshot())
    }

    private fun refreshPrivilege() {
        val session = MiraxApp.instance.session
        session.report(PrivilegeProbe.probe(this))
        applyEffects(session.snapshot())
    }

    private fun applyEffects(snapshot: SessionSnapshot) {
        if (SessionEffect.StopHelper in snapshot.effects) {
            PrivilegeProbe.requestStopHelper()
            MiraxApp.instance.session.handle(SessionAction.AcknowledgeEffects)
        }
    }

    private fun maybeRequestShizuku(snapshot: SessionSnapshot) {
        if (snapshot.shouldRequestShizukuPermission) {
            PrivilegeProbe.requestShizukuPermission(this)
        }
    }

    private fun scheduleAutoWaitIfFrozen() {
        handler.removeCallbacks(autoWaitRunnable)
        if (MiraxApp.instance.session.snapshot().phase == ScreenPhase.FROZEN) {
            handler.postDelayed(autoWaitRunnable, AUTO_WAIT_MS)
        }
    }

    private fun render(snapshot: SessionSnapshot) {
        binding.waitingAdbCommand.text = snapshot.helperStartCommand
        binding.advancedAdbCommand.text = snapshot.helperStartCommand
        val frozen = snapshot.phase == ScreenPhase.FROZEN
        binding.waitingScroll.visibility = if (frozen) View.VISIBLE else View.GONE
        binding.dashboardScroll.visibility = if (frozen) View.GONE else View.VISIBLE

        if (!frozen) {
            binding.displayCardStatus.setText(
                when (snapshot.phase) {
                    ScreenPhase.READY -> R.string.display_card_status_ready
                    ScreenPhase.ADVERTISING -> R.string.display_card_status_advertising
                    ScreenPhase.CONNECTED -> R.string.display_card_status_connected
                    ScreenPhase.FROZEN -> error("frozen branch unreachable when dashboard visible")
                },
            )
            updatingSwitch = true
            binding.advertisingSwitch.isChecked = snapshot.advertisingEnabled
            updatingSwitch = false
        }

        scheduleAutoWaitIfFrozen()
    }

    companion object {
        private const val AUTO_WAIT_MS = 2_000L
    }
}
