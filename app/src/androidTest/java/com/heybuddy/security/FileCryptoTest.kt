package com.heybuddy.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.heybuddy.files.FileTooLargeException
import com.heybuddy.util.MAX_FILE_BYTES
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.random.Random

/**
 * Checks [FileCrypto] on a real phone (rules 3, 4, 6 and 11).
 *
 * Needs no Keystore key: FileCrypto only uses document keys, which the tests create directly.
 * Uses its own test folders, deleted after every test even when it fails.
 */
@RunWith(AndroidJUnit4::class)
class FileCryptoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vaultDir = File(context.filesDir, "vault_test")
    private val viewTempDir = File(context.noBackupFilesDir, "view_tmp_test")
    private val fileCrypto = FileCrypto(vaultDir, viewTempDir)

    // generateDocumentKey never touches the Keystore, so this alias is never created.
    private val keyWrapper = KeyWrapper(MasterKeyManager(keyAlias = "not_used_by_file_tests"))

    @Before
    fun startClean() {
        deleteTestFolders()
    }

    @After
    fun cleanUp() {
        deleteTestFolders()
    }

    @Test
    fun roundTripForEdgeSizes() {
        val sizes = listOf(0, 1, TINK_SEGMENT_BYTES, TINK_SEGMENT_BYTES + 1, THREE_MEGABYTES)
        for (size in sizes) {
            val plaintext = Random(seed = size).nextBytes(size)
            val documentId = newDocumentId()
            val key = keyWrapper.generateDocumentKey()

            val reportedSize = fileCrypto.encryptToVault(ByteArrayInputStream(plaintext), key, documentId)
            val decrypted = fileCrypto.decryptVerified(documentId, key)

            assertEquals("size $size", size.toLong(), reportedSize)
            assertArrayEquals("size $size", plaintext, decrypted.bytes)
        }
    }

    @Test
    fun exactlyTheLimitIsAcceptedAndOneMoreIsRejected() {
        val key = keyWrapper.generateDocumentKey()
        val atLimitId = newDocumentId()
        val overLimitId = newDocumentId()

        val acceptedSize = fileCrypto.encryptToVault(GeneratedInputStream(MAX_FILE_BYTES), key, atLimitId)

        assertEquals(MAX_FILE_BYTES, acceptedSize)
        assertThrows(FileTooLargeException::class.java) {
            fileCrypto.encryptToVault(GeneratedInputStream(MAX_FILE_BYTES + 1), key, overLimitId)
        }
        assertNoFilesFor(overLimitId)
    }

    @Test
    fun oneChangedByteInTheMiddleFails() {
        val (documentId, key) = encryptSample(sizeBytes = THREE_MEGABYTES)
        val encryptedFile = fileCrypto.encryptedFileFor(documentId)

        changeByteAt(encryptedFile, encryptedFile.length() / 2)

        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(documentId, key)
        }
    }

    /**
     * Proves point 5: Tink's raw stream hands out early chunks before it meets a changed last
     * chunk, but FileCrypto returns nothing at all.
     */
    @Test
    fun changedLastChunkReturnsNoDataAtAll() {
        val (documentId, key) = encryptSample(sizeBytes = THREE_MEGABYTES)
        val encryptedFile = fileCrypto.encryptedFileFor(documentId)
        changeByteAt(encryptedFile, encryptedFile.length() - 10)

        val bytesFromRawStream = countBytesBeforeFailure(encryptedFile, key, documentId)

        assertTrue("Raw stream should leak early chunks", bytesFromRawStream > 0)
        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(documentId, key)
        }
    }

    @Test
    fun cutOffFileFails() {
        val (documentId, key) = encryptSample(sizeBytes = THREE_MEGABYTES)
        val encryptedFile = fileCrypto.encryptedFileFor(documentId)
        val fullBytes = encryptedFile.readBytes()

        encryptedFile.writeBytes(fullBytes.copyOf(fullBytes.size - 1))
        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(documentId, key)
        }

        encryptedFile.writeBytes(fullBytes.copyOf(fullBytes.size / 2))
        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(documentId, key)
        }
    }

    @Test
    fun wrongDocumentIdFails() {
        val (documentId, key) = encryptSample(sizeBytes = SMALL_BYTES)
        val otherId = newDocumentId()
        // Put the file under the other id's name, keeping the right key: only the id differs.
        fileCrypto.encryptedFileFor(documentId).renameTo(fileCrypto.encryptedFileFor(otherId))

        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(otherId, key)
        }
    }

    @Test
    fun swappedFilesBothFailToOpen() {
        val (firstId, firstKey) = encryptSample(sizeBytes = SMALL_BYTES)
        val (secondId, secondKey) = encryptSample(sizeBytes = SMALL_BYTES)
        val firstFile = fileCrypto.encryptedFileFor(firstId)
        val secondFile = fileCrypto.encryptedFileFor(secondId)
        val firstBytes = firstFile.readBytes()
        val secondBytes = secondFile.readBytes()

        firstFile.writeBytes(secondBytes)
        secondFile.writeBytes(firstBytes)

        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(firstId, firstKey)
        }
        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(secondId, secondKey)
        }
    }

    @Test
    fun encryptedFileDoesNotContainAKnownPattern() {
        val pattern = "HEY-BUDDY-KNOWN-PATTERN".toByteArray(Charsets.UTF_8)
        val plaintext = ByteArray(SIXTY_FOUR_KILOBYTES) { index -> pattern[index % pattern.size] }
        val documentId = newDocumentId()
        val key = keyWrapper.generateDocumentKey()

        fileCrypto.encryptToVault(ByteArrayInputStream(plaintext), key, documentId)
        val encryptedBytes = fileCrypto.encryptedFileFor(documentId).readBytes()

        assertFalse(containsSequence(encryptedBytes, pattern))
    }

    @Test
    fun errorDuringEncryptionLeavesNoFilesBehind() {
        val documentId = newDocumentId()
        val key = keyWrapper.generateDocumentKey()

        assertThrows(IOException::class.java) {
            fileCrypto.encryptToVault(FailingInputStream(goodBytes = TEN_KILOBYTES), key, documentId)
        }
        assertNoFilesFor(documentId)
    }

    @Test
    fun existingEncryptedFileIsNeverOverwritten() {
        val plaintext = Random(seed = 1).nextBytes(SMALL_BYTES)
        val documentId = newDocumentId()
        val key = keyWrapper.generateDocumentKey()
        fileCrypto.encryptToVault(ByteArrayInputStream(plaintext), key, documentId)

        assertThrows(IOException::class.java) {
            fileCrypto.encryptToVault(ByteArrayInputStream(ByteArray(SMALL_BYTES)), key, documentId)
        }
        assertArrayEquals(plaintext, fileCrypto.decryptVerified(documentId, key).bytes)
    }

    @Test
    fun invalidDocumentIdIsRejected() {
        val key = keyWrapper.generateDocumentKey()

        assertThrows(IllegalArgumentException::class.java) {
            fileCrypto.encryptToVault(ByteArrayInputStream(ByteArray(1)), key, "../evil")
        }
    }

    @Test
    fun decryptToFileOnlyWritesInsideViewTemp() {
        val (documentId, key) = encryptSample(sizeBytes = SMALL_BYTES)
        val outsideFolder = File(context.filesDir, "outside.pdf")
        val sneakyPath = File(viewTempDir, "../outside.pdf")

        assertThrows(IllegalArgumentException::class.java) {
            fileCrypto.decryptVerifiedToFile(documentId, key, outsideFolder)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fileCrypto.decryptVerifiedToFile(documentId, key, sneakyPath)
        }
        assertFalse(outsideFolder.exists())
    }

    @Test
    fun decryptToFileGivesTheVerifiedFile() {
        val plaintext = Random(seed = 2).nextBytes(THREE_MEGABYTES)
        val documentId = newDocumentId()
        val key = keyWrapper.generateDocumentKey()
        fileCrypto.encryptToVault(ByteArrayInputStream(plaintext), key, documentId)
        val destination = File(viewTempDir, "view.pdf")

        val verifiedFile = fileCrypto.decryptVerifiedToFile(documentId, key, destination)

        assertArrayEquals(plaintext, verifiedFile.file.readBytes())
        assertFalse(partFileFor(destination).exists())
    }

    @Test
    fun tamperedDecryptToFileLeavesNoPlaintextFile() {
        val (documentId, key) = encryptSample(sizeBytes = THREE_MEGABYTES)
        val encryptedFile = fileCrypto.encryptedFileFor(documentId)
        changeByteAt(encryptedFile, encryptedFile.length() - 10)
        val destination = File(viewTempDir, "view.pdf")

        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerifiedToFile(documentId, key, destination)
        }
        assertFalse(destination.exists())
        assertFalse(partFileFor(destination).exists())
    }

    private fun encryptSample(sizeBytes: Int): Pair<String, KeysetHandle> {
        val plaintext = Random(seed = sizeBytes).nextBytes(sizeBytes)
        val documentId = newDocumentId()
        val key = keyWrapper.generateDocumentKey()
        fileCrypto.encryptToVault(ByteArrayInputStream(plaintext), key, documentId)
        return Pair(documentId, key)
    }

    /** Reads with Tink's raw stream (as a careless caller would) and counts bytes before it fails. */
    private fun countBytesBeforeFailure(encryptedFile: File, key: KeysetHandle, documentId: String): Long {
        val streamingAead = key.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        val associatedData = documentAssociatedData(documentId)
        var count = 0L
        val buffer = ByteArray(TINK_SEGMENT_BYTES)
        try {
            FileInputStream(encryptedFile).use { fileInput ->
                streamingAead.newDecryptingStream(fileInput, associatedData).use { decrypting ->
                    while (true) {
                        val read = decrypting.read(buffer)
                        if (read == -1) break
                        count += read
                    }
                }
            }
        } catch (expected: IOException) {
            // Tink notices the change only when it reaches the last chunk.
        }
        return count
    }

    private fun changeByteAt(file: File, position: Long) {
        val bytes = file.readBytes()
        val index = position.toInt()
        bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)
    }

    private fun assertNoFilesFor(documentId: String) {
        val encryptedFile = fileCrypto.encryptedFileFor(documentId)
        val tempFile = File(encryptedFile.path + ".tmp")
        assertFalse("no .bin expected", encryptedFile.exists())
        assertFalse("no .tmp expected", tempFile.exists())
    }

    private fun partFileFor(destination: File): File = File(destination.path + ".part")

    private fun newDocumentId(): String = java.util.UUID.randomUUID().toString()

    private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        val lastStart = haystack.size - needle.size
        for (start in 0..lastStart) {
            val window = haystack.copyOfRange(start, start + needle.size)
            if (window.contentEquals(needle)) return true
        }
        return false
    }

    private fun deleteTestFolders() {
        vaultDir.deleteRecursively()
        viewTempDir.deleteRecursively()
        File(context.filesDir, "outside.pdf").delete()
    }

    private companion object {
        /** Tink AES256_GCM_HKDF_4KB works in 4096-byte chunks; sizes around it test the edges. */
        const val TINK_SEGMENT_BYTES = 4096
        const val SMALL_BYTES = 10_000
        const val TEN_KILOBYTES = 10 * 1024
        const val SIXTY_FOUR_KILOBYTES = 64 * 1024
        const val THREE_MEGABYTES = 3 * 1024 * 1024
    }
}
