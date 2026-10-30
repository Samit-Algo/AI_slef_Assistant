package com.heybuddy.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Task 2.6: PAN, Aadhaar, licence numbers and dates, on the FAKE samples' text and on real reading
 * output from Tasks 2.2 and 2.3 (with its mix-ups and garbage). No real data.
 */
class FactRulesPart1Test {

    private val today = LocalDate.of(2026, 10, 30)

    // ---- PAN ----

    @Test
    fun aCleanPanIsReadCleanly() {
        assertEquals(listOf(FoundFact(FactKind.PAN, "ABCPD1234Z", FactStatus.READ_CLEANLY)), PanRule.find("खाता संख्या / Account Number\nABCPD1234Z"))
    }

    @Test
    fun oneOrTwoMixUpsAreFixedByPositionAndMarkedPleaseCheck() {
        // A letter read as a digit (5 for S in a letter place), and a digit read as a letter (O for 0).
        assertEquals(listOf(FoundFact(FactKind.PAN, "ABSPD1234Z", FactStatus.PLEASE_CHECK)), PanRule.find("AB5PD1234Z"))
        assertEquals(listOf(FoundFact(FactKind.PAN, "ABCPD1034Z", FactStatus.PLEASE_CHECK)), PanRule.find("ABCPD1O34Z"))
        assertEquals(listOf(FoundFact(FactKind.PAN, "ABCPD1034Z", FactStatus.PLEASE_CHECK)), PanRule.find("A8CPD1O34Z"))
    }

    @Test
    fun tooManyMixUpsAreNotGuessed() {
        assertEquals(emptyList<FoundFact>(), PanRule.find("A8CPD1OS4Z"))
    }

    @Test
    fun onlyAPersonalPanIsAFactAboutTheUser() {
        assertEquals(emptyList<FoundFact>(), PanRule.find("ABCCD1234Z"))
    }

    @Test
    fun ordinaryWordsAreNeverAPan() {
        assertEquals(emptyList<FoundFact>(), PanRule.find("GOVERNMENT DEPARTMENT ACCOUNTING"))
    }

    // ---- Aadhaar ----

    @Test
    fun theFakeAadhaarNumberIsReadOnceFromFrontAndBack() {
        val text = "TESTA KUMARI\n9999 4567 1231\nAddress: ...\n9999 4567 1231"
        assertEquals(listOf(FoundFact(FactKind.AADHAAR_NUMBER, "999945671231", FactStatus.READ_CLEANLY)), AadhaarRule.find(text))
    }

    @Test
    fun lookAlikeLettersAreFixedOnlyWhenTheCheckDigitThenPasses() {
        assertEquals(listOf(FoundFact(FactKind.AADHAAR_NUMBER, "999945671231", FactStatus.PLEASE_CHECK)), AadhaarRule.find("9999 4567 I23I"))
        assertEquals(listOf(FoundFact(FactKind.AADHAAR_NUMBER, "999945671231", FactStatus.PLEASE_CHECK)), AadhaarRule.find("9999 4S67 1231"))
    }

    @Test
    fun aNumberThatFailsTheCheckIsLeftForTheUserToType() {
        assertEquals(listOf(FoundFact(FactKind.AADHAAR_NUMBER, "", FactStatus.PLEASE_TYPE)), AadhaarRule.find("9999 4567 1232"))
        assertEquals(listOf(FoundFact(FactKind.AADHAAR_NUMBER, "", FactStatus.PLEASE_TYPE)), AadhaarRule.find("9999 4567 I23O"))
    }

    @Test
    fun virtualIdsEnrolmentAndMaskedNumbersAreNeverTaken() {
        assertEquals(emptyList<FoundFact>(), AadhaarRule.find("VID: 9999 4567 1231 2468"))
        assertEquals(emptyList<FoundFact>(), AadhaarRule.find("VID: 9999 4567 I23I 2468"))
        assertEquals(emptyList<FoundFact>(), AadhaarRule.find("2026 9999 4567 1231"))
        assertEquals(emptyList<FoundFact>(), AadhaarRule.find("XXXX XXXX 1231"))
    }

    @Test
    fun indianLookAlikeDigitsAreRead() {
        assertEquals("999945671231", AadhaarRule.find("९९९९ 4567 123১").single().value)
    }

    // ---- Driving licence number ----

    @Test
    fun theTask22LicenceMissIsNowRead() {
        // Both readers wrote "TNO0" (letter O) for "TN00" in Task 2.2.
        val facts = LicenceNumberRule.find("DL No: TNO0 20260001234", currentYear = 2026)
        assertEquals(listOf(FoundFact(FactKind.DRIVING_LICENCE_NUMBER, "TN00 20260001234", FactStatus.PLEASE_CHECK)), facts)
    }

    @Test
    fun aCleanLicenceNumberIsReadCleanly() {
        val facts = LicenceNumberRule.find("DL No: TN00 20260001234", currentYear = 2026)
        assertEquals(listOf(FoundFact(FactKind.DRIVING_LICENCE_NUMBER, "TN00 20260001234", FactStatus.READ_CLEANLY)), facts)
    }

    @Test
    fun aLicenceNumberNeedsARealStateAndASensibleYear() {
        assertEquals(emptyList<FoundFact>(), LicenceNumberRule.find("XX00 20260001234", currentYear = 2026))
        assertEquals(emptyList<FoundFact>(), LicenceNumberRule.find("TN00 20990001234", currentYear = 2026))
    }

