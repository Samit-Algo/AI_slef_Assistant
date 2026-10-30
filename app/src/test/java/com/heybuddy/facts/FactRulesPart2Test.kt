package com.heybuddy.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2.7: names, relatives, gender, addresses and PIN codes, on the FAKE samples' text, on real
 * reading output from Tasks 2.2 and 2.3 (garbage included) and on made-up lines. No real data.
 */
class FactRulesPart2Test {

    private val aadhaarFront = """
        SAMPLE IDENTITY CARD
        टेस्टा कुमारी
        TESTA KUMARI
        டெஸ்டா குமாரி
        जन्म तिथि / DOB: 14/08/1990
        महिला / FEMALE
        Address: 12, SAMPLE STREET, MYLAPORE,
        CHENNAI, TAMIL NADU - 600004
        முகவரி: 12, மாதிரி தெரு, மயிலாப்பூர்,
        சென்னை, தமிழ்நாடு - 600004
        9999 4567 1231
    """.trimIndent()

    private val panCard = """
        SAMPLE TAX ID CARD
        नाम / Name
        SAMPLE RAVI DEMO
        पिता का नाम / Father's Name
        SAMPLE MOHAN DEMO
        जन्म की तारीख / Date of Birth
        03/11/1985
        ABCPD1234Z
    """.trimIndent()

    private val licenceAsRead = """
        SAMPLE DRIVING LICENCE
        DL No: TNO0 20260001234
        Name: DEMO ARUN TEST
        ০LUয: LGLDT অ(UH600 GL6dL-
        பெயர்: டெமோ அருண் டெஸ்ட்
        S/D/W of: DEMO KUMAR TEST
        DOB: 21-01-1992
        Address: 45, TEST NAGAR,
        COIMBATORE - 641001
        முகவரி: 45, சோதனை நகர்,
        கோயம்புத்தூர் - 641001
        Valid Till: 20-01-2042
    """.trimIndent()

    // ---- Names ----

    @Test
    fun aadhaarFrontNameIsTheEnglishLineAboveTheBirthDateAndTheTamilLineToo() {
        val facts = NameRule.find(aadhaarFront)
        assertEquals(setOf(name("TESTA KUMARI"), tamilName("டெஸ்டா குமாரி")), facts.toSet())
    }

    @Test
    fun panNameIsOnTheLineAfterItsBilingualLabel() {
        assertEquals(listOf(name("SAMPLE RAVI DEMO")), NameRule.find(panCard))
    }

    @Test
    fun licenceNameOnTheLabelLineAndTheTamilNameFromItsGarbageFreeLine() {
        assertEquals(setOf(name("DEMO ARUN TEST"), tamilName("டெமோ அருண் டெஸ்ட்")), NameRule.find(licenceAsRead).toSet())
    }

    @Test
    fun singleWordNamesAreAllowed() {
        // Owner rule: common in Tamil Nadu.
        assertEquals(listOf(name("MURUGAN")), NameRule.find("Name: MURUGAN"))
    }

    @Test
    fun initialsBeforeOrAfterTheNameAreKeptExactlyAsPrinted() {
        assertEquals(listOf(name("S. MURUGAN")), NameRule.find("Name: S. MURUGAN"))
        assertEquals(listOf(name("MURUGAN S")), NameRule.find("Name: MURUGAN S"))
        assertEquals(listOf(name("R.K. SELVI")), NameRule.find("Name: R.K. SELVI"))
        assertEquals(listOf(name("S MURUGAN")), NameRule.find("Name: S MURUGAN"))
        assertEquals(listOf(name("S.MURUGAN")), NameRule.find("Name: S.MURUGAN"))
    }

    @Test
    fun initialsAloneAreNotAName() {
        assertEquals(emptyList<FoundFact>(), NameRule.find("Name: R.K."))
    }

