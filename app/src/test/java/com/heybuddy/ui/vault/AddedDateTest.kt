package com.heybuddy.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Checks the date line on document cards ("27 Oct 2026", spec 5.14). */
class AddedDateTest {

    private val india = ZoneId.of("Asia/Kolkata")

    @Test
    fun formatsDayShortMonthAndYear() {
        val added = ZonedDateTime.of(2026, 10, 27, 15, 30, 0, 0, india).toInstant().toEpochMilli()

        assertEquals("27 Oct 2026", formatAddedDate(added, india, Locale.ENGLISH))
    }

    @Test
    fun usesThePhonesTimeZoneForTheDay() {
        // 23:30 in India on 27 Oct is still 27 Oct there, even though it is 18:00 UTC.
        val lateEvening = ZonedDateTime.of(2026, 10, 27, 23, 30, 0, 0, india).toInstant().toEpochMilli()

        assertEquals("27 Oct 2026", formatAddedDate(lateEvening, india, Locale.ENGLISH))
    }

    @Test
    fun singleDigitDaysHaveNoLeadingZero() {
        val added = ZonedDateTime.of(2026, 11, 3, 10, 0, 0, 0, india).toInstant().toEpochMilli()

        assertEquals("3 Nov 2026", formatAddedDate(added, india, Locale.ENGLISH))
    }
}