    // ---- Dates ----

    @Test
    fun datesAreAlwaysDayFirstNeverMonthFirst() {
        // Owner rule: 05/08/1994 is 5 August 1994 (Indian format), never May 8.
        val facts = DateRule.find("DOB: 05/08/1994", today)
        assertEquals(listOf(FoundFact(FactKind.DATE_OF_BIRTH, "1994-08-05", FactStatus.READ_CLEANLY)), facts)
    }

    @Test
    fun theThreeSeparatorsAllWork() {
        assertEquals("1990-08-14", DateRule.find("DOB: 14/08/1990", today).single().value)
        assertEquals("1992-01-21", DateRule.find("DOB: 21-01-1992", today).single().value)
        assertEquals("1985-11-03", DateRule.find("DOB: 03.11.1985", today).single().value)
    }

    @Test
    fun aDateUnderItsLabelLineIsTheDateOfBirth() {
        // The PAN-style card prints the label on its own line, the date below it.
        val facts = DateRule.find("जन्म की तारीख / Date of Birth\n03/11/1985", today)
        assertEquals(listOf(FoundFact(FactKind.DATE_OF_BIRTH, "1985-11-03", FactStatus.READ_CLEANLY)), facts)
    }

    @Test
    fun theLabelledDateWinsOverIssueAndValidityDates() {
        val facts = DateRule.find("Date of Issue: 07-10-2022\nDOB: 21-01-1992\nValid Till: 20-01-2042", today)
        assertEquals(listOf(FoundFact(FactKind.DATE_OF_BIRTH, "1992-01-21", FactStatus.READ_CLEANLY)), facts.filter { fact -> fact.kind == FactKind.DATE_OF_BIRTH })
        assertEquals(listOf("2022-10-07", "2042-01-20"), facts.filter { fact -> fact.kind == FactKind.OTHER_DATE }.map { fact -> fact.value })
    }

    @Test
    fun hindiAndTamilLabelsWork() {
        assertEquals("1990-08-14", DateRule.find("जन्म तिथि / DOB: 14/08/1990", today).single().value)
        assertEquals("1990-08-14", DateRule.find("பிறந்த தேதி: 14/08/1990", today).single().value)
    }

    @Test
    fun tamilMonthNamesAndTamilDigitsAreUnderstood() {
        assertEquals("1990-08-15", DateRule.find("பிறந்த தேதி: 15 ஆகஸ்ட் 1990", today).single().value)
        assertEquals("1990-08-15", DateRule.find("பிறந்த தேதி: ௧௫ ஆகஸ்ட் ௧௯௯௦", today).single().value)
        // The fake certificate's issue date: an other date, not a date of birth.
        assertEquals(listOf(FoundFact(FactKind.OTHER_DATE, "2026-01-05", FactStatus.READ_CLEANLY)), DateRule.find("வழங்கிய நாள்: 05 ஜனவரி 2026", today))
    }

    @Test
    fun englishMonthNamesWork() {
        assertEquals("1994-08-05", DateRule.find("Date of Birth: 5 Aug 1994", today).single().value)
        assertEquals("1994-09-05", DateRule.find("Date of Birth: 05-Sept-1994", today).single().value)
    }

    @Test
    fun aTraditionalTamilMonthIsNeverConvertedButLeftForTheUser() {
        val facts = DateRule.find("பிறந்த தேதி: 15 ஆவணி 1990", today)
        assertEquals(listOf(FoundFact(FactKind.DATE_OF_BIRTH, "", FactStatus.PLEASE_TYPE)), facts)
    }

    @Test
    fun theTamilWordForFatherIsNotMistakenForAMonth() {
        // "தை" is a traditional month AND the end of "தந்தை" (father): only day + month + year counts.
        assertEquals(emptyList<FoundFact>(), DateRule.find("தந்தை பெயர்: திரு டெமோ ராமன்", today))
    }

    @Test
    fun impossibleFutureAndTooOldBirthDatesAreRejected() {
        assertEquals(emptyList<FoundFact>(), DateRule.find("DOB: 31/02/1990", today))
        assertEquals(emptyList<FoundFact>(), DateRule.find("DOB: 01/01/2027", today))
        assertEquals(emptyList<FoundFact>(), DateRule.find("DOB: 01/01/1900", today))
    }

    @Test
    fun yearOfBirthOnlyCards() {
        assertEquals(listOf(FoundFact(FactKind.DATE_OF_BIRTH, "1990", FactStatus.READ_CLEANLY)), DateRule.find("Year of Birth: 1990", today))
    }

    @Test
    fun bengaliLookAlikeDigitsInADateAreRead() {
        // As the Devanagari reader once wrote it in Task 2.2: "199০".
        assertEquals("1990-08-14", DateRule.find("DOB: 14/08/199০", today).single().value)
    }

    @Test
    fun garbageGivesNoFacts() {
        val garbage = "LULT (ভDrf।\nGTUILDL छ - 647001\n6LLLD: LouluTÜHI\nAMPLE"
        assertTrue(PanRule.find(garbage).isEmpty())
        assertTrue(AadhaarRule.find(garbage).isEmpty())
        assertTrue(LicenceNumberRule.find(garbage, currentYear = 2026).isEmpty())
        assertTrue(DateRule.find(garbage, today).isEmpty())
    }
}
