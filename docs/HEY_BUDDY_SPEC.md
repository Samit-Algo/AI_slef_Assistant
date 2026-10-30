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
| **1. Secure vault** — **Done, with checks deferred to Phase 7** (owner decision, 2026-10-28; see section 8) | Add, view, delete documents; encryption; biometric lock; no internet | Files are unreadable off the phone; app locks itself |
| 2. Document reader — detailed spec **APPROVED** in section 7 (2026-10-28) | Camera capture, OCR (English, Hindi and **Tamil**), document type detection, fact extraction, confirm screen | Name, DOB, number read correctly from sample docs, including Tamil ones |
| 3. Autofill (text) | Autofill service + rules matcher + "Fill all" + review screen | Fills a test form in an app and in Chrome |
| 4. File uploads | Documents provider (appears in file pickers), photo resize. **Note:** photos can contain hidden details such as GPS location (EXIF). Strip this hidden data before any file is uploaded to a website. **Note (owner decision, Task 2.4):** in-app camera photos are NOT cropped to the guide frame (the full photo is kept, so nothing is cut off). Before any page is uploaded to a website, offer cropping to just the document (manual crop handles), because the full photo may include background objects or other papers. **Note (owner decision, Task 1.10p):** multi-page documents store each page as its own encrypted file (not one combined PDF), so a website can get front and back as separate uploads, or Phase 4 builds a combined PDF on the fly only when a website asks for one | Uploads photo + PDF on a real government portal |
| 5. On-device AI | AI matcher for unknown fields, confidence, site memory | Unusual labels filled correctly; unsure fields left empty |
| 6. Voice + screen reading | Assist API reader, push-to-talk, optional wake word | Tells which documents a form needs, offline |
| 7. Hardening + launch | Security review, phishing and prompt-injection tests, Play forms; **plus "Before first real user" (section 8): every check deferred from Phase 1** | No high-risk issues; Play review passed; section 8 all closed |

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

Room database (encrypted with SQLCipher), **version 2** since Task 1.10p. Version 1 had one file per
document; the real migration `MIGRATION_1_2` turned every old document into a one-page document
without re-encrypting anything (no destructive migration, ever).

**Table `documents`:**

| Column | Type | Notes |
| --- | --- | --- |
| `id` | String (UUID) | Primary key; part of every page's associated data |
| `displayName` | String | User-editable name, e.g. "My Aadhaar" |
| `type` | String (`DocumentType`) | Chosen by the user |
| `createdAt` | Long (epoch ms) | |
| `updatedAt` | Long (epoch ms) | |

**Table `document_pages`** (Task 1.10p, owner decision: each page is its own encrypted file):

| Column | Type | Notes |
| --- | --- | --- |
| `pageId` | String (UUID) | Primary key; also the file name |
| `documentId` | String | The document (foreign key, deleted with it) |
| `position` | Int | Page order 0, 1, 2… with no gaps; unique per document. The order lives only here, in the encrypted database |
| `mimeType` | String | Detected from file bytes, not from the file name |
| `sizeBytes` | Long | Size of the original (plaintext) file |
| `wrappedKey` | ByteArray | This page's key, encrypted by the master key |
| `formatVersion` | Int | 2 = bound to `document:<documentId>:page:<pageId>` (every new page); 1 = migrated old document, still bound to `document:<id>`, with page id = document id |

**Limits (owner decision, Task 1.10p):** at most 10 pages and 50 MB per document; each page at most 20 MB (rule 11).

**Encrypted file location:** `filesDir/vault/<pageId>.bin` (app-private storage only, never external
storage). A page picked on the Add screen waits as `<pageId>.bin.pending` until Save; pending files
are deleted on cancel, on lock and on every app start.

### 5.5 Security rules (must all be true at the end of Phase 1)

1. **No internet.** The final merged manifest must not contain `android.permission.INTERNET`.
   Add `<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />`
   so no library can add it secretly.
2. **Master key in the security chip.** AES-256 key in Android Keystore, alias `hb_master_key`.
   - Try StrongBox first (`setIsStrongBoxBacked(true)`); if not available, fall back to the normal hardware Keystore.
   - `setUserAuthenticationRequired(true)`
   - `setUserAuthenticationParameters(VAULT_UNLOCK_WINDOW_SECONDS, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`
   - `VAULT_UNLOCK_WINDOW_SECONDS` = **300 (5 minutes)** — owner decision in Task 1.9, after 30 seconds
     made "Confirm it's you" appear on almost every document opened. The app's own lock (rule 9:
     background, screen off, idle) is the **main** protection; this key window is a **second layer**
     that limits the key even if the app lock were bypassed. The window is fixed when a key is created.
   - Key can never leave the Keystore.
3. **One key per page.** Each page (a one-page document has one) gets its own random Tink keyset
   (streaming AEAD, `AES256_GCM_HKDF_4KB`), wrapped (encrypted) with the master key and stored in
   its `wrappedKey`. (Task 1.10p: was "one key per document" while every document had one file.)
4. **Bind file to its record.** Every new page's key and file use
   `document:<documentId>:page:<pageId>` as associated data, so a page file cannot be swapped with
   another page or moved to another document without failing to decrypt. Owner decision (Task 1.10p):
   bound by page **id**, not page number; the order is protected by the encrypted database, so
   reordering or removing a page never re-encrypts anything. Only pages migrated from version 1
   keep the old `document:<id>` label.
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
9. **Auto-lock.** Lock as soon as the app's screen stops being visible (the activity's `onStop`,
   ignoring screen rotation; owner decision in Task 1.9 — immediate, unlike `ProcessLifecycleOwner`,
   which waits about 0.7 s), when the screen turns off, and after `IDLE_LOCK_SECONDS` of no touches
   or typing. Exception: right before our own code opens a file picker, photo picker or
   fingerprint/PIN prompt, it issues a one-time pass, valid for at most 2 minutes; the screen
   turning off always locks. Locking clears decrypted data from memory.
10. **Phone must have a screen lock.** If `KeyguardManager.isDeviceSecure` is false, show a
    screen explaining why and block the vault until the user sets a lock.
11. **Validate files.** Max 20 MB. Check the real type from the first bytes ("magic bytes"):
    JPEG, PNG, WEBP, PDF only. Reject anything else with a clear message.
12. **No personal data in logs.** Never log file names, document names or contents.
    Use a small `SafeLog` wrapper that only logs in debug builds and only fixed messages.
