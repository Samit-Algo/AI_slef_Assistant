package com.heybuddy.ui.adddocument

import com.heybuddy.R
import com.heybuddy.data.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the type labels, the suggested names and that every failure has its own message. */
class AddDocumentTextsTest {

    @Test
    fun everyTypeHasItsOwnLabel() {
        val labels = DocumentType.entries.map { type -> labelFor(type) }

        assertEquals(DocumentType.entries.size, labels.toSet().size)
    }

    @Test
    fun suggestedNameIsTheLabelExceptForOther() {
        for (type in DocumentType.entries) {
            val expected = if (type == DocumentType.OTHER) R.string.type_default_name_other else labelFor(type)
            assertEquals(type.name, expected, defaultNameFor(type))
        }
    }

    @Test
    fun aadhaarSuggestsAadhaarCard() {
        assertEquals(R.string.type_label_aadhaar, defaultNameFor(DocumentType.AADHAAR))
    }

    @Test
    fun everyFailureHasItsOwnMessage() {
        val messages = AddDocumentFailure.entries.map { failure -> failure.message }

        assertEquals(AddDocumentFailure.entries.size, messages.toSet().size)
    }
}
