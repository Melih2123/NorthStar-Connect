# NorthStar Connect v0.6.0 — Accounts, Friends & P2P Signaling

## v0.6.0 architecture

- Account registration/login uses a stable random 128-bit UUID-style Account ID. The username is a changeable label, not the identity key.
- Passwords are verified on the backend using Argon2id with a unique random salt encoded into the stored PHC hash. The raw password is not stored on the device or server.
- Android stores the bearer session token encrypted with AES-256-GCM using an Android Keystore key. The server stores only a SHA-256 digest of each random session token.
- Friends can send/accept requests, view friends, and send direct or room invitations. Invitations are persisted so offline users can receive them later.
- The browser Friends page (`/friends.html`) performs WebRTC signaling through one-use, short-lived tickets and can establish an actual browser-to-browser WebRTC DataChannel for direct test messages. The page refreshes friend presence/invitation lists and sends signaling keepalives.
- STUN is provided by default. Configure TURN with `NORTHSTAR_TURN_URL`, `NORTHSTAR_TURN_USERNAME`, and `NORTHSTAR_TURN_CREDENTIAL` for restrictive NATs. Without TURN, some direct peer connections will fail.
- Android has native account/friend/invitation UI and notification delivery, plus a one-tap link to the responsive browser P2P page. Sign in again on that browser page to test P2P. Native Android WebRTC DataChannel transport is **not yet connected** in this release; the Android canvas/chat still use the existing room WebSocket. Browser P2P test messages are direct, but shared canvas events are still server-mediated.
- The signaling presence/ticket store is in-memory. Run one Uvicorn worker for this release; multi-worker/multi-host deployments need shared Redis or another shared presence store.
- Existing room-code access remains compatible and is not yet bound to accounts. Preserve `backend/data/` when updating.

## Install / update v0.6.0

1. Copy this ZIP's project contents over the existing repository root. Keep `.git`, `backend/data/`, `.venv/`, Firebase credentials and local-only files.
2. In `backend`, run `run-windows.cmd`. It checks for the new `argon2-cffi` dependency and installs missing requirements automatically; manual alternative: `python -m pip install -r requirements.txt`.
3. Restart the backend. SQLite creates the account/friend tables automatically in the existing database.
4. Push the source update using `git add -A`, `git commit`, and `git push`. GitHub Actions runs backend tests and builds the Android debug APK.
5. Open `/friends.html` in two browsers (or use the Android **TARAYICIDA DOĞRUDAN P2P AÇ** button), sign in to different accounts, add each other, accept the request, and test **P2P BAĞLAN**. The browser page has its own session, so sign in there with the same account; the Android app's encrypted token is not copied into browser storage.
6. Keep both Friends pages open while testing. The online indicator represents an active signaling WebSocket, not simply an installed app or an account that exists.

### Security and deployment notes

- Do not derive Account IDs from password hashes. Account IDs are random and stable; password changes do not change identity.
- Use HTTPS/Tailscale Funnel for remote sign-in. Do not share database files or account tokens.
- Add production-grade rate limiting, account recovery, email verification and abuse controls before public launch.
- TURN credentials should be short-lived in a public deployment; do not hardcode permanent TURN secrets into the app.

---

## v0.5.9: widget refresh and sync diagnostics

- Saving or joining a room immediately refreshes the Glance widget.
- Placing the widget on the home screen schedules an immediate room snapshot sync.
- Notification polling is isolated from canvas synchronization; notification errors no longer prevent preview updates.
- Every successful canvas fetch refreshes the widget, even if the event ID is unchanged.
- The widget displays the latest room and a readable sync/error status.
- One-time widget refresh jobs are de-duplicated.

## v0.5.9: single-device presence and widget room preview

- Presence counts unique device IDs rather than raw WebSocket connections.
- A reconnect from the same installation replaces its stale room socket.
- Android board Activity uses `singleTask`, preventing duplicate instances from repeated widget taps.
- Opening the board from the widget schedules an immediate snapshot refresh.
- The widget displays the last saved room code above its canvas preview.

# NorthStar Connect v0.6.0 — Accounts, Friends & P2P