13. **Delete = crypto-shred.** Deleting a document deletes all its page rows (with their
    `wrappedKey`s, in one transaction) and then their `.bin` files; removing one page does the same
    for that page. "Delete everything" also deletes the Keystore master key, the
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
- `androidx.profileinstaller` (owner OK, Task 1.10m): installs the startup profiles AndroidX libraries ship; its exported `ProfileInstallReceiver` is removed from the manifest (rule 15)
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
| **1.8a Design system** | Section 5.14 (approved by owner first), then Theme, colours, type, shapes, reusable components; restyle Unlock, No screen lock and Add document | Owner approves light + dark screenshots of each screen |
| **1.8b Vault list + viewer** | List screen (document cards per 5.14), image viewer, PDF viewer with temp-file rules (rule 6), `TempFileCleaner`; viewer menu: Rename only (Delete is Task 1.10) | Viewing works; `view_tmp` is empty after closing and after restart |
| **1.9 Lock + safety** | Auto-lock (rule 9), FLAG_SECURE (rule 8), key-invalidated handling (rule 14). **Note:** the file pickers and the fingerprint/PIN prompt open other screens, which briefly puts Hey Buddy in the background; auto-lock must NOT lock the vault during these | Leaving the app locks it; screenshot is black; removing screen lock shows recovery screen |
| **1.10 Delete + settings + onboarding** | Delete one, Delete everything (rule 13), Settings screen, Onboarding screen (5.6). Also: a theme choice in Settings — "Follow phone" (default), "Light", "Dark". It is not secret, so it is stored outside the vault in normal app preferences, so the Unlock screen can use it too. Also: clean up orphan `.bin` files (a `.bin` with no row, left by a crash). **Note:** this cleanup must only run while no save is in progress, using the same database lock (`VaultDatabaseHolder.withDatabase`), because during a save a `.bin` exists briefly before its row | After "Delete everything": no files, no DB, no Keystore key; onboarding shows on first start and again after "Delete everything" |
| **1.10p Multi-page documents** | Owner request (found in the Task 1.10 test: Aadhaar front and back could not be one document). One document holds several ordered pages; **owner decision: each page is its own encrypted file** (not combined into one PDF). Needs a real database migration from version 1 to 2 (no destructive migration) with a migration test; per-page associated data binding document id AND page id (**owner decision at plan approval: page id, not page number**; the order lives in the encrypted database; every new page uses `document:<documentId>:page:<pageId>`, only migrated pages keep `document:<id>`); limits 10 pages and 50 MB per document (owner decision); Add screen with several pages, reorder and remove before saving; viewer swipes between pages and can add/remove a page; delete order, crash safety and orphan cleanup for page files. Plan to be approved by the owner before coding | Aadhaar front + back saved as one document, viewed page by page; old single-file documents still open after the migration; all tests pass |
| **1.10m Motion upgrade** | Owner request (split from 1.10). First propose a "Motion" section for 5.14 and get approval, then implement on all screens: (1) shared element transition: a document card grows into the viewer and back (Compose `SharedTransitionLayout`, stable in Compose 1.12.1); (2) Unlock: the lock shackle springs open on success, the icon shakes gently on failure, light haptic on both; (3) spring physics: buttons and cards press down slightly when tapped, the "+" button and dialogs open with a soft spring; (4) predictive back (Android 14+): `enableOnBackInvokedCallback` in the manifest, supported in `VaultNavigator`; (5) staggered card appearance on the vault list, first appearance only; (6) shimmer skeleton instead of a plain spinner for "Opening…" and while saving; (7) onboarding: cards slide with subtle parallax, hero icons have a small looping animation, page dots animate between pages; (8) success moment after saving and renaming: a self-drawing check mark plus a light haptic; (9) Settings: smooth expand/collapse, and the theme change cross-fades instead of flashing. **Motion rules:** only Compose's built-in animation APIs and animated vector drawables, no animation library (no Lottie) without asking; locking is ALWAYS instant and no animation may delay hiding documents; respect "Remove animations" everywhere; smooth on the owner's phone with no dropped frames (quick performance check, report any stutter); every animation 150–400 ms; calm and premium, no cartoon bounces or flashy effects, every animation has a purpose. Deliverables: all tests pass, screenshots, a short `adb screenrecord` video of the main flows using the screenshot test window only (FLAG_SECURE off only there, sample data only) | Owner approves the Motion section, the video and the manual test; no dropped frames reported |
| ~~1.11 Phase review~~ **MOVED to Phase 7, section 8 (owner decision, 2026-10-28)** | Run all tests, write `docs/PHASE1_REPORT.md` listing each rule in 5.5 and how it is met. **Reminder:** remind the owner to delete the `Download/HeyBuddyTest/` sample-files folder on the test phone, and ask before deleting it; also remind the owner that battery optimisation was turned off for Hey Buddy on the test phone for testing (Task 1.10), and offer to turn it back on | Owner approves the report |

### 5.11 Manual security checks (owner does these at the end of Phase 1)

**Moved (owner decision, 2026-10-28):** these checks are done before the first real user, see section 8.2.

1. **No internet:** check the merged manifest in Android Studio (Merged Manifest tab) — no INTERNET permission.
2. **Files are unreadable:** on a debug build, copy a `.bin` file off the phone (`adb shell run-as com.heybuddy ...`) and try to open it — it must look like random bytes.
3. **Screenshots blocked:** take a screenshot inside the app — it must be black.
4. **Recent apps hidden:** open recent apps — Hey Buddy's preview must be blank.
5. **Auto-lock:** go to home screen and come back — unlock must be asked again.
6. **Bad file:** rename a text file to `.pdf` and try to add it — it must be rejected.
7. **Delete everything:** after it, reopen the app — it must start from onboarding.

### 5.12 Phase 1 exit criteria

**Status (owner decision, 2026-10-28): Phase 1 is DONE, with checks deferred to Phase 7.** Tasks 1.1 to
1.10m are done and committed. Everything below that is not yet met moved to section 8.

- All tasks 1.1–1.11 done and approved
- All 15 security rules in 5.5 met and listed in `docs/PHASE1_REPORT.md`
- All manual checks in 5.11 pass on at least one real phone (not only the emulator)
- All items in 5.13 are tested and closed

### 5.13 Open test items

**All moved to section 8.1 (owner decision, 2026-10-28).** They are closed before the first real user,
not before Phase 2.

### 5.14 UI design system — APPROVED by owner (2026-10-27)

Owner decisions: Inter font; wallpaper colours off; Aadhaar in amber. Added by owner: an original
adaptive app icon (sapphire shield/locker shape, no text, no government symbols; foreground,
background and monochrome layers) and correct edge-to-edge handling of system bars and keyboard on
every screen.

**Feel:** calm, trustworthy, premium — like a good banking or password-manager app. Quiet surfaces, one strong brand colour, generous white space. Not playful: no cartoons, no bright gradients, no bouncy motion.

#### Colours

A fixed brand palette in both themes. The phone's wallpaper colours ("Material You") are switched OFF, so the app always looks the same and recognisably Hey Buddy.

| Role | Light | Dark | Used for |
| --- | --- | --- | --- |
| Primary | `#1E4D8C` deep sapphire | `#A8C8FF` | Main buttons, focus, selected items |
| On primary | `#FFFFFF` | `#0A2D5C` | Text/icons on primary |
| Secondary | `#3D5A73` slate | `#B4C8DC` | Secondary buttons, chips |
| Accent | `#0B6E60` deep teal | `#7AD6C4` | "Encrypted" label, success states |
| Background | `#F5F7FB` | `#0E1117` | Screen background |
| Surface | `#FFFFFF` | `#161A22` | Cards, sheets |
| Surface variant | `#EBEFF5` | `#242936` | Input fields, quiet containers |
| On surface | `#171A21` | `#E3E6EE` | Main text |
| On surface variant | `#454C5B` | `#B8BFCD` | Secondary text (type, date) |
| Outline | `#737B8C` | `#8A92A3` | Card borders, dividers |
| Error | `#B3261E` | `#FFB4AB` | Error text and banners |

