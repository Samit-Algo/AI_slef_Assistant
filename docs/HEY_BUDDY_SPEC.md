# Hey Buddy — Build Spec for Claude Code

> **How to use this file with Claude Code**
>
> 1. Put this file in your project folder as `docs/HEY_BUDDY_SPEC.md`.
> 2. Add one line to your project's `CLAUDE.md`: `Read docs/HEY_BUDDY_SPEC.md before any work. Follow its rules and task order.`
> 3. Give Claude Code **one task at a time**, for example:
>    `Do Task 1.1 from docs/HEY_BUDDY_SPEC.md only. Stop and show me what you did.`
> 4. Check the task's "Done when" list yourself before asking for the next task.

---

## 1. Product summary

Hey Buddy is a private Android assistant that fills forms using the user's own documents
(Aadhaar, PAN, driving licence, photo, signature, certificates). Everything stays on the phone.

**The one rule of the whole product:** Hey Buddy *suggests and fills*, but **never acts alone**.
The user always reviews the values and presses Submit themselves.

## 2. Fixed decisions (do not change without the owner's approval)

| Topic | Decision | Why |
| --- | --- | --- |
| Platform | Android only, published on Google Play | Target users are on Android |
| Language | Kotlin + Jetpack Compose (Material 3) | Best access to Autofill, Keystore and Biometric APIs |
| Min / target SDK | minSdk 30 (Android 11), targetSdk = latest stable | Android 11+ gives clean biometric-bound key APIs |
| Network | **The app has NO internet permission** | Privacy must be provable, not just promised |
| Filling method | Android Autofill Service (Phase 3) | Official API, allowed by Google Play |
| Accessibility API | **Never used** in the Play build | Google Play bans it for apps that act on their own |
| Dependency injection | Manual (a simple `AppContainer` class), no Hilt/Dagger | Easier to read and change |
| Crypto library | Google Tink + Android Keystore | Well-tested; avoid deprecated Jetpack security-crypto |

## 3. Big-picture architecture (all phases)

```
            Form in another app or in Chrome
                          │
   ┌──────────────┬───────┴────────┬──────────────────┐
   │ Assist reader│ Autofill svc   │ File picker      │   ← 3 entry doors (Phases 3, 4, 6)
   └──────┬───────┴───────┬────────┴────────┬─────────┘
          └───────────────┼─────────────────┘
                   Field matcher                         ← rules first, on-device AI later (Phase 5)
                          │
                    Safety gate                          ← site check + biometric + user review
                          │
                  Encrypted vault  ◄── Document reader   ← Phase 1 (vault), Phase 2 (reader)
```

## 4. All phases (overview)

| Phase | What we build | Done when… |
| --- | --- | --- |
| **1. Secure vault** | Add, view, delete documents; encryption; biometric lock; no internet | Files are unreadable off the phone; app locks itself |
| 2. Document reader | Camera capture, OCR, document type detection, fact extraction, confirm screen | Name, DOB, number read correctly from sample docs |
| 3. Autofill (text) | Autofill service + rules matcher + "Fill all" + review screen | Fills a test form in an app and in Chrome |
| 4. File uploads | Documents provider (appears in file pickers), photo resize. **Note:** photos can contain hidden details such as GPS location (EXIF). Strip this hidden data before any file is uploaded to a website | Uploads photo + PDF on a real government portal |
| 5. On-device AI | AI matcher for unknown fields, confidence, site memory | Unusual labels filled correctly; unsure fields left empty |
| 6. Voice + screen reading | Assist API reader, push-to-talk, optional wake word | Tells which documents a form needs, offline |
| 7. Hardening + launch | Security review, phishing and prompt-injection tests, Play forms | No high-risk issues; Play review passed |

---

## 5. PHASE 1 — Secure Vault (detailed spec)

### 5.1 Goal in one sentence

The user can safely store, view and delete their documents on the phone, and nobody — not a thief,
not another app, not even a copy of the files — can read them without the user's fingerprint, face or PIN.

### 5.2 Simple picture

A **locker inside a locker**:

- Each document is locked in its own small box (its own key).
- All the small box keys are kept inside one big safe (the master key).
- The big safe is built into the phone's security chip and opens only with the user's fingerprint/PIN.
- Delete a small box key → that document is gone forever, even if the file bits remain ("crypto-shredding").

### 5.3 In scope / out of scope

**In scope (Phase 1):**

- Onboarding screen that explains privacy and checks the phone has a screen lock
- Unlock screen (biometric or device PIN/pattern/password)
- Vault list (all documents, with type and date added)
- Add document from the system file picker / photo picker (JPEG, PNG, WEBP, PDF)
- User chooses the document type manually
- View document (image or PDF)
- Rename and delete one document
- "Delete everything" in Settings
- Auto-lock when the app goes to the background and after idle time
- All security rules in 5.5

**Out of scope (later phases):**

- Camera capture and OCR (Phase 2)
- Any autofill, file-picker provider, AI, voice (Phases 3–6)
- Cloud backup or sync of any kind (never)

### 5.4 Data model

**Document types (enum `DocumentType`):** `AADHAAR`, `PAN`, `DRIVING_LICENCE`, `PASSPORT`, `PHOTO`,
`SIGNATURE`, `CERTIFICATE`, `OTHER`.

**Table `documents` (Room database, encrypted with SQLCipher):**

| Column | Type | Notes |
| --- | --- | --- |
| `id` | String (UUID) | Primary key; also used as encryption "associated data" |
| `displayName` | String | User-editable name, e.g. "My Aadhaar" |
| `type` | String (`DocumentType`) | Chosen by the user |
| `mimeType` | String | Detected from file bytes, not from the file name |
| `sizeBytes` | Long | Size of the original (plaintext) file |
| `wrappedKey` | ByteArray | This document's key, encrypted by the master key |
| `createdAt` | Long (epoch ms) | |
| `updatedAt` | Long (epoch ms) | |

**Encrypted file location:** `filesDir/vault/<id>.bin` (app-private storage only, never external storage).

### 5.5 Security rules (must all be true at the end of Phase 1)

1. **No internet.** The final merged manifest must not contain `android.permission.INTERNET`.
   Add `<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />`
   so no library can add it secretly.
2. **Master key in the security chip.** AES-256 key in Android Keystore, alias `hb_master_key`.
   - Try StrongBox first (`setIsStrongBoxBacked(true)`); if not available, fall back to the normal hardware Keystore.
   - `setUserAuthenticationRequired(true)`
   - `setUserAuthenticationParameters(VAULT_UNLOCK_WINDOW_SECONDS, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`
   - Key can never leave the Keystore.
3. **One key per document.** Each document gets its own random Tink keyset (streaming AEAD,
   `AES256_GCM_HKDF_4KB`), wrapped (encrypted) with the master key and stored in `wrappedKey`.
4. **Bind file to its record.** Use the document `id` as associated data, so an encrypted file
   cannot be swapped into another record without failing to decrypt.
5. **Database is encrypted.** Room + SQLCipher. The database passphrase is 32 random bytes,
   stored only in wrapped form (wrapped by the master key) in app-private storage.
6. **Never write plaintext to permanent storage.** Encrypt while reading the input stream.
   - Exception: PDF viewing needs a real file for `PdfRenderer`. Decrypt to
     `noBackupFilesDir/view_tmp/`, delete it when the viewer closes, and delete the whole
     folder on every app start (in case of a crash).
7. **No backups.** `android:allowBackup="false"`, plus `dataExtractionRules` and
   `fullBackupContent` that exclude everything (cloud backup and device-to-device transfer).
8. **No screenshots.** Set `FLAG_SECURE` on the main activity so screenshots and screen
   recording show a black screen, and the recent-apps preview is hidden.
9. **Auto-lock.** Lock when the app goes to the background (`ProcessLifecycleOwner` `ON_STOP`)
   and after `IDLE_LOCK_SECONDS` of no touches. Locking clears decrypted data from memory.
