package com.heybuddy.files

/**
 * The file types the vault accepts (rule 11).
 *
 * @property mimeType Saved in the database as the document's type.
 */
enum class DetectedFileType(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
    PDF("application/pdf"),
    ;

    /** True for the photo types, which are previewed by decoding the image. */
    val isImage: Boolean
        get() = this != PDF
}

/**
 * Finds a file's real type from its first bytes ("magic bytes", rule 11).
 *
 * The file name and the type the picker reports are ignored on purpose: both are easy to fake
 * (a .txt renamed to .pdf keeps its text bytes).
 */
object FileTypeDetector {

    private val JPEG_START = bytes(0xFF, 0xD8, 0xFF)
    private val PNG_START = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val RIFF_START = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_MARK = "WEBP".toByteArray(Charsets.US_ASCII)
    private val PDF_START = "%PDF-".toByteArray(Charsets.US_ASCII)

    // A WEBP file is "RIFF" + 4 size bytes + "WEBP"; other RIFF files (like WAV audio) differ here.
    private const val WEBP_MARK_OFFSET = 8

    /**
     * Detects the type of a file.
     *
     * @param fileBytes The file's bytes (only the first few are looked at).
     * @return The type, or null if it is not one of the four accepted types.
     */
    fun detect(fileBytes: ByteArray): DetectedFileType? {
        return when {
            startsWith(fileBytes, JPEG_START, offset = 0) -> DetectedFileType.JPEG
            startsWith(fileBytes, PNG_START, offset = 0) -> DetectedFileType.PNG
            isWebp(fileBytes) -> DetectedFileType.WEBP
            startsWith(fileBytes, PDF_START, offset = 0) -> DetectedFileType.PDF
            else -> null
        }
    }

    private fun isWebp(fileBytes: ByteArray): Boolean {
        val hasRiff = startsWith(fileBytes, RIFF_START, offset = 0)
        val hasWebpMark = startsWith(fileBytes, WEBP_MARK, offset = WEBP_MARK_OFFSET)
        return hasRiff && hasWebpMark
    }

    private fun startsWith(fileBytes: ByteArray, expected: ByteArray, offset: Int): Boolean {
        if (fileBytes.size < offset + expected.size) return false
        for (index in expected.indices) {
            if (fileBytes[offset + index] != expected[index]) return false
        }
        return true
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { index -> values[index].toByte() }
}
