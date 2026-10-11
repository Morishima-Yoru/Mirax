package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenViewportGeometryTest {

    @Test
    fun proportionalPlacement_centeredOnPanel_mapsCoordinatesCorrectly() {
        // Panel 2000x1000, picture 1000x1000 (aspect 1:1)
        // With PROPORTIONAL, factor = min(2000/1000, 1000/1000) = 1.0 -> width=1000, height=1000, left=500, top=0
        val geometry = ScreenViewportGeometry(
            panelWidth = 2000,
            panelHeight = 1000,
            pictureWidth = 1000,
            pictureHeight = 1000,
            scale = PictureScale.PROPORTIONAL,
        )

        assertThat(geometry.placement.left).isEqualTo(500)
        assertThat(geometry.placement.top).isEqualTo(0)
        assertThat(geometry.placement.width).isEqualTo(1000)
        assertThat(geometry.placement.height).isEqualTo(1000)

        // Contact at panel (400, 500) is in black border -> null
        assertThat(geometry.mapPanelContact(400f, 500f)).isNull()

        // Contact at panel (500, 0) -> picture (0, 0)
        val topLeft = geometry.mapPanelContact(500f, 0f)
        assertThat(topLeft).isNotNull()
        assertThat(topLeft!!.x).isEqualTo(0)
        assertThat(topLeft.y).isEqualTo(0)

        // Contact at panel (1000, 500) -> picture center (500, 500)
        val center = geometry.mapPanelContact(1000f, 500f)
        assertThat(center).isNotNull()
        assertThat(center!!.x).isEqualTo(500)
        assertThat(center.y).isEqualTo(500)
    }

    @Test
    fun contactLifecycle_endAllContactsClearsActivePointers() {
        val geometry = ScreenViewportGeometry(
            panelWidth = 1000,
            panelHeight = 1000,
            pictureWidth = 1000,
            pictureHeight = 1000,
        )

        geometry.onPointerDown(1)
        geometry.onPointerDown(2)

        val ended = geometry.endAllContacts()
        assertThat(ended).containsExactly(1, 2)

        assertThat(geometry.endAllContacts()).isEmpty()
    }
}