10. **Phone must have a screen lock.** If `KeyguardManager.isDeviceSecure` is false, show a
    screen explaining why and block the vault until the user sets a lock.
11. **Validate files.** Max 20 MB. Check the real type from the first bytes ("magic bytes"):
    JPEG, PNG, WEBP, PDF only. Reject anything else with a clear message.
12. **No personal data in logs.** Never log file names, document names or contents.
    Use a small `SafeLog` wrapper that only logs in debug builds and only fixed messages.
13. **Delete = crypto-shred.** Deleting a document deletes its `.bin` file and its
    `wrappedKey` row. "Delete everything" also deletes the Keystore master key, the
    database and the vault folder.
14. **Key invalidated** (for example, the user removed their screen lock): catch
    `KeyPermanentlyInvalidatedException`, explain clearly that the vault cannot be
    recovered, and offer "Delete everything and start again".
15. **Minimal exported components.** Only the launcher activity is `exported="true"`.

### 5.6 Screens

| Screen | What the user sees and does |
| --- | --- |
| Onboarding | 3 short cards: "Your documents stay on this phone", "No internet", "Only you can unlock". Button: "Set up my vault" |
| No screen lock | Explains why a screen lock is needed; button opens system security settings |
| Unlock | App name + "Unlock with fingerprint or PIN" button → BiometricPrompt |
| Vault list | List of documents (icon by type, name, date). Floating "+" button. Empty state text |
| Add document | Opens picker → shows preview → user picks type and name → "Save to vault" |
| Document view | Image (zoomable) or PDF (page by page). Menu: Rename, Delete |
| Settings | Auto-lock time, "Delete everything" (with typed confirmation "DELETE") |

### 5.7 Suggested package structure

```
app/src/main/java/com/heybuddy/
├── HeyBuddyApp.kt              // Application class; creates AppContainer
├── AppContainer.kt             // Manual dependency wiring
├── MainActivity.kt             // Single activity; FLAG_SECURE; hosts navigation
├── security/
│   ├── MasterKeyManager.kt     // Create / get / delete the Keystore master key
│   ├── KeyWrapper.kt           // Wrap and unwrap document keys with the master key
│   ├── FileCrypto.kt           // Encrypt / decrypt document streams (Tink streaming AEAD)
│   ├── DatabaseKeyProvider.kt  // Create / unwrap the SQLCipher passphrase
│   ├── VaultLockState.kt       // Locked / unlocked state + idle timer
│   └── BiometricUnlocker.kt    // BiometricPrompt wrapper
├── data/
│   ├── VaultDatabase.kt        // Room database (SQLCipher)
│   ├── DocumentEntity.kt
│   ├── DocumentDao.kt
│   ├── DocumentType.kt
│   └── VaultRepository.kt      // The only class the UI talks to for documents
├── files/
│   ├── FileTypeDetector.kt     // Magic-byte checks + size limit
│   └── TempFileCleaner.kt      // Clears view_tmp on start and on viewer close
├── ui/
│   ├── onboarding/  unlock/  vaultlist/  adddocument/  viewer/  settings/
│   └── theme/
└── util/
    ├── SafeLog.kt
    └── Constants.kt            // VAULT_UNLOCK_WINDOW_SECONDS, IDLE_LOCK_SECONDS, MAX_FILE_BYTES
```

### 5.8 Code style rules (apply to every task)

- Plain, readable Kotlin. **Small single-purpose functions.** No clever one-liners.
- Use **explicit intermediate variables** with clear names instead of long call chains.
- **KDoc on every public class and function.** Type annotations on public APIs.
- Comments explain **why**, not what.
- No magic numbers — put them in `Constants.kt` with a comment.
- Every error shown to the user is in simple English and says what to do next.
- Do not add any library that is not listed in 5.9 without asking the owner first.

### 5.9 Allowed libraries (Phase 1)

- Jetpack Compose + Material 3 + Navigation Compose
- `androidx.biometric`
- `androidx.lifecycle` (incl. `lifecycle-process`)
- Room + SQLCipher for Android (`net.zetetic:sqlcipher-android`)
- Google Tink for Android (`com.google.crypto.tink:tink-android`)
- Testing: JUnit, AndroidX Test, Compose UI test