**Measured contrast (WCAG):** all text pairs are between 7.2:1 and 17.4:1 (AA needs 4.5:1; most pass AAA's 7:1). Outline is 4.25:1 / 5.58:1 (non-text needs 3:1).

#### Document types: one colour and one icon each

Type colours are used for **icons only, never for text** (text always uses On surface / On surface variant). Each icon sits on a soft tint of its own colour (12% in light, 22% in dark).

| Type | Icon (Material Symbols) | Light | Dark | Icon contrast light / dark |
| --- | --- | --- | --- | --- |
| Aadhaar | `badge` | `#B45309` amber | `#FBBF24` | 4.25 / 6.33 |
| PAN | `credit_card` | `#4338CA` indigo | `#A5B4FC` | 6.48 / 5.49 |
| Driving licence | `directions_car` | `#0F766E` teal | `#5EEAD4` | 4.64 / 6.74 |
| Passport | `flight` | `#1D4ED8` blue | `#93C5FD` | 5.57 / 5.85 |
| Photo | `photo_camera` | `#7E22CE` purple | `#D8B4FE` | 5.71 / 5.96 |
| Signature | `signature` | `#BE185D` rose | `#F9A8D4` | 4.93 / 5.92 |
| Certificate | `workspace_premium` | `#15803D` green | `#86EFAC` | 4.27 / 6.96 |
| Other | `description` | `#475569` slate | `#CBD5E1` | 6.34 / 6.70 |

All pass the 3:1 rule for icons. Colour is never the only signal: every card also shows the type name in text.

#### Typography

- **Font: Inter** (version 4), by Rasmus Andersson. **Licence: SIL Open Font License 1.1** — free to bundle in a commercial app; the licence text ships with the app (an "Open-source licences" line in Settings, Task 1.10).
- Bundled as 3 font files in `res/font` (Regular 400, Medium 500, SemiBold 600), about 1.2 MB in total. **No downloadable fonts** — the app has no internet.
- Inter has no Devanagari letters; names typed in Hindi automatically fall back to the phone's own Devanagari font.
- All sizes in `sp`, so they grow with the phone's font-size setting.

| Style | Size / line height | Weight | Used for |
| --- | --- | --- | --- |
| Headline | 28 / 36 | SemiBold | Screen titles ("Your vault") |
| Title | 20 / 28 | SemiBold | Section and dialog titles |
| Card title | 16 / 24 | Medium | Document name |
| Body | 16 / 24 | Regular | Normal text |
| Body small | 14 / 20 | Regular | Type and date, helper text |
| Label | 14 / 20 | Medium | Buttons |
| Caption | 12 / 16 | Medium | "Encrypted" label, badges |

#### Spacing, shape and elevation

- **Spacing scale (dp):** 4, 8, 12, 16, 24, 32, 48. Screen side padding 24; space between cards 12.
- **Corner radius:** small 8 (badges, chips), medium 12 (inputs, banners), large 16 (cards, buttons), extra large 28 (dialogs, bottom sheets), full (round "+" button).
- **Cards:** Surface colour, 1 dp Outline border at low strength, no shadow (flat, calm). Pressed state: a light primary tint.
- **Elevation:** only the "+" button (level 3) and dialogs/sheets (level 3) cast a shadow. Everything else is flat.

#### Buttons

| Style | Look | Used for |
| --- | --- | --- |
| Primary | Filled Primary, 52 dp high, radius 16, Label text | One main action per screen (Unlock, Save to vault) |
| Secondary | Outlined, same size | Second choice (Choose a PDF or file) |
| Text | No border | Low-key actions (Cancel) |
| Floating "+" | Round, 56 dp, Primary, level 3 shadow | Add document (vault list) |

#### Icons

Only the Material Symbols (Outlined, weight 400) icons we use, copied as vector drawable XML files into `res/drawable`. **No icon library.** Material Symbols are by Google under the **Apache License 2.0** (listed with the font licence).

Planned set: the 8 type icons above, plus `lock`, `lock_open`, `fingerprint`, `add`, `arrow_back`, `more_vert`, `edit`, `check_circle`, `error`, `info`, `picture_as_pdf`, `image`, `chevron_left`, `chevron_right`, `shield` (about 23 files).

#### Motion — APPROVED by owner (Task 1.10m)

**Motion rules (everywhere):**

1. **Built-in tools only:** Compose's own animation APIs and Android animated vector drawables. No animation library (no Lottie) without asking the owner.
2. **Locking is ALWAYS instant.** No animation may delay hiding documents; on lock the screen changes at once and any running animation is cut off.
3. **"Remove animations" is respected everywhere:** every change becomes instant and loops stop (haptics follow their own rule below).
4. **Durations 150–400 ms.** Springs use no-bounce or very-low-bounce settings and settle within about 350 ms. **Loops (owner decision A):** each single movement lasts at most 400 ms, then rests 1.2 s; "Remove animations" stops every loop.
5. **Calm and premium:** no cartoon bounces or flashy effects; every animation has a purpose (where something came from, an action confirmed, or the app working).
6. **Smooth on the owner's phone:** a quick frame check (`adb shell dumpsys gfxinfo`) on the main flows; any stutter is reported.

**Haptics:** Android's built-in haptic feedback only (**no vibrate permission**). If the user has turned off touch vibration in the phone's settings, Hey Buddy gives **no** haptic feedback (owner decision, Task 1.10m).

**The 9 items:**

| # | Where | What happens | Time |
| --- | --- | --- | --- |
| 1 | List → viewer | The tapped card grows into the viewer; Back shrinks it into its card again. On lock: no reverse animation, instant. **Built as a "container transform"** (the viewer is revealed through a rounded window growing from the card to full screen while it fades in), **not with `SharedTransitionLayout`**: in the frame check on the owner's phone, `SharedTransitionLayout` doubled the cost (68 % slow frames vs 36 % for a plain fade); the container transform costs the same as a plain fade. Same look, same 350 ms, back-gesture preview still follows the finger. **Confirmed by the owner (Task 1.10m)** | 350 ms |
| 2 | Unlock | Success: the shackle springs open + light confirm haptic. **Real failure only** (lockout, too many attempts, key not usable): the icon shakes gently 3 times (4 dp) + light reject haptic. **Cancelling the prompt is a choice, not an error** (owner decision): no shake, no haptic, just the calm message | 300 ms / 300 ms |
| 3 | Buttons, cards, "+", dialogs | Pressed: sink to 97 %. The "+" button and dialogs open with a soft spring from 92 % | about 150 ms / about 250 ms |
| 4 | Predictive back (Android 14+) | System back-gesture preview on (`enableOnBackInvokedCallback`); a slow back swipe on the viewer, Add and Settings shows the screen behind, cancelling stays put. Tested on a real Android 14+ phone before the first real user (section 8.1; owner phone is Android 13; no emulator unless the owner asks) | follows the finger |
| 5 | Vault list | Cards fade in and rise one after another, first appearance only | 200 ms + 40 ms per card, at most 240 ms delay |
| 6 | "Opening…" and "Saving…" | A grey outline of the page/document with a soft light sweep instead of a spinner | one 400 ms sweep, 1.2 s rest |
| 7 | Onboarding | Cards slide with a slight depth effect; dots slide and stretch between pages; the hero icon has a small gentle loop | 300 ms slide; loop: one 300 ms nudge, 1.2 s rest |
| 8 | Saved / Renamed / page added | A self-drawing check mark + light confirm haptic | 400 ms |
| 9 | Settings | Sections expand/collapse smoothly (the Licences section opens and closes; it starts closed); the theme change cross-fades the whole screen instead of flashing (one picture of the old screen fades out over the new theme, so nothing is rebuilt per frame) | 250 ms / 300 ms |

**Frame check method:** a "profiling" build type (like release, not debuggable, debug-signed, not shrunk; never shipped) runs the motion script; each flow is played once unmeasured, then measured with `dumpsys gfxinfo`. A debug build gives misleading numbers for Compose.

**Tests:** "Remove animations" makes every change instant; locking from the viewer hides the document in the same frame; no animation starts on lock. The owner's phone blocks `adb screenrecord`, so the video was **skipped (owner decision, Task 1.10m)**; the scripted flows (`MotionVideoScript`) remain for frame checks.

#### Accessibility

- Every touch target at least **48 x 48 dp**.
- Every icon has a TalkBack description (decorative icons are marked as such, so they are skipped).
- Works with the phone's largest font size: text wraps instead of cutting off; no fixed-height text boxes.
- Contrast as measured above (AA everywhere, mostly AAA).

#### Rules

- **Never** use government logos, emblems or seals (Aadhaar logo, national emblem, Income Tax Department logo, etc.). Type icons are generic Material Symbols only.
- **No document thumbnails in the list** — a card shows icon, name, type and date only, so nothing sensitive is visible at a glance.
- Never show a document number or any document content outside the viewer.

#### Reusable components (built in Task 1.8a)

`PrimaryButton`, `SecondaryButton`, `DocumentCard`, `TypeBadge`, `EmptyState`, `MessageBanner` (info / success / error), `LoadingState`.

---

## 6. Open decisions (owner to answer before the phase that needs them)

| Decision | Needed before | Current default |
| --- | --- | --- |
| Play Store only, or also a direct-download build? | Phase 3 | Play Store only |
| Wake word in version 1, or push-to-talk only? | Phase 6 | Push-to-talk only |
| Lowest phone to support (decides if AI is required or optional) | Phase 5 | AI optional; rules must work alone |
| ~~Store full Aadhaar number, or masked only by default?~~ **DECIDED (owner, 2026-10-28):** only the last 4 digits by default; the full number only if the user explicitly chooses it; always shown masked | Phase 2 | — |

## 7. PHASE 2 — Document reader (APPROVED by owner, 2026-10-28)

### 7.1 Goal in one sentence

Hey Buddy reads the user's own documents on the phone (camera or vault) in **English, Hindi and
Tamil**, finds the important facts (name, date of birth, PAN, Aadhaar, address), and saves **only
the facts the user has checked and confirmed**, linked to the page they came from, without ever
going online.

**Tamil is a REQUIRED feature of Phase 2 (owner decision, 2026-10-28)**, not optional: Tamil Nadu
documents and certificates must be readable.

### 7.2 Simple picture

```
 Camera (in-app)  or  a page already in the vault
            │  image only in memory
            ▼
   OCR on the phone, both engines bundled, text only in memory, never saved:
     ML Kit  → English + Hindi        Tamil engine (Task 2.3) → Tamil
            └──── merged per page through the TextReader interface ────┘
            ▼
   Document type guess  →  fact rules (PAN, Aadhaar + Verhoeff, dates, name, address)
            ▼
   Confirm screen: the user checks, edits or rejects EVERY fact
            ▼
   Encrypted database: confirmed facts only (Aadhaar: last 4 digits unless the user chooses more)
```

### 7.3 Security rules for Phase 2 (in addition to all of 5.5)

1. **Still no internet.** OCR runs fully on the phone with models bundled in the app; nothing is
   downloaded, at install or later. ML Kit includes Google usage-logging parts
   (`transport-backend-cct`, `transport-runtime`); they ask for INTERNET and ACCESS_NETWORK_STATE.
   Both permissions and the logging job and receiver are removed from the manifest, so nothing
   can be sent. A phone test checks that the installed app holds no network permission and that
   only the launcher activity is exported. **The Tamil engine (Task 2.3) must meet the same rule:**
   bundled models, fully offline, no download and no network part.
2. **Images and OCR text live only in memory.** No photo is ever written to disk or to the gallery
   unencrypted; a captured photo becomes a pending page (the Task 1.10p encrypted pipeline).
   OCR text is never saved and never logged; it is dropped when the confirm screen closes, on
   Cancel, and at once on lock.
3. **Nothing unconfirmed is stored.** Only facts the user confirmed on the confirm screen reach the
   database (SQLCipher, rule 5).
4. **Aadhaar (owner decision, 2026-10-28):** only the **last 4 digits** are stored by default. The
   full 12-digit number is stored only if the user explicitly ticks "Also save the full number";
   it is **always shown masked** (XXXX XXXX 1234). The full number is never logged.
5. **Camera permission** is asked only when the user taps "Take a photo", with a plain reason.
   If refused, everything else still works (photo and file pickers).
6. **The user always decides** (product rule): the reader suggests; the user confirms.

### 7.4 OCR: ML Kit Text Recognition v2, bundled

| Topic | Answer |
| --- | --- |
| Library | **Approved:** `com.google.mlkit:text-recognition-devanagari:16.0.1` (bundled). Its recognizer reads **Devanagari (Hindi) and Latin (English) together**, so one recognizer covers an Aadhaar card. `com.google.mlkit:text-recognition:16.0.1` (Latin only) was measured in Task 2.2 and is **not used** (owner decision, see "Measured (Task 2.2)") |
| Bundled vs downloaded | **Bundled**: the models are inside the app. The "unbundled" variant downloads models through Google Play services, which needs internet; we never use it. Works in airplane mode, without Google Play services |
| Size (measured from the packages) | OCR engine: 10.6 MB for 64-bit phones (6.5 MB for older 32-bit). Models: about 1–3 MB. **About 13–15 MB more on a phone like the owner's.** An APK with both phone types (64 and 32-bit) grows by about 20 MB; a Play app bundle sends each phone only its own type. We keep only `arm64-v8a` and `armeabi-v7a` (no x86, which only emulators use) |
| Licence | Free to use, but **not open source**: ML Kit is covered by Google's ML Kit Terms of Service (Google APIs Terms). It bundles open-source parts listed in its `third_party_licenses.txt`, which the Settings → Licences screen will show |
| Brought along | Google Play services base and tasks, ML Kit common, Firebase components, Google data-transport (the usage logging blocked by rule 7.3-1) |
| Measured (Task 2.1) | App (both phone CPU types, profiling build) **37.98 MB → 55.82 MB (+17.8 MB)**, after the CPU filter also dropped the old x86 database copies. ML Kit itself: engine 10.6 MB (64-bit) + 6.5 MB (32-bit), models about 2.5 MB. **A phone like the owner's would download about 14 MB more** from a Play bundle |
| Removed from the manifest (Task 2.1) | `ACCESS_NETWORK_STATE` (INTERNET was already removed), and the data-transport `TransportBackendDiscovery` and `JobInfoSchedulerService` services and `AlarmManagerSchedulerBroadcastReceiver`. Kept (needed, not exported): `MlKitComponentDiscoveryService`, `MlKitInitProvider`, `GoogleApiActivity`. `NoNetworkAndExportsTest` checks the installed app on the phone |
| First reading (Task 2.1 smoke test) | Hindi "नमस्ते" read exactly; English "HEY BUDDY 2026" (default Android font) read as "HEY BUODY2026", which is an early sign for the Latin question in Task 2.2 |
| Measured (Task 2.2) | On the owner's phone, 15 generated fake samples (3 card styles × clean, tilted, shadow, blurred, low light). **Devanagari recognizer, no preparation:** clean: English 99.8 %, Hindi 97.5 %, names 3/3, dates 3/3, numbers 2/3, about 600 ms a page; photo-style: English 98.8 %, Hindi 94.0 %, names 12/12, dates 12/12, numbers 8/12, about 410 ms. **Latin only:** same names, dates and numbers, English within 0.5 points, Hindi 52–54 % (turned into look-alike garbage). **Both merged:** nothing more found, about twice the time. Every Aadhaar and PAN number was read exactly; all number misses were the licence number "TN00" read as "TNO0" (fixed by position in Task 2.6). Two fake internet sample pictures (Aadhaar-style, PAN-style) gave the same result. Latin model size: +0.04 MB |
| Image preparation (Task 2.2) | Measured: scaling to 2048 px, grey with stretched contrast, and both. None was clearly better; scaling and contrast lost some names and dates. **Decision: no preparation** (pages are only decoded at a long side of 2048 px); the preparation code is kept in test code for later measurements. **No perspective straightening** (tilted samples read fine) |
| Tamil lines through ML Kit (Task 2.2) | Each Tamil line becomes 2–4 lines of garbage (mixed Bengali/Devanagari look-alikes). None looked like a name, date, PAN or Aadhaar number, but once a Tamil address line produced a wrong PIN code. **Rule:** the fact rules (2.6, 2.7) never take facts from ML Kit lines that stand where the Tamil engine found Tamil text. The Devanagari recognizer sometimes writes Bengali look-alike digits ("199০"); the fact rules map Indic digits to ASCII before checking numbers |
| Real phone-camera test | **Not done yet** in Task 2.2 (no phone photos of the generated samples). Moved to Task 2.4, and to 8.1 in case it is not closed there |
| Internet needed? | **No.** Recognition is on the phone; there is no model download; the only network part (usage logging) cannot send anything, because the app holds no internet permission and those parts are removed |

### 7.4a Tamil reading engine (Task 2.3, chosen by measurement)

ML Kit has no Tamil recognizer, so Tamil needs a second, fully offline engine with its models
inside the app. The engine is **picked by measurement, not by guess**, comparing:

| | (a) Tesseract (for example Tesseract4Android) + Tamil model `tam` | (b) PaddleOCR Tamil recognition model on ONNX Runtime Mobile |
| --- | --- | --- |
| What it is | Classic open-source OCR engine with a trained Tamil language file | Small neural text detector + Tamil recognizer, run by Microsoft's ONNX Runtime |
| Expected licences (to confirm in 2.3) | Apache 2.0 (engine and `tessdata` models) | Apache 2.0 (PaddleOCR models) + MIT (ONNX Runtime) |

**Measured on made-up sample Tamil documents only** (never real ones):
1. a Tamil Nadu style certificate (printed, form layout),
2. a Tamil name and address block,
3. a phone photo of a sample with a slight angle and a shadow.

**Measured for each engine:** character accuracy (against the known sample text), speed per page on
the owner's phone, app size increase, licence, and that it needs **no internet at all** (installed
app holds no network permission; works in airplane mode). Results go in a table with a
recommendation, and **the owner is asked before either library is added**. The measuring code for
the engine that is not chosen is removed again.

