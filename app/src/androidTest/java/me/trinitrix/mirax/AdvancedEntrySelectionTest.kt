package me.trinitrix.mirax

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.ControlPanelEntry
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies Advanced's real view actions without depending on unrelated system permission dialogs. */
@RunWith(AndroidJUnit4::class)
class AdvancedEntrySelectionTest {
    /** Missing or unknown persisted choices do not strand the user without an entry. */
    @Test
    fun invalidOrMissingEntry_defaultsToControlButton() {
        val app = ApplicationProvider.getApplicationContext<MiraxApp>()
        val prefs = app.getSharedPreferences("mirax_session", Context.MODE_PRIVATE)
        val original = prefs.getString("control_panel_entry", null)
        try {
            prefs.edit().putString("control_panel_entry", "UNRECOGNIZED").commit()
            assertThat(SessionPreferences.load(app).controlPanelEntry).isEqualTo(ControlPanelEntry.FLOATING_BUTTON)
            prefs.edit().remove("control_panel_entry").commit()
            assertThat(SessionPreferences.load(app).controlPanelEntry).isEqualTo(ControlPanelEntry.FLOATING_BUTTON)
        } finally {
            prefs.edit().apply {
                if (original == null) remove("control_panel_entry") else putString("control_panel_entry", original)
            }.commit()
        }
    }

    /** Choosing either entry updates the selected chip and persists it across settings reloads. */
    @Test
    fun advancedEntrySelection_persistsBothOptions() {
        val app = ApplicationProvider.getApplicationContext<MiraxApp>()
        val original = SessionPreferences.load(app).controlPanelEntry
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.menuButton).performClick()
                val matches = ArrayList<View>()
                val label = activity.getString(R.string.nav_advanced)
                activity.window.decorView.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                matches.single { it.contentDescription.toString() == label && it.isClickable }.performClick()

                val options = activity.window.decorView.findViewWithTag<LinearLayout>("control-panel-entry")
                assertThat(options).isNotNull()
                val button = options.getChildAt(0) as TextView
                val volume = options.getChildAt(1) as TextView
                assertThat(button.text.toString()).isEqualTo(activity.getString(R.string.control_panel_entry_button))
                assertThat(volume.text.toString()).isEqualTo(activity.getString(R.string.control_panel_entry_volume))
                volume.performClick()
                assertThat(SessionPreferences.load(app).controlPanelEntry).isEqualTo(ControlPanelEntry.VOLUME_KEYS)
                val selectedVolume = activity.window.decorView
                    .findViewWithTag<LinearLayout>("control-panel-entry").getChildAt(1) as TextView
                assertThat(selectedVolume.currentTextColor).isEqualTo(activity.getColor(R.color.mirax_chip_text))
                val nextButton = activity.window.decorView
                    .findViewWithTag<LinearLayout>("control-panel-entry").getChildAt(0) as TextView
                nextButton.performClick()
                assertThat(SessionPreferences.load(app).controlPanelEntry).isEqualTo(ControlPanelEntry.FLOATING_BUTTON)
                val selectedButton = activity.window.decorView
                    .findViewWithTag<LinearLayout>("control-panel-entry").getChildAt(0) as TextView
                assertThat(selectedButton.currentTextColor).isEqualTo(activity.getColor(R.color.mirax_chip_text))
            }
        } finally {
            scenario.close()
            SessionHost.setControlPanelEntry(app, original)
        }
    }
}