Use the latest stable versions at the time of building; confirm with the owner if a version is a pre-release.

### 5.10 Task list for Claude Code (do in order, stop after each)

| Task | What to build | Done when… |
| --- | --- | --- |
| **1.1 Project setup** | New Kotlin + Compose project, minSdk 30, package structure from 5.7, `Constants.kt`, `SafeLog.kt`, manifest rules 1, 7, 15, INTERNET removed | Project builds; merged manifest has no INTERNET; allowBackup is false |
| **1.2 Master key** | `MasterKeyManager` (rule 2), StrongBox with fallback | Unit/instrumented test: key is created once, reused, and deleted on request |
| **1.3 Biometric unlock** | `BiometricUnlocker`, `VaultLockState`, Unlock screen, no-screen-lock screen (rule 10) | Unlock works with fingerprint and PIN; blocked when phone has no lock |
| **1.4 Key wrapping** | `KeyWrapper` + `DatabaseKeyProvider` (rules 3, 5) | Test: wrap → unwrap returns the same key; fails when locked |
| **1.5 File encryption** | `FileCrypto` with streaming AEAD and id as associated data (rules 3, 4, 6) | Tests: round trip OK; tampered file fails; wrong id fails |
| **1.6 Database** | Room + SQLCipher, `DocumentEntity`, `DocumentDao`, `VaultRepository` | Test: save, list, rename, delete work; DB file unreadable without passphrase |
| **1.7 Add document** | Picker, `FileTypeDetector` (rule 11), type + name form, save flow | Adding JPEG/PNG/WEBP/PDF works; a .txt renamed to .pdf is rejected |
| **1.8 Vault list + viewer** | List screen, image viewer, PDF viewer with temp-file rules (rule 6), `TempFileCleaner` | Viewing works; `view_tmp` is empty after closing and after restart |
| **1.9 Lock + safety** | Auto-lock (rule 9), FLAG_SECURE (rule 8), key-invalidated handling (rule 14). **Note:** the file pickers and the fingerprint/PIN prompt open other screens, which briefly puts Hey Buddy in the background; auto-lock must NOT lock the vault during these | Leaving the app locks it; screenshot is black; removing screen lock shows recovery screen |
| **1.10 Delete + settings + onboarding** | Delete one, Delete everything (rule 13), Settings screen, Onboarding screen (5.6). Also: clean up orphan `.bin` files (a `.bin` with no row, left by a crash). **Note:** this cleanup must only run while no save is in progress, using the same database lock (`VaultDatabaseHolder.withDatabase`), because during a save a `.bin` exists briefly before its row | After "Delete everything": no files, no DB, no Keystore key; onboarding shows on first start and again after "Delete everything" |
| **1.11 Phase review** | Run all tests, write `docs/PHASE1_REPORT.md` listing each rule in 5.5 and how it is met. **Reminder:** remind the owner to delete the `Download/HeyBuddyTest/` sample-files folder on the test phone, and ask before deleting it | Owner approves the report |

### 5.11 Manual security checks (owner does these at the end of Phase 1)

1. **No internet:** check the merged manifest in Android Studio (Merged Manifest tab) — no INTERNET permission.
2. **Files are unreadable:** on a debug build, copy a `.bin` file off the phone (`adb shell run-as com.heybuddy ...`) and try to open it — it must look like random bytes.
3. **Screenshots blocked:** take a screenshot inside the app — it must be black.
4. **Recent apps hidden:** open recent apps — Hey Buddy's preview must be blank.
5. **Auto-lock:** go to home screen and come back — unlock must be asked again.
6. **Bad file:** rename a text file to `.pdf` and try to add it — it must be rejected.
7. **Delete everything:** after it, reopen the app — it must start from onboarding.

### 5.12 Phase 1 exit criteria