**Measured (Task 2.3, owner's phone, 16 fake Tamil samples: a Tamil Nadu style certificate form and a
Tamil name and address block in clean, tilted and shadow looks, and the Tamil lines of the Aadhaar-style
and licence-style cards in 5 looks; each engine in a fresh process).** Full lines read:
`docs/measurements/task-2.3-what-each-engine-read.txt`.

| | **Tesseract 5.5.1 + `tam` fast (CHOSEN)** | Tesseract + `tam` best | PaddleOCR PP-OCRv5 mobile on ONNX Runtime 1.31.1 | ML Kit alone (no Tamil) |
| --- | --- | --- | --- | --- |
| Tamil characters, all 16 | 95.4 % | 95.7 % | 91.5 % | 23.7 % (garbage) |
| Tamil characters, clean / photo-style | 99.3 % / 94.2 % | 99.7 % / 94.3 % | 85.2 % / 93.6 % | 22.3 % / 24.1 % |
| Tamil name read exactly | 15/16 | 15/16 | 12/16 | 0/16 |
| PIN code read exactly | 16/16 | 16/16 | 16/16 | 14/16 |
| Time per page | 0.6 s | 0.7 s | 2.3 s | 0.35 s |
| First-time loading | 0.7 s | 0.9 s | 3.2 s | 1.0 s |
| Peak extra RAM | 58 MB | 68 MB | 318 MB | 131–174 MB |
| Size, 64-bit phone | +10.7 MB (7.5 engine + 3.2 model) | +13.5 MB | +47 MB (34.3 engine + 12.7 models) | — |
| Licence | Apache 2.0 (+ Leptonica BSD-2, libjpeg, libpng) | same | Apache 2.0 (models) + MIT | ML Kit terms |
| Network | none | none | none | none |

Notes: PaddleOCR splits these Tamil lines into single words at every picture size tried, and a split
word can lose its left vowel sign (டெமோ → டமோ); its official models (Hugging Face, PaddlePaddle) were
converted with paddle2onnx 2.1.0 on PaddlePaddle 3.1.1 (3.3.1 does not load the converter on
Windows); checksums are kept on the owner's PC (HeyBuddyTools). With the shared cleanup below,
Tesseract fast reads the clean samples at 100 % (Task 2.3, final run).

