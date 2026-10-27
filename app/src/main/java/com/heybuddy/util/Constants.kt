package com.heybuddy.util

/**
 * How long (in seconds) the Keystore master key stays usable after one fingerprint/PIN check.
 *
 * Short on purpose: if the phone is grabbed after unlock, the key soon stops working
 * even if the app's own lock has not kicked in yet.
 *
 * TODO(Tasks 1.3 and 1.4): after this window has passed, any use of the master key
 *  (for example, adding a document 5 minutes after unlock) throws
 *  UserNotAuthenticatedException. Catch it and calmly show the fingerprint/PIN prompt again,
 *  then retry. It must never crash or show an error message.
 */
const val VAULT_UNLOCK_WINDOW_SECONDS: Int = 30

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
