package com.heybuddy.files

import com.heybuddy.util.ENCRYPTING_TEMP_SUFFIX
import com.heybuddy.util.PENDING_PAGE_SUFFIX
import java.io.File

/**
 * Removes leftovers that a crash can leave behind. Runs on every app start.
 *
 * - ".tmp" files in the vault folder: half-finished encryptions (never valid documents).
 * - ".pending" files in the vault folder: pages picked on the Add screen but never saved (their
 *   keys lived only in memory, so after a restart they can never be opened anyway).
 * - everything in view_tmp, including ".part" files: decrypted plaintext for viewing (rule 6).
 *
 * Task 1.8 also calls [clearViewTemp] when the viewer closes.
 *
 * @param vaultDir The encrypted files folder.
 * @param viewTempDir The decrypted-for-viewing folder.
 */
class TempFileCleaner(
    private val vaultDir: File,
    private val viewTempDir: File,
) {

    /** Deletes all leftovers. Safe to call when the folders do not exist yet. */
    fun cleanOnStart() {
        deleteUnfinishedEncryptions()
        clearViewTemp()
    }

    /** Deletes every file in view_tmp, finished or not: none of it should outlive a viewing. */
    fun clearViewTemp() {
        val leftovers = viewTempDir.listFiles() ?: return
        for (file in leftovers) {
            file.deleteRecursively()
        }
    }

    private fun deleteUnfinishedEncryptions() {
        val files = vaultDir.listFiles() ?: return
        for (file in files) {
            val isUnfinished = file.isFile &&
                (file.name.endsWith(ENCRYPTING_TEMP_SUFFIX) || file.name.endsWith(PENDING_PAGE_SUFFIX))
            if (isUnfinished) {
                file.delete()
            }
        }
    }
}