    @Test
    fun courtesyTitlesAreNotPartOfTheName() {
        assertEquals(listOf(tamilName("டெஸ்டா குமாரி")), NameRule.find("பெயர்: செல்வி டெஸ்டா குமாரி"))
        assertEquals(listOf(name("RAVI DEMO")), NameRule.find("Name: Shri RAVI DEMO"))
    }

    @Test
    fun tamilInitialsAreAllowed() {
        assertEquals(listOf(tamilName("ச. முருகன்")), NameRule.find("பெயர்: ச. முருகன்"))
    }

    @Test
    fun aNameWithDigitsOrTooManyWordsOrALabelWordIsRejected() {
        assertEquals(emptyList<FoundFact>(), NameRule.find("Name: RAVI 1234"))
        assertEquals(emptyList<FoundFact>(), NameRule.find("Name: ONE TWO THREE FOUR FIVE SIX"))
        assertEquals(emptyList<FoundFact>(), NameRule.find("Name: FEMALE"))
    }

    @Test
    fun anOldPanCardWithoutLabelsGivesNameThenFather() {
        val oldPan = "INCOME TAX DEPARTMENT\nGOVT. OF INDIA\nSAMPLE RAVI DEMO\nSAMPLE MOHAN DEMO\n03/11/1985\nABCPD1234Z"
        assertEquals(listOf(name("SAMPLE RAVI DEMO")), NameRule.find(oldPan))
        assertEquals(listOf(fact(FactKind.FATHER_NAME, "SAMPLE MOHAN DEMO")), RelativeNameRule.find(oldPan))
    }

    // ---- Father, husband, relative ----

    @Test
    fun fathersNameAfterItsLabelInEveryLanguage() {
        assertEquals(listOf(fact(FactKind.FATHER_NAME, "SAMPLE MOHAN DEMO")), RelativeNameRule.find(panCard))
        assertEquals(listOf(fact(FactKind.FATHER_NAME, "டெமோ கணேசன்")), RelativeNameRule.find("தந்தை பெயர்: டெமோ கணேசன்"))
        assertEquals(listOf(fact(FactKind.FATHER_NAME, "டெமோ ராமன்")), RelativeNameRule.find("தந்தை பெயர்: திரு டெமோ ராமன்"))
    }

    @Test
    fun relationMarkersBecomePleaseCheckHints() {
        assertEquals(listOf(fact(FactKind.FATHER_NAME, "SURESH KUMAR")), RelativeNameRule.find("S/O: SURESH KUMAR, 123 CIVIL LINES"))
        assertEquals(listOf(fact(FactKind.FATHER_NAME, "SURESH KUMAR")), RelativeNameRule.find("D/O SURESH KUMAR"))
        assertEquals(listOf(fact(FactKind.HUSBAND_NAME, "DEMO ARUN")), RelativeNameRule.find("W/O: DEMO ARUN, 7 TEST ROAD"))
        assertEquals(listOf(fact(FactKind.RELATIVE_NAME, "DEMO ARUN")), RelativeNameRule.find("C/O DEMO ARUN"))
        assertEquals(listOf(fact(FactKind.RELATIVE_NAME, "DEMO KUMAR TEST")), RelativeNameRule.find(licenceAsRead))
        assertEquals(listOf(fact(FactKind.FATHER_NAME, "டெமோ ராமன்")), RelativeNameRule.find("த/பெ டெமோ ராமன்"))
    }

    // ---- Gender ----

    @Test
    fun genderInEnglishHindiAndTamil() {
        assertEquals(listOf(gender("FEMALE")), GenderRule.find(aadhaarFront))
        assertEquals(listOf(gender("MALE")), GenderRule.find("पुरुष / MALE"))
        assertEquals(listOf(gender("FEMALE")), GenderRule.find("பாலினம்: பெண்"))
        assertEquals(listOf(gender("MALE")), GenderRule.find("பாலினம்: ஆண்"))
        assertEquals(listOf(gender("TRANSGENDER")), GenderRule.find("பாலினம்: மூன்றாம் பாலினம்"))
        assertEquals(listOf(gender("MALE")), GenderRule.find("Sex: M"))
    }

