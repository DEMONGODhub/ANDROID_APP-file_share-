# LocalShare

Host any folder from your Android phone as a website on your Wi-Fi / hotspot.
Android 5.0+ (API 21+). Kotlin + Jetpack Compose (Material 3), zero third-party server libraries.

## Build
1. Android Studio (latest) -> Open this folder -> let Gradle sync (accept any "upgrade AGP" prompt).
2. Run on a phone, or Build > Build APK(s).

## Use
Choose folder -> Start sharing -> open the shown `http://IP:8080` on any device on the same network.
Settings: password, max devices, port, game on/off. Devices tab: kick / block / unblock.
"Browse files & play the game here" opens the same site inside the app as host (no password).

## Layout
- `app/src/main/java/.../server/` pure-Java server (HTTP, Range streaming, sessions, block list, WebSocket game). No Android imports.
- `app/src/main/assets/web/index.html` the website guests see (same colour tokens as `ui/Theme.kt`).
- `tools/test/Harness.java` desktop test of the server core:
  `javac -d out app/src/main/java/com/localshare/app/server/*.java tools/test/Harness.java`
  `java -cp out Harness test <some-folder> app/src/main/assets/web`

## Re-skin
Edit the colour tokens in `ui/Theme.kt` and the `:root{...}` block at the top of `assets/web/index.html`.
