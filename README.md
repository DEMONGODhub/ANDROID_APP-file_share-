# LocalShare v2

Share folders from your Android phone as a website on your Wi-Fi or hotspot. Android 5.0+ (API 21+).
Kotlin + Jetpack Compose (Material 3). The server is pure Java with no third-party libraries.

## What's new in v2
- Guests first **choose which shared folder** to open; add as many folders as you like.
- Photos and PDFs open in a **new tab**. A **⋮ menu** on every item: open, download, copy link, delete.
- Smooth browsing: server thumbnails, lazy loading, chunked lists, stale-while-revalidate listings.
- Guest controls: upload, delete, session length, password, max devices, block by device. Activity log.
- **Per-device settings** (gear button next to Kick and Block on the Devices tab): let one device delete, rename or edit text files, or limit it to the game only.
- **Game-only mode**: host just Orb Arena. No folders needed, nobody sees any file.
- Keeps sharing while the phone is locked (foreground service, wake and Wi-Fi locks) with plain-language permission help.
- **Full-screen** Orb Arena game: server-authoritative, minimap, leaderboard, touch joystick and boost.
- Per-device help (?) and **PC keyboard shortcuts** (press `?` on a computer).
- **Send to TV**: scan the code shown by [LocalShare TV](../LocalShareTV); the phone also announces itself so the TV lists it.
- Donate dialog with ₹1–₹10 quick amounts (UPI QR).

## Build
Android Studio (latest) -> Open this folder -> Gradle sync -> Run. For smoothness test a **release** build (debug Compose is slow).

## Test the server on a desktop
```
javac --release 8 -d out $(find app/src/main/java/com/localshare/app/server tools/test -name '*.java')
java -cp out Harness test "$PWD/app/src/main/assets/web"
```

## Layout
- `app/src/main/java/.../server/` pure-Java HTTP + WebSocket server (no Android imports).
- `app/src/main/assets/web/` the website guests see (`index.html`, `pdfview.html`, PDF.js).
- `app/src/main/java/.../*.kt` the Compose app; `ui/Theme.kt` and the `:root{}` block of `index.html` share one palette.

## Build the APK
1. Install [Android Studio](https://developer.android.com/studio) (it includes the Android SDK and the JDK it needs).
2. **File -> Open** and choose this folder. Wait for the Gradle sync to finish (first time it downloads dependencies).
3. Test: connect a phone with USB debugging on (or start an emulator) and press **Run**.
4. Debug APK: **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**, then click *locate*. The file is `app/build/outputs/apk/debug/app-debug.apk`.
5. Release APK for sharing: **Build -> Generate Signed App Bundle / APK -> APK**, create a keystore once (keep it and its passwords safe), choose **release**. The file is in `app/release/`.
6. Install on a phone: copy the APK over and open it (allow "Install unknown apps" for your file manager), or `adb install app-debug.apk`.

Command line instead: `./gradlew assembleDebug` (needs `ANDROID_HOME` pointing at the Android SDK; use `gradlew.bat` on Windows).

## Rights
Copyright (c) 2026 DEMON_GOD (@demongod). All rights reserved. The source code, the design, the logo and the name of this project belong to DEMON_GOD, and nothing here may be copied, redistributed or sold without written permission (see `LICENSE`).
Third-party libraries keep their own owners and licenses, listed in `NOTICE.md`; those rights belong to their authors.
