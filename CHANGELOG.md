# Changelog

## v0.6.1 - Windows desktop client groundwork

- Added a Tauri 2 Windows desktop client that reuses the existing web canvas, Friends/account page and J.A.R.V.I.S. viewer.
- Added desktop navigation and a persistent backend URL/health check.
- Updated the drawing and Friends pages to use the configured backend URL, including the Friends signaling WebSocket.
- Added a Windows GitHub Actions workflow to build and upload an NSIS installer and executable.
- The client continues to use the existing server WebSocket for shared canvas/chat; the current WebRTC DataChannel remains a direct-connection test, not full canvas P2P.

## 0.6.0 — Accounts, friends and P2P signaling
- Added account registration/login with random stable Account IDs and Argon2id salted password hashes.
- Android session tokens are encrypted locally with Android Keystore AES-GCM; server stores a SHA-256 token digest.
- Added friends, incoming/outgoing friend requests, accepted relationships, direct/room invitations and persisted offline inbox.
- Added account-scoped FCM device registration and notification polling based on the backend's actual FCM capability.
- Added one-use signaling tickets and friend-only WebSocket signaling for WebRTC offer/answer/ICE exchange.
- Added a browser-to-browser WebRTC DataChannel direct-message test on `/friends.html`, presence refresh and signaling keepalives.
- Added a one-tap Android link to the browser P2P page.
- Added an authenticated backend capability check so Android polling fallback is used when server-side FCM credentials are missing, even if Firebase SDK exists on the client.
- Preserved registered FCM tokens when a device refreshes registration without sending a new token.
- Added optional STUN/TURN configuration.
- Android native account/friend/invite UI is included; native Android WebRTC DataChannel and P2P transfer of shared-canvas events remain future work. Existing room canvas/chat still use server WebSockets.
- Fixed duplicate local variable in Android friend acceptance flow.
- Added tests for notification transport status and FCM token preservation.

# Changelog

## 0.5.9
- Refresh Glance immediately after a room is saved/joined and when the widget is added.
- Decouple notification polling failures from canvas preview sync.
- Always refresh the widget after successful canvas fetches.
- Show sync status/errors directly inside the widget.
- De-duplicate one-time refresh jobs.

# Changelog

## 0.5.8
- One installation counts as one room participant, even if duplicate sockets briefly exist.
- Stale same-device WebSocket connections are replaced on reconnect.
- Repeated widget taps reuse one Android board Activity.
- Widget-launched board refreshes the saved room snapshot.
- Widget displays the last joined room code.

# NorthStar Connect v0.5.7

## Tailscale notification fallback
- Added a default Tailscale Funnel server URL when the server field has not been configured.
- Added an incremental room updates endpoint for canvas events and chat messages.
- When Firebase is not initialized, Android WorkManager polls the Funnel endpoint and displays local notifications for remote canvas/chat activity.
- Existing FCM delivery remains preferred when Firebase is configured.
- Added per-room cursors so old room history is restored silently and is not announced as new.
- Periodic fallback checks use WorkManager's 15-minute minimum interval; Android may defer execution due to battery and background limits.
- Android versionCode/versionName: 9 / 0.5.7.

## Important limitation
Tailscale Funnel is the network path to the backend, not an Android push-notification service. The fallback is periodic polling, not instant push. It cannot run after Android force-stops the app, and delivery may be delayed by system battery restrictions. Keep the Funnel host and backend running.

# NorthStar Connect v0.5.6

- Web remembers the last server and room and automatically reconnects on page reload.
- Android automatically reopens the last saved room after an app/process restart.
- Room history restores strokes, canvas photos, and chat history from the server's persistent SQLite event/message store.
- Web photo editing: select, drag to move, Shift+drag to resize, and delete; all operations are persisted and broadcast to peers.
- Backend validates image update/delete operations against the current room's uploaded image records.
- FCM push notifications are also triggered by room chat messages, retaining the generic `NorthStar Connect` / `Yeni resim eklendi` notification text.
- Added backend validation tests for image transform/delete.

## Notes
- Existing `backend/data/northstar.sqlite3` and `backend/data/drawings/` must be preserved. They contain the room history and uploaded photos.
- Push notifications require Firebase Admin credentials on the backend and `google-services.json` in the Android build.
- Android supports selecting, moving, and deleting shared photos; web additionally supports Shift+drag resizing.
# Changelog

## 0.5.2 — JARVIS viewer integration
- Replaced the editable drawing tab in J.A.R.V.I.S. with a read-only shared-canvas viewer.
- Added Connect server / room configuration in the J.A.R.V.I.S. sidebar.
- Added `role=viewer` WebSocket mode and server-side rejection of viewer writes.
- Kept the collaborative editor at `/connect-drawing.html` for PC and phone.
- Preserved Android shared-board editing and generic notification text: “Yeni resim eklendi”.
- Added viewer read-only regression test and expanded Actions path triggers.

## 0.5.1
- Customized shared canvas brush-size slider.

## 0.5.0
- Added realtime collaborative canvas, persistent events, Android shared-board editing, and FCM integration.

## v0.5.3
- Corrected the Glance ColorProvider import used by the Android widget.
- Added NorthStar adaptive launcher icon.
- Incremented Android versionCode/versionName for the next APK test build.


## v0.5.4 — Paint workspace
- Full-viewport responsive shared canvas on web/PC.
- Added 15-color palette, custom color, brush size 1–64 px, pencil, marker and eraser.
- Added redo using the shared `stroke_restore` event.
- Android shared editor now has a horizontally scrollable palette and expanded tools.
- JARVIS viewer remains read-only and supports marker/restore rendering.


## 0.5.5 — Room chat and image paste
- Added persistent room chat and live WebSocket message broadcast.
- Added chat image attachments and clipboard paste into chat or directly onto the shared board.
- Added image picker and drag-and-drop support for the web editor.
- Added Android gallery-to-board and gallery-to-chat image sharing, plus chat image previews.
- J.A.R.V.I.S. remains read-only and displays chat and canvas images.
- Updated Android widget snapshot renderer for pasted images, marker, undo and redo.
- Added backend tests to GitHub Actions before APK build.
