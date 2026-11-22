# AGENTS.md

## Project
Android app (`PindahSesi` / "ChatGPT Switcher") that snapshots and swaps the data directory of
`com.openai.chatgpt` using root (`su -c`). Kotlin, single activity, no tests.

## Build
Toolchain: JDK 21, Android SDK (compileSdk 34, build-tools 34.0.0), Gradle wrapper 8.10.2,
AGP 8.7.3, Kotlin 2.0.21.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=$HOME/android-sdk
printf 'sdk.dir=%s/android-sdk\n' "$HOME" > local.properties
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed, installable).

## Notes
- `minSdk = 24` so the APK installs and runs on Android 7.0 through 14, including Android 10 (API 29).
- `namespace`/`applicationId` is `com.example.cgptswitcher` and must match the Kotlin package
  (`app/src/main/java/com/example/cgptswitcher/`); the manifest uses the relative `.MainActivity`.
- Runtime requires root (Magisk) because it manipulates `/data/data/com.openai.chatgpt` and
  stores profiles under `/data/adb/chatgpt_switcher`.
