package me.trinitrix.mirax

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import me.trinitrix.mirax.databinding.ActivityMainBinding
import me.trinitrix.mirax.session.AppLanguage
import me.trinitrix.mirax.session.DeviceNameReport
import me.trinitrix.mirax.session.LanguagePreference
import me.trinitrix.mirax.session.MiraxDisplayReport
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.SystemLocaleReport
import me.trinitrix.mirax.session.VideoMode

/**
 * Renders [me.trinitrix.mirax.session.MiraxSession] output and forwards user actions.
 *
 * Does not own product state; privilege probing is delegated to [PrivilegeProbe].
 * Language, display-name, preferred-mode text, and standard-mode checks stay
 * available while the session is frozen. Resolution UI only renders the session
 * advertisement-set output and forwards edit, leave-field, and use-this-screen.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var updatingSwitch = false
    private var updatingLanguage = false
    private var updatingPreferredMode = false
    private var updatingStandardModes = false
    private var updatingBottomHandle = false
    private var updatingFloatingBall = false
    private var preferredModeEdited = false
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

        binding.preferredModeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updatingPreferredMode || preferredModeEdited) {
                    return
                }
                val session = MiraxApp.instance.session
                // View-state restore rewrites the same text; only a real change is an edit.
                if (s?.toString().orEmpty() == session.snapshot().preferredModeText) {
                    return
                }
                preferredModeEdited = true
                session.handle(SessionAction.PreferredModeFieldEdited)
                SessionPreferences.saveResolutionSettings(this@MainActivity, session.exportSettings())
            }
        })
        binding.preferredModeInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                commitPreferredModeFromField()
            }
        }
        binding.preferredModeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitPreferredModeFromField()
                true
            } else {
                false
            }
        }
        binding.useThisScreenButton.setOnClickListener {
            val displayId = binding.root.display?.displayId ?: 0
            MiraxApp.instance.session.report(MiraxDisplayReport(displayId))
            render(SessionHost.useThisScreen(this, displayId))
        }

        binding.bottomHandleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingBottomHandle) {
                return@setOnCheckedChangeListener
            }
            render(SessionHost.setBottomHandleEnabled(this, isChecked))
        }

        binding.floatingBallSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingFloatingBall) {
                return@setOnCheckedChangeListener
            }
            render(SessionHost.setFloatingBallEnabled(this, isChecked))
        }

        binding.overlayReminderButton.setOnClickListener {
            render(SessionHost.requestOverlayPermission(this))
        }

        val session = MiraxApp.instance.session
        refreshEnvironmentInputs()
        refreshPrivilege()
        session.handle(SessionAction.OpenApp)
        // Apply OpenApp effects (including the initial overlay permission request).
        SessionHost.commit(this)
        maybeRequestShizuku(session.snapshot())
        render(session.snapshot())
    }

    override fun onStart() {
        super.onStart()
        showing = true
        refreshEnvironmentInputs()
        refreshPrivilege()
        // Opening Mirax's dashboard while connected is not "going home".
        render(SessionHost.openedMiraxDashboard(this))
        scheduleAutoWaitIfFrozen()
    }

    override fun onStop() {
        handler.removeCallbacks(autoWaitRunnable)
        showing = false
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
        session.report(MiraxDisplayReport(binding.root.display?.displayId ?: 0))
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

    private fun commitPreferredModeFromField() {
        val session = MiraxApp.instance.session
        val raw = binding.preferredModeInput.text?.toString().orEmpty()
        if (!preferredModeEdited && raw == session.snapshot().preferredModeText) {
            return
        }
        session.handle(SessionAction.CommitPreferredModeText(raw))
        SessionPreferences.saveResolutionSettings(this, session.exportSettings())
        preferredModeEdited = false
        render(session.snapshot())
    }

    private fun render(snapshot: SessionSnapshot) {
        binding.waitingAdbCommand.text = snapshot.helperStartCommand
        binding.advancedAdbCommand.text = snapshot.helperStartCommand
        val frozen = snapshot.phase == ScreenPhase.FROZEN
        binding.waitingScroll.visibility = if (frozen) View.VISIBLE else View.GONE
        binding.dashboardScroll.visibility = if (frozen) View.GONE else View.VISIBLE

        renderLanguage(snapshot)
        renderDisplayName(snapshot)
        renderPreferredMode(snapshot)
        renderStandardModes(snapshot)
        renderBottomHandle(snapshot)
        renderFloatingBall(snapshot)
        renderOverlayReminder(snapshot)
        binding.useThisScreenButton.isEnabled = snapshot.canUseThisScreen

        if (!frozen) {
            binding.displayCardName.text = snapshot.effectiveBroadcastName
            binding.displayCardResolution.text = snapshot.currentResolutionText.ifEmpty {
                getString(R.string.display_card_resolution_none)
            }
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
            if (snapshot.displayNameFollowsDevice) {
                binding.displayNameLayout.hint = snapshot.displayNameFieldHint
            }
            return
        }
        if (snapshot.displayNameFollowsDevice) {
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

    private fun renderPreferredMode(snapshot: SessionSnapshot) {
        if (binding.preferredModeInput.hasFocus()) {
            return
        }
        val text = snapshot.preferredModeText
        if (binding.preferredModeInput.text?.toString() != text) {
            updatingPreferredMode = true
            binding.preferredModeInput.setText(text)
            binding.preferredModeInput.setSelection(text.length)
            updatingPreferredMode = false
        }
    }

    private fun renderStandardModes(snapshot: SessionSnapshot) {
        val list = binding.standardModesList
        val existing = mutableMapOf<VideoMode, CheckBox>()
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i) as? CheckBox ?: continue
            val mode = child.tag as? VideoMode ?: continue
            existing[mode] = child
        }
        if (existing.keys != snapshot.standardModes.map { it.mode }.toSet()) {
            list.removeAllViews()
            updatingStandardModes = true
            for (row in snapshot.standardModes) {
                val box = CheckBox(this).apply {
                    text = row.mode.format()
                    tag = row.mode
                    isChecked = row.checked
                    setTextColor(getColor(R.color.mirax_on_surface))
                    setOnCheckedChangeListener { _, isChecked ->
                        if (updatingStandardModes) {
                            return@setOnCheckedChangeListener
                        }
                        val session = MiraxApp.instance.session
                        session.handle(SessionAction.SetStandardModeChecked(row.mode, isChecked))
                        SessionPreferences.saveResolutionSettings(
                            this@MainActivity,
                            session.exportSettings(),
                        )
                        render(session.snapshot())
                    }
                }
                list.addView(box)
            }
            updatingStandardModes = false
            return
        }
        updatingStandardModes = true
        for (row in snapshot.standardModes) {
            existing[row.mode]?.isChecked = row.checked
        }
        updatingStandardModes = false
    }

    private fun renderBottomHandle(snapshot: SessionSnapshot) {
        if (binding.bottomHandleSwitch.isChecked != snapshot.bottomHandleEnabled) {
            updatingBottomHandle = true
            binding.bottomHandleSwitch.isChecked = snapshot.bottomHandleEnabled
            updatingBottomHandle = false
        }
    }

    private fun renderFloatingBall(snapshot: SessionSnapshot) {
        if (binding.floatingBallSwitch.isChecked != snapshot.floatingBallEnabled) {
            updatingFloatingBall = true
            binding.floatingBallSwitch.isChecked = snapshot.floatingBallEnabled
            updatingFloatingBall = false
        }
    }

    private fun renderOverlayReminder(snapshot: SessionSnapshot) {
        val show = !frozenPhase(snapshot) && snapshot.showOverlayPermissionReminder
        binding.overlayReminder.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun frozenPhase(snapshot: SessionSnapshot): Boolean =
        snapshot.phase == ScreenPhase.FROZEN

    companion object {
        private const val AUTO_WAIT_MS = 2_000L

        @Volatile
        private var showing: Boolean = false

        fun isShowing(): Boolean = showing
    }
}
