package com.heybuddy.security

import com.heybuddy.util.PASSPHRASE_FILE_FORMAT_VERSION
import java.security.GeneralSecurityException

/** The passphrase file's first byte is a version this app does not understand. */
class UnknownFileFormatException(message: String) : GeneralSecurityException(message)

/**
 * Layout of the wrapped-passphrase file: one version byte, then the wrapped passphrase.
 *
 * The version byte lets a later app version change the layout and still recognise old files.
 */
object PassphraseFileFormat {

    private const val VERSION_BYTE_COUNT = 1

    /**
     * Builds the file contents.
     *
     * @param wrappedPassphrase Output of [KeyWrapper.wrapBytes].
     * @return The bytes to write to the file.
     */
    fun encode(wrappedPassphrase: ByteArray): ByteArray {
        val versionPrefix = byteArrayOf(PASSPHRASE_FILE_FORMAT_VERSION)
        return versionPrefix + wrappedPassphrase
    }

    /**
     * Reads the file contents.
     *
     * @param fileBytes Everything read from the file.
     * @return The wrapped passphrase, ready for [KeyWrapper.unwrapBytes].
     * @throws UnknownFileFormatException If the file is empty or has an unknown version.
     */
    fun decode(fileBytes: ByteArray): ByteArray {
        if (fileBytes.size <= VERSION_BYTE_COUNT) {
            throw UnknownFileFormatException("Passphrase file is empty or too short")
        }
        val version = fileBytes[0]
        if (version != PASSPHRASE_FILE_FORMAT_VERSION) {
            throw UnknownFileFormatException("Passphrase file has unknown format version $version")
        }
        return fileBytes.copyOfRange(VERSION_BYTE_COUNT, fileBytes.size)
    }
}