## What's new
- Last room auto-reconnects in the web editor after refresh.
- Android automatically reopens the last saved room after app/process restart.
- Server-side room history restores strokes, shared canvas photos, and room chat after reconnect.
- Web photo editing: click `FOTO SEÇ / TAŞI`, click a photo, drag to move, hold Shift while dragging to resize, then use `FOTO SİL` to remove it for everyone.
- Photo edits and deletion are persisted as room events and broadcast to connected peers.
- Chat messages trigger Firebase push notifications to other registered Android devices. Notification text remains generic: `NorthStar Connect` / `Yeni resim eklendi`.

## Install / update
1. Extract this ZIP. Copy the contents of `NorthStar-Connect-v0.6.0-Full-Project` over the existing repository root.
2. Preserve `.git`, `backend/data/`, `backend/.venv/`, local Firebase credentials, and `android/app/google-services.json`.
3. From repository root run:
   ```cmd
   git add .
   git commit -m "Restore rooms and add shared photo editing"
   git push
   ```
4. Backend: `cd backend` then `run-windows.cmd`.
5. GitHub Actions builds the Android debug APK. Download it from the successful workflow artifact.

## Important data preservation
The server's SQLite database and `backend/data/drawings/` contain the persistent rooms, board event history, chat messages, and uploaded images. Do not delete these when updating. The ZIP intentionally excludes runtime data and credentials.

## Push setup
Firebase Cloud Messaging is the preferred near-instant notification path and requires Android `google-services.json` plus backend Firebase Admin credentials. When Firebase is not configured, Android automatically falls back to polling the Tailscale Funnel backend with WorkManager and posts local Android notifications for new room activity. The fallback is delayed and subject to Android background scheduling; it is not instant push.

## Current scope
Photo selection/move/resize/delete is implemented in the PC/web editor and synchronized to all peers. Android restores and displays photo positions from history; native Android photo transform controls are not included in this version. JARVIS remains a read-only viewer.

# NorthStar Connect v0.5.5 — Shared Paint, Room Chat & Image Paste

NorthStar Connect is now a **shared collaborative canvas**, not a send-one-image-at-a-time messenger. Every connected participant in a room sees the same board and can draw, erase, undo their own strokes, and clear the shared board.


## v0.5.9: notification fallback without Firebase

- The Android server field now defaults to the configured Tailscale Funnel URL: `https://melih-uzun.taild8cc3a.ts.net`. A manually entered server address still takes precedence.
- When Firebase is not initialized, WorkManager polls `/api/rooms/{code}/updates` and displays local Android notifications for remote canvas changes and room messages.
- Firebase Cloud Messaging remains preferred when Firebase is configured.
- The first poll for a room records a baseline, so existing canvas/chat history does not generate a flood of notifications.
- The periodic fallback is subject to Android scheduling and battery limits. WorkManager's periodic minimum is 15 minutes; this is not instant push. It will not run while Android has force-stopped the app.
- Tailscale Funnel must be enabled and the backend host must remain online. Funnel makes the backend internet-reachable, so use room access controls and do not expose secrets.

## v0.5.5 highlights

- **Room chat:** persistent messages, live WebSocket delivery, and image attachments.
- **Paste photos into the shared board:** copy an image, click the drawing canvas, and press `Ctrl+V` (or `Cmd+V` on macOS). Use `FOTO EKLE` or drag-and-drop as alternatives.
- **Paste photos into room chat:** focus the chat composer, then paste. The image appears as an attachment preview before sending.
- **Android:** gallery picker for board images and chat attachments; chat image thumbnails are displayed in the room.
- **J.A.R.V.I.S.:** remains strictly read-only, showing the shared board and a read-only chat feed.
- **Generic notification text:** `NorthStar Connect` / `Yeni resim eklendi`.

## What changed in v0.5

