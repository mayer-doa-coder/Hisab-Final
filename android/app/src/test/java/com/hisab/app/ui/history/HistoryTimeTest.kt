package com.hisab.app.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * How a history row says when something happened (Step 42).
 *
 * The zone and "today" are passed in rather than read from the system, so
 * these tests do not change their answer depending on what day they run.
 */
class HistoryTimeTest {
    private val dhaka = ZoneId.of("Asia/Dhaka")
    private val english = Locale.forLanguageTag("en")
    private val today = LocalDate.of(2026, 9, 16)

    private fun text(instant: Instant): String =
        whenText(
            instant = instant,
            locale = english,
            todayLabel = "Today",
            yesterdayLabel = "Yesterday",
            zone = dhaka,
            today = today,
        )

    @Test
    fun `something from today is named, not dated`() {
        // 04:00 UTC is 10:00 in Dhaka, the same day.
        assertTrue(text(Instant.parse("2026-09-16T04:00:00Z")).startsWith("Today"))
    }

    @Test
    fun `something from yesterday says so`() {
        assertTrue(text(Instant.parse("2026-09-15T04:00:00Z")).startsWith("Yesterday"))
    }

    @Test
    fun `anything older gets its date, because some days ago stops being useful`() {
        val older = text(Instant.parse("2026-09-01T04:00:00Z"))
        assertTrue(older, older.contains("2026"))
        assertTrue(older, !older.startsWith("Today") && !older.startsWith("Yesterday"))
    }

    @Test
    fun `every row says the time, whichever day it was`() {
        for (
        instant in
        listOf(
            Instant.parse("2026-09-16T04:00:00Z"),
            Instant.parse("2026-09-15T04:00:00Z"),
            Instant.parse("2026-09-01T04:00:00Z"),
        )
        ) {
            assertTrue(text(instant), text(instant).contains("·"))
        }
    }

    // The shop's own day is what matters, not UTC's. A sale at 11pm in Dhaka
    // is still today for the shopkeeper, even though UTC has moved on.
    @Test
    fun `the day is worked out in the shop's zone, not in UTC`() {
        val lateEvening = Instant.parse("2026-09-16T17:30:00Z") // 23:30 in Dhaka
        assertTrue(text(lateEvening).startsWith("Today"))
    }

    @Test
    fun `an early morning sale belongs to the day it happened locally`() {
        val earlyMorning = Instant.parse("2026-09-15T23:00:00Z") // 05:00 on the 16th in Dhaka
        assertEquals(
            LocalDate.of(2026, 9, 16),
            earlyMorning.atZone(dhaka).toLocalDate(),
        )
        assertTrue(text(earlyMorning).startsWith("Today"))
    }
}
