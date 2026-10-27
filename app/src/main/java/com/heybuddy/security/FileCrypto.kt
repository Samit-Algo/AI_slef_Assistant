package com.heybuddy.security

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.heybuddy.files.SizeLimitedInputStream
import com.heybuddy.util.DECRYPTING_PART_SUFFIX
import com.heybuddy.util.ENCRYPTED_FILE_SUFFIX
import com.heybuddy.util.ENCRYPTING_TEMP_SUFFIX
import com.heybuddy.util.MAX_FILE_BYTES
import com.heybuddy.util.STREAM_BUFFER_BYTES
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.util.UUID

/**
 * The encrypted file was changed, cut short, swapped with another document's file, or
 * belongs to a different document. Encryption cannot tell these apart, and does not need to:
 * in every case the file must not be used.
 */
class DocumentUnreadableException(cause: Throwable) :
    IOException("Document could not be decrypted and verified", cause)

/**
 * Decrypted document bytes that have been verified to the very end.
 *
 * Only [FileCrypto] creates these (its implementation is private to this file). Screens accept
 * only this type, so they cannot receive unverified data by mistake. Kotlin cannot fully stop
 * another class in this package from adding a subclass, but doing so would be a deliberate,
 * easy-to-spot change.
 */
sealed class VerifiedBytes {

    /** The verified plaintext. Call [wipe] when it is no longer shown. */
    abstract val bytes: ByteArray

    /** Fills the bytes with zeros (best effort: copies the JVM made elsewhere cannot be reached). */
    fun wipe() {
        bytes.fill(0)
    }
}

/**
 * A decrypted file in view_tmp that has been verified to the very end (for PdfRenderer, rule 6).
 * Only [FileCrypto] creates these; see [VerifiedBytes] for why.
 */
sealed class VerifiedFile {

    /** The verified plaintext file, inside noBackupFilesDir/view_tmp. */
    abstract val file: File

    /** Deletes the plaintext file. Call it when the viewer closes (rule 6). */
    fun delete() {
        file.delete()
    }
}

private class VerifiedBytesImpl(override val bytes: ByteArray) : VerifiedBytes()

private class VerifiedFileImpl(override val file: File) : VerifiedFile()

/**
 * Encrypts documents into the vault and decrypts them back (rules 3, 4 and 6).
 *
 * Each file uses Tink streaming AEAD with the document's own key, and "document:<id>" as
 * associated data, so a file only opens for its own record.
 *
 * WHY DECRYPTION ONLY RETURNS VERIFIED DATA: streaming AEAD decrypts in 4 KB chunks. It can hand
 * out early chunks before it notices that a later chunk was changed, and it only notices a
 * cut-off file at the very end. So this class never gives out a decrypting stream. It reads to
 * the last byte (including Tink's end-of-file check) and only then returns [VerifiedBytes] or
 * [VerifiedFile]. On any failure, everything decrypted so far is wiped or deleted.
 *
 * This class never touches the Keystore master key: it gets the already-unwrapped document key.
 *
 * @param vaultDir The encrypted files folder (filesDir/vault in the app).
 * @param viewTempDir The only folder decrypted files may be written to (noBackupFilesDir/view_tmp).
 */