- Realtime collaboration over WebSockets.
- Strokes are streamed in small point batches while the pointer is moving.
- The backend assigns a room-wide event sequence and persists every operation in SQLite.
- New participants receive the current board history on connect.
- The board survives browser/app close and server restart as long as the SQLite data directory is preserved.
- Web drawing client now supports live shared drawing, eraser, undo-own-last-stroke, and clear-for-everyone.
- Android app now opens a native shared drawing screen after joining a room; it uses the same WebSocket event protocol.
- Android widget displays a snapshot of the shared board; tapping it opens the full drawing screen. A home-screen widget is a preview/launcher, not the full canvas editor.
- WebSocket clients attempt to reconnect after connection loss.
- FCM data-message integration sends a generic Android notification after a completed stroke or clear. The notification text is exactly **NorthStar Connect — Yeni resim eklendi**. Notifications are throttled to at most one per room per 12 seconds to avoid a ping for every quick brush stroke. FCM requires Firebase client and server configuration.
- The old PNG upload/download endpoints remain for v0.4 compatibility.
- Room chat is shared by all editor clients and persists in SQLite; reconnecting clients receive the latest 100 messages.
- Chat supports text plus optional PNG/JPEG/WebP image attachments.
- Desktop image paste: press `Ctrl+V` over the canvas to insert the clipboard image onto the shared board; focus the chat text box first to paste the image as a chat attachment.
- Desktop image import also supports the `FOTO EKLE` button and drag/drop onto the canvas.
- Android supports gallery image insertion onto the shared board and gallery attachments in room chat; received chat photos render as thumbnails.
- J.A.R.V.I.S. remains read-only: it displays the shared board and a read-only room-chat feed, but cannot draw or send chat messages.

## Architecture

```text
Browser shared canvas  <---- WebSocket ----> FastAPI room event stream
        |                                            |
        |                                            +--> SQLite canvas_events
        |                                            |
Android shared canvas  <--- WebSocket --------------+
        |
        +--> Android widget snapshot (WorkManager / FCM-triggered refresh)

When Firebase is configured:
FastAPI -- FCM data message --> Android notification + board snapshot refresh

When Firebase is absent:
Android WorkManager -- periodic HTTPS poll via Tailscale Funnel --> local Android notification
```

Every point is normalized to a 0..1 coordinate pair. The backend persists a globally ordered sequence of `stroke_begin`, `stroke_points`, `stroke_end`, `stroke_delete`, and `clear` events. Each client renders the same ordered stream. Separate users can draw concurrently; the server serializes accepted events so all clients replay them in the same order.

## 1. Update your existing local project

Extract this ZIP **over the root of your current NorthStar Connect project**. It is an overlay package with `.github/`, `android/`, `backend/`, `web/`, `.gitignore`, and `README.md` at its root.

Do not delete your existing `.git/` folder. Do not overwrite or delete your `backend/.venv/` environment. The existing `backend/data/` folder contains your room database and board history; preserve it if you want to keep previously created rooms. This release adds the `canvas_events` table automatically when the backend starts.

## 2. Run the backend

Windows:

```cmd
cd /d F:\NorthStar-Connect-Android-v0.4-sync\backend
run-windows.cmd
```

The script creates `.venv` if needed, installs the requirements, and starts Uvicorn. If you prefer manual control, activate `.venv` and run `python -m uvicorn app.main:app --host 127.0.0.1 --port 8000`. Keep the server terminal open.

- Health: `http://127.0.0.1:8000/health`
- API docs: `http://127.0.0.1:8000/docs`
- Shared drawing app: `http://127.0.0.1:8000/`

The backend now serves `web/index.html` at `/` and `web/connect-drawing.html` as a secondary direct page.

## 3. Expose it using Tailscale Funnel

In a second PowerShell window:

```powershell
tailscale funnel --bg 8000
tailscale funnel status
```

Open the HTTPS Funnel URL. The root URL should show the shared drawing web app. The same HTTPS URL is the Android app's Server URL. It automatically uses WSS for the live canvas socket.

Tailscale Funnel makes this service publicly reachable. Do not share room codes publicly. This prototype uses the 12-character room code as a bearer access key and does not yet implement accounts, per-user permissions, room revocation, or rate limiting. Use it only with trusted participants until those controls are added.

## 4. Use the shared canvas from the web

1. Open the Funnel URL in a browser.
2. Click **ODA OLUŞTUR** to create a room, or enter an existing room code and click **TUVALE KATIL**.
3. Share that room code with your collaborator.
4. Anyone entering that room code joins the same persistent board.
5. Draw, erase, undo your own last stroke, or clear the whole shared board.

A connected peer sees the stroke while it is being drawn, not only after a PNG upload. If a peer disconnects, it can reconnect and replay the saved board history.


### Room chat and image paste

