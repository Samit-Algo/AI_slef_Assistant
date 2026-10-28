package com.heybuddy.util

/**
 * How long (in seconds) the Keystore master key stays usable after one fingerprint/PIN check.
 *
 * 5 minutes (owner decision, Task 1.9): with 30 seconds, "Confirm it's you" appeared on almost
 * every document opened, which was too often for real use.
 *
 * Why this is still safe: the app's OWN lock is the main protection. It locks at once when the
 * app leaves the screen or the screen turns off, and after IDLE_LOCK_SECONDS without a touch, and
 * a locked vault never uses the key. This key window is a second layer underneath: it only matters
 * if the app lock were somehow bypassed, and then it still limits the key to 5 minutes.
 *
 * The window is fixed when the key is CREATED. A key made with an older value keeps that value
 * until the vault is deleted and set up again.
 *
 * After this window has passed, any use of the master key throws UserNotAuthenticatedException.
 * Every such use goes through MasterKeyAccess, which calmly asks "Confirm it's you" and retries once.
 */
const val VAULT_UNLOCK_WINDOW_SECONDS: Int = 300

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

/**
 * Longest time (in seconds) a one-time pass keeps the vault open while our own system screen
 * (file picker, photo picker, fingerprint/PIN prompt) is in front (rule 9, owner decision).
 * Without a limit, wandering off from a picker would keep the vault open forever.
 */
const val EXTERNAL_SCREEN_PASS_SECONDS: Int = 120

/**
 * DEFAULT time (in seconds) the app may sit with no touches before it locks itself (rule 9).
 * Since Task 1.10 the user can choose 1, 2 or 5 minutes in Settings; this is the starting value.
 */
const val IDLE_LOCK_SECONDS: Int = 120

/** The SharedPreferences file for non-secret settings (theme, auto-lock time, onboarding done). */
const val SETTINGS_FILE_NAME: String = "hey_buddy_settings"

/** What the user must type to confirm "Delete everything". Exact match, capital letters. */
const val DELETE_EVERYTHING_CONFIRMATION_WORD: String = "DELETE"

/** Folder inside filesDir (app-private) that holds the encrypted documents. Renaming it orphans the vault. */
const val VAULT_FOLDER_NAME: String = "vault"

/** Folder inside noBackupFilesDir for decrypted PDFs while they are viewed (rule 6). Emptied on every app start. */
const val VIEW_TEMP_FOLDER_NAME: String = "view_tmp"

/** Ending of an encrypted page file: vault/<pageId>.bin. */
const val ENCRYPTED_FILE_SUFFIX: String = ".bin"

/** Ending of a file still being encrypted. Renamed to .bin only when encryption finished. */
const val ENCRYPTING_TEMP_SUFFIX: String = ".tmp"

/** Ending of a file still being decrypted into view_tmp. Renamed only after full verification. */
const val DECRYPTING_PART_SUFFIX: String = ".part"

/** Bytes copied per step while streaming. 8 KB keeps memory use tiny and still copies quickly. */
const val STREAM_BUFFER_BYTES: Int = 8 * 1024

/** File name of the SQLCipher database (in the app-private databases folder). Renaming it orphans the vault. */
const val VAULT_DATABASE_NAME: String = "vault.db"

/**
 * Longest document name, in characters. Fits one line of the vault list; real names such as
 * "Aadhaar card - front (2024)" are well under half of this.
 */
const val MAX_DISPLAY_NAME_CHARACTERS: Int = 60

/** Most the viewer zooms in (owner decision, Task 1.8b). */
const val VIEWER_MAX_ZOOM: Float = 4f

/** Documents are drawn at this many times the screen size, so they stay sharp when zoomed in. */
const val VIEWER_RENDER_SCALE: Int = 2

/** Longest side, in pixels, of any picture the viewer makes (owner decision, Task 1.8b). */
const val VIEWER_MAX_SIDE_PIXELS: Int = 4096

/**
 * Most pixels in any picture the viewer makes (owner decision, Task 1.8b): about 32 MB at 4 bytes
 * per pixel, so one strange, huge page cannot run the phone out of memory.
 */
const val VIEWER_MAX_PIXELS: Long = 8_000_000L

/** Bytes in one megabyte, so file size limits can be written in megabytes. */
private const val BYTES_PER_MEGABYTE: Long = 1024L * 1024L

/** Largest file the vault accepts (rule 11). Big enough for a scanned PDF, small enough to keep the phone fast. */
const val MAX_FILE_BYTES: Long = 20L * BYTES_PER_MEGABYTE

/**
 * Most pages one document may have (owner decision, Task 1.10p). Covers front and back, passport
 * pages and multi-page certificates, while keeping one document quick to open.
 */
const val MAX_PAGES_PER_DOCUMENT: Int = 10

/** Largest total size of all pages of one document (owner decision, Task 1.10p). Each page still obeys MAX_FILE_BYTES. */
const val MAX_DOCUMENT_BYTES: Long = 50L * BYTES_PER_MEGABYTE

/**
 * Ending of a page that is encrypted but not saved yet (Add screen, Task 1.10p). Renamed to .bin
 * only when the document is saved; deleted on cancel, on lock and on every app start.
 */
const val PENDING_PAGE_SUFFIX: String = ".pending"

/**
 * Page format 1: a document from before Task 1.10p, made into a one-page document by the database
 * migration. Its key and file stay bound to "document:<id>", and its page id equals the document id.
 * Only migrated pages may use it; new pages never do.
 */
const val PAGE_FORMAT_LEGACY: Int = 1

/** Page format 2: key and file bound to "document:<documentId>:page:<pageId>". Every new page uses it. */
const val PAGE_FORMAT_CURRENT: Int = 2

/** Middle part of a format-2 page's associated data: "document:<documentId>" + this + "<pageId>". */
const val PAGE_ASSOCIATED_DATA_INFIX: String = ":page:"

/**
 * Longest side of a page thumbnail on the Add screen, in pixels. Small, because up to
 * MAX_PAGES_PER_DOCUMENT of them stay in memory at once (about 0.6 MB each at most).
 */
const val THUMBNAIL_MAX_PIXELS: Int = 384

/**
 * Long side, in pixels, of a page picture handed to OCR (Task 2.2). A card photo at this size has
 * letters well above ML Kit's suggested 16 pixels, while reading stays quick and memory small.
 */
const val READING_LONG_SIDE_PIXELS: Int = 2048

/** Most pages of one PDF page-file that are read for details (a certificate rarely has more). */
const val MAX_PDF_PAGES_TO_READ: Int = 5
