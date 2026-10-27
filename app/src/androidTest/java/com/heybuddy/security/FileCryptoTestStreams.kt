package com.heybuddy.security

import java.io.IOException
import java.io.InputStream

/**
 * Produces [totalBytes] bytes of a repeating pattern without holding them in memory,
 * so size-limit tests never need a 20 MB array.
 */
internal class GeneratedInputStream(private val totalBytes: Long) : InputStream() {

    private var position = 0L

    override fun read(): Int {
        if (position >= totalBytes) return -1
        val value = (position % PATTERN_LENGTH).toInt()
        position++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= totalBytes) return -1
        val remaining = totalBytes - position
        val count = minOf(length.toLong(), remaining).toInt()
        for (index in 0 until count) {
            buffer[offset + index] = ((position + index) % PATTERN_LENGTH).toByte()
        }
        position += count
        return count
    }

    private companion object {
        const val PATTERN_LENGTH = 251L
    }
}

/** Gives [goodBytes] bytes, then fails, like a file picker whose source disappears mid-read. */
internal class FailingInputStream(private val goodBytes: Int) : InputStream() {

    private var position = 0

    override fun read(): Int {
        if (position >= goodBytes) throw IOException("Simulated read failure")
        position++
        return 0
    }
}