- In the PC/phone web editor (`/connect-drawing.html`), press **CHAT** to open the room chat.
- Type a message and press Enter or **GÖNDER**. The latest 100 messages are restored when a client reconnects.
- To paste a copied screenshot/photo into the shared canvas, click the canvas area and press `Ctrl+V` (or `Cmd+V` on macOS). The image is uploaded to the room, then an `image_add` event broadcasts its position to the other participants.
- To attach an image to a chat message, focus the chat message box and press `Ctrl+V`; or use **＋ FOTO**. A preview appears before sending.
- The **▧ FOTO EKLE** toolbar button and drag-and-drop are alternative ways to put an image directly on the shared canvas.
- On Android, **FOTO EKLE** opens the photo picker for the canvas. The chat dialog's **FOTO** button attaches a gallery image to a room message.
- J.A.R.V.I.S. shows the shared board and room-chat feed in read-only mode. Use the PC editor or Android app to edit or send messages.

## 5. Android build and test

Push the updated repository to GitHub. The workflow `.github/workflows/android-build.yml` builds a debug APK using JDK 17 and Gradle 8.7.

Download `NorthStar-Connect-debug-apk` from the successful Actions run. Install the APK, open it, enter the same HTTPS Funnel URL, then create or join the same room code. The app opens the native shared canvas screen after connecting.

The Android widget shows the latest server-rendered shared board snapshot. Tap the widget to open the full Android drawing screen. While the app is open, the Android canvas uses WebSocket live events. When the app is in the background, FCM can prompt a board snapshot refresh if configured; WorkManager also has a 15-minute periodic fallback, which Android may defer for battery management.

## 6. Configure Firebase notifications (optional)

Without Firebase, foreground WebSocket collaboration still works. The v0.5.9 Android client also polls the Tailscale Funnel using WorkManager and creates local notifications. This fallback normally checks no more often than every 15 minutes, may be delayed by Android battery management, and does not run while the OS has force-stopped the app.

### Android Firebase config

1. Create a Firebase project: https://console.firebase.google.com/
2. Add an Android app with package name `com.northstar.connect`.
3. Download `google-services.json`.
4. For a local build, place it at `android/app/google-services.json` (ignored by Git).
5. For GitHub Actions, convert the JSON to Base64 and add a repository Actions secret named `GOOGLE_SERVICES_JSON_BASE64`.

