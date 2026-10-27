package com.heybuddy.files

import com.heybuddy.util.ENCRYPTED_FILE_SUFFIX
import java.util.UUID

/**
 * The document id in an encrypted vault file name, or null if the name is not EXACTLY
 * "<valid UUID>.bin" (lowercase standard form, as the app writes it).
 *
 * Owner rule B (Task 1.10): orphan cleanup may only touch files of exactly this shape. Anything
 * else in the folder (half-written ".tmp" files, unknown files) is left alone.
 *
 * @param fileName A file name, without folders.
 * @return The id, or null.
 */
fun documentIdFromVaultFileName(fileName: String): String? {
    if (!fileName.endsWith(ENCRYPTED_FILE_SUFFIX)) return null
    val idPart = fileName.removeSuffix(ENCRYPTED_FILE_SUFFIX)
    val parsed = try {
        UUID.fromString(idPart)
    } catch (notAUuid: IllegalArgumentException) {
        return null
    }
    // UUID.fromString also accepts odd forms (for example "1-1-1-1-1"); only the exact form counts.
    return if (parsed.toString() == idPart) idPart else null
}

/**
 * Picks the encrypted files that no database row points to.
 *
 * Such a file is left behind only by a crash between steps of a save or a delete. Its key is
 * stored nowhere, so it can never be opened: removing it frees space and changes nothing else.
 *
 * @param fileNames The names of the files in the vault folder.
 * @param knownIds The ids of every document in the database.
 * @return The document ids of the orphan files (each file is "<id>.bin").
 */
fun findOrphanIds(fileNames: List<String>, knownIds: Set<String>): List<String> {
    val orphans = mutableListOf<String>()
    for (name in fileNames) {
        val documentId = documentIdFromVaultFileName(name) ?: continue
        if (documentId !in knownIds) orphans.add(documentId)
    }
    return orphans
}
