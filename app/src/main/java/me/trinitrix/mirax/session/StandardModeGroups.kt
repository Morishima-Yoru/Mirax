package me.trinitrix.mirax.session

/**
 * Standard-mode rows folded into 16:9, 16:10, or 4:3, tallest first inside each group.
 *
 * A size within 0.02 of one of those ratios keeps it. Wider leftovers, from
 * 5:3 upward, join 16:9 — so 1280×768 joins 16:9. Narrower leftovers join whichever
 * of 16:10 and 4:3 is closer, so 1280×1024 (5:4) joins 4:3.
 */
object StandardModeGroups {
    private val RATIOS = listOf(
        Ratio("16:9", 16.0 / 9.0),
        Ratio("16:10", 16.0 / 10.0),
        Ratio("4:3", 4.0 / 3.0),
    )
    private const val TOLERANCE = 0.02
    /** 1280:768. Wider than this, and not already a near-match, is treated as 16:9. */
    private const val WIDE_FAMILY = 5.0 / 3.0

    data class Group(
        val label: String,
        val rows: List<StandardModeRow>,
    )

    fun groups(rows: List<StandardModeRow>): List<Group> {
        val buckets = LinkedHashMap<String, MutableList<StandardModeRow>>()
        for (row in rows) {
            val key = ratioKey(row.mode.width, row.mode.height)
            buckets.getOrPut(key) { mutableListOf() }.add(row)
        }
        val ordered = ArrayList<Group>()
        for (ratio in RATIOS) {
            val bucket = buckets[ratio.label] ?: continue
            ordered.add(Group(ratio.label, bucket.sortedWith(DESCENDING)))
        }
        return ordered
    }

    private fun ratioKey(width: Int, height: Int): String {
        if (height <= 0) {
            return RATIOS.first().label
        }
        val ratio = width.toDouble() / height.toDouble()
        val nearest = RATIOS.minBy { kotlin.math.abs(it.value - ratio) }
        if (kotlin.math.abs(nearest.value - ratio) < TOLERANCE) {
            return nearest.label
        }
        if (ratio + 1e-9 >= WIDE_FAMILY) {
            return "16:9"
        }
        return RATIOS.filter { it.label != "16:9" }
            .minBy { kotlin.math.abs(it.value - ratio) }
            .label
    }

    private data class Ratio(val label: String, val value: Double)

    private val DESCENDING = compareByDescending<StandardModeRow> { row ->
        row.mode.width.toLong() * row.mode.height
    }.thenByDescending { it.mode.width }
        .thenByDescending { it.mode.refreshHz }
}
