package me.trinitrix.mirax

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import me.trinitrix.mirax.databinding.ActivityMainBinding
import me.trinitrix.mirax.session.AppLanguage
import me.trinitrix.mirax.session.DeviceNameReport
import me.trinitrix.mirax.session.LanguagePreference
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.SystemLocaleReport

/**
 * Renders [me.trinitrix.mirax.session.MiraxSession] output and forwards user actions.
 *
 * Does not own product state; privilege probing is delegated to [PrivilegeProbe].
 * Language and display-name controls stay available while the session is frozen.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var updatingSwitch = false
    private var updatingLanguage = false
    private var appliedAppLanguage: AppLanguage? = null

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
            render(SessionHost.setAdvertising(this, isChecked))
        }

        binding.languageGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingLanguage) {
                return@setOnCheckedChangeListener
            }
            val preference = when (checkedId) {
                binding.languageTraditionalChinese.id -> LanguagePreference.TRADITIONAL_CHINESE
                binding.languageEnglish.id -> LanguagePreference.ENGLISH
                else -> LanguagePreference.FOLLOW_SYSTEM
            }
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetLanguagePreference(preference))
            SessionPreferences.saveLanguagePreference(this, preference)
            applyResolvedLanguage(session.snapshot().appLanguage, recreateUi = true)
        }

        binding.displayNameSave.setOnClickListener {
            saveDisplayNameFromField()
        }
        binding.displayNameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveDisplayNameFromField()
                true
            } else {
                false
            }
        }

        val session = MiraxApp.instance.session
        refreshEnvironmentInputs()
        refreshPrivilege()
        session.handle(SessionAction.OpenApp)
        maybeRequestShizuku(session.snapshot())
        render(session.snapshot())
    }

    override fun onStart() {
        super.onStart()
        refreshEnvironmentInputs()
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
        refreshEnvironmentInputs()
        refreshPrivilege()
        render(session.snapshot())
    }

    private fun refreshEnvironmentInputs() {
        val session = MiraxApp.instance.session
        session.report(SystemLocaleReport(HostEnvironment.isSystemTraditionalChinese()))
        session.report(DeviceNameReport(HostEnvironment.readDeviceName(this)))
        applyResolvedLanguage(session.snapshot().appLanguage, recreateUi = true)
    }

    private fun applyResolvedLanguage(language: AppLanguage, recreateUi: Boolean) {
        if (appliedAppLanguage == language) {
            return
        }
        HostEnvironment.applyAppLanguage(language)
        val previous = appliedAppLanguage
        appliedAppLanguage = language
        if (recreateUi && previous != null) {
            recreate()
        }
    }

    private fun refreshPrivilege() {
        SessionHost.refreshPrivilegeAndSurfaces(this)
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

    private fun saveDisplayNameFromField() {
        val session = MiraxApp.instance.session
        val raw = binding.displayNameInput.text?.toString().orEmpty()
        session.handle(SessionAction.SetDisplayNameOverride(raw))
        val snap = session.snapshot()
        SessionPreferences.saveDisplayNameOverride(this, snap.displayNameOverride)
        render(snap)
    }

    private fun render(snapshot: SessionSnapshot) {
        binding.waitingAdbCommand.text = snapshot.helperStartCommand
        binding.advancedAdbCommand.text = snapshot.helperStartCommand
        val frozen = snapshot.phase == ScreenPhase.FROZEN
        binding.waitingScroll.visibility = if (frozen) View.VISIBLE else View.GONE
        binding.dashboardScroll.visibility = if (frozen) View.GONE else View.VISIBLE

        renderLanguage(snapshot)
        renderDisplayName(snapshot)

        if (!frozen) {
            binding.displayCardName.text = snapshot.effectiveBroadcastName
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

    private fun renderLanguage(snapshot: SessionSnapshot) {
        val checkedId = when (snapshot.languagePreference) {
            LanguagePreference.FOLLOW_SYSTEM -> binding.languageFollowSystem.id
            LanguagePreference.TRADITIONAL_CHINESE -> binding.languageTraditionalChinese.id
            LanguagePreference.ENGLISH -> binding.languageEnglish.id
        }
        if (binding.languageGroup.checkedRadioButtonId != checkedId) {
            updatingLanguage = true
            binding.languageGroup.check(checkedId)
            updatingLanguage = false
        }
    }

    private fun renderDisplayName(snapshot: SessionSnapshot) {
        if (binding.displayNameInput.hasFocus()) {
            // Keep in-progress edits; still refresh the gray device-name hint while following.
            if (snapshot.displayNameFollowsDevice) {
                binding.displayNameLayout.hint = snapshot.displayNameFieldHint
            }
            return
        }
        if (snapshot.displayNameFollowsDevice) {
            // Gray hint shows the live device name; it is not a saved override.
            binding.displayNameLayout.hint = snapshot.displayNameFieldHint
            if (binding.displayNameInput.text?.isNotEmpty() == true) {
                binding.displayNameInput.setText("")
            }
        } else {
            binding.displayNameLayout.hint = getString(R.string.settings_display_name_title)
            val override = snapshot.displayNameOverride.orEmpty()
            if (binding.displayNameInput.text?.toString() != override) {
                binding.displayNameInput.setText(override)
                binding.displayNameInput.setSelection(override.length)
            }
        }
    }

    companion object {
        private const val AUTO_WAIT_MS = 2_000L
    }
}
