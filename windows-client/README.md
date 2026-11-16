# NorthStar Connect for Windows (v0.6.1)

Tauri 2 desktop wrapper around the existing NorthStar Connect web client. The drawing editor, friends/account page and J.A.R.V.I.S. viewer are bundled from the repository's `web/` folder; the app does not ship a second backend or database.

## What it does

- Opens the existing shared drawing workspace in a native Windows window.
- Provides desktop navigation for the canvas, friends/accounts and J.A.R.V.I.S. viewer.
- Stores the selected backend URL in the WebView's local storage and shares it across the bundled pages.
- Uses the configured backend for REST calls and WebSocket signaling from the Friends page.
- Retains the existing room WebSocket collaboration path. The current P2P page is still the existing signaling/DataChannel test, not a P2P replacement for room canvas transport.

## Windows prerequisites

- Windows 10/11 x64.
- Node.js 20+ (Node 22 recommended).
- Rust stable with the MSVC target and Visual Studio C++ Build Tools.
- Microsoft Edge WebView2 Runtime (normally already present on current Windows 10/11 systems).

## Build locally

Open PowerShell in `windows-client/`:

```powershell
npm install
npm run dev
```

Create the NSIS installer and release app:

```powershell
npm run build
```

Expected output:

- Installer: `src-tauri/target/release/bundle/nsis/`
- App executable: `src-tauri/target/release/northstar-connect.exe`

The NSIS setup is configured as per-user installation and should not require administrator rights.

## Build with GitHub Actions

Commit and push the project to `main`. The `NorthStar Connect Windows Client` workflow builds on `windows-latest` and uploads the installer/app as the `NorthStar-Connect-Windows-v0.6.1` workflow artifact. The workflow artifact is not a signed public release.

## Backend URL

The first launch defaults to:

`https://melih-uzun.taild8cc3a.ts.net`

Use the SERVER field at the top-right to change this. The app checks `GET /health` and stores the selected URL for the drawing and Friends pages. The backend must be reachable over HTTP(S), and room collaboration requires its WebSocket endpoint to be reachable over WS(S).

The Windows client is only the desktop UI. Keep the existing FastAPI backend and its `backend/data/` database running on the host you chose. It shares the same rooms and account system as Android and browser clients.

## Current scope / honest limitations

- The app is a Tauri WebView wrapper, not a full native rewrite.
- Room canvas/chat collaboration continues to use the existing FastAPI WebSocket transport.
- Friends-page WebRTC signaling and its current DataChannel test are carried over into WebView2. This does not yet make the full drawing canvas P2P.
- J.A.R.V.I.S. remains a read-only viewer.
- No code signing certificate is configured, so Windows SmartScreen may show an unknown-publisher warning for locally built installers.
- No Windows build was run in the Linux authoring environment. Use the included Windows GitHub Actions workflow or build on Windows to produce the actual `.exe`.
