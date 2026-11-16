import struct
import zlib

from fastapi.testclient import TestClient

from app.main import app


def make_tiny_png() -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    raw = b"\x00\xff\x00\x00\xff"
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">2I5B", 1, 1, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


def test_room_upload_list_and_download():
    with TestClient(app) as client:
        room_response = client.post("/api/rooms")
        assert room_response.status_code == 200
        room_code = room_response.json()["room_code"]
        assert len(room_code) == 12

        upload = client.post(
            f"/api/rooms/{room_code}/drawings",
            files={"file": ("test.png", make_tiny_png(), "image/png")},
            data={"device_id": "pytest"},
        )
        assert upload.status_code == 200
        drawing_id = upload.json()["id"]

        listing = client.get(f"/api/rooms/{room_code}/drawings?after_id=0")
        assert listing.status_code == 200
        assert listing.json()["items"][0]["id"] == drawing_id

        image = client.get(
            f"/api/rooms/{room_code}/drawings/{drawing_id}/image"
        )
        assert image.status_code == 200
        assert image.content.startswith(b"\x89PNG\r\n\x1a\n")


def test_web_editor_and_jarvis_homepage_are_served():
    with TestClient(app) as client:
        home = client.get("/")
        editor = client.get("/connect-drawing.html")
        friends = client.get("/friends.html")
        assert home.status_code == 200
        assert "NORTHSTAR" in home.text.upper()
        assert editor.status_code == 200
        assert "ROOM CHAT" in editor.text
        assert "pasteImageToCanvas" in editor.text
        assert friends.status_code == 200
        assert "HESAP" in friends.text
        assert "RTCPeerConnection" in friends.text


def test_updates_endpoint_returns_incremental_cursors_and_chat():
    with TestClient(app) as client:
        room = client.post("/api/rooms").json()["room_code"]
        initial = client.get(f"/api/rooms/{room}/updates")
        assert initial.status_code == 200
        assert initial.json()["latest_event_id"] == 0
        assert initial.json()["latest_message_id"] == 0

        with client.websocket_connect(f"/ws/rooms/{room}?device_id=web-test") as socket:
            history = socket.receive_json()
            assert history["type"] == "history"
            socket.receive_json()  # presence
            socket.send_json({
                "type": "chat_message",
                "payload": {"text": "Fallback test message", "sender": "Web Test"},
            })
            chat_event = socket.receive_json()
            assert chat_event["type"] == "chat_message"
            socket.send_json({
                "type": "stroke_begin",
                "stroke_id": "fallback-stroke",
                "payload": {"point": [0.2, 0.3], "color": "#123456", "width": 0.006, "tool": "pen"},
            })
            stroke_event = socket.receive_json()
            assert stroke_event["type"] == "event"

        updates = client.get(f"/api/rooms/{room}/updates?after_event_id=0&after_message_id=0")
        assert updates.status_code == 200
        payload = updates.json()
        assert len(payload["messages"]) == 1
        assert payload["messages"][0]["text"] == "Fallback test message"
        assert payload["latest_message_id"] == payload["messages"][0]["id"]
        assert len(payload["events"]) == 1
        assert payload["events"][0]["type"] == "stroke_begin"
        assert payload["latest_event_id"] == payload["events"][0]["event_id"]

