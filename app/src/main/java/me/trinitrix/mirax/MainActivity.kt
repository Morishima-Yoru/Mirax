package me.trinitrix.mirax

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import me.trinitrix.mirax.databinding.ActivityMainBinding
import me.trinitrix.mirax.session.AppLanguage
import me.trinitrix.mirax.session.BroadcastNameRules
import me.trinitrix.mirax.session.ConnectionRun
import me.trinitrix.mirax.session.DeviceNameReport
import me.trinitrix.mirax.session.LanguagePreference
import me.trinitrix.mirax.session.MiraxDisplayReport
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.StandardModeGroups
import me.trinitrix.mirax.session.SystemLocaleReport
import me.trinitrix.mirax.session.VideoMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders [me.trinitrix.mirax.session.MiraxSession] and forwards user actions.
 *
 * Narrow windows replace the page. Wide windows keep a 50/50 split: the start
 * pane is the section, the end pane is the open subpage.
 */
class MainActivity : AppCompatActivity() {
    private enum class Page {
        DASHBOARD,
        HISTORY,
        GENERAL,
        LANGUAGE,
        PICTURE,
        CUSTOM,
        MODES,
        SCALE,
        ADVANCED,
        ABOUT,
    }

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var page: Page = Page.DASHBOARD
    private var drawerOpen: Boolean = false
    private var updatingControls: Boolean = false
    private var enableBallWhenOverlayGranted: Boolean = false
    private var appliedAppLanguage: AppLanguage? = null
    private val historyOpen = mutableSetOf<Int>()

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
        savedInstanceState?.getString(STATE_PAGE)?.let { name ->
            page = Page.entries.firstOrNull { it.name == name } ?: Page.DASHBOARD
        }
        binding.menuButton.setOnClickListener { openDrawer() }
        binding.startBack.setOnClickListener { goToParentOfStart() }
        binding.endBack.setOnClickListener { goToParentOfEnd() }
        binding.drawerScrim.setOnClickListener { closeDrawer() }

