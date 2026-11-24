package me.trinitrix.mirax.ui.standby.settings

import android.content.Context
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.trinitrix.mirax.R
import me.trinitrix.mirax.data.standby.theme.StandbyLanguage
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId

/**
 * Embeddable standby settings panel; the host wires gateways and handles
 * locale re-measurement.
 *
 * Shows three theme choices (Landscape / Matter / Hardline) and three
 * language choices (follow-system / Traditional Chinese / English). All display
 * strings come from resources; stored identifiers are never translated.
 * Selection is announced with a text marker plus `contentDescription`, never
 * by color alone. Every option has at least a 48dp touch target (both
 * `minimumWidth` and `minimumHeight`), and DPAD focus forms a complete chain:
 * up/down/left/right plus forward all follow theme options then language
 * options (vertical list, so left mirrors up and right mirrors down).
 *
 * State reads and writes go only through [StandbyThemeGateway] and
 * [StandbyLanguageGateway]. Successful language writes invoke
 * [onLocaleChangeRequested] after the state callback so the host can re-measure;
 * this view never edits the configuration itself. After a locale change the host
 * recreates this view so resources re-resolve. Construct and use it on the main
 * thread.
 */
class StandbySettingsPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    /**
     * Called after a language choice is stored, so the host can re-measure
     * cached text and layout. Never invoked for theme changes.
     */
    var onLocaleChangeRequested: (() -> Unit)? = null

    /** Receives the original gateway exception on the main thread; cancellation is not an error. */
    var onError: ((Exception) -> Unit)? = null

    init {
        checkMainThread()
    }

    /** Stable traversal order for theme options. */
    val themeOptionIds: List<StandbyThemeId> = StandbySettingsPanelModel.THEME_ORDER

    /** Stable traversal order for language options; `null` is follow-system. */
    val languageOptionIds: List<StandbyLanguage?> = StandbySettingsPanelModel.LANGUAGE_ORDER

    private val themeGroup = RadioGroup(context)
    private val languageLabel = TextView(context)
    private val languageGroup = RadioGroup(context)
    private val themeButtons: Map<StandbyThemeId, RadioButton>
    private val languageButtons: Map<StandbyLanguage?, RadioButton>
    private var suppressCallbacks = false
    private val submission = StandbySettingsSubmission(
        dispatcher = Dispatchers.Main,
        render = ::renderOptions,
        setInputEnabled = ::setOptionsEnabled,
        logFailure = { Log.e(TAG, "Failed to persist standby settings", it) },
    )

    /** Base (unselected) TalkBack description per theme option. */
    private val themeBaseDescriptions = mutableMapOf<StandbyThemeId, String>()

    /** Base (unselected) TalkBack description per language option. */
    private val languageBaseDescriptions = mutableMapOf<StandbyLanguage?, String>()

    init {
        orientation = VERTICAL
        val padding = dpToPx(MIN_TOUCH_DP / 4f)
        setPadding(padding, padding, padding, padding)

        val title = TextView(context)
        title.setText(R.string.standby_settings_panel_title)
        title.textSize = 20f
        addView(title, layoutParams())

        val themeLabel = TextView(context)
        themeLabel.setText(R.string.standby_settings_theme_section)
        addView(themeLabel, layoutParams())
        themeGroup.orientation = RadioGroup.VERTICAL
        addView(themeGroup, layoutParams())
        themeButtons = themeOptionIds.associateWith { id ->
            makeOption(themeGroup, id.name)
        }
        themeButtons[StandbyThemeId.LANDSCAPE]?.apply {
            setText(R.string.standby_settings_theme_landscape)
            contentDescription = resources.getString(R.string.standby_settings_cd_theme_landscape)
            themeBaseDescriptions[StandbyThemeId.LANDSCAPE] = contentDescription?.toString().orEmpty()
        }
        themeButtons[StandbyThemeId.MATTER]?.apply {
            setText(R.string.standby_settings_theme_matter)
            contentDescription = resources.getString(R.string.standby_settings_cd_theme_matter)
            themeBaseDescriptions[StandbyThemeId.MATTER] = contentDescription?.toString().orEmpty()
        }
        themeButtons[StandbyThemeId.HARDLINE]?.apply {
            setText(R.string.standby_settings_theme_hardline)
            contentDescription = resources.getString(R.string.standby_settings_cd_theme_hardline)
            themeBaseDescriptions[StandbyThemeId.HARDLINE] = contentDescription?.toString().orEmpty()
        }

        languageLabel.setText(R.string.standby_settings_language_section)
        addView(languageLabel, layoutParams())
        languageGroup.orientation = RadioGroup.VERTICAL
        addView(languageGroup, layoutParams())
        languageButtons = languageOptionIds.associateWith { language ->
            makeOption(languageGroup, language?.name ?: FOLLOW_SYSTEM_KEY)
        }
        languageButtons[null]?.apply {
            setText(R.string.standby_settings_lang_follow)
            contentDescription = resources.getString(R.string.standby_settings_cd_lang_follow)
            languageBaseDescriptions[null] = contentDescription?.toString().orEmpty()
        }
        languageButtons[StandbyLanguage.TRADITIONAL_CHINESE]?.apply {
            setText(R.string.standby_settings_lang_zh)
            contentDescription = resources.getString(R.string.standby_settings_cd_lang_zh)
            languageBaseDescriptions[StandbyLanguage.TRADITIONAL_CHINESE] =
                contentDescription?.toString().orEmpty()
        }
        languageButtons[StandbyLanguage.ENGLISH]?.apply {
            setText(R.string.standby_settings_lang_en)
            contentDescription = resources.getString(R.string.standby_settings_cd_lang_en)
            languageBaseDescriptions[StandbyLanguage.ENGLISH] = contentDescription?.toString().orEmpty()
        }

        // Explicit DPAD/TalkBack order: themes first, then languages. This is a
        // vertical list, so left mirrors up and right mirrors down; forward
        // follows down. Ends are clamped to themselves via the same rule in
        // [StandbySettingsPanelA11y.applyFocusChain].
        val ordered = themeOptionIds.mapNotNull { themeButtons[it] } +
            languageOptionIds.mapNotNull { languageButtons[it] }
        val orderedIds = ordered.map { it.id }
        orderedIds.forEachIndexed { index, id ->
            val button = ordered[index]
            val up = if (index > 0) orderedIds[index - 1] else button.id
            val down = if (index < orderedIds.size - 1) orderedIds[index + 1] else button.id
            button.nextFocusUpId = up
            button.nextFocusDownId = down
            button.nextFocusLeftId = up
            button.nextFocusRightId = down
            button.nextFocusForwardId = down
        }
    }

    /** Shows only themes when the host already has a dedicated language page. */
    fun showThemesOnly() {
        checkMainThread()
        languageLabel.visibility = View.GONE
        languageGroup.visibility = View.GONE
        val first = themeButtons[themeOptionIds.first()]
        val last = themeButtons[themeOptionIds.last()]
        first?.nextFocusUpId = View.NO_ID
        first?.nextFocusLeftId = View.NO_ID
        last?.nextFocusDownId = View.NO_ID
        last?.nextFocusRightId = View.NO_ID
        last?.nextFocusForwardId = View.NO_ID
    }

    /**
     * Renders [model] without touching storage; check marks plus
     * `contentDescription` carry the selected state.
     *
     * TalkBack always perceives selection: selected options append the
     * selected suffix to their base description, unselected options revert to
     * the base description with no residue.
     *
     * Call with the host's confirmed state on the main thread. A later host
     * change to a different selection supersedes the pending completion.
     *
     * @param model pure selection state to display.
     */
    fun render(model: StandbySettingsPanelModel) {
        checkMainThread()
        submission.updateConfirmed(model)
    }

    private fun renderOptions(model: StandbySettingsPanelModel) {
        suppressCallbacks = true
        try {
            val suffix = resources.getString(R.string.standby_settings_selected_suffix)
            for ((id, button) in themeButtons) {
                val selected = model.isThemeSelected(id)
                val base = themeBaseDescriptions[id].orEmpty()
                val control = radioButtonControl(button)
                StandbySettingsPanelA11y.applySelection(control, base, suffix, selected)
                button.text = control.label
                button.contentDescription = control.description
                button.isChecked = selected
            }
            for ((language, button) in languageButtons) {
                val selected = model.isLanguageSelected(language)
                val base = languageBaseDescriptions[language].orEmpty()
                val control = radioButtonControl(button)
                StandbySettingsPanelA11y.applySelection(control, base, suffix, selected)
                button.text = control.label
                button.contentDescription = control.description
                button.isChecked = selected
            }
        } finally {
            suppressCallbacks = false
        }
    }

    /**
     * Wires option taps to the injected gateways; the host owns [scope].
     *
     * Call on the main thread after rendering the host's confirmed state.
     * Options are disabled while a write is pending. A successful write invokes
     * [onStateChanged], followed by [onLocaleChangeRequested] for language.
     * Failures are logged and delivered to [onError]; cancellation is propagated.
     * Rebinding or detaching cancels only the outstanding child and requires the
     * host to bind again after reattachment. A cancelled write may already have
     * committed, so the host must observe or reload the gateways when restoring.
     * Gateways shared by replacement views must serialize writes that outlive
     * cancellation; the panel serializes writes only within this view.
     *
     * @param themeGateway persisted theme lane.
     * @param languageGateway persisted language lane.
     * @param scope host scope for gateway writes.
     * @param onStateChanged receives confirmed state after a successful write.
     */
    fun bind(
        themeGateway: StandbyThemeGateway,
        languageGateway: StandbyLanguageGateway,
        scope: CoroutineScope,
        onStateChanged: ((StandbySettingsPanelModel) -> Unit)? = null,
    ) {
        checkMainThread()
        submission.bind(
            scope = scope,
            onStateChanged = onStateChanged,
            onLocaleChange = { onLocaleChangeRequested?.invoke() },
            onError = { onError?.invoke(it) },
        )
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) {
                return@setOnCheckedChangeListener
            }
            val selected = themeButtons.entries.firstOrNull { it.value.id == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            submission.submit(submission.confirmed.withTheme(selected), changesLocale = false) {
                themeGateway.selectTheme(selected)
            }
        }
        languageGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) {
                return@setOnCheckedChangeListener
            }
            if (!languageButtons.values.any { it.id == checkedId }) {
                return@setOnCheckedChangeListener
            }
            val selected = languageButtons.entries.firstOrNull { it.value.id == checkedId }?.key
            submission.submit(submission.confirmed.withLanguage(selected), changesLocale = true) {
                languageGateway.selectLanguage(selected)
            }
        }
    }

    /** Cancels the current binding before the host stops or replaces this view. */
    fun unbind() {
        checkMainThread()
        submission.unbind()
        themeGroup.setOnCheckedChangeListener(null)
        languageGroup.setOnCheckedChangeListener(null)
        setOptionsEnabled(false)
    }

    override fun onDetachedFromWindow() {
        unbind()
        super.onDetachedFromWindow()
    }

    private fun setOptionsEnabled(enabled: Boolean) {
        themeButtons.values.forEach { it.isEnabled = enabled }
        languageButtons.values.forEach { it.isEnabled = enabled }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "StandbySettingsPanelView must be used on the main thread"
        }
    }

    /**
     * Returns current on-screen selection as a pure model.
     *
     * @return model built from checked buttons, defaulting to MATTER/follow-system.
     */
    fun currentModel(): StandbySettingsPanelModel {
        checkMainThread()
        val theme = themeButtons.entries.firstOrNull { it.value.isChecked }?.key
            ?: StandbyThemeId.MATTER
        val language = languageButtons.entries.firstOrNull { it.value.isChecked }?.key
        return StandbySettingsPanelModel(theme = theme, themeRaw = theme.storageValue, language = language)
    }

    private fun makeOption(group: RadioGroup, tag: String): RadioButton {
        val button = RadioButton(context)
        button.id = View.generateViewId()
        button.tag = tag
        button.isFocusable = true
        // 48dp floor on both axes; minimumWidth/minimumHeight keep padding
        // from shrinking the touch target below 48dp.
        val touch = dpToPx(MIN_TOUCH_DP)
        val control = radioButtonControl(button)
        StandbySettingsPanelA11y.applyTouchTarget(control, touch)
        StandbySettingsPanelA11y.applyTouchModeFocus(control, false)
        button.minWidth = control.minWidthPx
        button.minHeight = control.minHeightPx
        button.minimumWidth = control.minimumWidthPx
        button.minimumHeight = control.minimumHeightPx
        val params = RadioGroup.LayoutParams(
            RadioGroup.LayoutParams.MATCH_PARENT,
            RadioGroup.LayoutParams.WRAP_CONTENT,
        )
        group.addView(button, params)
        return button
    }

    private fun layoutParams(): LayoutParams {
        return LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private fun dpToPx(dp: Float): Int {
        // TypedValue handles density scaling plus rounding; shortcut: do not
        // hand-roll (dp * density + 0.5f) here, density changes need the same
        // platform path.
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics,
        ).roundToInt()
    }

    /**
     * Adapts a real [RadioButton] to the JVM-testable option seam.
     *
     * @param button platform button.
     * @return control view over the same button.
     */
    private fun radioButtonControl(button: RadioButton): StandbySettingsPanelA11y.OptionControl {
        return object : StandbySettingsPanelA11y.OptionControl {
            override val viewId: Int get() = button.id
            override var checked: Boolean
                get() = button.isChecked
                set(value) { button.isChecked = value }
            override var label: String
                get() = button.text.toString()
                set(value) { button.text = value }
            override var description: String?
                get() = button.contentDescription?.toString()
                set(value) { button.contentDescription = value }
            override var minWidthPx: Int
                get() = button.minWidth
                set(value) { button.minWidth = value }
            override var minimumWidthPx: Int
                get() = button.minimumWidth
                set(value) { button.minimumWidth = value }
            override var minHeightPx: Int
                get() = button.minHeight
                set(value) { button.minHeight = value }
            override var minimumHeightPx: Int
                get() = button.minimumHeight
                set(value) { button.minimumHeight = value }
            override var nextUp: Int
                get() = button.nextFocusUpId
                set(value) { button.nextFocusUpId = value }
            override var nextDown: Int
                get() = button.nextFocusDownId
                set(value) { button.nextFocusDownId = value }
            override var nextLeft: Int
                get() = button.nextFocusLeftId
                set(value) { button.nextFocusLeftId = value }
            override var nextRight: Int
                get() = button.nextFocusRightId
                set(value) { button.nextFocusRightId = value }
            override var nextForward: Int
                get() = button.nextFocusForwardId
                set(value) { button.nextFocusForwardId = value }
            override var focusableInTouchMode: Boolean
                get() = button.isFocusableInTouchMode
                set(value) { button.isFocusableInTouchMode = value }
        }
    }

    private fun withSelectionMarker(label: String, suffix: String, selected: Boolean): String {
        // Kept as the single label-marker helper; selection description is
        // handled by [StandbySettingsPanelA11y.applySelection] so TalkBack
        // and text stay in sync.
        val stripped = if (label.endsWith(suffix)) label.dropLast(suffix.length) else label
        return if (selected) stripped + suffix else stripped
    }

    companion object {
        private const val TAG = "StandbySettingsPanel"

        /** Minimum touch target per Material accessibility guidance. */
        const val MIN_TOUCH_DP = 48f

        /** Stable tag for the follow-system option; storage still uses `null`. */
        const val FOLLOW_SYSTEM_KEY = "__FOLLOW_SYSTEM__"

        /**
         * Inspectable option metadata: label and description resource IDs in
         * stable traversal order (themes, then languages). JVM tests assert
         * every entry carries a content description without inflating views.
         */
        val OPTION_METADATA: List<PanelOption> = listOf(
            PanelOption(
                key = StandbyThemeId.LANDSCAPE.name,
                labelRes = R.string.standby_settings_theme_landscape,
                descriptionRes = R.string.standby_settings_cd_theme_landscape,
            ),
            PanelOption(
                key = StandbyThemeId.MATTER.name,
                labelRes = R.string.standby_settings_theme_matter,
                descriptionRes = R.string.standby_settings_cd_theme_matter,
            ),
            PanelOption(
                key = StandbyThemeId.HARDLINE.name,
                labelRes = R.string.standby_settings_theme_hardline,
                descriptionRes = R.string.standby_settings_cd_theme_hardline,
            ),
            PanelOption(
                key = FOLLOW_SYSTEM_KEY,
                labelRes = R.string.standby_settings_lang_follow,
                descriptionRes = R.string.standby_settings_cd_lang_follow,
            ),
            PanelOption(
                key = StandbyLanguage.TRADITIONAL_CHINESE.name,
                labelRes = R.string.standby_settings_lang_zh,
                descriptionRes = R.string.standby_settings_cd_lang_zh,
            ),
            PanelOption(
                key = StandbyLanguage.ENGLISH.name,
                labelRes = R.string.standby_settings_lang_en,
                descriptionRes = R.string.standby_settings_cd_lang_en,
            ),
        )
    }
}

/**
 * Inspectable descriptor for one panel option.
 *
 * @param key stable tag matching the view tag (`__FOLLOW_SYSTEM__` for follow-system).
 * @param labelRes display label resource; never a stored identifier.
 * @param descriptionRes content description resource for TalkBack.
 */
data class PanelOption(
    val key: String,
    val labelRes: Int,
    val descriptionRes: Int,
)
