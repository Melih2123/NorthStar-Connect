# NorthStar Connect Android

Android widget prototype + GitHub Actions debug APK build.

## What is included

- Kotlin Android app shell
- Jetpack Glance home-screen widget
- Static local demo message (`Merhaba!`)
- GitHub Actions workflow that builds a debug APK on pushes to `main`
- APK uploaded as a 14-day Actions artifact

## Build with GitHub Actions

1. Create a GitHub repository named `NorthStar-Connect`.
2. Extract this project ZIP.
3. Upload/push the contents so `.github/workflows/android-build.yml` is at the repository root.
4. Push to the `main` branch, or open **Actions → NorthStar Connect Android APK → Run workflow**.
5. Open the completed workflow run and download **NorthStar-Connect-debug-apk** under Artifacts.
6. Transfer `app-debug.apk` to the Android phone and install it. Android may ask permission to install apps from that source.
7. Add the widget: long-press home screen → Widgets → NorthStar Connect.

## Local build

Requires JDK 17, Android SDK platform 35, and Gradle 8.7:

```bash
gradle -p android assembleDebug
```

APK output:

`android/app/build/outputs/apk/debug/app-debug.apk`

## Current limitations

This is a first widget proof-of-concept. It displays a local fixed demo message.
It does **not** yet send drawings between phones, use Firebase, or include accounts.
The next milestone is a FastAPI sync service plus authenticated upload/download and
Android background refresh. Do not put service credentials in the APK or GitHub repo.

## Compatibility

- minSdk 26 (Android 8.0)
- targetSdk 35
- JDK 17
- Android Gradle Plugin 8.6.1
- Gradle 8.7
- Kotlin 1.9.24
- Jetpack Glance 1.1.1