        val session = MiraxApp.instance.session
        refreshEnvironmentInputs()
        refreshPrivilege()
        session.handle(SessionAction.OpenApp)
        SessionHost.commit(this)
        maybeRequestShizuku(session.snapshot())
        render(session.snapshot())
    }

    override fun onStart() {
        super.onStart()
        instance = this
        showing = true
        refreshEnvironmentInputs()
        refreshPrivilege()
        val session = MiraxApp.instance.session
        if (enableBallWhenOverlayGranted && !session.snapshot().showOverlayPermissionReminder) {
            enableBallWhenOverlayGranted = false
            session.handle(SessionAction.SetFloatingBallEnabled(true))
        }
        render(SessionHost.openedMiraxDashboard(this))
        scheduleAutoWaitIfFrozen()
    }

    override fun onStop() {
        handler.removeCallbacks(autoWaitRunnable)
        if (instance === this) {
            instance = null
        }
        showing = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, page.name)
    }

    private fun renderFromHost() {
        if (!showing || !::binding.isInitialized) {
            return
        }
        render(MiraxApp.instance.session.snapshot())
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

    private fun wideWindow(): Boolean = resources.configuration.screenWidthDp >= WIDE_BREAKPOINT_DP

    private fun parentOf(target: Page): Page? {
        return when (target) {
            Page.HISTORY -> Page.DASHBOARD
            Page.LANGUAGE -> Page.GENERAL
            Page.CUSTOM, Page.MODES, Page.SCALE -> Page.PICTURE
            else -> null
        }
    }

    private fun openPage(target: Page) {
        page = target
        closeDrawer()
        render(MiraxApp.instance.session.snapshot())
    }

    private fun goToParentOfStart() {
        val wide = wideWindow()
        val shown = if (wide) parentOf(page) ?: page else page
        parentOf(shown)?.let { openPage(it) }
    }

    private fun goToParentOfEnd() {
        parentOf(page)?.let { openPage(it) }
    }

    private fun openDrawer() {
        drawerOpen = true
        render(MiraxApp.instance.session.snapshot())
    }

    private fun closeDrawer() {
        if (!drawerOpen) {
            return
        }
        drawerOpen = false
        binding.drawer.visibility = View.GONE
        binding.drawerScrim.visibility = View.GONE
    }

    private fun render(snapshot: SessionSnapshot) {
        val wide = wideWindow()
        val parent = parentOf(page)
        val startPage = if (wide && parent != null) parent else page
        val endPage = if (wide) parent?.let { page } else null
        binding.paneEnd.visibility = if (wide) View.VISIBLE else View.GONE
        val startParams = binding.paneStart.layoutParams as LinearLayout.LayoutParams
        if (wide) {
            startParams.width = 0
            startParams.weight = 1f
        } else {
            startParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            startParams.weight = 0f
        }
        binding.paneStart.layoutParams = startParams

        val startHasParent = parentOf(startPage) != null
        binding.menuButton.visibility = if (startHasParent) View.GONE else View.VISIBLE
        binding.startBack.visibility = if (startHasParent) View.VISIBLE else View.GONE
        binding.endBar.visibility = if (endPage != null) View.VISIBLE else View.GONE

        showPage(binding.pageStart, startPage, snapshot)
        if (endPage != null) {
            showPage(binding.pageEnd, endPage, snapshot)
        } else {
            binding.pageEnd.removeAllViews()
        }
        renderDrawer(snapshot)
        binding.drawer.visibility = if (drawerOpen) View.VISIBLE else View.GONE
        binding.drawerScrim.visibility = if (drawerOpen) View.VISIBLE else View.GONE
        scheduleAutoWaitIfFrozen()
    }

    private fun showPage(host: android.widget.FrameLayout, target: Page, snapshot: SessionSnapshot) {
        val signature = pageSignature(target, snapshot)
        val existing = host.getChildAt(0)
        val samePage = existing?.tag == target && existing.getTag(R.id.page_signature) == signature
        if (!samePage && !hostHasFocus(host)) {
            host.removeAllViews()
            val built = buildPage(target, snapshot)
            built.setTag(R.id.page_signature, signature)
            host.addView(built)
            return
        }
        if (existing != null) {
            updatePage(existing, target, snapshot)
        }
    }

    private fun pageSignature(target: Page, snapshot: SessionSnapshot): String {
        return when (target) {
            Page.DASHBOARD -> "${snapshot.phase}|${snapshot.advertisingEnabled}|${snapshot.connectionRuns.size}"
            Page.PICTURE -> "${customSummary(snapshot)}|${modesSummary(snapshot)}|${scaleLabel(snapshot.pictureScale)}|${snapshot.touchEnabled}"
            Page.CUSTOM -> "${snapshot.autoAddWmSizeOnConnect}|${snapshot.customModes.joinToString { it.format() }}"
            Page.GENERAL -> "${snapshot.displayNameOverride}|${ballOn(snapshot)}|${snapshot.languagePreference}"
            Page.MODES -> snapshot.standardModes.joinToString { "${it.mode.format()}=${it.checked}" }
            Page.SCALE -> snapshot.pictureScale.name
            Page.LANGUAGE -> snapshot.languagePreference.name
            Page.ADVANCED -> snapshot.showDebugMessages.toString()
            Page.HISTORY -> snapshot.connectionRuns.joinToString { "${it.startedAtEpochMs}:${it.succeeded}" } + historyOpen.joinToString()
            Page.ABOUT -> versionName()
        }
    }

    private fun hostHasFocus(host: View): Boolean {
        var focused = currentFocus
        while (focused != null) {
            if (focused === host) {
                return true
            }
            val parent = focused.parent
            focused = parent as? View
        }
        return false
    }

    private fun buildPage(target: Page, snapshot: SessionSnapshot): View {
        val content = when (target) {
            Page.DASHBOARD -> buildDashboard(snapshot)
            Page.HISTORY -> buildHistory(snapshot)
            Page.GENERAL -> buildGeneral(snapshot)
            Page.LANGUAGE -> buildLanguage(snapshot)
            Page.PICTURE -> buildPicture(snapshot)
            Page.CUSTOM -> buildCustom(snapshot)
            Page.MODES -> buildModes(snapshot)
            Page.SCALE -> buildScale(snapshot)
            Page.ADVANCED -> buildAdvanced(snapshot)
            Page.ABOUT -> buildAbout()
        }
        return scroll(content).apply { tag = target }
    }

    private fun updatePage(root: View, target: Page, snapshot: SessionSnapshot) {
        updatingControls = true
        when (target) {
            Page.DASHBOARD -> updateDashboard(root, snapshot)
            Page.GENERAL -> updateGeneral(root, snapshot)
            Page.PICTURE -> updateSwitch(root, "touch", snapshot.touchEnabled)
            Page.CUSTOM -> {
                updateSwitch(root, "auto-wm", snapshot.autoAddWmSizeOnConnect)
                updateCustomList(root, snapshot)
            }
            Page.MODES -> updateModes(root, snapshot)
            Page.LANGUAGE -> updateLanguage(root, snapshot)
            Page.SCALE -> updateScale(root, snapshot)
            Page.ADVANCED -> updateSwitch(root, "debug", snapshot.showDebugMessages)
            Page.HISTORY -> replacePage(root, target, snapshot)
            Page.ABOUT -> Unit
        }
        updatingControls = false
    }

    private fun replacePage(root: View, target: Page, snapshot: SessionSnapshot) {
        val host = root.parent as? android.widget.FrameLayout ?: return
        updatingControls = false
        host.removeAllViews()
        host.addView(buildPage(target, snapshot))
    }

    private fun buildDashboard(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        if (snapshot.phase == ScreenPhase.FROZEN) {
            column.addView(title(getString(R.string.waiting_title), 32f).apply { tag = "phase" })
            column.addView(body(getString(R.string.waiting_body)))
            column.addView(section(getString(R.string.waiting_adb_label)))
            column.addView(body(snapshot.helperStartCommand).apply {
                tag = "adb"
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
            column.addView(textButton(getString(R.string.share_helper)) {
                shareText(snapshot.helperStartCommand)
            })
            column.addView(filledButton(getString(R.string.waiting_retry)) { onRetry() })
            return column
        }
        column.addView(title(dashboardTitle(snapshot), 36f).apply { tag = "phase" })
        column.addView(body(dashboardHint(snapshot)).apply { tag = "hint" })
        val log = body("").apply {
            tag = "log"
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        column.addView(section(getString(R.string.handshake_label)).apply { tag = "log-label" })
        column.addView(log)
        column.addView(filledButton(broadcastLabel(snapshot)) {
            val live = MiraxApp.instance.session.snapshot()
            if (live.phase == ScreenPhase.FROZEN) {
                return@filledButton
            }
            render(SessionHost.setAdvertising(this, !live.advertisingEnabled))
        }.apply { tag = "broadcast" })
        column.addView(jump(getString(R.string.history_entry), historyCount(snapshot)) {
            openPage(Page.HISTORY)
        })
        updateDashboard(column, snapshot)
        return column
    }

    private fun updateDashboard(root: View, snapshot: SessionSnapshot) {
        if (snapshot.phase == ScreenPhase.FROZEN) {
            return
        }
        val column = root as? LinearLayout ?: (root as ScrollView).getChildAt(0) as LinearLayout
        (column.findViewWithTag<TextView>("phase"))?.text = dashboardTitle(snapshot)
        (column.findViewWithTag<TextView>("hint"))?.text = dashboardHint(snapshot)
        val connecting = snapshot.phase == ScreenPhase.CONNECTING
        column.findViewWithTag<TextView>("log")?.let { log ->
            log.text = snapshot.handshakeLog
            log.visibility = if (connecting) View.VISIBLE else View.GONE
        }
        column.findViewWithTag<TextView>("log-label")?.visibility =
            if (connecting) View.VISIBLE else View.GONE
        if (connecting) {
            val scroll = root as? ScrollView ?: column.parent as? ScrollView
            scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        column.findViewWithTag<MaterialButton>("broadcast")?.text = broadcastLabel(snapshot)
    }

    private fun dashboardTitle(snapshot: SessionSnapshot): String {
        return when (snapshot.phase) {
            ScreenPhase.READY -> getString(R.string.display_card_status_ready)
            ScreenPhase.CONNECTING -> getString(R.string.keepalive_status_connecting)
            ScreenPhase.ADVERTISING, ScreenPhase.CONNECTED ->
                getString(R.string.display_card_status_advertising)
            ScreenPhase.FROZEN -> getString(R.string.waiting_title)
        }
    }

    private fun dashboardHint(snapshot: SessionSnapshot): String {
        return if (snapshot.phase == ScreenPhase.CONNECTING) {
            getString(R.string.connecting_hint)
        } else {
            getString(R.string.dashboard_hint)
        }
    }

    private fun broadcastLabel(snapshot: SessionSnapshot): String {
        return getString(
            if (snapshot.advertisingEnabled) R.string.stop_broadcast else R.string.start_broadcast,
        )
    }

    private fun buildHistory(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(title(getString(R.string.history_title), 26f))
        column.addView(body(getString(R.string.history_body)))
        if (snapshot.connectionRuns.isEmpty()) {
            column.addView(body(getString(R.string.custom_empty)))
            return column
        }
        snapshot.connectionRuns.forEachIndexed { index, run ->
            column.addView(historyRun(index, run))
        }
        return column
    }

    private fun historyRun(index: Int, run: ConnectionRun): LinearLayout {
        val block = column()
        val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(run.startedAtEpochMs))
        val host = run.remoteHost.ifBlank { getString(R.string.history_unknown_host) }
        val result = getString(if (run.succeeded) R.string.history_success else R.string.history_failure)
        block.addView(jump("$host · $result", whenText) {
            if (index in historyOpen) historyOpen.remove(index) else historyOpen.add(index)
            render(MiraxApp.instance.session.snapshot())
        })
        if (index !in historyOpen) {
            return block
        }
        block.addView(section(getString(R.string.history_metadata)))
        block.addView(facts(run.metadata.map { it.label to it.value }))
        block.addView(section(getString(R.string.history_configuration)))
        block.addView(facts(run.configuration.map { it.label to it.value }))
        block.addView(section(getString(R.string.history_log)))
        block.addView(body(run.log.ifBlank { getString(R.string.fact_none) }).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        })
        return block
    }

    private fun facts(rows: List<Pair<String, String>>): LinearLayout {
        val column = column()
        for ((label, value) in rows) {
            column.addView(body("${factLabel(label)}  ${factValue(label, value)}"))
        }
        return column
    }

    private fun factLabel(label: String): String {
        return when (label) {
            "broadcast name" -> getString(R.string.fact_broadcast_name)
            "custom resolutions" -> getString(R.string.fact_custom_resolutions)
            "standard modes" -> getString(R.string.fact_standard_modes)
            "touch" -> getString(R.string.fact_touch)
            "selected mode" -> getString(R.string.fact_selected_mode)
            else -> label
        }
    }

    private fun factValue(label: String, value: String): String {
        return when {
            value == "none" -> getString(R.string.fact_none)
            label == "touch" && (value == "on" || value == "touch") -> getString(R.string.fact_touch_on)
            label == "touch" && (value == "off" || value == "display only") ->
                getString(R.string.fact_touch_off)
            else -> value
        }
    }

    private fun buildGeneral(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(title(getString(R.string.nav_general), 26f))
        column.addView(section(getString(R.string.section_broadcast_name)))
        val name = EditText(this).apply {
            tag = "name"
            setTextColor(getColor(R.color.mirax_on_surface))
            setHintTextColor(getColor(R.color.mirax_muted))
            hint = if (snapshot.displayNameFollowsDevice) {
                snapshot.displayNameFieldHint
            } else {
                getString(R.string.section_broadcast_name)
            }
            setText(if (snapshot.displayNameFollowsDevice) "" else snapshot.displayNameOverride.orEmpty())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val cleaned = BroadcastNameRules.sanitize(s?.toString().orEmpty())
                    if (cleaned != s?.toString()) {
                        setText(cleaned)
                        setSelection(cleaned.length)
                    }
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    commitBroadcastName(this)
                }
            }
        }
        column.addView(name)
        column.addView(section(getString(R.string.section_leave_projection)))
        column.addView(switchRow(getString(R.string.show_floating_ball), ballOn(snapshot)) { enabled ->
            onFloatingBall(enabled)
        }.apply { tag = "ball" })
        column.addView(jump(getString(R.string.settings_language_title), languageLabel(snapshot.languagePreference)) {
            openPage(Page.LANGUAGE)
        })
        return column
    }

    private fun updateGeneral(root: View, snapshot: SessionSnapshot) {
        val column = contentColumn(root)
        val name = column.findViewWithTag<EditText>("name")
        if (name != null && !name.hasFocus()) {
            name.hint = if (snapshot.displayNameFollowsDevice) {
                snapshot.displayNameFieldHint
            } else {
                getString(R.string.section_broadcast_name)
            }
            val text = if (snapshot.displayNameFollowsDevice) "" else snapshot.displayNameOverride.orEmpty()
            if (name.text?.toString() != text) {
                name.setText(text)
            }
        }
        updateSwitch(column, "ball", ballOn(snapshot))
    }

    private fun ballOn(snapshot: SessionSnapshot): Boolean {
        return snapshot.floatingBallEnabled && !snapshot.showOverlayPermissionReminder
    }

    private fun onFloatingBall(enabled: Boolean) {
        if (!enabled) {
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetFloatingBallEnabled(false))
            SessionPreferences.saveFloatingBallEnabled(this, false)
            render(session.snapshot())
            return
        }
        val session = MiraxApp.instance.session
        if (!session.snapshot().showOverlayPermissionReminder) {
            session.handle(SessionAction.SetFloatingBallEnabled(true))
            SessionPreferences.saveFloatingBallEnabled(this, true)
            render(session.snapshot())
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_request_title)
            .setMessage(R.string.overlay_request_body)
            .setNegativeButton(R.string.overlay_deny) { _, _ ->
                enableBallWhenOverlayGranted = false
                AlertDialog.Builder(this)
                    .setTitle(R.string.overlay_denied_title)
                    .setMessage(R.string.overlay_denied_body)
                    .setPositiveButton(R.string.overlay_dismiss, null)
                    .show()
                render(MiraxApp.instance.session.snapshot())
            }
            .setPositiveButton(R.string.overlay_grant) { _, _ ->
                enableBallWhenOverlayGranted = true
                render(SessionHost.requestOverlayPermission(this))
            }
            .setOnCancelListener { render(MiraxApp.instance.session.snapshot()) }
            .show()
    }

    private fun commitBroadcastName(field: EditText) {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.SetDisplayNameOverride(field.text?.toString().orEmpty()))
        val snap = session.snapshot()
        SessionPreferences.saveDisplayNameOverride(this, snap.displayNameOverride)
        render(snap)
    }

    private fun buildLanguage(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(title(getString(R.string.settings_language_title), 26f))
        val group = RadioGroup(this).apply { tag = "language" }
        group.addView(radio(getString(R.string.language_follow_system), LanguagePreference.FOLLOW_SYSTEM.name))
        group.addView(radio(getString(R.string.language_traditional_chinese), LanguagePreference.TRADITIONAL_CHINESE.name))
        group.addView(radio(getString(R.string.language_english), LanguagePreference.ENGLISH.name))
        group.setOnCheckedChangeListener { _, checkedId ->
            if (updatingControls) {
                return@setOnCheckedChangeListener
            }
            val button = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            val preference = LanguagePreference.valueOf(button.tag as String)
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetLanguagePreference(preference))
            SessionPreferences.saveLanguagePreference(this, preference)
            applyResolvedLanguage(session.snapshot().appLanguage, recreateUi = true)
        }
        column.addView(group)
        updateLanguage(column, snapshot)
        return column
    }

    private fun updateLanguage(root: View, snapshot: SessionSnapshot) {
        val group = contentColumn(root).findViewWithTag<RadioGroup>("language") ?: return
        val want = snapshot.languagePreference.name
        for (index in 0 until group.childCount) {
            val button = group.getChildAt(index) as RadioButton
            if (button.tag == want && !button.isChecked) {
                group.check(button.id)
            }
        }
    }

    private fun buildPicture(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(title(getString(R.string.nav_picture), 26f))
        column.addView(jump(getString(R.string.custom_resolution), customSummary(snapshot)) {
            openPage(Page.CUSTOM)
        })
        column.addView(jump(getString(R.string.standard_modes_label), modesSummary(snapshot)) {
            openPage(Page.MODES)
        })
        column.addView(jump(getString(R.string.settings_scale_title), scaleLabel(snapshot.pictureScale)) {
            openPage(Page.SCALE)
        })
        column.addView(switchRow(getString(R.string.section_touch), snapshot.touchEnabled) { enabled ->
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetTouchEnabled(enabled))
            persist(session)
            render(session.snapshot())
        }.apply { tag = "touch" })
        return column
    }

    private fun buildCustom(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(title(getString(R.string.custom_resolution), 26f))
        column.addView(body(getString(R.string.custom_resolution_body)))
        column.addView(switchRow(getString(R.string.auto_wm_size), snapshot.autoAddWmSizeOnConnect) { enabled ->
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetAutoAddWmSizeOnConnect(enabled))
            persist(session)
            render(session.snapshot())
        }.apply { tag = "auto-wm" })
        column.addView(body(getString(R.string.auto_wm_size_hint)))
        val width = numberField(getString(R.string.custom_width)).apply { tag = "w" }
        val height = numberField(getString(R.string.custom_height)).apply { tag = "h" }
        val refresh = numberField(getString(R.string.custom_refresh)).apply { tag = "hz" }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(width, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(height, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(refresh, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        column.addView(fields)
        column.addView(textButton(getString(R.string.custom_add)) {
            addCustomFrom(width, height, refresh)
        })
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "custom-list"
        })
        column.addView(section(getString(R.string.custom_disclaimer_title)))
        column.addView(body(getString(R.string.custom_disclaimer_body)))
        updateCustomList(column, snapshot)
        return column
    }

    private fun addCustomFrom(width: EditText, height: EditText, refresh: EditText) {
        val w = width.text?.toString()?.trim()?.toIntOrNull()
        val h = height.text?.toString()?.trim()?.toIntOrNull()
        val hz = refresh.text?.toString()?.trim()?.toIntOrNull()
        if (w == null || h == null || hz == null || w <= 0 || h <= 0 || hz <= 0) {
            return
        }
        val session = MiraxApp.instance.session
        session.handle(SessionAction.AddCustomMode(w, h, hz))
        persist(session)
        width.setText("")
        height.setText("")
        refresh.setText("")
        render(session.snapshot())
    }

    private fun updateCustomList(root: View, snapshot: SessionSnapshot) {
        val list = contentColumn(root).findViewWithTag<LinearLayout>("custom-list") ?: return
        list.removeAllViews()
        if (snapshot.customModes.isEmpty()) {
            list.addView(body(getString(R.string.custom_empty)))
            return
        }
        snapshot.customModes.forEachIndexed { index, mode ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(body(mode.format()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(textButton(getString(R.string.custom_up)) {
                moveCustom(index, index - 1)
            })
            row.addView(textButton(getString(R.string.custom_down)) {
                moveCustom(index, index + 1)
            })
            row.addView(textButton(getString(R.string.custom_remove)) {
                val session = MiraxApp.instance.session
                session.handle(SessionAction.RemoveCustomMode(mode))
                persist(session)
                render(session.snapshot())
            })
            list.addView(row)
        }
    }

    private fun moveCustom(from: Int, to: Int) {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.MoveCustomMode(from, to))
        persist(session)
        render(session.snapshot())
    }

    private fun buildModes(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(title(getString(R.string.standard_modes_label), 26f))
        val list = column().apply { tag = "modes" }
        column.addView(list)
        updateModes(column, snapshot)
        return column
    }

    private fun updateModes(root: View, snapshot: SessionSnapshot) {
        val list = contentColumn(root).findViewWithTag<LinearLayout>("modes") ?: return
        list.removeAllViews()
        for (group in StandardModeGroups.groups(snapshot.standardModes)) {
            list.addView(section(group.label))
            for (row in group.rows) {
                list.addView(switchRow(row.mode.format(), row.checked) { checked ->
                    val session = MiraxApp.instance.session
                    session.handle(SessionAction.SetStandardModeChecked(row.mode, checked))
                    persist(session)
                    render(session.snapshot())
                })
            }
        }
    }

    private fun buildScale(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(title(getString(R.string.settings_scale_title), 26f))
        val group = RadioGroup(this).apply { tag = "scale" }
        group.addView(radio(getString(R.string.scale_proportional), PictureScale.PROPORTIONAL.name))
        group.addView(radio(getString(R.string.scale_center_crop), PictureScale.CENTER_CROP.name))
        group.addView(radio(getString(R.string.scale_match_edges), PictureScale.MATCH_EDGES.name))
        group.addView(radio(getString(R.string.scale_actual), PictureScale.ACTUAL.name))
        group.setOnCheckedChangeListener { _, checkedId ->
            if (updatingControls) {
                return@setOnCheckedChangeListener
            }
            val button = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            val scale = PictureScale.valueOf(button.tag as String)
            render(SessionHost.setPictureScale(this, scale))
        }
        column.addView(group)
        updateScale(column, snapshot)
        return column
    }

    private fun updateScale(root: View, snapshot: SessionSnapshot) {
        val group = contentColumn(root).findViewWithTag<RadioGroup>("scale") ?: return
        val want = snapshot.pictureScale.name
        for (index in 0 until group.childCount) {
            val button = group.getChildAt(index) as RadioButton
            if (button.tag == want && !button.isChecked) {
                group.check(button.id)
            }
        }
    }

    private fun buildAdvanced(snapshot: SessionSnapshot): LinearLayout {
        val column = column()
        column.addView(title(getString(R.string.nav_advanced), 26f))
        column.addView(switchRow(getString(R.string.debug_messages), snapshot.showDebugMessages) { enabled ->
            val session = MiraxApp.instance.session
            session.handle(SessionAction.SetShowDebugMessages(enabled))
            persist(session)
            render(session.snapshot())
        }.apply { tag = "debug" })
        return column
    }

    private fun buildAbout(): LinearLayout {
        val column = column()
        column.addView(title(getString(R.string.nav_about), 26f))
        column.addView(section(getString(R.string.about_version)))
        column.addView(title(versionName(), 22f))
        column.addView(body(getString(R.string.about_body)))
        return column
    }

    private fun renderDrawer(snapshot: SessionSnapshot) {
        val drawer = binding.drawerBody
        drawer.removeAllViews()
        drawer.addView(title(getString(R.string.nav_menu), 20f))
        drawer.addView(drawerRow(Page.DASHBOARD, getString(R.string.nav_dashboard), dashboardTitle(snapshot)))
        drawer.addView(section(getString(R.string.nav_settings)))
        drawer.addView(drawerRow(Page.GENERAL, getString(R.string.nav_general), snapshot.effectiveBroadcastName))
        drawer.addView(drawerRow(Page.PICTURE, getString(R.string.nav_picture), pictureDetail(snapshot)))
        drawer.addView(drawerRow(Page.ADVANCED, getString(R.string.nav_advanced), ""))
        drawer.addView(section(getString(R.string.nav_about_group)))
        drawer.addView(drawerRow(Page.ABOUT, getString(R.string.nav_about), versionName()))
    }

    private fun drawerRow(target: Page, label: String, detail: String): LinearLayout {
        return jump(label, detail) { openPage(target) }
    }

    private fun pictureDetail(snapshot: SessionSnapshot): String {
        val labels = snapshot.customModes.map { it.format() } +
            snapshot.standardModes.filter { it.checked }.map { it.mode.format() }
        val first = labels.firstOrNull() ?: getString(R.string.custom_empty)
        val others = (labels.size - 1).coerceAtLeast(0)
        val scale = scaleLabel(snapshot.pictureScale)
        if (others == 0) {
            return "$scale · $first"
        }
        return getString(R.string.picture_menu_detail, scale, first, others)
    }

    private fun customSummary(snapshot: SessionSnapshot): String {
        val modes = snapshot.customModes
        if (modes.isEmpty()) {
            return getString(R.string.custom_empty)
        }
        if (modes.size == 1) {
            return modes[0].format()
        }
        return "${modes.size} · ${modes[0].format()}"
    }

    private fun modesSummary(snapshot: SessionSnapshot): String {
        val checked = snapshot.standardModes.count { it.checked }
        return getString(R.string.modes_checked_count, checked)
    }

    private fun historyCount(snapshot: SessionSnapshot): String = snapshot.connectionRuns.size.toString()

    private fun languageLabel(preference: LanguagePreference): String {
        return when (preference) {
            LanguagePreference.FOLLOW_SYSTEM -> getString(R.string.language_follow_system)
            LanguagePreference.TRADITIONAL_CHINESE -> getString(R.string.language_traditional_chinese)
            LanguagePreference.ENGLISH -> getString(R.string.language_english)
        }
    }

    private fun scaleLabel(scale: PictureScale): String {
        return when (scale) {
            PictureScale.PROPORTIONAL -> getString(R.string.scale_proportional)
            PictureScale.CENTER_CROP -> getString(R.string.scale_center_crop)
            PictureScale.MATCH_EDGES -> getString(R.string.scale_match_edges)
            PictureScale.ACTUAL -> getString(R.string.scale_actual)
        }
    }

    private fun persist(session: me.trinitrix.mirax.session.MiraxSession) {
        SessionPreferences.saveResolutionSettings(this, session.exportSettings())
    }

    private fun shareText(text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                getString(R.string.share_helper),
            ),
        )
    }

    private fun contentColumn(root: View): LinearLayout {
        return if (root is ScrollView) root.getChildAt(0) as LinearLayout else root as LinearLayout
    }

    private fun updateSwitch(root: View, tag: String, checked: Boolean) {
        val column = if (root is LinearLayout && root.findViewWithTag<View>(tag) != null) {
            root
        } else {
            contentColumn(root)
        }
        val switch = column.findViewWithTag<MaterialSwitch>(tag) ?: return
        if (switch.isChecked != checked) {
            switch.isChecked = checked
        }
    }

    private fun scroll(content: View): ScrollView {
        return ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }
    }

    private fun column(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(32))
        }
    }

    private fun title(text: String, sizeSp: Float): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.mirax_on_surface))
            textSize = sizeSp
        }
    }

    private fun body(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.mirax_muted))
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
        }
    }

    private fun section(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.mirax_on_surface))
            textSize = 13f
            setPadding(0, dp(20), 0, dp(8))
        }
    }

    private fun jump(label: String, detail: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(getColor(R.color.mirax_surface))
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = label
                setTextColor(getColor(R.color.mirax_on_surface))
                textSize = 17f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (detail.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = detail
                    setTextColor(getColor(R.color.mirax_muted))
                    textSize = 14f
                })
            }
        }.also { row ->
            (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
    }

    private fun filledButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) }
        }
    }

    private fun textButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = label
            setOnClickListener { onClick() }
        }
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch {
        return MaterialSwitch(this).apply {
            text = label
            isChecked = checked
            setTextColor(getColor(R.color.mirax_on_surface))
            setOnCheckedChangeListener { _, isChecked ->
                if (updatingControls) {
                    return@setOnCheckedChangeListener
                }
                onChange(isChecked)
            }
        }
    }

    private fun radio(label: String, value: String): RadioButton {
        return RadioButton(this).apply {
            text = label
            tag = value
            id = View.generateViewId()
            setTextColor(getColor(R.color.mirax_on_surface))
        }
    }

    private fun numberField(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(getColor(R.color.mirax_on_surface))
            setHintTextColor(getColor(R.color.mirax_muted))
        }
    }

    private fun versionName(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val AUTO_WAIT_MS = 2_000L
        private const val WIDE_BREAKPOINT_DP = 600
        private const val STATE_PAGE = "dashboard_page"

        @Volatile
        private var showing: Boolean = false

        @Volatile
        private var instance: MainActivity? = null

        fun isShowing(): Boolean = showing

        fun refreshIfShowing() {
            val activity = instance ?: return
            activity.runOnUiThread { activity.renderFromHost() }
        }
    }
}
