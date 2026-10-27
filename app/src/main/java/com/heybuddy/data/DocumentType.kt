package com.heybuddy.data

/** The kinds of document the user can store (spec 5.4). The user picks one when adding a document. */
enum class DocumentType {
    AADHAAR,
    PAN,
    DRIVING_LICENCE,
    PASSPORT,
    PHOTO,
    SIGNATURE,
    CERTIFICATE,
    OTHER,
    ;

    companion object {
        /**
         * Reads a type back from the database.
         *
         * @param storedValue The enum name saved in the `type` column.
         * @return The matching type, or [OTHER] if the value is unknown (for example written by a
         *   newer app version), so an unexpected value can never crash the list.
         */
        fun fromStoredValue(storedValue: String): DocumentType {
            return entries.firstOrNull { type -> type.name == storedValue } ?: OTHER
        }
    }
}