**Owner decision (2026-10-29):** Tesseract with the **fast** Tamil model. PaddleOCR, ONNX Runtime and
the "best" model are removed. Tesseract4Android is **built by us from its official source** (no
JitPack): exact commit, checksum and rebuild steps in `docs/TESSERACT_BUILD.md`; the app's build stops
if the file differs. The Tamil model is the official `tessdata_fast` `tam.traineddata`, stored
uncompressed in the app's assets and copied on first use to `noBackupFilesDir/ocr_models/tessdata/`
(the public model only, never anyone's data).

**Merging (owner rule, Task 2.3):** both engines sit behind `TextReader`; `MergedTextReader` runs
ML Kit, then Tesseract (one after the other, so only one engine's working memory at a time), and
`LineMerge` decides per spot. Two lines are in the same spot when they share more than half of the
lower line's height and overlap from left to right.
- ML Kit's line is **GARBAGE** if it has letters of any script other than Latin or Devanagari (its
  look-alikes for Tamil are often Bengali), or ML Kit's own confidence is below 0.5. It is **REAL** if
  it has only Latin/Devanagari letters and confidence ≥ 0.7 (or no confidence). Otherwise UNSURE.
- Tesseract's line is **SURELY TAMIL** if at least 80 % of its letters are Tamil and Tesseract's
  confidence is ≥ 0.8.
- ML Kit REAL and Tesseract not surely Tamil → keep ML Kit's line only (Tesseract's garbage over
  English/Hindi). Tesseract surely Tamil and ML Kit GARBAGE → keep Tesseract's line only.
  **Anything else → keep BOTH.** A line with nothing on top of it is always kept. Never drop a line
  that might be real.
- The limits come from the confidences measured on 31 fake samples: ML Kit's real English/Hindi
  0.71–0.94, its Tamil garbage 0.18–0.64; Tesseract's real Tamil 0.88–0.96, its garbage 0.00–0.72.
  Replayed on all samples, no real line of either engine was dropped, and ML Kit's wrong PIN
  ("…647001" for 641001, Task 2.2) is always replaced by Tesseract's correct line.
- Merged reader, measured: Tamil 95.7 % (clean 100 %), English and Hindi unchanged, about 1.1 s a
  page, 1.6 s first time, about 163 MB peak extra RAM for both engines together.

**Tamil text cleanup (owner rule):** one shared function, `TamilText.clean`, used everywhere Tamil is
handled (reading results now; typed fields in 2.10; fact matching in 2.6 and 2.7): removes the
invisible joiners Tesseract writes after each pulli, puts a left vowel sign (ெ ே ை) written before
its consonant back after it ("தந்‌ைத" → "தந்தை"), and applies Unicode NFC.

**Still open:** the Tamil scores on real phone-camera photos (Task 2.4, with the camera test; and 8.1).

### 7.5 Camera: in-app CameraX (recommended) vs the system camera app

| | (a) CameraX inside Hey Buddy — **recommended** | (b) The phone's own camera app |
| --- | --- | --- |
| Where the photo goes | Straight into memory (`ImageCapture` with an in-memory callback); we turn it into JPEG bytes and encrypt it as a pending page. **Never a file, never the gallery** | The camera app must write a full photo to a file we give it (plaintext on disk for a moment), and some phone makers' camera apps **also save a copy to the gallery**, which we cannot prevent or even detect |
| Screen protection | Our own screen: FLAG_SECURE, auto-lock and overlay protection stay on | Another app's screen: our protections do not apply while it is open |
| Permission | Needs the CAMERA permission (asked when the user taps "Take a photo") | No permission, but a FileProvider and a one-time grant |
| Work | More: preview, capture button, flash, simple framing guide | Less |
| Licence | Apache 2.0 (AndroidX) | — |

**Decision (owner, 2026-10-28): (a) CameraX inside the app, capture to memory only**, because rule 7.3-2 (never on disk or in the gallery unencrypted)
can only be guaranteed when the photo never leaves our app's memory.

