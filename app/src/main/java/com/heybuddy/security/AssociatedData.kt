package com.heybuddy.security

import com.heybuddy.util.DOCUMENT_KEY_ASSOCIATED_DATA_PREFIX

/**
 * Associated data that binds something to one document: "document:<id>" (rule 4).
 *
 * Used both for the wrapped document key and for the encrypted file, so neither can be moved
 * to another document's record without failing to decrypt.
 *
 * @param documentId The document's id.
 * @return The label as bytes.
 */
internal fun documentAssociatedData(documentId: String): ByteArray {
    val label = DOCUMENT_KEY_ASSOCIATED_DATA_PREFIX + documentId
    return label.toByteArray(Charsets.UTF_8)
}
