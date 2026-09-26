package com.hisab.app.ui.customer

import org.junit.Assert.assertEquals
import org.junit.Test

/** The optional phone field on the Add customer dialog. */
class CustomerInputTest {
    @Test
    fun `a phone number is optional`() {
        assertEquals(PhoneInput.None, parsePhone(""))
        assertEquals(PhoneInput.None, parsePhone("   "))
    }

    @Test
    fun `an ordinary mobile number is accepted as typed`() {
        assertEquals(PhoneInput.Valid("01711000000"), parsePhone("01711000000"))
    }

    @Test
    fun `Bangla digits are stored as ASCII so a search works in either`() {
        assertEquals(PhoneInput.Valid("01711000000"), parsePhone("০১৭১১০০০০০০"))
    }

    @Test
    fun `spaces and hyphens people put in the middle are ignored`() {
        assertEquals(PhoneInput.Valid("01711000000"), parsePhone("01711 000 000"))
        assertEquals(PhoneInput.Valid("01711000000"), parsePhone("01711-000-000"))
    }

    @Test
    fun `a leading plus is allowed for an international number`() {
        assertEquals(PhoneInput.Valid("+8801711000000"), parsePhone("+880 1711-000000"))
    }

    @Test
    fun `things that cannot be called are refused, not saved`() {
        listOf("12345", "abc", "0171100000x", "+", "1234567890123456", "017+1100").forEach {
            assertEquals("'$it' should be invalid", PhoneInput.Invalid, parsePhone(it))
        }
    }
}
