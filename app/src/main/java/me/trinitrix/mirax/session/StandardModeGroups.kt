package me.trinitrix.mirax.session

/**
 * Standard-mode rows grouped by picture ratio, tallest first inside each group.
 *
 * 16:9, 16:10, and 4:3 come first. Any other ratio keeps its own group after those.
 */
object StandardModeGroups {
    private val PRIMARY = listOf("16:9", "16:10", "4:3")
    private const val TOLERANCE = 0.02

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
        val seen = HashSet<String>()
        for (key in PRIMARY) {
            val bucket = buckets[key] ?: continue
            seen.add(key)
            ordered.add(Group(key, bucket.sortedWith(DESCENDING)))
        }
        for ((key, bucket) in buckets) {
            if (key in seen) {
                continue
            }
            ordered.add(Group(key, bucket.sortedWith(DESCENDING)))
        }
        return ordered
    }

    private fun ratioKey(width: Int, height: Int): String {
        if (height <= 0) {
            return "$width:$height"
        }
        val ratio = width.toDouble() / height.toDouble()
        if (kotlin.math.abs(ratio - 16.0 / 9.0) < TOLERANCE) {
            return "16:9"
        }
        if (kotlin.math.abs(ratio - 16.0 / 10.0) < TOLERANCE) {
            return "16:10"
        }
        if (kotlin.math.abs(ratio - 4.0 / 3.0) < TOLERANCE) {
            return "4:3"
        }
        return "$width:$height"
    }

    private val DESCENDING = compareByDescending<StandardModeRow> { row ->
        row.mode.width.toLong() * row.mode.height
    }.thenByDescending { it.mode.width }
        .thenByDescending { it.mode.refreshHz }
}
