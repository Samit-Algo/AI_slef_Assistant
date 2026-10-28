package com.heybuddy.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Long licence files are shown paragraph by paragraph (Task 2.1). */
class LicenceParagraphsTest {

    @Test
    fun splitsAtBlankLinesAndDropsEmptyParts() {
        val text = "First line\nstill first\n\n\nSecond\r\n\r\nThird\n   \n"

        assertEquals(listOf("First line\nstill first", "Second", "Third"), splitIntoParagraphs(text))
    }

    @Test
    fun aTextWithoutBlankLinesStaysOneParagraph() {
        assertEquals(listOf("One\nblock"), splitIntoParagraphs("One\nblock"))
    }
}