- All tasks 1.1–1.11 done and approved
- All 15 security rules in 5.5 met and listed in `docs/PHASE1_REPORT.md`
- All manual checks in 5.11 pass on at least one real phone (not only the emulator)
- All items in 5.13 are tested and closed

### 5.13 Open test items (must be closed before Phase 1 ends)

| Item | From task | Why still open | How to close it |
| --- | --- | --- | --- |
| Fingerprint unlock: NOT TESTED YET | 1.3 | Owner's test phone has no working fingerprint sensor | In Task 1.11, on the Android emulator with a virtual fingerprint: unlock with fingerprint and confirm "Vault unlocked" in logcat |
| Wrong-finger lockout message: NOT TESTED YET | 1.3 | Same as above | In Task 1.11, on the emulator: use a wrong virtual finger until lockout; confirm "Fingerprint lockout" in logcat, the "too many tries" message on screen, and that PIN still unlocks |
| Tink unwrap after the 30-second window: NOT TESTED YET | 1.4 | The one real run was spoiled by a PIN unlock during the wait; owner chose to move on. Expiry itself is proven (Task 1.2) and the cause-chain check is proven with a fake error | In Task 1.11: unlock, run `AuthRequiredKeyWrapTest`, do not touch the phone for 40 seconds; both tests must pass |
| **HIGHEST PRIORITY** — `fake_pdf.pdf` (text renamed to .pdf) is rejected: NOT TESTED YET on the phone | 1.7 | Owner approved Task 1.7 after testing one image and one PDF. Covered by automated tests (`FileTypeDetectorTest`), not yet by hand | In Task 1.11, with the files in `Download/HeyBuddyTest/`: pick it; expect "Hey Buddy can only store photos (JPEG, PNG, WEBP) and PDF files…" and the document count unchanged |
| **HIGHEST PRIORITY** — `broken_photo.jpg` (right first bytes, damaged after) is rejected: NOT TESTED YET | 1.7 | Same as above. No automated test decodes a real damaged image | In Task 1.11: pick it; expect "This image seems to be damaged…" and the count unchanged |
| **HIGHEST PRIORITY** — `too_large.jpg` (21,000,000 bytes) is rejected: NOT TESTED YET on the phone | 1.7 | Same as above. Size limit covered by automated tests (`PickedFileReaderTest`, `FileCryptoTest`), not yet through the picker | In Task 1.11: pick it; expect "This file is larger than 20 MB…" and the count unchanged |
| `sample_photo.png` and `sample_photo.webp` save correctly: NOT TESTED YET | 1.7 | Owner tested one image (JPEG) and one PDF only | In Task 1.11: add both; each shows a preview, saves, and the count goes up by 1 |
| Check A (cancel): cancelling "Confirm it's you" when saving: NOT TESTED YET by hand | 1.7 | The confirm path was seen working on the phone (log shows the re-ask twice, both saves went through). The cancel path is covered by an automated test (`AddDocumentSaverTest`) only | In Task 1.11: unlock, wait 40 seconds, tap Save, cancel the prompt; expect "Please confirm it's you to save. Tap Save to try again." and the count unchanged |
| Check B: name rules on the Add screen: NOT TESTED YET by hand | 1.7 | Rules are covered by automated tests (`DisplayNameValidatorTest`); the screen behaviour is not | In Task 1.11: clear the name (message shows, Save greyed out); type your own name, then change the type (your name stays) |
| Check C: "Choose a photo" opens the system photo picker with no permission question: NOT TESTED YET | 1.7 | Owner tested through the picker but did not report which button | In Task 1.11: tap "Choose a photo"; the photo picker opens with no permission question |

---

## 6. Open decisions (owner to answer before the phase that needs them)

| Decision | Needed before | Current default |
| --- | --- | --- |
| Play Store only, or also a direct-download build? | Phase 3 | Play Store only |
| Wake word in version 1, or push-to-talk only? | Phase 6 | Push-to-talk only |
| Lowest phone to support (decides if AI is required or optional) | Phase 5 | AI optional; rules must work alone |
| Store full Aadhaar number, or masked only by default? | Phase 2 | Masked by default; full only if the user chooses |