PowerShell conversion:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\google-services.json")) | Set-Content -NoNewline .\google-services.base64
```

Copy the Base64 text into the secret, then delete the temporary Base64 file. Never commit the JSON or Base64 value.

### Backend Firebase service account

Create a Firebase service-account JSON key and store it outside the repository. Before starting Uvicorn in CMD, set:

```cmd
set GOOGLE_APPLICATION_CREDENTIALS=C:\secure\path\to\northstar-firebase-service-account.json
```

Then start Uvicorn in that same terminal. Alternatively set `FIREBASE_SERVICE_ACCOUNT_JSON` to the JSON content in the server environment. Never put a service-account key in the APK, source tree, GitHub commit, or public issue.

When another device completes a stroke or clears the board, the backend sends a data-only FCM message to registered devices in that room. When Firebase is absent, Android instead checks the incremental `/api/rooms/{code}/updates` endpoint through the configured Tailscale Funnel and creates a local notification. The FCM path displays:

- Title: `NorthStar Connect`
- Body: `Yeni resim eklendi`

Android 13+ requires notification permission. The app asks for it when launched. FCM delivery timing is not guaranteed and depends on Firebase, network state, Android power management, and the user's notification settings.

## 7. Data, limitations, and security

- The server stores a durable event stream in `backend/data/northstar.sqlite3`.
- `backend/data/` is local runtime data and is intentionally not included in the ZIP or source control.
- Keep the backend's data directory on persistent storage. Deleting it deletes rooms and canvas history.
- Single-process SQLite is suitable for this prototype. Do not run multiple Uvicorn workers against this implementation; live sockets and per-room locks are in-process.
- The 12-character room code grants shared edit access to anyone who has it. There is no account/profile system yet.
- No end-to-end encryption, per-user role system, abuse moderation, event compaction, or server-side rate limiting yet.
- The WebSocket event size and point batch are validated, but this is still an early trusted-group prototype, not a hardened public collaboration service.
- FCM is for background notification and widget refresh. The live canvas itself uses WebSocket while the app/page is open.

## Roadmap — keep these items visible

- [x] Android app and home-screen widget skeleton
- [x] GitHub Actions debug APK build
- [x] FastAPI rooms and 12-character invitation codes
- [x] Web shared canvas and realtime multi-user stroke stream
- [x] Persistent server-side canvas event history
- [x] Android native shared canvas over the same WebSocket protocol
- [x] Widget board preview + tap-to-open editor
- [x] FCM integration points with exact generic notification text
- [ ] Verify Android APK build in GitHub Actions
- [ ] Configure Tailscale Funnel on the actual machine (Firebase is optional; without it the Android polling fallback is used)
- [ ] Verify live simultaneous drawing between two real devices
- [ ] Add proper user accounts, authenticated room invitations, revocation, and rate limiting
- [ ] Add stroke ownership/permissions, presence names, and conflict/undo UX polish
- [x] Integrate J.A.R.V.I.S. with a server-enforced read-only Connect Viewer
- [ ] Add board snapshots/event compaction and deploy to a persistent production backend


## v0.5.2 — JARVIS VIEWER INTEGRATION

The root web page (`/`) is now the NorthStar J.A.R.V.I.S. hand interface. Its center
workspace has a `CONNECT VIEWER` tab that renders the shared collaborative canvas.
It is intentionally read-only: there are no drawing, erasing, undo, or clear controls
in J.A.R.V.I.S. The connection uses `role=viewer`; the backend rejects any incoming
canvas-edit message from that socket.

To configure the J.A.R.V.I.S. viewer, use the left `SHARED BOARD FEED` panel to enter
the backend URL and the 12-character room code, then select `CONNECT VIEWER`. If
J.A.R.V.I.S. is served by this FastAPI instance, the server URL is prefilled from the
current page origin (including the Tailscale Funnel HTTPS origin).

Editing is done separately from a phone or PC at `/connect-drawing.html`, or in the
Android app's Shared Board screen. All editor clients in the same room continue to
share one live canvas over WebSocket. The J.A.R.V.I.S. viewer receives the same
ordered event stream and replays saved history when it reconnects.

Notification wording remains generic and does not reveal sender names. FCM notifications use title `NorthStar Connect`, body `Yeni resim eklendi`. Without Firebase, the Tailscale fallback displays `Yeni resim eklendi` for canvas activity and `Yeni oda mesajı var` for chat. This fallback is periodic and can be delayed; it is not equivalent to FCM push.

### Viewer vs editor roles
- `role=viewer`: receives history, live canvas events, and presence only; writes are rejected.
- `role=editor` (default for existing web/Android editors): can publish strokes, erase, undo own last stroke, and clear the room.
- The viewer role is a UI/connection mode guard, not user authentication. Anyone with the room code can still open an editor client. Add per-user auth/invite permissions before exposing sensitive rooms broadly.

### v0.5.2 smoke tests
Run from `backend/` with its virtual environment active:

```cmd
python -m pytest -q
```

The suite includes a test that verifies J.A.R.V.I.S. viewer sockets cannot persist drawing events.


### Windows quick start without reinstalling a healthy virtual environment
From the project root run:

```cmd
cd backend
run-windows.cmd
```

The launcher checks for FastAPI, Uvicorn, and multipart first. It only invokes pip if one
of those required packages is missing, so an already-working `.venv` does not trigger a
network install on every start.


## Android app build and install (v0.5.3)

The Android app source is under `android/`. It includes:
- Native room create/join screen
- Shared canvas editor (Android users can draw and edit together)
- WebSocket realtime client with reconnect
- Home-screen Glance widget showing the shared canvas snapshot
- Optional FCM integration; requires Firebase configuration
- NorthStar adaptive launcher icon

### Build APK through GitHub Actions

1. Copy the contents of this ZIP into the root of the existing `Melih2123/NorthStar-Connect` repository.
2. Do not overwrite `.git`, `backend/data`, or any local `.venv` directory. This ZIP intentionally contains no Git metadata or runtime database.
3. Commit and push:
   ```cmd
   git add .
   git commit -m "Prepare NorthStar Connect Android v0.5.3"
   git push
   ```
4. Open GitHub → Actions → `NorthStar Connect Android APK`.
5. Open the successful run and download `NorthStar-Connect-debug-apk` from Artifacts.
6. Extract the artifact ZIP and transfer `app-debug.apk` to the Android phone.
7. Install it. Android may ask to allow app installs from the browser/file manager you used.
8. Open NorthStar Connect, enter the HTTPS Tailscale Funnel URL and create a room or join an existing room.
9. Open the shared canvas and draw together with the PC web editor.
10. Add the widget from the Android launcher's Widgets panel. Tapping it opens the shared canvas activity.

### Important

- This is a debug APK, suitable for testing. It is not a Play Store release.
- The Android app can use the realtime WebSocket shared-canvas connection while open.
- Firebase gives the preferred near-instant push path. Without Firebase, v0.5.9 uses periodic Tailscale Funnel polling and local notifications; Android may delay the worker, and force-stopping the app prevents it from running.
- Notification text is intentionally generic: title `NorthStar Connect`, body `Yeni resim eklendi`.
- JARVIS's integrated Connect Viewer remains read-only; editing is done from Android or the PC web editor.
- Android Gradle compilation must be verified by the GitHub Actions run. This environment did not run an Android SDK build.


## v0.5.4 — Paint workspace

### Editor improvements
- The PC/web editor is now a full-viewport workspace; the shared canvas expands to fill the available screen.
- Horizontal color palette with 15 presets and a custom color selector.
- Brush size: 1–64 px.
- Pencil, translucent marker/highlighter, eraser, undo, redo, and clear shared canvas.
- Fullscreen toggle and Ctrl/Cmd+Z, Ctrl/Cmd+Y shortcuts.
- Responsive sizing for desktop, tablet and mobile.
- Android editor has the matching palette, marker, eraser, 1–64 px size control, undo/redo and fullscreen canvas layout.
- JARVIS remains a strictly read-only viewer and now renders marker strokes and redo events correctly.

### Shared protocol changes
- The WebSocket protocol accepts the `marker` drawing tool.
- Added `stroke_restore` so redo is broadcast to every connected editor and to read-only viewers.
- Canvas points remain normalized, so the board can resize to each device's screen without changing stored coordinates.

### Upgrade
Copy this update overlay into your current repository root, preserving `.git`, `backend/data`, `.venv` and any private config. Then run:
```cmd
git add .
git commit -m "Upgrade shared canvas to Paint workspace"
git push
```

The Android debug APK is rebuilt by GitHub Actions. This package was not compiled with a local Android SDK; verify the Gradle result in Actions before installing.


## v0.5.5 — Room chat and clipboard images

- Added persistent per-room chat messages over the existing WebSocket connection.
- Added text chat, image attachments, and chat history on web/PC, Android, and the J.A.R.V.I.S. read-only viewer.
- Added desktop clipboard image paste: paste over the board to add an image to the shared canvas, or paste while the chat box is focused to attach the image to a message.
- Added canvas image picker and drag-and-drop on the PC web editor.
- Added Android gallery-to-canvas image insertion and gallery image attachments in room chat. Android chat now loads and displays shared image thumbnails.
- Kept J.A.R.V.I.S. server-enforced read-only for both canvas editing and chat sending.
- Updated Android widget snapshot rendering to include pasted images and marker/undo/redo replay.
- Updated GitHub Actions to run the backend test suite before building the Android debug APK.
- Android version: 0.5.5 (versionCode 7).

## Windows desktop client (v0.6.1)

A Tauri 2 Windows client is included in `windows-client/`. It wraps the existing web canvas, Friends/accounts page and read-only J.A.R.V.I.S. viewer in a desktop window. It uses the same FastAPI backend and does not create a second database.

Build on Windows from `windows-client/` with `npm install` followed by `npm run build`. The NSIS installer and app executable are written under `windows-client/src-tauri/target/release/`. The `NorthStar Connect Windows Client` GitHub Actions workflow also builds them on `windows-latest` and uploads a workflow artifact. See `windows-client/README.md` for prerequisites and details.

The shared canvas still uses the existing server WebSocket transport. The Friends page's current WebRTC DataChannel is a direct-message test; it does not yet transport the shared canvas P2P.
