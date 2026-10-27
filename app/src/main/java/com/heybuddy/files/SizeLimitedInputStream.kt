package com.heybuddy.files

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The input is bigger than allowed (rule 11).
 *
 * @property maxBytes The limit that was passed.
 */
class FileTooLargeException(val maxBytes: Long) : IOException("Input is larger than $maxBytes bytes")

/**
 * Counts the bytes actually read and stops once there are more than [maxBytes].
 *
 * The limit is checked on the real bytes while streaming, because the size a file picker
 * reports can be wrong or faked.
 *
 * @param input The stream to read from. Closing this stream closes it too.
 * @param maxBytes The most bytes allowed. Exactly this many is fine; one more is not.
 */
class SizeLimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {

    /** How many bytes have been read so far. */
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val value = super.read()
        if (value != END_OF_STREAM) {
            countBytes(1)
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) {
            countBytes(count)
        }
        return count
    }

    override fun skip(byteCount: Long): Long {
        // Skipped bytes still count towards the limit.
        val skipped = super.skip(byteCount)
        if (skipped > 0) {
            countBytes(skipped)
        }
        return skipped
    }

    // mark/reset would let the same bytes be counted twice, so they are switched off.
    override fun markSupported(): Boolean = false

    private fun countBytes(count: Number) {
        bytesRead += count.toLong()
        if (bytesRead > maxBytes) {
            throw FileTooLargeException(maxBytes)
        }
    }

    private companion object {
        const val END_OF_STREAM = -1
    }
}
