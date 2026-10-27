package com.heybuddy.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Checks the magic-byte type detection (rule 11). */
class FileTypeDetectorTest {

    @Test
    fun detectsJpeg() {
        val jpeg = bytesOf(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)

        assertEquals(DetectedFileType.JPEG, FileTypeDetector.detect(jpeg))
    }

    @Test
    fun detectsPng() {
        val png = bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)

        assertEquals(DetectedFileType.PNG, FileTypeDetector.detect(png))
    }

    @Test
    fun detectsWebp() {
        val webp = ascii("RIFF") + bytesOf(0x24, 0x00, 0x00, 0x00) + ascii("WEBPVP8 ")

        assertEquals(DetectedFileType.WEBP, FileTypeDetector.detect(webp))
    }

    @Test
    fun detectsPdf() {
        val pdf = ascii("%PDF-1.7\n%âãÏÓ")

        assertEquals(DetectedFileType.PDF, FileTypeDetector.detect(pdf))
    }

    @Test
    fun textRenamedToPdfIsRejected() {
        // What fake_pdf.pdf contains: plain text, whatever the file name says.
        val text = ascii("This is just a text file, renamed to .pdf\n")

        assertNull(FileTypeDetector.detect(text))
    }

    @Test
    fun otherRiffFilesLikeWavAreRejected() {
        val wav = ascii("RIFF") + bytesOf(0x24, 0x00, 0x00, 0x00) + ascii("WAVEfmt ")

        assertNull(FileTypeDetector.detect(wav))
    }

    @Test
    fun pdfMarkerWithoutTheDashIsRejected() {
        assertNull(FileTypeDetector.detect(ascii("%PDF1.7")))
    }

    @Test
    fun tooFewBytesAreRejected() {
        assertNull(FileTypeDetector.detect(bytesOf(0xFF, 0xD8)))
        assertNull(FileTypeDetector.detect(ascii("RIFF")))
    }

    @Test
    fun emptyFileIsRejected() {
        assertNull(FileTypeDetector.detect(ByteArray(0)))
    }

    @Test
    fun onlyPdfIsNotAnImage() {
        assertEquals(listOf(DetectedFileType.PDF), DetectedFileType.entries.filter { type -> !type.isImage })
    }

    private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { index -> values[index].toByte() }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)
}