**How it is built (Task 2.4):**
- **Libraries:** CameraX 1.6.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-compose`), approved in 7.11.
- **Capture:** `ImageCapture` with the **in-memory** callback (capture size asked: 3072 x 2304). The camera's
  JPEG is decoded, turned upright, shrunk to at most **3072 px** on the long side and re-saved as JPEG
  **quality 90** (owner decision). Re-saving writes the pixels only, so **all photo metadata (EXIF: time,
  phone, location) is removed**. The camera's own JPEG is wiped at once; the new one goes into
  `PagePreparer.prepareCaptured`, which shares the type check, thumbnail, storage check and encryption of a
  picked file (pending page, Task 1.10p) and wipes the bytes.
- **No crop:** the guide frame (Card or A4 Page shape) is only a guide; the photo is kept whole (owner
  decision; cropping comes before uploads, Phase 4 note).
- **Permission:** `CAMERA`, asked only after "Take photo" and our own short explanation. `uses-feature
  camera.any required=false`: phones without a camera can install the app and never see "Take photo".
  Because Android cannot tell "Don't ask again" apart from "turned off in Settings" BEFORE asking, our
  explanation always comes first when the permission is missing; "Don't ask again" is decided from
  Android's answer (denied and no rationale), and then the screen offers **Choose a photo instead** and
  **Open settings** (with the usual one-time auto-lock pass). A plain "No" offers Choose a photo and Ask
  again. CAMERA is the only permission that needs the user's yes (phone test).
- **Hints (measured, advice only):** brightness = average light value; sharpness = variance of the
  Laplacian on a 640 px wide picture. Live: the middle value of the last 8 preview frames gives "More
  light needed" or "Hold steady"; after the shot: "More light needed" or "Too blurry, try again". The
  limits (60 and 60) are PROVISIONAL until the real-camera measurement (8.1). Each preview frame is measured
  and **closed at once**; none is kept or queued (CameraX keeps only the newest; a phone test proves
  frames keep flowing, and a fake frame proves close-after-measure even on errors).
- **Safety:** the vault lock closes the camera at once and wipes any photo, also one that finishes after
  the lock; leaving the screen releases the camera; the preview is drawn inside our window (EMBEDDED
  mode, not a separate surface), so FLAG_SECURE covers it (phone screenshot test is black).
- **Measured (Task 2.4):** a camera page in the phone test (camera facing a plain wall) was 349 KB; a
  printed document has more detail, so expect a few hundred KB to about 1 MB a page.
- **Testing note:** the owner's Realme phone refuses `pm grant` / `pm revoke` from the PC ("shell has no
  GRANT_RUNTIME_PERMISSIONS"), so the camera permission is given once through the app itself, and the
  deny paths are covered by computer tests, screenshots and manual checks.

### 7.6 Document type detection (rules on the OCR text)

A pure Kotlin function scores the text for each type and suggests the best one; the user can change it.

- **Aadhaar:** "Aadhaar" / "आधार", "Government of India" / "भारत सरकार", "Unique Identification
  Authority", "VID", plus a 12-digit number in groups of 4 that passes Verhoeff (7.7).
- **PAN:** "Income Tax Department" / "आयकर विभाग", "Permanent Account Number", plus a valid PAN (7.7).
- **Passport:** "Republic of India", "Passport", or a machine-readable line starting `P<IND`.
- **Driving licence:** "Driving Licence", "DL No", "Transport Department", "Union of India".
- Tamil words count too, for example "இந்திய அரசு" (Government of India), "தமிழ்நாடு அரசு"
  (Government of Tamil Nadu), "சான்றிதழ்" (certificate).
- **Tamil Nadu certificates (owner decision, 2026-10-28) are target document types of Phase 2:**
  community certificate (சாதிச் சான்றிதழ்), income certificate (வருமானச் சான்றிதழ்), nativity
  certificate (இருப்பிடச் சான்றிதழ்), and school / transfer certificate (மாற்றுச் சான்றிதழ்). They
  are added to the document type list in **Task 2.11**, with a database version step and a real,
  tested migration. (The type is stored as its name in text, so the new values may need no table
  change; the task still raises the version with a real migration and a migration test, so older
  data is checked and stays valid.)
- Otherwise **Other**. A weak score means no suggestion (the user picks).

### 7.7 Fact extraction rules (rules first, no AI yet)

Every rule is a small pure function with computer tests on made-up sample text (never real data).

| Fact | Rule, simply |
| --- | --- |
| **PAN** | 10 characters: 5 letters, 4 digits, 1 letter (`ABCDE1234F`). The 4th letter tells the holder type (P = person, C = company, H = family business, F = firm, T = trust …); for a personal card it must be P. OCR mix-ups are fixed only by position: in a letter position 0→O, 1→I, 5→S, 8→B; in a digit position the reverse. If it still does not fit, we do not guess |
| **Aadhaar number** | 12 digits, usually shown as 4-4-4; it never starts with 0 or 1. **Verhoeff check:** the last digit is a check digit worked out from the other 11 with a fixed table. If the OCR misreads any one digit, or swaps two neighbouring digits, the check fails, so we know the number is wrong and ask the user instead of guessing. 16-digit VIDs, 14-digit enrolment numbers and already masked numbers (XXXX XXXX 1234) are recognised and never taken as the Aadhaar number |
| **Date of birth** | Day first, as on Indian documents: `DD/MM/YYYY`, `DD-MM-YYYY`, `DD.MM.YYYY`, and "Year of Birth: YYYY" (older Aadhaar: year only). Must be a real date, not in the future, age 0–120. A date next to "DOB", "Date of Birth" or "जन्म तिथि" wins over other dates (such as issue dates) |
| **Name** | Aadhaar front: the English line just above the date-of-birth line (the Hindi line above it is skipped). PAN: the line after "Name" / "नाम" (new card) or the first name line under "INCOME TAX DEPARTMENT" (old card). Only letters, spaces and dots, 2–5 words. Always marked "please check" |
| **Father's name** | PAN: the line after "Father's Name" / "पिता का नाम" (or the second name line on old cards) |
| **Gender** | "MALE" / "FEMALE" / "TRANSGENDER", or "पुरुष" / "महिला" |
| **Address** | Aadhaar back: the text after "Address:" / "पता:" up to and including the PIN code, lines joined |
| **PIN code** | 6 digits, first digit 1–9, normally at the end of the address |
| **Tamil labels** | Every label rule also knows the Tamil label: **பெயர்** (name), **தந்தை பெயர்** (father's name), **பிறந்த தேதி** (date of birth), **முகவரி** (address), **பாலினம்** (gender), with Tamil gender words **ஆண்** (male), **பெண்** (female), **மூன்றாம் பாலினம்** (third gender) |
| **Tamil dates** | Dates with Tamil month names are understood: ஜனவரி, பிப்ரவரி, மார்ச், ஏப்ரல், மே, ஜூன், ஜூலை, ஆகஸ்ட், செப்டம்பர், அக்டோபர், நவம்பர், டிசம்பர் (for example "15 ஆகஸ்ட் 1990"). Tamil digits (௦–௯), if printed, are read as 0–9. Traditional Tamil calendar months (சித்திரை …) belong to a different calendar and are never converted; such a date is left for the user |

### 7.8 Aadhaar Secure QR (separate, later task: 2.12)

Yes, it can be read **and verified fully offline**. The QR on Aadhaar cards and e-Aadhaar (since
2019) holds a very large number: turned into bytes and unzipped, it gives the details (reference
id with the last 4 Aadhaar digits, name, date of birth, gender, address, photo) and, at the end, a
**digital signature by UIDAI**. We check that signature with UIDAI's public certificate,
which we put inside the app (taken once from uidai.gov.in at build time; no internet when used).
A valid signature proves the details are exactly what UIDAI issued, so these facts can be marked
"verified". It is complex (a dense QR, an exact byte format, certificate updates; the photo is
JPEG 2000, which Android cannot show, so it is not kept), so it is a **separate task after the
OCR flow works**. Older QR codes (before 2019) carry no signature and are treated as unverified.
Library decision at that task: ZXing core (open source, Apache 2.0, small, pure Java) or ML Kit
Barcode Scanning (bundled; 4.7 MB engine on 64-bit phones, same usage-logging parts as 7.4).
**Recommended: ZXing core.**

### 7.9 Confirm screen

- Shows each found fact with what it is ("Date of birth") and where it came from ("Aadhaar card, page 1").
- Every fact has: the value in an editable field, **Keep** (default ON only when the rule is sure, for
  example a PAN in the right format or an Aadhaar number that passes Verhoeff) and **Remove**.
  Facts marked "please check" (names, addresses) start unticked until the user looks at them.
- Aadhaar: shown as XXXX XXXX 1234; a separate tick box "Also save the full number" is OFF by default.
- One button: **Save checked details**. Back or Cancel saves nothing. Lock discards everything at once.
- Saving needs no fingerprint/PIN beyond the unlocked vault (the facts go into the already-open
  encrypted database).

**"My details" (owner decision, 2026-10-28):** a separate tab on the vault screen,
**"Documents | My details"**, using the approved design system (5.14) and motion. It lists every
confirmed fact (Aadhaar always masked), with its source ("Aadhaar card, page 1" or "page deleted"),
and lets the user edit or delete each one.

**"My details" profile page, in detail (owner's profile-page request, confirmed in Task 2.7):**
1. **Sections, in this order:** **Personal** (name, name in Tamil, date of birth, gender), **Family**
   (father's name, father's name in Tamil, husband's name, other relative from "C/O" / "S/D/W of"),
   **Identity numbers** (Aadhaar, PAN, driving licence number), **Address** (address, address in
   Tamil, PIN code), **Other** (anything else). Empty sections still show their typed-field
   offers (7.9) where they have one, otherwise they are hidden.
2. **"From: <document>"** under each detail, for example "From: Aadhaar card, page 1"; "From: typed
   by you" for typed values; "From: page deleted" when the source was deleted but the detail kept.
3. **Mismatch warning:** when two documents give DIFFERENT values for the same detail (for example
   two dates of birth), the detail shows a calm warning ("Your documents disagree") and lists each
   value with its source; the user **picks the correct one** (stored as the chosen value, 7.10). The
   others stay, marked "not chosen", until the user deletes them. Comparison ignores spacing, case
   and the Tamil cleanup differences (TamilText.clean).
4. **Completeness line** at the top, for example "6 of 9 details filled", counting the main details
   (name, date of birth, gender, father's name, Aadhaar, PAN, address, PIN code, name in Tamil).
5. **Masked numbers:** Aadhaar is always shown masked ("XXXX XXXX 1231"); the full number, when the
   user chose to store it (7.3-4), is **revealed only after "Confirm it's you"** (fingerprint/PIN) and
   hides again when the screen is left or the vault locks. PAN and licence numbers follow the same
   reveal rule.
6. **No copy to clipboard for ID numbers** (Aadhaar, PAN, licence): no copy button, and the text is not
   selectable, because other apps can read the clipboard. Names and addresses may be copied.

**Typed Tamil fields (owner decision, 2026-10-28):** "My details" always offers **Name in Tamil**,
**Father's name in Tamil** and **Address in Tamil**, which the user can type (or correct) by hand,
so Tamil works even when a photo is hard to read. Typed values are stored with origin `TYPED` and
need no source page.

### 7.10 Data model: profile facts (database version 3, real migration)

New table `profile_facts`:

| Column | Type | Notes |
| --- | --- | --- |
| `factId` | String (UUID) | Primary key |
| `kind` | String (`FactKind`) | `FULL_NAME`, `FATHER_NAME`, `HUSBAND_NAME`, `RELATIVE_NAME`, `DATE_OF_BIRTH`, `YEAR_OF_BIRTH`, `GENDER`, `PAN`, `AADHAAR_LAST4`, `AADHAAR_FULL`, `DRIVING_LICENCE_NUMBER`, `ADDRESS`, `PIN_CODE`, and the Tamil fields `FULL_NAME_TAMIL`, `FATHER_NAME_TAMIL`, `ADDRESS_TAMIL` (husband/relative and licence number added in Task 2.7 from the fact rules) |
| `value` | String | As confirmed by the user (dates stored as `YYYY-MM-DD`) |
| `documentId` | String, nullable | The source document (null for facts the user typed in "My details") (foreign key, set to null if the user keeps the fact when the document is deleted) |
| `pageId` | String, nullable | The page it came from (foreign key to `document_pages`, set to null if the user keeps the fact when the page is deleted) |
| `sourceDeletedAt` | Long, nullable | Set when the source page/document was deleted but the user kept the fact; shown as "page deleted" |
| `origin` | String | `OCR`, `QR_VERIFIED` (Task 2.12) or `TYPED` (the user corrected it) |
| `chosen` | Boolean | True for the value the user picked when documents disagree ("My details" mismatch warning); true by default when there is only one value of that kind |
| `confirmedAt` / `updatedAt` | Long (epoch ms) | |

- Index on `documentId` and on `pageId`.
- **Migration 2 → 3**: only adds the table (no existing data changes), tested like `MIGRATION_1_2`
  (a real version-2 database built from `2.json`, opened by the app, checked by Room).
- **Deleting a source (owner decision, 2026-10-28):** when a page or document that facts were read
  from is deleted, its delete dialog also asks **"Also delete the N details read from this page?"**
  (or "…from this document?") with **Yes selected by default**.
  - **Yes:** the facts are deleted in the same transaction as the page/document rows.
  - **No:** the facts are kept; their `pageId`/`documentId` become null and `sourceDeletedAt` is set,
    so "My details" shows their source as **"page deleted"**.
  - Dialogs for pages/documents with no facts stay exactly as they are now.

### 7.11 New libraries (each needs the owner's approval)

| # | Library | Version (latest stable) | Licence | Why |
| --- | --- | --- | --- | --- |
| 1 | `com.google.mlkit:text-recognition-devanagari` (bundled) — **APPROVED** | 16.0.1 | ML Kit Terms (free, closed source) | Hindi + English OCR on the phone |
| 2 | `com.google.mlkit:text-recognition` (bundled, Latin) — **NOT USED** (owner decision after the Task 2.2 measurement: not clearly better) | 16.0.1 | ML Kit Terms | — |
| 3 | `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-compose` — **APPROVED** | 1.6.2 | Apache 2.0 | In-app camera, photo straight to memory |
| 4 | Tamil engine: **Tesseract4Android, built by us from source** (`app/libs/`, see docs/TESSERACT_BUILD.md) + the official `tam` fast model — **APPROVED** (owner, Task 2.3). PaddleOCR/ONNX Runtime were measured and NOT added | 4.9.0 (Tesseract 5.5.1) | Apache 2.0 (+ Leptonica BSD-2, libjpeg, libpng) | Tamil reading, fully offline |
| 5 | `com.google.zxing:core` — **ask the owner at Task 2.12** | latest stable at that time | Apache 2.0 | Reading the Aadhaar Secure QR offline |

Library 1 brings along Google Play services base/basement/tasks, ML Kit common and vision
common, Firebase components and encoders, and Google data-transport (blocked, see 7.3-1).
No JSON, network or analytics library is added on purpose. **Owner rule:** the ML Kit
network/logging parts stay stripped, and a phone test proves the installed app holds no network
permission.

### 7.12 Task list (do in order, stop after each; plan first, as in Phase 1)

| Task | What | Done when |
| --- | --- | --- |
| **2.1 OCR library and privacy guard** | Add the bundled ML Kit recognizer; remove ACCESS_NETWORK_STATE and the data-transport job/receiver from the manifest; keep only `arm64-v8a` and `armeabi-v7a`; phone test: installed app has no network permission, only the launcher is exported; Licences screen shows ML Kit's third-party licences; report the real size added | Test passes; app size growth reported; airplane-mode check by the owner |
| **2.2 Text reader** | `TextReader` (an interface, so the Tamil engine of 2.3 plugs in next to ML Kit): reads one page (photo bytes in memory, or a PDF page drawn with PdfRenderer) and returns lines with their positions; memory only; wiped after use; never logged. Phone tests on made-up sample images (rendered English and Hindi text). Compare Devanagari-only vs adding Latin on the same samples | Sample text read correctly; decision on library 2 |
| **2.3 Tamil reading engine** | Compare (a) Tesseract + `tam` and (b) PaddleOCR Tamil + ONNX Runtime Mobile on three made-up Tamil samples (7.4a): character accuracy, speed on the owner's phone, size, licence, no internet. Table + recommendation; **ask the owner before adding either library**; then build the chosen engine behind `TextReader` and merge its Tamil lines with ML Kit's per page | Owner picks the engine from the measured table; Tamil sample text read with the measured accuracy; still no network permission |
| **2.4 In-app camera** | CameraX screen: preview, capture to memory, framing guide, flash; CAMERA permission asked only on "Take a photo", with a plain reason and a working path when refused; the photo becomes a pending page (Task 1.10p pipeline); camera closes on lock and in the background; FLAG_SECURE stays | Photo saved as an encrypted page; nothing in the gallery or on disk (checked); lock closes the camera; **real phone-camera reading test** (from Task 2.2): the owner photographs the 3 generated clean fake samples with the phone camera and they are measured like Task 2.2 (character accuracy, fields found, time) as a "real photo" group, and the Tamil scores re-checked on real photos of the Tamil samples (from Task 2.3) — **moved to 8.1 by the owner (2026-10-30); the measuring screen is built** |
| **2.5 Document type guess** | Pure rules (7.6) + computer tests | Each sample type guessed; unknown stays Other |
| **2.6 Facts part 1: numbers and dates (including Tamil month names)** | PAN rule, Aadhaar rule with Verhoeff, VID/enrolment/masked exclusion, date rules with Tamil month names and Tamil digits (7.7) + computer tests (including OCR mix-ups) | All rule tests pass |
| **2.7 Facts part 2: name, gender, address (English, Hindi and Tamil labels)** | Heuristics (7.7) for Aadhaar front/back and PAN old/new layouts, Tamil labels (7.7), Tamil-script names and addresses + computer tests on made-up samples | All rule tests pass |
| **2.8 Profile facts storage** | Table, `FactKind`, migration 2 → 3 with test, repository (save confirmed facts, list, edit, delete), Aadhaar storage rule (7.3-4), the delete-source rule (7.10: "Also delete the N details…?" in the page and document delete dialogs, Yes by default; No keeps them as "page deleted") | Migration test passes; old vault still opens; both delete choices tested |
| **2.9 Confirm screen** | 7.9, with masked Aadhaar and the "full number" tick box; Save stores only kept facts; lock/cancel discard everything | Nothing unconfirmed stored (test); manual test |
| **2.10 Entry points and "My details" tab** | "Read details" after saving a document and in the viewer menu; the vault screen gets two tabs, **"Documents | My details"** (design system and motion of 5.14); "My details" as the profile page of 7.9: sections Personal, Family, Identity numbers, Address, Other; "From: <document>" under each detail; mismatch warning with picking the correct value; completeness line; masked numbers revealed only after "Confirm it's you"; no copy to clipboard for ID numbers; edit, delete; the typed Tamil fields (Name, Father's name, Address in Tamil) | Owner reads a sample Aadhaar, PAN and a Tamil sample end to end; typed Tamil fields saved |
| **2.11 Tamil Nadu certificate types** | Add community, income, nativity and school/transfer certificate to the document types (labels, colours and icons from the design system), with a database version step, a real migration and a migration test; detection rules (7.6) for them | Types selectable and detected on samples; migration test passes; old vault opens |
| **2.12 Aadhaar Secure QR (later)** | 7.8: scan, decode, verify the UIDAI signature offline, mark facts verified | Signed QR verified; tampered QR rejected |
| **2.13 Phase 2 review** | All tests, a short Phase 2 report, size and privacy summary | Owner approves |

### 7.13 Owner decisions (2026-10-28)

1. Libraries: ML Kit Devanagari 16.0.1 and CameraX 1.6.2 approved now; ML Kit Latin only if Task 2.2
   shows it reads English clearly better (ask then); ZXing at Task 2.12 (ask then). ML Kit
   network/logging parts stay stripped, proven by a phone test.
2. Camera: CameraX inside the app, capture to memory only.
3. Deleting a source page/document asks "Also delete the N details read from this page?" (Yes by
   default); No keeps the facts marked "page deleted" (7.10).
4. "My details" is a separate tab on the vault screen: "Documents | My details".
5. **Task 2.2 (2026-10-28):** ML Kit Latin model NOT added; no image preparation in the app (kept
   in test code); no perspective straightening; the real phone-camera test moves to Task 2.4 and 8.1.
6. **Task 2.3 (2026-10-29):** Tamil engine = Tesseract with the fast model, built by us from its
   official source (no JitPack); PaddleOCR, ONNX Runtime and the best model removed; the 7.4a merge
   rule (keep both when unsure); one shared Tamil cleanup function; Tamil scores re-checked on real
   photos in Task 2.4.
7. **Task 2.4 (2026-10-30):** camera photos re-saved as JPEG quality 90 without metadata; no crop to
   the guide frame (crop offered before uploads, Phase 4); preview frames closed right after measuring;
   the tests may grant/revoke CAMERA for Hey Buddy only and must leave it granted (the Realme phone
   refuses this from the PC, so it is given once in the app).
8. **Tamil is required** (later the same day): a Tamil reading engine chosen by measurement in
   Task 2.3 (ask before adding it), Tamil labels and month names in the fact rules, typed Tamil
   fields in "My details", and Tamil Nadu certificates as document types (Task 2.11, with a migration).

## 8. PHASE 7 — Before first real user (checks deferred from Phase 1)

Owner decision (2026-10-28): Phase 1 is done; these checks are deferred, and **all must be closed
before the first real user** gets the app. **No emulator anywhere unless the owner asks.**

### 8.1 Open test items (moved from 5.13)

| Item | From task | Why still open | How to close it |
| --- | --- | --- | --- |
| Fingerprint unlock: NOT TESTED YET | 1.3 | Owner's test phone has no working fingerprint sensor | On a real phone with a working fingerprint sensor (no emulator unless the owner asks): unlock with fingerprint and confirm "Vault unlocked" in logcat |
| Wrong-finger lockout message: NOT TESTED YET | 1.3 | Same as above | On that phone: use a wrong finger until lockout; confirm "Fingerprint lockout" in logcat, the "too many tries" message on screen, and that PIN still unlocks |
| Tink unwrap after the key window: NOT TESTED YET | 1.4 | The one real run was spoiled by a PIN unlock during the wait; owner chose to move on. Expiry itself is proven (Task 1.2) and the cause-chain check is proven with a fake error. The test uses a 30-second test key (the app key is 5 minutes). Task 1.10p full run (143/145): both tests failed again only because the phone had not been unlocked with PIN in the last few seconds ("unlock window was already closed"); owner decision: close it before the first real user | Unlock, run `AuthRequiredKeyWrapTest`, do not touch the phone for 40 seconds; both tests must pass |
| **HIGHEST PRIORITY** — `fake_pdf.pdf` (text renamed to .pdf) is rejected: NOT TESTED YET on the phone | 1.7 | Owner approved Task 1.7 after testing one image and one PDF. Covered by automated tests (`FileTypeDetectorTest`), not yet by hand | With the files in `Download/HeyBuddyTest/`: pick it; expect "Hey Buddy can only store photos (JPEG, PNG, WEBP) and PDF files…" and the document count unchanged |
| **HIGHEST PRIORITY** — `broken_photo.jpg` (right first bytes, damaged after) is rejected: NOT TESTED YET | 1.7 | Same as above. No automated test decodes a real damaged image | Pick it; expect "This image seems to be damaged…" and the count unchanged |
| **HIGHEST PRIORITY** — `too_large.jpg` (21,000,000 bytes) is rejected: NOT TESTED YET on the phone | 1.7 | Same as above. Size limit covered by automated tests (`PickedFileReaderTest`, `FileCryptoTest`), not yet through the picker | Pick it; expect "This file is larger than 20 MB…" and the count unchanged |
| `sample_photo.png` and `sample_photo.webp` save correctly: NOT TESTED YET | 1.7 | Owner tested one image (JPEG) and one PDF only | Add both; each shows a preview, saves, and the count goes up by 1 |
| Check A (cancel): cancelling "Confirm it's you" when saving: NOT TESTED YET by hand | 1.7 | The confirm path was seen working on the phone (log shows the re-ask twice, both saves went through). The cancel path is covered by an automated test (`AddDocumentSaverTest`) only | Unlock, keep using the app (touch the screen at least once a minute so the idle lock does not trigger) for more than 5 minutes, tap Save, cancel the prompt; expect "Please confirm it's you to save. Tap Save to try again." and the count unchanged |
| Check B: name rules on the Add screen: NOT TESTED YET by hand | 1.7 | Rules are covered by automated tests (`DisplayNameValidatorTest`); the screen behaviour is not | Clear the name (message shows, Save greyed out); type your own name, then change the type (your name stays) |
| Check C: "Choose photos" (called "Choose a photo" before Task 1.10p) opens the system photo picker with no permission question: NOT TESTED YET | 1.7 | Owner tested through the picker but did not report which button | Tap "Choose photos"; the photo picker opens with no permission question |
| Keyboard does not cover the Name field or the Save button (Add screen): NOT TESTED YET | 1.8a | Owner skipped this check; the screenshot test cannot open the keyboard | On the Add screen with a picked file, tap Name; the field and "Save to vault" must stay visible (or reachable by scrolling) above the keyboard |
| Home-screen app icon looks right: NOT TESTED YET | 1.8a | Owner skipped this check. App info showed the themed (one-colour) icon correctly | Check the icon on the home screen, with themed icons both on and off if the phone allows |
| Back on each screen (viewer → list, Add → list, Rename dialog → closes only the dialog, list → leaves the app): NOT TESTED YET by hand | 1.8b | Owner confirmed list, open, rename and zoom, but not Back. Covered by automated tests (`VaultNavigatorTest`, `ViewerViewModelTest`) | Try Back on each screen and with the Rename dialog open |
| Locking while a document is open: NOT TESTED YET by hand | 1.8b | Not explicitly confirmed. Covered by `ViewerViewModelTest` (view_tmp empty, picture freed, back at the list) | Open a PDF, let the vault lock, unlock; expect the list, and `view_tmp` empty |
| Cancelling "Confirm it's you" when opening a document: NOT TESTED YET | 1.8b | Not explicitly confirmed; no automated test (needs a real auth-required key) | Keep using the app for more than 5 minutes after unlock (touching at least once a minute), open a document, cancel the prompt; expect the calm message and "Try again" |
| Keyboard in the Rename dialog does not hide the name field or Save: NOT TESTED YET | 1.8b | Not explicitly confirmed | Open Rename, tap the field; the field and Save must stay visible above the keyboard |
| "Confirm it's you" no longer appears on almost every document: NOT TESTED YET with a 5-minute key | 1.9 | DECIDED: owner counted it during Task 1.9 (appeared nearly every time over ~20 opens with 30 s), so `VAULT_UNLOCK_WINDOW_SECONDS` is now 300. The key on the owner's phone was created with 30 s and keeps it until the vault is set up again | After "Delete everything" (Task 1.10) creates a new key: open ~20 documents in a row within 5 minutes; "Confirm it's you" must not appear |
| Predictive back preview: NOT TESTED YET | 1.10m | The back-swipe preview only exists on Android 14+; the owner's phone runs Android 13 | On a real Android 14+ phone (no emulator unless the owner asks): swipe back slowly on the viewer, Add and Settings screens; a preview of the previous screen must show, and cancelling the swipe stays on the screen |
| `SecureWindowTest.ourOwnPickerDoesNotLockTheVault` was flaky once: RECHECK | 1.10 | One full run (100 of 101 passed) failed it with "Activity never becomes requested state RESUMED" while the phone was freezing and unfreezing test processes (battery optimisation). It passed 3 of 3 times on its own | Owner decision: recheck in the full run before the first real user, AFTER battery optimisation is turned back on for Hey Buddy; it must pass |
| **Real phone-camera measurement** (deferred from 2.2, 2.3 and 2.4; owner decision 2026-10-30): NOT DONE YET | 2.2, 2.3, 2.4 | The measuring screen is built (`RealCameraMeasurement`, test code) but the owner will photograph later | Print the 5 fake samples (`aadhaar_style_clean`, `pan_style_clean`, `licence_style_clean` from Task 2.2; `tn_certificate_style_clean`, `tamil_address_block_clean` from Task 2.3). Run `adb shell am instrument -w -e realCamera yes -e class com.heybuddy.ui.camera.RealCameraMeasurement com.heybuddy.test/androidx.test.runner.AndroidJUnitRunner` and take the 12 photos the screen asks for (each sample good + harder, one blurry, one dark). Then: English/Hindi/Tamil accuracy and fields on real photos; re-check the merge cut-offs 0.5 / 0.7 / 0.8 (no real line dropped); set the camera hint limits (brightness 60 and sharpness 60 are provisional) from the measured values; ask the owner before changing any cut-off |
| Edge-to-edge with 3-button navigation: NOT TESTED YET | 1.8a | Owner's phone uses gesture navigation; owner chose to skip switching for now. Gesture navigation is verified (screenshots, Task 1.8a) | Switch the phone to 3-button navigation, open every screen; nothing may sit behind the navigation buttons; then switch back |

### 8.1a Play Store and privacy policy (from Task 2.4)

| Item | How to close it |
| --- | --- |
| Data safety form | Photos are taken, encrypted and read only on the phone and never sent anywhere, so (on-device processing is not "collection") the answers stay **no data collected, no data shared**. Re-check against Google's form at release time |
| Camera permission | The Play listing will show the CAMERA permission; no extra declaration form is needed for it |
| Privacy policy | Add: "The camera is used only to photograph documents into your encrypted vault on this phone; photos are never uploaded or saved to your gallery." |

### 8.2 Phase 1 review checks (moved from Task 1.11)

| Check | How to close it |
| --- | --- |
| Full test run | Debug build, `adb shell am instrument` with stay-on (CLAUDE.md); every test passes, including `AuthRequiredKeyWrapTest` (8.1) and the `SecureWindowTest` recheck (8.1) |
| **Release build with R8** | Build the release variant (shrinking and obfuscation on), install it on a real phone, and use every screen once: unlock, add (photo and PDF, several pages), view, rename, delete, settings, delete everything. R8 must not break Room, SQLCipher or Tink (add keep rules only if something breaks). Merged release manifest: no INTERNET or other network permission; only the launcher activity exported |
| Manual security checks | All 7 checks of section 5.11, on a real phone |
| `docs/PHASE1_REPORT.md` | Lists each of the 15 rules in 5.5 and how it is met (with the test or check that proves it); owner approves it |
| Battery optimisation back on | It was turned off for Hey Buddy on the owner's test phone for testing (Task 1.10). Offer to turn it back on (an adb command that changes the phone: ask the owner first) |
| Delete the sample files | Delete `Download/HeyBuddyTest/` on the owner's test phone (ask the owner first) |

### 8.3 Done when

Every row of 8.1 and 8.2 is closed, and the owner approves `docs/PHASE1_REPORT.md`.
