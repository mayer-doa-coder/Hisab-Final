package com.hisab.app.domain

/**
 * Selling units, stored as language-neutral codes. The database and any sync
 * payload always hold the code; only the label on screen changes with the
 * language (D011), exactly like status codes.
 */
object ProductUnits {
    const val PIECE = "piece"
    const val KG = "kg"
    const val GRAM = "gram"
    const val LITRE = "litre"
    const val PACKET = "packet"
    const val BOTTLE = "bottle"
    const val DOZEN = "dozen"

    const val DEFAULT = PIECE

    val ALL = listOf(PIECE, KG, GRAM, LITRE, PACKET, BOTTLE, DOZEN)

    fun isKnown(code: String): Boolean = code in ALL
}
