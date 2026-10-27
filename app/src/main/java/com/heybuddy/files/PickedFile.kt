package com.heybuddy.files

import com.heybuddy.util.STREAM_BUFFER_BYTES
import java.io.InputStream

/**
 * A picked file, read once into memory (at most MAX_FILE_BYTES).
 *
 * The type check, the preview and the save all use these exact bytes, so the file cannot change
 * between being checked and being saved. Call [wipe] as soon as the bytes are no longer needed.
 *
 * @property bytes The file's bytes.
 * @property type The type found from the first bytes.
 */
class PickedFile(val bytes: ByteArray, val type: DetectedFileType) {

    /** Fills the bytes with zeros (best effort: copies the JVM made elsewhere cannot be reached). */
    fun wipe() {
        bytes.fill(0)
    }
}

/** Reads a picked file into memory without ever going over the size limit (rule 11). */
object PickedFileReader {

    /**
     * Reads the whole stream, stopping as soon as it goes over [maxBytes].
     *
     * Grows its own buffer (instead of ByteArrayOutputStream) so that every temporary copy of the
     * plaintext can be wiped.
     *
     * @param input The picker's stream. Not closed here.
     * @param maxBytes The most bytes allowed.
     * @param sizeHint The size the picker reports, used ONLY to pick a starting buffer size,
     *   never as a limit (it may be wrong or faked). Pass 0 when unknown.
     * @return All the bytes.
     * @throws FileTooLargeException If the stream is longer than [maxBytes].
     */
    fun readAll(input: InputStream, maxBytes: Long, sizeHint: Long): ByteArray {
        val limitedInput = SizeLimitedInputStream(input, maxBytes)
        var buffer = ByteArray(startingCapacity(sizeHint, maxBytes))
        var filled = 0
        try {
            while (true) {
                if (filled == buffer.size) {
                    buffer = grow(buffer, maxBytes)
                }
                val count = limitedInput.read(buffer, filled, buffer.size - filled)
                if (count == END_OF_STREAM) break
                filled += count
            }
            return buffer.copyOf(filled)
        } finally {
            buffer.fill(0)
        }
    }

    private fun startingCapacity(sizeHint: Long, maxBytes: Long): Int {
        // One byte more than the hint, so a correct hint never needs a resize to detect the end.
        val fromHint = if (sizeHint > 0) sizeHint + 1 else STREAM_BUFFER_BYTES.toLong()
        val capped = minOf(fromHint, maxBytes + 1)
        return capped.toInt()
    }

    private fun grow(buffer: ByteArray, maxBytes: Long): ByteArray {
        val doubled = buffer.size.toLong() * 2
        // +1 lets SizeLimitedInputStream see the byte that goes over the limit and stop.
        val newSize = minOf(doubled, maxBytes + 1).toInt()
        val bigger = buffer.copyOf(newSize)
        buffer.fill(0)
        return bigger
    }

    private const val END_OF_STREAM = -1
}
