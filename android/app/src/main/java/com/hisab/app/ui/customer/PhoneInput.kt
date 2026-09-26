package com.hisab.app.ui.customer

import com.hisab.app.domain.normalizeDigits

/** What was typed in the optional phone field. */
sealed interface PhoneInput {
    /** Nothing typed, which is allowed: a phone number is optional. */
    data object None : PhoneInput

    data class Valid(
        val number: String,
    ) : PhoneInput

    data object Invalid : PhoneInput
}

private val PHONE_PATTERN = Regex("""^\+?\d{6,15}$""")

/**
 * Reads a phone number a shopkeeper typed. Bangla digits are accepted as
 * readily as ASCII ones, and spaces and hyphens people put in the middle of a
 * number are ignored. It must then be 6–15 digits, with an optional leading "+"
 * — loose on purpose, since shops write local and international numbers alike.
 * Anything else is refused rather than saved as a number nobody can call.
 *
 * The number is stored as plain ASCII digits so a search for it works whichever
 * digits it was typed or is searched in.
 */
fun parsePhone(input: String): PhoneInput {
    val cleaned = normalizeDigits(input).filterNot { it == ' ' || it == '-' }
    if (cleaned.isEmpty()) return PhoneInput.None
    return if (PHONE_PATTERN.matches(cleaned)) PhoneInput.Valid(cleaned) else PhoneInput.Invalid
}
