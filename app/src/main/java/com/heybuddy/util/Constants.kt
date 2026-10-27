package com.heybuddy.util

/**
 * How long (in seconds) the Keystore master key stays usable after one fingerprint/PIN check.
 *
 * Short on purpose: if the phone is grabbed after unlock, the key soon stops working
 * even if the app's own lock has not kicked in yet.
 *
 * After this window has passed, any use of the master key (for example, adding a document
 * 5 minutes after unlock) throws UserNotAuthenticatedException. Every such use must go through
 * MasterKeyAccess, which calmly shows the fingerprint/PIN prompt again and retries once.
 * It must never crash or show an error message.
 */
const val VAULT_UNLOCK_WINDOW_SECONDS: Int = 30

/** Length of the database passphrase in bytes (rule 5). 32 bytes = 256 bits of randomness. */
const val DB_PASSPHRASE_BYTES: Int = 32

/** File (in noBackupFilesDir) holding the wrapped database passphrase. Renaming it would orphan the database. */
const val DB_PASSPHRASE_FILE_NAME: String = "db_passphrase.bin"

/** First byte of the passphrase file, so the file layout can change later without guessing. */
const val PASSPHRASE_FILE_FORMAT_VERSION: Byte = 1

/** Associated data for the wrapped database passphrase (point 2 of Task 1.4). Changing it breaks unwrapping. */
const val DB_PASSPHRASE_ASSOCIATED_DATA: String = "db-passphrase"

/**
 * Start of the associated data for a wrapped document key; the document id follows.
 * The prefix keeps document keys and the passphrase apart, so one can never be accepted as the other.
 */
const val DOCUMENT_KEY_ASSOCIATED_DATA_PREFIX: String = "document:"

/** Name of the vault's master key inside Android Keystore (rule 2). Changing it would orphan existing vaults. */
const val MASTER_KEY_ALIAS: String = "hb_master_key"

/** Master key size. AES-256 is the strongest AES size Keystore supports (rule 2). */
const val MASTER_KEY_SIZE_BITS: Int = 256

/** How long (in seconds) the app may sit with no touches before it locks itself (rule 9). */
const val IDLE_LOCK_SECONDS: Int = 120

/** Bytes in one megabyte, so file size limits can be written in megabytes. */
private const val BYTES_PER_MEGABYTE: Long = 1024L * 1024L

/** Largest file the vault accepts (rule 11). Big enough for a scanned PDF, small enough to keep the phone fast. */
const val MAX_FILE_BYTES: Long = 20L * BYTES_PER_MEGABYTE
