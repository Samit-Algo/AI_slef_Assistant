package com.heybuddy.facts

import com.heybuddy.data.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The type guess (Task 2.5) on the text of the FAKE samples of Tasks 2.2 and 2.3 (their answer
 * keys, and real reading output from the Task 2.3 record), plus made-up lines. No real document.
 */
class DocumentTypeGuesserTest {

    private val aadhaarStyleCard = """
        SAMPLE IDENTITY CARD
        NOT A REAL DOCUMENT - FOR TESTING ONLY
        टेस्टा कुमारी
        TESTA KUMARI
        டெஸ்டா குமாரி
        जन्म तिथि / DOB: 14/08/1990
        महिला / FEMALE
        Address: 12, SAMPLE STREET, MYLAPORE,
        CHENNAI, TAMIL NADU - 600004
        9999 4567 1231
    """.trimIndent()

    private val panStyleCard = """
        SAMPLE TAX ID CARD
        NOT A REAL DOCUMENT - FOR TESTING ONLY
        नाम / Name
        SAMPLE RAVI DEMO
        पिता का नाम / Father's Name
        SAMPLE MOHAN DEMO
        जन्म की तारीख / Date of Birth
        03/11/1985
        खाता संख्या / Account Number
        ABCPD1234Z
    """.trimIndent()

    private val licenceStyleCard = """
        SAMPLE DRIVING LICENCE
        NOT A REAL DOCUMENT - FOR TESTING ONLY
        DL No: TN00 20260001234
        Name: DEMO ARUN TEST
        பெயர்: டெமோ அருண் டெஸ்ட்
        DOB: 21-01-1992
        COIMBATORE - 641001
    """.trimIndent()

    private val tamilCertificate = """
        SAMPLE CERTIFICATE - NOT A REAL DOCUMENT
        மாதிரி சான்றிதழ்
        சான்றிதழ் எண்: மாதிரி 2026 0042
        பெயர்: செல்வி டெஸ்டா குமாரி
        வழங்கிய நாள்: 05 ஜனவரி 2026
    """.trimIndent()

    private val tamilAddressBlock = """
        SAMPLE ADDRESS - NOT A REAL DOCUMENT
        பெயர்: டெமோ செல்வம்
        தந்தை பெயர்: டெமோ கணேசன்
        கதவு எண் 7, சோதனை வீதி,
        மதுரை - 625020
    """.trimIndent()

    @Test
    fun eachFakeSampleIsGuessedRight() {
        assertEquals(DocumentType.AADHAAR, DocumentTypeGuesser.guess(aadhaarStyleCard))
        assertEquals(DocumentType.PAN, DocumentTypeGuesser.guess(panStyleCard))
        assertEquals(DocumentType.DRIVING_LICENCE, DocumentTypeGuesser.guess(licenceStyleCard))
        assertEquals(DocumentType.CERTIFICATE, DocumentTypeGuesser.guess(tamilCertificate))
    }

    @Test
    fun aPageWithNoSignOfAnyTypeGetsNoSuggestion() {
        assertNull(DocumentTypeGuesser.guess(tamilAddressBlock))
    }

    @Test
    fun realReadingOutputWithGarbageIsStillGuessedRight() {
        // What the merged reader produced on the licence-style sample (Task 2.3 record), garbage included.
        val read = """
            SAMPLE DRIVING LICENCE
            NOT A REAL DOCUMENT - FOR TESTING ONLY
            DL No: TNO0 20260001234
            Name: DEMO ARUN TEST
            ০LUয: LGLDT অ(UH600 GL6dL-
            பெயர்: டெமோ அருண் டெஸ்ட்
            AMPLE
        """.trimIndent()
        assertEquals(DocumentType.DRIVING_LICENCE, DocumentTypeGuesser.guess(read))
    }

    @Test
    fun garbageAndEmptyPagesGetNoSuggestion() {
        assertNull(DocumentTypeGuesser.guess(""))
        assertNull(DocumentTypeGuesser.guess("LULT (ভDrf।\nGTUILDL छ - 647001\n6LLLD: LouluTÜHI"))
    }

    @Test
    fun aTwelveDigitNumberThatFailsTheCheckIsNoAadhaarSign() {
        val text = "SAMPLE CARD\nTESTA KUMARI\n9999 4567 1232"
        assertNull(DocumentTypeGuesser.guess(text))
    }

    @Test
    fun aVirtualIdOrAMaskedNumberIsNoAadhaarSign() {
        assertNull(DocumentTypeGuesser.guess("SAMPLE CARD\n9999 4567 1231 2468"))
        assertNull(DocumentTypeGuesser.guess("SAMPLE CARD\nXXXX XXXX 1231"))
    }

    @Test
    fun keyWordsInEnglishHindiAndTamilWork() {
        assertEquals(DocumentType.AADHAAR, DocumentTypeGuesser.guess("Government of India\nआधार - सामान्य आदमी का अधिकार"))
        assertEquals(DocumentType.PAN, DocumentTypeGuesser.guess("INCOME TAX DEPARTMENT\nGOVT. OF INDIA\nPermanent Account Number"))
        assertEquals(DocumentType.PAN, DocumentTypeGuesser.guess("आयकर विभाग\nभारत सरकार"))
        assertEquals(DocumentType.PASSPORT, DocumentTypeGuesser.guess("REPUBLIC OF INDIA\nP<INDSAMPLE<<TEST<<<<<<<<<<"))
        assertEquals(DocumentType.DRIVING_LICENCE, DocumentTypeGuesser.guess("ஓட்டுநர் உரிமம்"))
    }

    @Test
    fun supportingWordsAloneAreNotEnough() {
        assertNull(DocumentTypeGuesser.guess("Government of India"))
        assertNull(DocumentTypeGuesser.guess("DL No: 1234"))
    }

    @Test
    fun aWordInsideAnotherWordDoesNotCount() {
        // "vid" inside "provided" and "passport" inside "passports-office" style words.
        assertEquals(null, DocumentTypeGuesser.scores("provided by the office")[DocumentType.AADHAAR])
    }

    @Test
    fun aMixedPageWithTwoStrongTypesGetsNoSuggestion() {
        // For example a photocopy with an Aadhaar and a PAN side by side: the user decides.
        val both = aadhaarStyleCard + "\n" + panStyleCard
        assertNull(DocumentTypeGuesser.guess(both))
    }
}
