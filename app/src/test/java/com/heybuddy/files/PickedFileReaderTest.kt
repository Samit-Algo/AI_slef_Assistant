package com.heybuddy.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

/** Checks that a picked file is read completely, but never past the size limit. */
class PickedFileReaderTest {

    private val content = Random(seed = 7).nextBytes(CONTENT_BYTES)

    @Test
    fun readsEverythingWithoutASizeHint() {
        val result = PickedFileReader.readAll(ByteArrayInputStream(content), LIMIT, sizeHint = 0)

        assertArrayEquals(content, result)
    }

    @Test
    fun aWrongSizeHintDoesNotChangeTheResult() {
        val tooSmallHint = PickedFileReader.readAll(ByteArrayInputStream(content), LIMIT, sizeHint = 10)
        val tooBigHint = PickedFileReader.readAll(ByteArrayInputStream(content), LIMIT, sizeHint = 999_999)

        assertArrayEquals(content, tooSmallHint)
        assertArrayEquals(content, tooBigHint)
    }

    @Test
    fun exactlyTheLimitIsRead() {
        val atLimit = ByteArray(LIMIT.toInt()) { 1 }

        val result = PickedFileReader.readAll(ByteArrayInputStream(atLimit), LIMIT, sizeHint = 0)

        assertArrayEquals(atLimit, result)
    }

    @Test
    fun oneByteOverTheLimitStopsEvenWithALyingSizeHint() {
        val overLimit = ByteArray(LIMIT.toInt() + 1)

        assertThrows(FileTooLargeException::class.java) {
            // The picker claims the file is tiny; the real bytes decide.
            PickedFileReader.readAll(ByteArrayInputStream(overLimit), LIMIT, sizeHint = 5)
        }
    }

    @Test
    fun emptyFileGivesNoBytes() {
        val result = PickedFileReader.readAll(ByteArrayInputStream(ByteArray(0)), LIMIT, sizeHint = 0)

        assertArrayEquals(ByteArray(0), result)
    }

    private companion object {
        const val CONTENT_BYTES = 30_000
        const val LIMIT = 50_000L
    }
}
