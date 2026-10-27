package com.heybuddy.files

import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Checks the "phone is out of storage" detection. Runs on the phone for the real Android error classes. */
@RunWith(AndroidJUnit4::class)
class StorageErrorsTest {

    @Test
    fun fileWriteOutOfSpaceIsRecognisedEvenWhenWrapped() {
        // This is how a FileOutputStream reports a full disk.
        val noSpace = ErrnoException("write", OsConstants.ENOSPC)
        val wrapped = IOException("write failed: ENOSPC (No space left on device)", noSpace)
        val wrappedTwice = IllegalStateException("save failed", wrapped)

        assertTrue(isStorageFull(wrapped))
        assertTrue(isStorageFull(wrappedTwice))
    }

    @Test
    fun databaseFullIsRecognised() {
        // SQLCipher throws this same Android class when the disk is full.
        assertTrue(isStorageFull(SQLiteFullException("database or disk is full")))
    }

    @Test
    fun otherErrorsAreNotStorageFull() {
        val otherErrno = IOException("failed", ErrnoException("open", OsConstants.EACCES))

        assertFalse(isStorageFull(otherErrno))
        assertFalse(isStorageFull(IOException("something else")))
    }
}