    @Test
    fun femaleIsNotReadAsMaleAndTamilWordsInsideOthersDoNotCount() {
        assertEquals(listOf(gender("FEMALE")), GenderRule.find("FEMALE"))
        // "ஆண்டு" means "year"; it contains "ஆண்" but is not the word for male.
        assertEquals(emptyList<FoundFact>(), GenderRule.find("2026 ஆண்டு"))
    }

    @Test
    fun twoDifferentGendersGiveNoAnswer() {
        assertEquals(emptyList<FoundFact>(), GenderRule.find("MALE\nFEMALE"))
    }

    // ---- Address and PIN ----

    @Test
    fun englishAndTamilAddressesAreSeparateAndEndAtThePin() {
        val facts = AddressRule.find(aadhaarFront)
        assertEquals(
            listOf(
                fact(FactKind.ADDRESS, "12, SAMPLE STREET, MYLAPORE, CHENNAI, TAMIL NADU - 600004"),
                fact(FactKind.ADDRESS_TAMIL, "12, மாதிரி தெரு, மயிலாப்பூர், சென்னை, தமிழ்நாடு - 600004"),
            ),
            facts,
        )
        assertEquals(listOf(FoundFact(FactKind.PIN_CODE, "600004", FactStatus.READ_CLEANLY)), PinCodeRule.find(aadhaarFront))
    }

    @Test
    fun theLicenceAddressesAndPinFromRealReadingOutput() {
        val facts = AddressRule.find(licenceAsRead)
        assertEquals(listOf("45, TEST NAGAR, COIMBATORE - 641001", "45, சோதனை நகர், கோயம்புத்தூர் - 641001"), facts.map { fact -> fact.value })
        assertEquals(listOf("641001"), PinCodeRule.find(licenceAsRead).map { fact -> fact.value })
    }

    @Test
    fun aRelationAtTheStartIsKeptOutOfTheAddress() {
        // Owner rule: "S/O <name>," is a hint for the father's name, not part of the address.
        val onOneLine = AddressRule.find("Address: S/O: SURESH KUMAR, 123 CIVIL LINES,\nLUCKNOW, UTTAR PRADESH 226001")
        assertEquals(listOf("123 CIVIL LINES, LUCKNOW, UTTAR PRADESH 226001"), onOneLine.map { fact -> fact.value })
        val onItsOwnLine = AddressRule.find("पता:\nC/O: DEMO ARUN\n7 TEST ROAD\nMADURAI 625020")
        assertEquals(listOf("7 TEST ROAD, MADURAI 625020"), onItsOwnLine.map { fact -> fact.value })
    }

    @Test
    fun anAddressWithoutAPinStopsAtTheNextLabel() {
        val facts = AddressRule.find("Address: 7 TEST ROAD,\nANNA NAGAR\nDOB: 01/01/1990")
        assertEquals(listOf("7 TEST ROAD, ANNA NAGAR"), facts.map { fact -> fact.value })
        assertTrue(PinCodeRule.find("Address: 7 TEST ROAD,\nANNA NAGAR\nDOB: 01/01/1990").isEmpty())
    }

    @Test
    fun garbageLinesNeverGiveANameAnAddressOrAWrongPin() {
        // ML Kit's Tamil garbage from Task 2.2, including the WRONG PIN 647001.
        val garbage = "முகவரி:\nGTUILDL छ - 647001\n০LUয: LGLDT অ(UH600 GL6dL-"
        assertTrue(NameRule.find(garbage).isEmpty())
        assertTrue(PinCodeRule.find(garbage).isEmpty())
    }

    private fun name(value: String) = fact(FactKind.NAME, value)

    private fun tamilName(value: String) = fact(FactKind.NAME_TAMIL, value)

    private fun gender(value: String) = FoundFact(FactKind.GENDER, value, FactStatus.READ_CLEANLY)

    private fun fact(kind: FactKind, value: String) = FoundFact(kind, value, FactStatus.PLEASE_CHECK)
}
