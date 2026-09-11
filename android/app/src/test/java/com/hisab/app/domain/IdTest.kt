package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdTest {
    private val uuidPattern =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    @Test
    fun `generateId produces a well-formed UUID`() {
        assertTrue(uuidPattern.matches(generateId().value))
    }

    @Test
    fun `generateId produces unique values`() {
        val ids = (1..1000).map { generateId().value }.toSet()
        assertEquals(1000, ids.size)
    }
}
