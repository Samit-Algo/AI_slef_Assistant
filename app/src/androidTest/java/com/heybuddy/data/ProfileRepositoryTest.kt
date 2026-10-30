package com.heybuddy.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.security.MasterKeyManager
import com.heybuddy.security.VaultHealthMonitor
import com.heybuddy.security.requirePhoneUnlocked
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The confirmed profile details (Task 2.8, spec 7.10) on a real phone, with a test vault and FAKE
 * values only: stored only inside the encrypted database, Aadhaar last 4 digits by default, one
 * chosen value per kind, both delete choices for pages and documents, and "Delete everything".
 */
@RunWith(AndroidJUnit4::class)
class ProfileRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = TestVault(context, name = "profile")
    private val profiles = vault.profileRepository

    @Before
    fun startClean() {
        requirePhoneUnlocked()
        vault.deleteAll()
        vault.unlock()
    }

    @After
    fun cleanUp() {
        vault.deleteAll()
    }

    @Test
    fun detailsAreStoredOnlyInsideTheEncryptedDatabase() = runBlocking {
        val (documentId, pageId) = twoPageDocument().let { (document, pages) -> document to pages.first() }
        profiles.saveConfirmed(listOf(ocr(ProfileFactKind.FULL_NAME, SAMPLE_NAME, documentId, pageId)), keepFullAadhaar = false)

        assertEquals(listOf(SAMPLE_NAME), profiles.allFacts().map { fact -> fact.value })
        // The value must not appear in plain text in ANY file of the app, the database file included.
        for (file in context.dataDir.walkTopDown().filter { file -> file.isFile && file.canRead() }) {
            assertFalse("\"$SAMPLE_NAME\" readable in ${file.name}", contains(file, SAMPLE_NAME))
        }
    }

    @Test
    fun aadhaarKeepsOnlyTheLastFourDigitsUnlessTheUserChoseTheFullNumber() = runBlocking {
        profiles.saveConfirmed(listOf(typed(ProfileFactKind.AADHAAR_FULL, "9999 4567 1231")), keepFullAadhaar = false)
        assertEquals(listOf(ProfileFactKind.AADHAAR_LAST4 to "1231"), profiles.allFacts().map { fact -> fact.kind to fact.value })

        vault.deleteAll()
        vault.unlock()
        profiles.saveConfirmed(listOf(typed(ProfileFactKind.AADHAAR_FULL, "9999 4567 1231")), keepFullAadhaar = true)
        assertEquals(listOf(ProfileFactKind.AADHAAR_FULL to "999945671231"), profiles.allFacts().map { fact -> fact.kind to fact.value })
    }

    @Test
    fun anAadhaarNumberThatFailsItsCheckDigitIsNeverStored() = runBlocking {
        try {
            profiles.saveConfirmed(listOf(typed(ProfileFactKind.AADHAAR_FULL, "9999 4567 1232")), keepFullAadhaar = true)
            fail("A wrong Aadhaar number must be refused")
        } catch (expected: InvalidFactValueException) {
            assertTrue(profiles.allFacts().isEmpty())
        }
    }

    @Test
    fun theSameValueTwiceIsStoredOnce() = runBlocking {
        profiles.saveConfirmed(listOf(typed(ProfileFactKind.FULL_NAME, "S. MURUGAN")), keepFullAadhaar = false)
        profiles.saveConfirmed(listOf(typed(ProfileFactKind.FULL_NAME, "s.  murugan")), keepFullAadhaar = false)

        assertEquals(1, profiles.allFacts().size)
    }

    @Test
    fun whenDocumentsDisagreeTheFirstStaysChosenUntilTheUserPicks() = runBlocking {
        profiles.saveConfirmed(listOf(typed(ProfileFactKind.DATE_OF_BIRTH, "1990-08-14")), keepFullAadhaar = false)
        profiles.saveConfirmed(listOf(typed(ProfileFactKind.DATE_OF_BIRTH, "1990-08-15")), keepFullAadhaar = false)
        assertEquals(mapOf("1990-08-14" to true, "1990-08-15" to false), chosenByValue())

        val second = profiles.allFacts().first { fact -> fact.value == "1990-08-15" }
        profiles.chooseFact(second.factId)
        assertEquals(mapOf("1990-08-14" to false, "1990-08-15" to true), chosenByValue())

        profiles.deleteFact(second.factId)
        assertEquals("the remaining one is chosen again", mapOf("1990-08-14" to true), chosenByValue())
    }

    @Test
    fun anEditedDetailCountsAsTyped() = runBlocking {
        val (documentId, pages) = twoPageDocument()
        profiles.saveConfirmed(listOf(ocr(ProfileFactKind.FULL_NAME, "TESTA KUMARl", documentId, pages.first())), keepFullAadhaar = false)
        val fact = profiles.allFacts().single()

        profiles.editFact(fact.factId, SAMPLE_NAME)

        val edited = profiles.allFacts().single()
        assertEquals(SAMPLE_NAME, edited.value)
        assertEquals(FactOrigin.TYPED, edited.origin)
    }

    @Test
    fun removingAPageWithYesDeletesItsDetails() = runBlocking {
        val (documentId, pages) = twoPageDocument()
        profiles.saveConfirmed(listOf(ocr(ProfileFactKind.FULL_NAME, SAMPLE_NAME, documentId, pages[0]), ocr(ProfileFactKind.PIN_CODE, "600004", documentId, pages[1])), keepFullAadhaar = false)
        assertEquals(1, profiles.countForPage(pages[0]))

        vault.repository.removePage(documentId, pages[0], keepDetails = false)

        assertEquals(listOf("600004"), profiles.allFacts().map { fact -> fact.value })
    }

    @Test
    fun removingAPageWithNoKeepsItsDetailsAsPageDeleted() = runBlocking {
        val (documentId, pages) = twoPageDocument()
        profiles.saveConfirmed(listOf(ocr(ProfileFactKind.FULL_NAME, SAMPLE_NAME, documentId, pages[0])), keepFullAadhaar = false)

        vault.repository.removePage(documentId, pages[0], keepDetails = true)

        val kept = profiles.allFacts().single()
        assertEquals(SAMPLE_NAME, kept.value)
        assertNull(kept.pageId)
        assertTrue(kept.sourceDeleted)
    }

    @Test
    fun deletingADocumentWithYesDeletesItsDetails() = runBlocking {
        val (documentId, pages) = twoPageDocument()
        profiles.saveConfirmed(listOf(ocr(ProfileFactKind.FULL_NAME, SAMPLE_NAME, documentId, pages[0]), typed(ProfileFactKind.GENDER, "FEMALE")), keepFullAadhaar = false)
        assertEquals(1, profiles.countForDocument(documentId))

        vault.repository.deleteDocument(documentId, keepDetails = false)

        assertEquals("only the typed detail stays", listOf("FEMALE"), profiles.allFacts().map { fact -> fact.value })
    }

    @Test
    fun deletingADocumentWithNoKeepsItsDetailsAsPageDeleted() = runBlocking {
        val (documentId, pages) = twoPageDocument()
        profiles.saveConfirmed(listOf(ocr(ProfileFactKind.FULL_NAME, SAMPLE_NAME, documentId, pages[0])), keepFullAadhaar = false)

        vault.repository.deleteDocument(documentId, keepDetails = true)

        val kept = profiles.allFacts().single()
        assertNull(kept.documentId)
        assertNull(kept.pageId)
        assertTrue(kept.sourceDeleted)
    }

    @Test
    fun deletingAChosenDetailWithItsDocumentChoosesTheOtherValue() = runBlocking {
        val (documentId, pages) = twoPageDocument()
        profiles.saveConfirmed(listOf(ocr(ProfileFactKind.DATE_OF_BIRTH, "1990-08-14", documentId, pages[0])), keepFullAadhaar = false)
        profiles.saveConfirmed(listOf(typed(ProfileFactKind.DATE_OF_BIRTH, "1990-08-15")), keepFullAadhaar = false)

        vault.repository.deleteDocument(documentId, keepDetails = false)

        assertEquals(mapOf("1990-08-15" to true), chosenByValue())
    }

    @Test
    fun deleteEverythingRemovesAllDetails() = runBlocking {
        profiles.saveConfirmed(listOf(typed(ProfileFactKind.FULL_NAME, SAMPLE_NAME)), keepFullAadhaar = false)
        val files = VaultFiles(
            databaseFile = context.getDatabasePath(vault.databaseName),
            passphraseFile = File(context.noBackupFilesDir, "db_passphrase_profile_test.bin"),
            vaultDir = vault.vaultDir,
            viewTempDir = vault.viewTempDir,
        )
        val key = MasterKeyManager(keyAlias = "hb_master_key_profile_test", requireUserAuthentication = false)
        val health = VaultHealthMonitor(keyExists = key::hasMasterKey, vaultDataExists = files::vaultDataExists)
        val eraser = VaultEraser(context, vault.lockState, vault.holder, key, health, files, onErased = {})

        eraser.eraseEverything()

        assertFalse("the database (and every detail in it) is gone", files.databaseFile.exists())
        vault.unlock()
        assertTrue(profiles.allFacts().isEmpty())
    }

    @Test
    fun detailsCannotBeReadWhileLocked() = runBlocking {
        profiles.saveConfirmed(listOf(typed(ProfileFactKind.FULL_NAME, SAMPLE_NAME)), keepFullAadhaar = false)
        vault.lock()
        try {
            profiles.allFacts()
            fail("Locked vault must refuse")
        } catch (expected: VaultLockedException) {
            // As required.
        }
    }

    /** A saved two-page document; returns its id and its page ids in order. */
    private suspend fun twoPageDocument(): Pair<String, List<String>> {
        val documentId = vault.repository.newDocumentId()
        val pages = listOf(TestVault.sampleJpegBytes(), TestVault.sampleJpegBytes().reversedArray()).map { bytes ->
            vault.repository.preparePage(documentId, ByteArrayInputStream(bytes), MIME_JPEG)
        }
        vault.repository.saveDocument(documentId, pages, "Sample card", DocumentType.AADHAAR)
        val pageIds = vault.repository.documentDetails(documentId).pages.map { page -> page.pageId }
        return documentId to pageIds
    }

    private suspend fun chosenByValue(): Map<String, Boolean> = profiles.allFacts().associate { fact -> fact.value to fact.chosen }

    private fun ocr(kind: ProfileFactKind, value: String, documentId: String, pageId: String) = NewProfileFact(kind, value, documentId, pageId, FactOrigin.OCR)

    private fun typed(kind: ProfileFactKind, value: String) = NewProfileFact(kind, value, null, null, FactOrigin.TYPED)

    private fun contains(file: File, text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        val bytes = file.readBytes()
        if (bytes.size < needle.size) return false
        for (start in 0..bytes.size - needle.size) {
            var match = true
            for (offset in needle.indices) {
                if (bytes[start + offset] != needle[offset]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private companion object {
        const val SAMPLE_NAME = "TESTA KUMARI"
        const val MIME_JPEG = "image/jpeg"
    }
}
