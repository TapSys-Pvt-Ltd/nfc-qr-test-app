# NFC QR Test App

Android app that plays the customer's bank app for Tap2Pay: it opens on a Tap2Pay NFC tap (even when closed),
checks every APDU and the QR the POS sends, and shows the data a bank app would use. Full description, screens and the
scenario matrix: [bank-app/README.md](bank-app/README.md).

| Module | What |
|---|---|
| `core` | Plain Java, unit-tested: HCE responder, QR parsing and checks, NFC/QR reports, tap history, log text. |
| `bank-app` | The Android app (applicationId `pk.paysys.tap2pay.bankapp`), label "NFC QR Test App". |

## Build

Needs JDK 17 (Gradle 7.4.2 / AGP 7.2.2) and the Android SDK.

1. Create `local.properties` with `sdk.dir=/path/to/Android/sdk`.
2. `gradle.properties` pins `org.gradle.java.home` to Homebrew's JDK 17 on macOS. Change or remove that line if your
   JDK 17 is elsewhere (or `JAVA_HOME` already points at one).
3. `./gradlew :core:test :bank-app:assembleDebug`

The APK lands in `bank-app/build/outputs/apk/debug/bank-app-debug.apk`.