class FileCrypto(
    private val vaultDir: File,
    private val viewTempDir: File,
) {

    /**
     * Encrypts a document into vault/<id>.bin while reading it. Plaintext never touches the disk.
     *
     * Writes to <id>.bin.tmp first and renames only when encryption has finished, so a crash or
     * error never leaves a half-written .bin. On any error the .tmp file is deleted.
     *
     * @param input The document's plaintext. Read once, not closed here (the caller owns it).
     * @param documentKey The document's own key (from [KeyWrapper.generateDocumentKey]).
     * @param documentId The document's id (a UUID).
     * @return The number of plaintext bytes encrypted (the real size, for the database).
     * @throws com.heybuddy.files.FileTooLargeException If the input is over MAX_FILE_BYTES (rule 11).
     * @throws IOException If <id>.bin already exists (never overwritten) or writing fails.
     */
    fun encryptToVault(input: InputStream, documentKey: KeysetHandle, documentId: String): Long {
        requireValidDocumentId(documentId)
        val targetFile = encryptedFileFor(documentId)
        if (targetFile.exists()) {
            throw IOException("Encrypted file already exists; refusing to overwrite it")
        }
        vaultDir.mkdirs()
        val tempFile = File(vaultDir, targetFile.name + ENCRYPTING_TEMP_SUFFIX)
        try {
            val plaintextSize = encryptToFile(input, documentKey, documentId, tempFile)
            moveIntoPlace(tempFile, targetFile)
            return plaintextSize
        } catch (failure: Exception) {
            tempFile.delete()
            throw failure
        }
    }

    /**
     * Decrypts a whole document into memory and returns it only after full verification.
     *
     * TODO(Task 1.8): when showing images, decode these bytes with downsampling to the screen
     *  size (BitmapFactory inSampleSize), so a large photo cannot run the app out of memory.
     *
     * @param documentId The document's id.
     * @param documentKey The document's own key.
     * @return The verified plaintext.
     * @throws FileNotFoundException If the document has no encrypted file.
     * @throws DocumentUnreadableException If the file fails verification in any way.
     */
    fun decryptVerified(documentId: String, documentKey: KeysetHandle): VerifiedBytes {
        requireValidDocumentId(documentId)
        val sourceFile = requireEncryptedFile(documentId)
        requirePlausibleSize(sourceFile)
        // Plaintext is always shorter than its ciphertext, so the file size is a safe buffer size.
        val capacity = sourceFile.length().toInt()
        try {
            val plaintext = decryptIntoMemory(sourceFile, documentKey, documentId, capacity)
            return VerifiedBytesImpl(plaintext)
        } catch (failure: IOException) {
            throw DocumentUnreadableException(failure)
        } catch (failure: GeneralSecurityException) {
            throw DocumentUnreadableException(failure)
        }
    }

    /**
     * Decrypts a document into a file in view_tmp, and makes it visible only after full verification.
     *
     * Writes to <destination>.part and renames it only when the whole file has been verified.
     * On failure the .part file is deleted, so unverified plaintext never stays on disk.
     *
     * @param documentId The document's id.
     * @param documentKey The document's own key.
     * @param destination Where the verified file should appear. Must be directly inside view_tmp.
     * @return The verified file.
     * @throws IllegalArgumentException If [destination] is not directly inside view_tmp.
     * @throws FileNotFoundException If the document has no encrypted file.
     * @throws DocumentUnreadableException If the file fails verification in any way.
     */
    fun decryptVerifiedToFile(documentId: String, documentKey: KeysetHandle, destination: File): VerifiedFile {
        requireValidDocumentId(documentId)
        requireInsideViewTemp(destination)
        val sourceFile = requireEncryptedFile(documentId)
        viewTempDir.mkdirs()
        val partFile = File(destination.path + DECRYPTING_PART_SUFFIX)
        try {
            decryptIntoFile(sourceFile, documentKey, documentId, partFile)
            moveIntoPlace(partFile, destination)
            return VerifiedFileImpl(destination)
        } catch (failure: IOException) {
            partFile.delete()
            throw DocumentUnreadableException(failure)
        } catch (failure: GeneralSecurityException) {
            partFile.delete()
            throw DocumentUnreadableException(failure)
        }
    }

    /**
     * A new, unused file name inside view_tmp for viewing a PDF.
     *
     * The name is random, never the document's name or id, so nothing about the document can be
     * read from the folder.
     *
     * @return A file in view_tmp that does not exist yet.
     */
    fun newViewTempFile(): File {
        return File(viewTempDir, UUID.randomUUID().toString() + VIEW_FILE_SUFFIX)
    }

    /**
     * Where a document's encrypted file lives.
     *
     * @param documentId The document's id (a UUID).
     * @return vault/<id>.bin (it may not exist yet).
     */
    fun encryptedFileFor(documentId: String): File {
        requireValidDocumentId(documentId)
        return File(vaultDir, documentId + ENCRYPTED_FILE_SUFFIX)
    }

    /**
     * The names of the plain files in the vault folder (for orphan cleanup, Task 1.10).
     *
     * @return File names only, no folders; empty if the folder does not exist yet.
     */
    fun vaultFileNames(): List<String> {
        val files = vaultDir.listFiles() ?: return emptyList()
        val names = mutableListOf<String>()
        for (file in files) {
            if (file.isFile) names.add(file.name)
        }
        return names
    }

    private fun encryptToFile(input: InputStream, documentKey: KeysetHandle, documentId: String, file: File): Long {
        val streamingAead = streamingAeadFor(documentKey)
        val associatedData = documentAssociatedData(documentId)
        val limitedInput = SizeLimitedInputStream(input, MAX_FILE_BYTES)
        // Tink's close() writes the final chunk and closes the file stream, which syncs first.
        val fileOutput = SyncOnCloseOutputStream(FileOutputStream(file))
        streamingAead.newEncryptingStream(fileOutput, associatedData).use { encrypting ->
            copyStream(limitedInput, encrypting)
        }
        return limitedInput.bytesRead
    }

    private fun decryptIntoMemory(file: File, documentKey: KeysetHandle, documentId: String, capacity: Int): ByteArray {
        val streamingAead = streamingAeadFor(documentKey)
        val associatedData = documentAssociatedData(documentId)
        FileInputStream(file).use { fileInput ->
            streamingAead.newDecryptingStream(fileInput, associatedData).use { decrypting ->
                return readToVerifiedEnd(decrypting, capacity)
            }
        }
    }

    private fun decryptIntoFile(source: File, documentKey: KeysetHandle, documentId: String, target: File) {
        val streamingAead = streamingAeadFor(documentKey)
        val associatedData = documentAssociatedData(documentId)
        FileInputStream(source).use { fileInput ->
            streamingAead.newDecryptingStream(fileInput, associatedData).use { decrypting ->
                // Also guards against a huge file swapped into the vault.
                val limitedPlaintext = SizeLimitedInputStream(decrypting, MAX_FILE_BYTES)
                SyncOnCloseOutputStream(FileOutputStream(target)).use { fileOutput ->
                    copyStream(limitedPlaintext, fileOutput)
                }
            }
        }
    }

    /**
     * Reads until the end of the stream, which is when Tink checks the final chunk.
     * Returns nothing unless that end was reached without error.
     */
    private fun readToVerifiedEnd(decrypting: InputStream, capacity: Int): ByteArray {
        val buffer = ByteArray(capacity)
        var filled = 0
        try {
            while (true) {
                val spaceLeft = buffer.size - filled
                if (spaceLeft == 0) {
                    // A valid file always has room left; anything more means the file is not what it claims.
                    if (decrypting.read() == END_OF_STREAM) break
                    throw IOException("Decrypted data is longer than expected")
                }
                val count = decrypting.read(buffer, filled, spaceLeft)
                if (count == END_OF_STREAM) break
                filled += count
            }
            return buffer.copyOf(filled)
        } finally {
            // The working buffer holds plaintext; wipe it whether or not verification passed.
            buffer.fill(0)
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count == END_OF_STREAM) break
                output.write(buffer, 0, count)
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun streamingAeadFor(documentKey: KeysetHandle): StreamingAead {
        return documentKey.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    private fun moveIntoPlace(from: File, to: File) {
        // A rename inside one folder is atomic: the target is either complete or absent.
        val renamed = from.renameTo(to)
        if (!renamed) {
            throw IOException("Could not move finished file into place")
        }
    }

    private fun requireEncryptedFile(documentId: String): File {
        val sourceFile = encryptedFileFor(documentId)
        if (!sourceFile.exists()) {
            throw FileNotFoundException("Encrypted file is missing")
        }
        return sourceFile
    }

    private fun requirePlausibleSize(sourceFile: File) {
        // Refuse before allocating memory if the file is far bigger than any real vault file.
        if (sourceFile.length() > MAX_CIPHERTEXT_BYTES) {
            throw DocumentUnreadableException(IOException("Encrypted file is larger than any valid document"))
        }
    }

    private fun requireInsideViewTemp(destination: File) {
        // Canonical paths resolve tricks like "view_tmp/../secret.pdf" before comparing.
        val destinationFolder = destination.canonicalFile.parentFile
        val allowedFolder = viewTempDir.canonicalFile
        require(destinationFolder == allowedFolder) {
            "Decrypted files may only be written directly inside view_tmp"
        }
    }

    // The id becomes part of a file path, so it must be a plain UUID (no "../" or slashes).
    private fun requireValidDocumentId(documentId: String) {
        val parsed = try {
            UUID.fromString(documentId)
        } catch (notAUuid: IllegalArgumentException) {
            throw IllegalArgumentException("Document id must be a UUID", notAUuid)
        }
        require(parsed.toString() == documentId) { "Document id must be a UUID in standard lowercase form" }
    }

    private companion object {
        const val END_OF_STREAM = -1

        /** Ending of a decrypted PDF in view_tmp (PdfRenderer does not care, but it helps debugging). */
        const val VIEW_FILE_SUFFIX = ".pdf"

        /**
         * Largest encrypted file we accept. Tink adds about 16 bytes per 4 KB chunk plus a small
         * header (well under 1%); allowing 1/64 extra plus 4 KB leaves a wide safety margin.
         */
        const val MAX_CIPHERTEXT_BYTES: Long = MAX_FILE_BYTES + MAX_FILE_BYTES / 64 + 4096
    }
}

/** Forces the data onto storage before closing, so a rename never exposes a file still in the OS cache. */
private class SyncOnCloseOutputStream(private val fileOutput: FileOutputStream) : OutputStream() {

    override fun write(byteValue: Int) {
        fileOutput.write(byteValue)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        fileOutput.write(buffer, offset, length)
    }

    override fun flush() {
        fileOutput.flush()
    }

    override fun close() {
        fileOutput.flush()
        fileOutput.fd.sync()
        fileOutput.close()
    }
}
