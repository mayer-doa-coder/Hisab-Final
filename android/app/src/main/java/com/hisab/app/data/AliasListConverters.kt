package com.hisab.app.data

import androidx.room.TypeConverter

/**
 * Product aliases are a short list of words, so they live in one text column
 * separated by newlines rather than in a second table — a join would cost
 * more than it gives here, and the product search can then match aliases with
 * a plain LIKE. Aliases are trimmed of newlines before they are saved, so the
 * separator can never appear inside one.
 */
class AliasListConverters {
    @TypeConverter
    fun fromAliases(aliases: List<String>?): String = aliases.orEmpty().joinToString(SEPARATOR)

    @TypeConverter
    fun toAliases(stored: String?): List<String> =
        stored
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    companion object {
        const val SEPARATOR = "\n"
    }
}
