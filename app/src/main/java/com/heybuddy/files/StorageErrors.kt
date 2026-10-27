package com.heybuddy.files

import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants

/** Longest cause chain we follow, so a chain that loops back on itself cannot hang us. */
private const val MAX_CAUSE_DEPTH = 16

/**
 * Tells whether an error means "the phone is out of storage space".
 *
 * Android reports this in different ways depending on where it happened: a file write gives an
 * ErrnoException(ENOSPC) wrapped in an IOException, and a database write gives SQLiteFullException.
 * The whole cause chain is checked, because other code may wrap these.
 *
 * @param error Any error from a save.
 * @return true if the phone ran out of space.
 */
fun isStorageFull(error: Throwable): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (isNoSpaceErrno(current)) return true
        // SQLCipher reports a full disk with Android's own SQLiteFullException too.
        if (current is SQLiteFullException) return true
        current = current.cause
        depth++
    }
    return false
}

private fun isNoSpaceErrno(error: Throwable): Boolean {
    return error is ErrnoException && error.errno == OsConstants.ENOSPC
}
