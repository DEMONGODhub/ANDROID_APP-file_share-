# LocalShare v2

Share folders from your Android phone as a website on your Wi-Fi or hotspot. Android 5.0+ (API 21+).
Kotlin + Jetpack Compose (Material 3). The server is pure Java with no third-party libraries.

## What's new in v2
- Guests first **choose which shared folder** to open; add as many folders as you like.
- Photos and PDFs open in a **new tab**. A **⋮ menu** on every item: open, download, copy link, delete.
- Smooth browsing: server thumbnails, lazy loading, chunked lists, stale-while-revalidate listings.
- Guest controls: upload, delete, session length, password, max devices, block by device. Activity log.
- Keeps sharing while the phone is locked (foreground service, wake and Wi-Fi locks) with plain-language permission help.
- **Full-screen** Orb Arena game: server-authoritative, minimap, leaderboard, touch joystick and boost.
- Per-device help (?) and **PC keyboard shortcuts** (press `?` on a computer).
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

Copyright (c) 2026 DEMON_GOD. All rights reserved. See `LICENSE` and `NOTICE.md`.
