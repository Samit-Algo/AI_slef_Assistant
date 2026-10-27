package com.heybuddy.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

/** Checks the streaming size limit (rule 11, point 4 of the Task 1.5 plan). */
class SizeLimitedInputStreamTest {

    @Test
    fun countsEveryByteRead() {
        val limited = SizeLimitedInputStream(ByteArrayInputStream(ByteArray(10)), maxBytes = 100)

        limited.read()
        limited.read(ByteArray(4))

        assertEquals(5L, limited.bytesRead)
    }

    @Test
    fun exactlyTheLimitIsAllowed() {
        val limited = SizeLimitedInputStream(ByteArrayInputStream(ByteArray(LIMIT.toInt())), maxBytes = LIMIT)

        val allBytes = limited.readBytes()

        assertEquals(LIMIT.toInt(), allBytes.size)
        assertEquals(LIMIT, limited.bytesRead)
    }

    @Test
    fun oneByteOverTheLimitStops() {
        val oneTooMany = ByteArray(LIMIT.toInt() + 1)
        val limited = SizeLimitedInputStream(ByteArrayInputStream(oneTooMany), maxBytes = LIMIT)

        assertThrows(FileTooLargeException::class.java) {
            limited.readBytes()
        }
    }

    @Test
    fun skippedBytesCountTowardsTheLimit() {
        val limited = SizeLimitedInputStream(ByteArrayInputStream(ByteArray(LIMIT.toInt() + 1)), maxBytes = LIMIT)

        assertThrows(FileTooLargeException::class.java) {
            limited.skip(LIMIT + 1)
        }
    }

    private companion object {
        const val LIMIT = 50L
    }
}
