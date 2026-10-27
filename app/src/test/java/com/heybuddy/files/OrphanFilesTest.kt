package com.heybuddy.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Owner rule B: orphan cleanup only touches files named exactly "<valid UUID>.bin". */
class OrphanFilesTest {

    @Test
    fun aValidVaultFileNameGivesItsId() {
        assertEquals(KNOWN_ID, documentIdFromVaultFileName("$KNOWN_ID.bin"))
    }

    @Test
    fun anyOtherNameIsIgnored() {
        val notVaultFiles = listOf(
            "$KNOWN_ID.bin.tmp", // a save still in progress (or crashed): handled by TempFileCleaner
            "$KNOWN_ID.BIN",
            "$KNOWN_ID.pdf",
            KNOWN_ID,
            "${KNOWN_ID.uppercase()}.bin", // the app always writes lowercase
            "1-1-1-1-1.bin", // UUID.fromString accepts this odd form; only the standard form counts
            "notes.bin",
            ".bin",
            "../$KNOWN_ID.bin",
        )
        for (name in notVaultFiles) {
            assertNull("\"$name\" must be ignored", documentIdFromVaultFileName(name))
        }
    }

    @Test
    fun onlyVaultFilesWithoutARowAreOrphans() {
        val fileNames = listOf("$KNOWN_ID.bin", "$ORPHAN_ID.bin", "$ORPHAN_ID.bin.tmp", "readme.txt")

        val orphans = findOrphanIds(fileNames, knownIds = setOf(KNOWN_ID))

        assertEquals(listOf(ORPHAN_ID), orphans)
    }

    @Test
    fun nothingIsAnOrphanWhenEveryFileHasARow() {
        val orphans = findOrphanIds(listOf("$KNOWN_ID.bin"), knownIds = setOf(KNOWN_ID))

        assertEquals(emptyList<String>(), orphans)
    }

    private companion object {
        const val KNOWN_ID = "6f1c2a4e-1b2c-4d3e-8f90-123456789abc"
        const val ORPHAN_ID = "0a0b0c0d-1111-4222-8333-444455556666"
    }
}
