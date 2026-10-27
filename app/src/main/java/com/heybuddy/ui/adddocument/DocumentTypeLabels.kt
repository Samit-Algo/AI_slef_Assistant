package com.heybuddy.ui.adddocument

import androidx.annotation.StringRes
import com.heybuddy.R
import com.heybuddy.data.DocumentType

/**
 * The label shown for a document type in the dropdown.
 *
 * @param type The document type.
 * @return Its label's string resource.
 */
@StringRes
fun labelFor(type: DocumentType): Int {
    return when (type) {
        DocumentType.AADHAAR -> R.string.type_label_aadhaar
        DocumentType.PAN -> R.string.type_label_pan
        DocumentType.DRIVING_LICENCE -> R.string.type_label_driving_licence
        DocumentType.PASSPORT -> R.string.type_label_passport
        DocumentType.PHOTO -> R.string.type_label_photo
        DocumentType.SIGNATURE -> R.string.type_label_signature
        DocumentType.CERTIFICATE -> R.string.type_label_certificate
        DocumentType.OTHER -> R.string.type_label_other
    }
}

/**
 * The name suggested for a new document of this type, for example "Aadhaar card".
 *
 * @param type The document type.
 * @return The suggested name's string resource.
 */
@StringRes
fun defaultNameFor(type: DocumentType): Int {
    // "Other" on its own would be an odd document name, so it gets "Document" instead.
    if (type == DocumentType.OTHER) return R.string.type_default_name_other
    return labelFor(type)
}
