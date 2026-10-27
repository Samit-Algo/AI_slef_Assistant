package com.heybuddy.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.util.DB_PASSPHRASE_BYTES
import com.heybuddy.util.DOCUMENT_KEY_ASSOCIATED_DATA_PREFIX
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.GeneralSecurityException

/**
 * Logic tests for [DatabaseKeyProvider] (rule 5).
 *
 * Uses a test key WITHOUT the fingerprint/PIN requirement and its own test file,
 * so the real passphrase file and the real vault key are never touched.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseKeyProviderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val passphraseFile = File(context.noBackupFilesDir, TEST_FILE_NAME)
    private val masterKeyManager = MasterKeyManager(
        keyAlias = TEST_KEY_ALIAS,
        requireUserAuthentication = false,
    )
    private val keyWrapper = KeyWrapper(masterKeyManager)

    @Before
    fun startClean() {
        requirePhoneUnlocked()
        deleteTestKeyAndFile()
    }

    @After
    fun cleanUp() {
        // @After runs even when a test fails, so nothing from the test stays on the phone.
        deleteTestKeyAndFile()
    }

    @Test
    fun createsThirtyTwoBytesAndReadsTheSameBack() {
        val firstProvider = DatabaseKeyProvider(keyWrapper, passphraseFile)
        val created = firstProvider.getPassphrase()

        // A fresh provider has nothing in memory, so it must read and unwrap the file.
        val secondProvider = DatabaseKeyProvider(keyWrapper, passphraseFile)
        val readBack = secondProvider.getPassphrase()

        assertEquals(DB_PASSPHRASE_BYTES, created.size)
        assertArrayEquals(created, readBack)
    }

    @Test
    fun fileDoesNotContainThePlainPassphrase() {
        val provider = DatabaseKeyProvider(keyWrapper, passphraseFile)
        val passphrase = provider.getPassphrase()

        val fileBytes = passphraseFile.readBytes()

        assertFalse(containsSequence(fileBytes, passphrase))
    }

    @Test
    fun returnsACopySoWipingItDoesNotWipeTheCachedPassphrase() {
        val provider = DatabaseKeyProvider(keyWrapper, passphraseFile)
        val firstCopy = provider.getPassphrase()
        val original = firstCopy.copyOf()

        firstCopy.fill(0)
        val secondCopy = provider.getPassphrase()

        assertArrayEquals(original, secondCopy)
    }

    @Test
    fun passphraseIsWipedFromMemoryWhenTheVaultLocks() {
        val lockState = VaultLockState()
        val provider = DatabaseKeyProvider(keyWrapper, passphraseFile)
        lockState.addOnLockListener { provider.clear() }
        provider.getPassphrase()
        val cachedArray = provider.cachedPassphraseForTest()
        assertNotNull(cachedArray)

        lockState.lock()

        assertTrue(cachedArray!!.all { byte -> byte == ZERO })
        assertNull(provider.cachedPassphraseForTest())
    }

    @Test
    fun unknownFileVersionIsRejected() {
        val unknownVersion: Byte = 9
        passphraseFile.writeBytes(byteArrayOf(unknownVersion, 1, 2, 3, 4))
        val provider = DatabaseKeyProvider(keyWrapper, passphraseFile)

        assertThrows(UnknownFileFormatException::class.java) {
            provider.getPassphrase()
        }
    }

    @Test
    fun documentKeyMaterialCannotBeUsedAsThePassphrase() {
        // Simulates swapping in bytes that were wrapped for a document, not for the passphrase.
        val documentLabel = (DOCUMENT_KEY_ASSOCIATED_DATA_PREFIX + "some-document").toByteArray(Charsets.UTF_8)
        val wrappedForDocument = keyWrapper.wrapBytes(ByteArray(DB_PASSPHRASE_BYTES), documentLabel)
        passphraseFile.writeBytes(PassphraseFileFormat.encode(wrappedForDocument))
        val provider = DatabaseKeyProvider(keyWrapper, passphraseFile)

        assertThrows(GeneralSecurityException::class.java) {
            provider.getPassphrase()
        }
    }

    private fun deleteTestKeyAndFile() {
        masterKeyManager.deleteMasterKey()
        passphraseFile.delete()
    }

    private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        val lastStart = haystack.size - needle.size
        for (start in 0..lastStart) {
            val window = haystack.copyOfRange(start, start + needle.size)
            if (window.contentEquals(needle)) return true
        }
        return false
    }

    private companion object {
        const val TEST_KEY_ALIAS = "hb_master_key_passphrase_test"
        const val TEST_FILE_NAME = "db_passphrase_test.bin"
        const val ZERO: Byte = 0
    }
}
