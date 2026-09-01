package com.obsidian.connect.draw

/**
 * The pen colours.
 *
 * Stored as ARGB longs because that is what survives a Firestore round trip —
 * Compose's Color is a value class over a ULong and does not serialise.
 *
 * Deliberately few. A full colour picker on a shared scribble pad is more
 * decision than the activity deserves.
 */
object DrawPalette {
    const val Ink = 0xFF16161AL
    const val Coral = 0xFFE8735AL
    const val Sky = 0xFF4A9DFFL
    const val Leaf = 0xFF3FBF7FL
    const val Sun = 0xFFF5C542L
    const val Violet = 0xFFB06AD9L
    const val Chalk = 0xFFFFFFFFL

    val colors = listOf(Ink, Coral, Sky, Leaf, Sun, Violet, Chalk)

    val default = Coral

    /** Pen thicknesses, in canvas pixels. */
    val widths = listOf(3f, 6f, 12f, 22f)
}
