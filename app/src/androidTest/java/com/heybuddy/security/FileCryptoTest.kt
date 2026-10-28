package com.heybuddy.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.heybuddy.files.FileTooLargeException
import com.heybuddy.util.MAX_FILE_BYTES
import com.heybuddy.util.PAGE_FORMAT_LEGACY
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
import java.io.InputStream
import java.util.UUID
import kotlin.random.Random

/**
 * Checks [FileCrypto] on a real phone (rules 3, 4, 6 and 11), with page bindings (Task 1.10p).
 *
 * Needs no Keystore key: FileCrypto only uses page keys, which the tests create directly.
 * Uses its own test folders, deleted after every test even when it fails.
 */
@RunWith(AndroidJUnit4::class)
class FileCryptoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vaultDir = File(context.filesDir, "vault_test")
    private val viewTempDir = File(context.noBackupFilesDir, "view_tmp_test")
    private val fileCrypto = FileCrypto(vaultDir, viewTempDir)

    // generatePageKey never touches the Keystore, so this alias is never created.
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
            val binding = newBinding()
            val key = keyWrapper.generatePageKey()

            val reportedSize = encryptAndCommit(ByteArrayInputStream(plaintext), key, binding)
            val decrypted = fileCrypto.decryptVerified(binding, key)

            assertEquals("size $size", size.toLong(), reportedSize)
            assertArrayEquals("size $size", plaintext, decrypted.bytes)
        }
    }

    @Test
    fun exactlyTheLimitIsAcceptedAndOneMoreIsRejected() {
        val key = keyWrapper.generatePageKey()
        val atLimit = newBinding()
        val overLimit = newBinding()

        val acceptedSize = encryptAndCommit(GeneratedInputStream(MAX_FILE_BYTES), key, atLimit)

        assertEquals(MAX_FILE_BYTES, acceptedSize)
        assertThrows(FileTooLargeException::class.java) {
            fileCrypto.encryptToPending(GeneratedInputStream(MAX_FILE_BYTES + 1), key, overLimit)
        }
        assertNoFilesFor(overLimit.pageId)
    }

    @Test
    fun oneChangedByteInTheMiddleFails() {
        val (binding, key) = encryptSample(sizeBytes = THREE_MEGABYTES)
        val encryptedFile = fileCrypto.encryptedFileFor(binding.pageId)

        changeByteAt(encryptedFile, encryptedFile.length() / 2)

        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(binding, key)
        }
    }

    /**
     * Proves point 5: Tink's raw stream hands out early chunks before it meets a changed last
     * chunk, but FileCrypto returns nothing at all.
     */
    @Test
    fun changedLastChunkReturnsNoDataAtAll() {
        val (binding, key) = encryptSample(sizeBytes = THREE_MEGABYTES)
        val encryptedFile = fileCrypto.encryptedFileFor(binding.pageId)
        changeByteAt(encryptedFile, encryptedFile.length() - 10)

        val bytesFromRawStream = countBytesBeforeFailure(encryptedFile, key, binding)

        assertTrue("Raw stream should leak early chunks", bytesFromRawStream > 0)
        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(binding, key)
        }
    }

    @Test
    fun cutOffFileFails() {
        val (binding, key) = encryptSample(sizeBytes = THREE_MEGABYTES)
        val encryptedFile = fileCrypto.encryptedFileFor(binding.pageId)
        val fullBytes = encryptedFile.readBytes()

        encryptedFile.writeBytes(fullBytes.copyOf(fullBytes.size - 1))
        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(binding, key)
        }

        encryptedFile.writeBytes(fullBytes.copyOf(fullBytes.size / 2))
        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(binding, key)
        }
    }

    @Test
    fun pageFileUnderAnotherPageIdFails() {
        val (binding, key) = encryptSample(sizeBytes = SMALL_BYTES)
        val otherPage = PageBinding.forNewPage(binding.documentId, newId())
        // Put the file under the other page's name, keeping the right key: only the page id differs.
        fileCrypto.encryptedFileFor(binding.pageId).renameTo(fileCrypto.encryptedFileFor(otherPage.pageId))

        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(otherPage, key)
        }
    }

    @Test
    fun pageMovedToAnotherDocumentFails() {
        val (binding, key) = encryptSample(sizeBytes = SMALL_BYTES)
        // Same page id and key, but claimed by a different document.
        val sameIdOtherDocument = PageBinding.forNewPage(newId(), binding.pageId)

        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(sameIdOtherDocument, key)
        }
    }

    @Test
    fun swappedPagesOfOneDocumentBothFailToOpen() {
        val documentId = newId()
        val (firstPage, firstKey) = encryptSample(sizeBytes = SMALL_BYTES, documentId = documentId)
        val (secondPage, secondKey) = encryptSample(sizeBytes = SMALL_BYTES, documentId = documentId)
        val firstFile = fileCrypto.encryptedFileFor(firstPage.pageId)
        val secondFile = fileCrypto.encryptedFileFor(secondPage.pageId)
        val firstBytes = firstFile.readBytes()
        val secondBytes = secondFile.readBytes()

        firstFile.writeBytes(secondBytes)
        secondFile.writeBytes(firstBytes)

        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(firstPage, firstKey)
        }
        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(secondPage, secondKey)
        }
    }

    /** A page from before Task 1.10p opens with its old "document:<id>" binding, and only with that. */
    @Test
    fun legacyPageOpensOnlyWithTheLegacyBinding() {
        val documentId = newId()
        val key = keyWrapper.generatePageKey()
        val plaintext = Random(seed = 3).nextBytes(SMALL_BYTES)
        writeLegacyFile(documentId, key, plaintext)
        val legacy = PageBinding.forStoredPage(documentId, documentId, PAGE_FORMAT_LEGACY)
        val currentFormat = PageBinding.forNewPage(documentId, documentId)

        assertArrayEquals(plaintext, fileCrypto.decryptVerified(legacy, key).bytes)
        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerified(currentFormat, key)
        }
    }

    /** Owner rule (Task 1.10p): new pages always use the new binding, never the old one. */
    @Test
    fun newPagesCannotBeWrittenWithTheLegacyBinding() {
        val documentId = newId()
        val legacy = PageBinding.forStoredPage(documentId, documentId, PAGE_FORMAT_LEGACY)

        assertThrows(IllegalArgumentException::class.java) {
            fileCrypto.encryptToPending(ByteArrayInputStream(ByteArray(1)), keyWrapper.generatePageKey(), legacy)
        }
        assertNoFilesFor(documentId)
    }

    @Test
    fun pendingPageIsNotInTheVaultUntilCommitted() {
        val binding = newBinding()
        val key = keyWrapper.generatePageKey()

        fileCrypto.encryptToPending(ByteArrayInputStream(ByteArray(SMALL_BYTES)), key, binding)

        assertTrue(fileCrypto.pendingFileFor(binding.pageId).exists())
        assertFalse(fileCrypto.encryptedFileFor(binding.pageId).exists())
        assertFalse("a pending page is not a vault file", fileCrypto.vaultFileNames().contains("${binding.pageId}.bin"))

        fileCrypto.commitPending(binding.pageId)

        assertFalse(fileCrypto.pendingFileFor(binding.pageId).exists())
        assertTrue(fileCrypto.encryptedFileFor(binding.pageId).exists())
    }

    @Test
    fun returnToPendingUndoesACommit() {
        val (binding, key) = encryptSample(sizeBytes = SMALL_BYTES)

        fileCrypto.returnToPending(binding.pageId)

        assertFalse(fileCrypto.encryptedFileFor(binding.pageId).exists())
        assertTrue(fileCrypto.pendingFileFor(binding.pageId).exists())
        fileCrypto.commitPending(binding.pageId)
        assertEquals(SMALL_BYTES, fileCrypto.decryptVerified(binding, key).bytes.size)
    }

    @Test
    fun deletePendingNeverTouchesASavedPage() {
        val (binding, key) = encryptSample(sizeBytes = SMALL_BYTES)

        fileCrypto.deletePending(binding.pageId)

        assertEquals(SMALL_BYTES, fileCrypto.decryptVerified(binding, key).bytes.size)
    }

    @Test
    fun encryptedFileDoesNotContainAKnownPattern() {
        val pattern = "HEY-BUDDY-KNOWN-PATTERN".toByteArray(Charsets.UTF_8)
        val plaintext = ByteArray(SIXTY_FOUR_KILOBYTES) { index -> pattern[index % pattern.size] }
        val binding = newBinding()
        val key = keyWrapper.generatePageKey()

        encryptAndCommit(ByteArrayInputStream(plaintext), key, binding)
        val encryptedBytes = fileCrypto.encryptedFileFor(binding.pageId).readBytes()

        assertFalse(containsSequence(encryptedBytes, pattern))
    }

    @Test
    fun errorDuringEncryptionLeavesNoFilesBehind() {
        val binding = newBinding()
        val key = keyWrapper.generatePageKey()

        assertThrows(IOException::class.java) {
            fileCrypto.encryptToPending(FailingInputStream(goodBytes = TEN_KILOBYTES), key, binding)
        }
        assertNoFilesFor(binding.pageId)
    }

    @Test
    fun existingPageFileIsNeverOverwritten() {
        val plaintext = Random(seed = 1).nextBytes(SMALL_BYTES)
        val binding = newBinding()
        val key = keyWrapper.generatePageKey()
        encryptAndCommit(ByteArrayInputStream(plaintext), key, binding)

        assertThrows(IOException::class.java) {
            fileCrypto.encryptToPending(ByteArrayInputStream(ByteArray(SMALL_BYTES)), key, binding)
        }
        assertArrayEquals(plaintext, fileCrypto.decryptVerified(binding, key).bytes)
    }

    @Test
    fun invalidIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PageBinding.forNewPage(newId(), "../evil")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fileCrypto.encryptedFileFor("../evil")
        }
    }

    @Test
    fun decryptToFileOnlyWritesInsideViewTemp() {
        val (binding, key) = encryptSample(sizeBytes = SMALL_BYTES)
        val outsideFolder = File(context.filesDir, "outside.pdf")
        val sneakyPath = File(viewTempDir, "../outside.pdf")

        assertThrows(IllegalArgumentException::class.java) {
            fileCrypto.decryptVerifiedToFile(binding, key, outsideFolder)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fileCrypto.decryptVerifiedToFile(binding, key, sneakyPath)
        }
        assertFalse(outsideFolder.exists())
    }

    @Test
    fun decryptToFileGivesTheVerifiedFile() {
        val plaintext = Random(seed = 2).nextBytes(THREE_MEGABYTES)
        val binding = newBinding()
        val key = keyWrapper.generatePageKey()
        encryptAndCommit(ByteArrayInputStream(plaintext), key, binding)
        val destination = File(viewTempDir, "view.pdf")

        val verifiedFile = fileCrypto.decryptVerifiedToFile(binding, key, destination)

        assertArrayEquals(plaintext, verifiedFile.file.readBytes())
        assertFalse(partFileFor(destination).exists())
    }

    @Test
    fun tamperedDecryptToFileLeavesNoPlaintextFile() {
        val (binding, key) = encryptSample(sizeBytes = THREE_MEGABYTES)
        val encryptedFile = fileCrypto.encryptedFileFor(binding.pageId)
        changeByteAt(encryptedFile, encryptedFile.length() - 10)
        val destination = File(viewTempDir, "view.pdf")

        assertThrows(DocumentUnreadableException::class.java) {
            fileCrypto.decryptVerifiedToFile(binding, key, destination)
        }
        assertFalse(destination.exists())
        assertFalse(partFileFor(destination).exists())
    }

    private fun encryptAndCommit(input: InputStream, key: KeysetHandle, binding: PageBinding): Long {
        val size = fileCrypto.encryptToPending(input, key, binding)
        fileCrypto.commitPending(binding.pageId)
        return size
    }

    private fun encryptSample(sizeBytes: Int, documentId: String = newId()): Pair<PageBinding, KeysetHandle> {
        val plaintext = Random(seed = sizeBytes).nextBytes(sizeBytes)
        val binding = PageBinding.forNewPage(documentId, newId())
        val key = keyWrapper.generatePageKey()
        encryptAndCommit(ByteArrayInputStream(plaintext), key, binding)
        return Pair(binding, key)
    }

    /** Writes a page exactly as the app did before Task 1.10p: vault/<id>.bin bound to "document:<id>". */
    private fun writeLegacyFile(documentId: String, key: KeysetHandle, plaintext: ByteArray) {
        val streamingAead = key.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        val associatedData = "document:$documentId".toByteArray(Charsets.UTF_8)
        vaultDir.mkdirs()
        fileCrypto.encryptedFileFor(documentId).outputStream().use { fileOutput ->
            streamingAead.newEncryptingStream(fileOutput, associatedData).use { encrypting -> encrypting.write(plaintext) }
        }
    }

    /** Reads with Tink's raw stream (as a careless caller would) and counts bytes before it fails. */
    private fun countBytesBeforeFailure(encryptedFile: File, key: KeysetHandle, binding: PageBinding): Long {
        val streamingAead = key.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        val associatedData = binding.associatedData()
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

    private fun assertNoFilesFor(pageId: String) {
        val encryptedFile = fileCrypto.encryptedFileFor(pageId)
        val pendingFile = fileCrypto.pendingFileFor(pageId)
        assertFalse("no .bin expected", encryptedFile.exists())
        assertFalse("no .pending expected", pendingFile.exists())
        assertFalse("no .tmp expected", File(pendingFile.path + ".tmp").exists())
    }

    private fun partFileFor(destination: File): File = File(destination.path + ".part")

    private fun newId(): String = UUID.randomUUID().toString()

    private fun newBinding(): PageBinding = PageBinding.forNewPage(newId(), newId())

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
