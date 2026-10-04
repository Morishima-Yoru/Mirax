package me.trinitrix.mirax.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StandardModeGroupsTest {
    @Test
    fun groupsPrimaryRatiosFirst_andSortsEachGroupHighToLow() {
        val rows = listOf(
            row(1280, 720, 60),
            row(1920, 1080, 30),
            row(1920, 1080, 60),
            row(1280, 800, 60),
            row(1024, 768, 60),
            row(800, 480, 60),
        ).map { StandardModeRow(it, checked = true) }

        val groups = StandardModeGroups.groups(rows)
        assertThat(groups.map { it.label }).containsExactly(
            "16:9",
            "16:10",
            "4:3",
        ).inOrder()
        assertThat(groups[0].rows.map { it.mode }).containsExactly(
            VideoMode(1920, 1080, 60),
            VideoMode(1920, 1080, 30),
            VideoMode(1280, 720, 60),
            VideoMode(800, 480, 60),
        ).inOrder()
    }

    @Test
    fun oddRatiosJoinTheFamiliarBucket() {
        val rows = listOf(
            row(1280, 768, 60),
            row(1280, 1024, 60),
            row(1680, 1024, 60),
            row(720, 480, 60),
            row(864, 480, 60),
        ).map { StandardModeRow(it, checked = false) }

        val groups = StandardModeGroups.groups(rows)
        assertThat(groups.single { it.label == "16:9" }.rows.map { it.mode }).containsExactly(
            VideoMode(1280, 768, 60),
            VideoMode(864, 480, 60),
        ).inOrder()
        assertThat(groups.single { it.label == "16:10" }.rows.map { it.mode }).containsExactly(
            VideoMode(1680, 1024, 60),
            VideoMode(720, 480, 60),
        ).inOrder()
        assertThat(groups.single { it.label == "4:3" }.rows.map { it.mode }).containsExactly(
            VideoMode(1280, 1024, 60),
        )
    }

    private fun row(width: Int, height: Int, refresh: Int): VideoMode =
        VideoMode(width, height, refresh)
}
