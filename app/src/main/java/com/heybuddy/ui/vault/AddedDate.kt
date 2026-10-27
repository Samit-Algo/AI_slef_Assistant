package com.heybuddy.ui.vault

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Day, short month, year, for example "27 Oct 2026" (spec 5.14); month names follow the phone's language. */
private const val ADDED_DATE_PATTERN = "d MMM yyyy"

/**
 * Formats when a document was added, for example "27 Oct 2026".
 *
 * @param epochMillis The document's createdAt.
 * @param zone The phone's time zone.
 * @param locale The phone's language (for month names).
 * @return The date text (without the word "Added", which comes from strings.xml).
 */
fun formatAddedDate(epochMillis: Long, zone: ZoneId, locale: Locale): String {
    val formatter = DateTimeFormatter.ofPattern(ADDED_DATE_PATTERN, locale)
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    return formatter.format(date)
}
