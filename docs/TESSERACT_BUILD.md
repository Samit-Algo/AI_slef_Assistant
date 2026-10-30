# Building Tesseract4Android (Tamil reading)

Hey Buddy reads Tamil with **Tesseract4Android 4.9.0**. Owner rule (Task 2.3): we **build it
ourselves from its official source** instead of downloading a ready-made copy, and the app's build
stops if the file is not exactly the one we built.

## What is used

| Item | Value |
| --- | --- |
| Source | https://github.com/adaptech-cz/Tesseract4Android (Apache 2.0) |
| Tag | `4.9.0` |
| Exact commit | `15c534717b1cb58261b58d4e4c1200c7f81f668c` (2026-02-05). The tag was moved after the June 2025 release; the later commits only fix the CMake build and JitPack, not Tesseract's code |
| Inside | Tesseract 5.5.1, Leptonica 1.85.0, libjpeg v9f, libpng 1.6.48 (each under its own free licence) |
| Variant | `standard` (not `openmp`) |
| NDK | 27.2.12479018 (r27c), as pinned in the source's `tesseract4android/build.gradle` |
| CMake | 3.22.1 (Android SDK package) |
| Gradle / JDK | The source's own Gradle wrapper (8.11.1); JDK 17 |
| Result in this project | `app/libs/tesseract4android-4.9.0.aar` |
| SHA-256 of that file | `3aede2b48ab17d2b3b5a6bef51ec43989831dadb9eb9226fea9fec76d1189508` (also checked in `app/build.gradle.kts`) |
| Checks done | No permissions in its manifest; 64-bit libraries aligned to 16 KB pages (needed on newer Android); same files and sizes as the JitPack copy measured in Task 2.3 |

The Tamil model is separate: `app/src/main/assets/tessdata/tam.traineddata`, the official
`tam.traineddata` from https://github.com/tesseract-ocr/tessdata_fast (Apache 2.0), SHA-256
`d02fbec24be4b07e32e80d0ccfc3b6b67a3c5d61c9d0a7c8532677990912c6ec`.

## How to rebuild it (Windows, Git Bash)

1. Install the NDK once: Android SDK Manager → `ndk;27.2.12479018`. CMake `3.22.1` must also be installed.
2. Get the exact source (outside this project):
   ```
   git clone --branch 4.9.0 https://github.com/adaptech-cz/Tesseract4Android.git tesseract4android-src
   cd tesseract4android-src
   git rev-parse HEAD     # must print 15c534717b1cb58261b58d4e4c1200c7f81f668c
   ```
3. Tell it where the Android SDK is: create `local.properties` with `sdk.dir=C:/Android/Sdk`.
4. Build: `./gradlew :tesseract4android:assembleRelease`
5. Take `tesseract4android/build/outputs/aar/tesseract4android-standard-release.aar`, copy it to
   `app/libs/tesseract4android-4.9.0.aar`, and check `sha256sum`.

A native build is not always byte-for-byte the same on another computer or toolchain. If a rebuild
gives a different checksum, compare the contents (the same `.so` files, no permissions), then
update the checksum in `app/build.gradle.kts` and in this file, and say so in the task report.

## Updating to a newer version

Ask the owner first (CLAUDE.md: no library change without asking). Then repeat the steps with the
new tag, record the new commit and checksum here, and run the Tamil measurement
(`TamilMeasurement`) and all phone tests again.
