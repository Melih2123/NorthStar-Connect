# NorthStar Connect Android — v0.3 build-only

## Current milestone
Build and install a basic Android app with a Jetpack Glance home-screen widget that displays the local demo text `Merhaba!`.

## Build through GitHub Actions

1. Create an **empty** GitHub repository named `NorthStar-Connect` (do not add a README, license, or .gitignore there).
2. Extract this ZIP. Open PowerShell in the extracted `NorthStar-Connect-Android-v0.3-build` folder.
3. Run the commands below, replacing `YOUR_GITHUB_USERNAME` with your GitHub username:

```powershell
git init -b main
git add .
git commit -m "Initial NorthStar Connect Android widget"
git remote add origin https://github.com/YOUR_GITHUB_USERNAME/NorthStar-Connect.git
git push -u origin main
```

4. Open the repository on GitHub → **Actions** → `NorthStar Connect Android APK`.
5. Wait for the workflow to finish with a green check.
6. Open the run and download the `NorthStar-Connect-debug-apk` artifact.
7. Extract the downloaded artifact ZIP. Install `app-debug.apk` on the Android phone.
8. Long-press the home screen → **Widgets** → **NorthStar Connect** → add it.

The workflow also runs automatically on pushes to `main`. If GitHub asks you to sign in during `git push`, finish its browser authentication.

## Local build

Requires JDK 17, Android SDK Platform 35, and Gradle 8.7:

```powershell
gradle -p android assembleDebug
```

APK path: `android/app/build/outputs/apk/debug/app-debug.apk`

## Current limitations

- This is a static widget proof-of-concept. It displays `Merhaba!` locally.
- No backend, accounts, invitations, remote drawing upload, push notifications, or friend-to-friend sync yet.
- Debug APK is suitable for installing/testing. Release signing is deliberately postponed until the app works.

## NorthStar Connect roadmap

- [x] Android widget/app skeleton and debug APK CI workflow
- [ ] Install and verify widget on a real Android device
- [ ] Read/display a locally stored drawing in the widget
- [ ] FastAPI sync backend and drawing upload/download
- [ ] Friend invitation codes and authenticated sharing
- [ ] Android background refresh / push notification integration
- [ ] Two-way drawing exchange and drawing history
