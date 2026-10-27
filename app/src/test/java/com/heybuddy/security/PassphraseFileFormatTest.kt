package com.heybuddy.security

import com.heybuddy.util.PASSPHRASE_FILE_FORMAT_VERSION
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Checks the passphrase file layout: one version byte, then the wrapped passphrase. */
class PassphraseFileFormatTest {

    private val wrappedPassphrase = byteArrayOf(10, 20, 30, 40)

    @Test
    fun encodeStartsWithTheVersionByte() {
        val fileBytes = PassphraseFileFormat.encode(wrappedPassphrase)

        assertEquals(PASSPHRASE_FILE_FORMAT_VERSION, fileBytes[0])
        assertEquals(wrappedPassphrase.size + 1, fileBytes.size)
    }

    @Test
    fun decodeReturnsWhatWasEncoded() {
        val fileBytes = PassphraseFileFormat.encode(wrappedPassphrase)

        val decoded = PassphraseFileFormat.decode(fileBytes)

        assertArrayEquals(wrappedPassphrase, decoded)
    }

    @Test
    fun unknownVersionIsRejected() {
        val unknownVersion: Byte = 9
        val fileBytes = byteArrayOf(unknownVersion) + wrappedPassphrase

        assertThrows(UnknownFileFormatException::class.java) {
            PassphraseFileFormat.decode(fileBytes)
        }
    }

    @Test
    fun emptyOrVersionOnlyFileIsRejected() {
        assertThrows(UnknownFileFormatException::class.java) {
            PassphraseFileFormat.decode(ByteArray(0))
        }
        assertThrows(UnknownFileFormatException::class.java) {
            PassphraseFileFormat.decode(byteArrayOf(PASSPHRASE_FILE_FORMAT_VERSION))
        }
    }
}
