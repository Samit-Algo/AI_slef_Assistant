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

/**
 * Folder in noBackupFilesDir for the OCR model files that must be real files (Tesseract). Only the
 * public models live there, never anyone's data (Task 2.3).
 */
const val OCR_MODELS_FOLDER_NAME: String = "ocr_models"

/** Tesseract's name for the Tamil model ("tam"). */
const val TAMIL_MODEL_LANGUAGE: String = "tam"

/**
 * The official Tamil "fast" model inside the app's assets (tessdata_fast, Apache 2.0; checksum in
 * docs/TESSERACT_BUILD.md). Stored uncompressed (build.gradle.kts), so its size can be checked.
 */
const val TAMIL_MODEL_ASSET: String = "tessdata/tam.traineddata"

// Merging ML Kit's and Tesseract's lines (spec 7.4a); see LineMerge for the rule. Set from the
// confidence values measured on the 31 fake samples in Task 2.3 (owner's phone): ML Kit's real
// English/Hindi lines 0.71-0.94, its Tamil garbage 0.18-0.64; Tesseract's real Tamil 0.88-0.96, its
// garbage on English/Hindi 0.00-0.72. The gaps are left as "unsure", where BOTH lines are kept.

/** Below this, ML Kit itself says the line is very likely wrong: treated as garbage. */
const val MERGE_ML_KIT_GARBAGE_BELOW: Float = 0.5f

/** From this up, a line with only Latin/Devanagari letters is treated as real English or Hindi. */
const val MERGE_ML_KIT_REAL_FROM: Float = 0.7f

/** From this up, Tesseract is sure enough of a mostly-Tamil line to let it replace ML Kit's garbage. */
const val MERGE_TAMIL_SURE_FROM: Float = 0.8f

/** Share of a Tesseract line's letters that must be Tamil for it to count as a Tamil line. */
const val MERGE_MIN_TAMIL_SHARE: Float = 0.8f

// In-app camera (Task 2.4).

/**
 * Long side, in pixels, of a page photographed with the in-app camera. Sharp enough to zoom in and
 * to read (OCR uses 2048), and a page stays far below the 20 MB file limit.
 */
const val CAMERA_PAGE_LONG_SIDE_PIXELS: Int = 3072

/** The size CameraX is asked to capture (4:3, the sensor's usual shape), close to the page size above. */
const val CAMERA_CAPTURE_WIDTH_PIXELS: Int = 3072
const val CAMERA_CAPTURE_HEIGHT_PIXELS: Int = 2304

/** JPEG quality of a camera page (owner decision, Task 2.4): good detail, about 1 MB a page. */
const val CAMERA_JPEG_QUALITY: Int = 90

/** Long side of the picture shown on the "Retake or keep" screen; the page itself stays full size. */
const val CAMERA_REVIEW_LONG_SIDE_PIXELS: Int = 1600

/** Size of the preview frames measured for the hints: small enough to measure quickly. */
const val CAMERA_ANALYSIS_WIDTH_PIXELS: Int = 640
const val CAMERA_ANALYSIS_HEIGHT_PIXELS: Int = 480

/**
 * Below this average brightness (0 = black, 255 = white) a frame is "too dark": the hint says
 * "More light needed". PROVISIONAL until measured with the owner's real photos (Task 2.4 step 5).
 */
const val CAMERA_DARK_BELOW: Double = 60.0

/**
 * Below this sharpness (variance of the Laplacian on a 640 px wide picture) a picture is "blurry".
 * PROVISIONAL until measured with the owner's real photos (Task 2.4 step 5).
 */
const val CAMERA_BLURRY_BELOW: Double = 60.0

/** How many recent preview frames the live hint looks at, so it does not flicker. */
const val CAMERA_HINT_WINDOW_FRAMES: Int = 8

/** Shape of the guide frame: an ID card (85.6 x 54 mm) and an A4 page (210 x 297 mm), long side / short side. */
const val CARD_ASPECT_RATIO: Float = 85.6f / 54f
const val A4_ASPECT_RATIO: Float = 297f / 210f

// Document type guess (Task 2.5, spec 7.6).

/** Points for a word that by itself names the type ("Aadhaar", "Permanent Account Number") or a valid number. */
const val TYPE_GUESS_DECISIVE_POINTS: Int = 3

/** Points for a word that only supports a type ("Government of India", "DL No"). */
const val TYPE_GUESS_SUPPORTING_POINTS: Int = 1

/** Fewest points for a suggestion: one decisive sign, or three supporting words together. */
const val TYPE_GUESS_MIN_SCORE: Int = 3

/** How far the best type must be ahead of the next one, so a mixed page gets no suggestion. */
const val TYPE_GUESS_MIN_LEAD: Int = 2

// Fact rules part 1 (Task 2.6, spec 7.7).

/** At most this many look-alike fixes in one PAN; more would be guessing, so it is not taken. */
const val PAN_MAX_FIXES: Int = 2

/** Oldest age accepted for a date of birth (spec 7.7: age 0 to 120). */
const val MAX_AGE_YEARS: Int = 120

/** Earliest year in a driving licence number (the format is far newer, so older is a misread). */
const val DL_EARLIEST_YEAR: Int = 1950

/** Range for other dates (issue, validity): wide, but outside it a "date" is a misread number. */
const val DATE_EARLIEST_YEAR: Int = 1900
const val DATE_LATEST_YEAR: Int = 2100
