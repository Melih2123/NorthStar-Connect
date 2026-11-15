import json

from app.main import validate_canvas_message


def test_valid_stroke_begin_is_normalized():
    event_type, stroke_id, payload = validate_canvas_message({
        "type": "stroke_begin",
        "stroke_id": "stroke-1",
        "payload": {
            "point": [0.25, 0.75],
            "color": "#12abEF",
            "width": 0.006,
            "tool": "pen",
        },
    })
    assert event_type == "stroke_begin"
    assert stroke_id == "stroke-1"
    assert payload["color"] == "#12ABEF"
    assert payload["point"] == [0.25, 0.75]


def test_invalid_point_is_rejected():
    try:
        validate_canvas_message({
            "type": "stroke_begin",
            "stroke_id": "stroke-2",
            "payload": {"point": [1.2, -0.1]},
        })
    except ValueError as exc:
        assert "normalized" in str(exc)
    else:
        raise AssertionError("Out-of-range coordinates should be rejected")


def test_point_batch_is_limited():
    try:
        validate_canvas_message({
            "type": "stroke_points",
            "stroke_id": "stroke-3",
            "payload": {"points": [[0.5, 0.5]] * 65},
        })
    except ValueError as exc:
        assert "64" in str(exc)
    else:
        raise AssertionError("More than 64 points should be rejected")


def test_clear_has_no_payload():
    event_type, stroke_id, payload = validate_canvas_message({"type": "clear"})
    assert event_type == "clear"
    assert stroke_id is None
    assert payload == {}


def test_two_websocket_clients_share_live_events():
    from fastapi.testclient import TestClient
    from app.main import app

    with TestClient(app) as client:
        room = client.post("/api/rooms").json()["room_code"]
        with client.websocket_connect(f"/ws/rooms/{room}?device_id=peer-one") as first:
            first_history = first.receive_json()
            assert first_history["type"] == "history"
            # Initial presence event follows the history snapshot.
            assert first.receive_json()["type"] == "presence"

            with client.websocket_connect(f"/ws/rooms/{room}?device_id=peer-two") as second:
                second_history = second.receive_json()
                assert second_history["type"] == "history"
                assert len(second_history["events"]) == 0
                assert first.receive_json()["type"] == "presence"
                assert second.receive_json()["type"] == "presence"

                first.send_json({
                    "type": "stroke_begin",
                    "stroke_id": "shared-stroke-1",
                    "payload": {
                        "point": [0.1, 0.2],
                        "color": "#123456",
                        "width": 0.006,
                        "tool": "pen",
                    },
                })
                one_event = first.receive_json()
                two_event = second.receive_json()
                assert one_event["type"] == "event"
                assert two_event["event"]["stroke_id"] == "shared-stroke-1"

                first.send_json({
                    "type": "stroke_points",
                    "stroke_id": "shared-stroke-1",
                    "payload": {"points": [[0.2, 0.3], [0.3, 0.4]]},
                })
                assert first.receive_json()["event"]["type"] == "stroke_points"
                assert second.receive_json()["event"]["payload"]["points"] == [[0.2, 0.3], [0.3, 0.4]]

                first.send_json({"type": "stroke_end", "stroke_id": "shared-stroke-1", "payload": {}})
                assert first.receive_json()["event"]["type"] == "stroke_end"
                assert second.receive_json()["event"]["type"] == "stroke_end"

                first.send_json({"type": "stroke_delete", "payload": {"target_stroke_id": "shared-stroke-1"}})
                assert first.receive_json()["event"]["type"] == "stroke_delete"
                assert second.receive_json()["event"]["type"] == "stroke_delete"

                first.send_json({"type": "clear", "payload": {}})
                assert first.receive_json()["event"]["type"] == "clear"
                assert second.receive_json()["event"]["type"] == "clear"

                first.send_json({
                    "type": "stroke_begin",
                    "stroke_id": "stroke-after-clear",
                    "payload": {"point": [0.4, 0.4], "color": "#00AACC", "width": 0.007, "tool": "pen"},
                })
                assert first.receive_json()["event"]["stroke_id"] == "stroke-after-clear"
                assert second.receive_json()["event"]["stroke_id"] == "stroke-after-clear"

        # The persisted current board survives disconnect/reconnect; cleared strokes stay cleared.
        with client.websocket_connect(f"/ws/rooms/{room}?device_id=peer-three") as third:
            history = third.receive_json()
            assert history["type"] == "history"
            assert [event["type"] for event in history["events"]] == ["clear", "stroke_begin"]
            assert history["events"][-1]["stroke_id"] == "stroke-after-clear"


def test_delete_stroke_does_not_require_its_own_stroke_id():
    event_type, stroke_id, payload = validate_canvas_message({
        "type": "stroke_delete",
        "payload": {"target_stroke_id": "stroke-to-remove"},
    })
    assert event_type == "stroke_delete"
    assert stroke_id is None
    assert payload["target_stroke_id"] == "stroke-to-remove"


def test_viewer_websocket_is_read_only_and_cannot_persist_events():
    from fastapi.testclient import TestClient
    from app.main import app

    with TestClient(app) as client:
        room = client.post("/api/rooms").json()["room_code"]
        with client.websocket_connect(f"/ws/rooms/{room}?device_id=jarvis-viewer&role=viewer") as viewer:
            assert viewer.receive_json()["type"] == "history"
            presence = viewer.receive_json()
            assert presence["type"] == "presence"
            assert presence["viewers"] == 1
            assert presence["editors"] == 0
            viewer.send_json({
                "type": "stroke_begin",
                "stroke_id": "must-not-save",
                "payload": {
                    "point": [0.2, 0.2], "color": "#123456",
                    "width": 0.006, "tool": "pen"
                },
            })
            rejected = viewer.receive_json()
            assert rejected["type"] == "error"
            assert rejected["code"] == "read_only"

        with client.websocket_connect(f"/ws/rooms/{room}?device_id=editor&role=editor") as editor:
            history = editor.receive_json()
            assert history["type"] == "history"
            assert history["events"] == []


def test_marker_tool_and_restore_event_are_accepted():
    marker_type, marker_id, marker_payload = validate_canvas_message({
        "type": "stroke_begin",
        "stroke_id": "marker-stroke",
        "payload": {
            "point": [0.1, 0.2],
            "color": "#F59E0B",
            "width": 0.02,
            "tool": "marker",
        },
    })
    assert marker_type == "stroke_begin"
    assert marker_id == "marker-stroke"
    assert marker_payload["tool"] == "marker"

    event_type, stroke_id, payload = validate_canvas_message({
        "type": "stroke_restore",
        "payload": {"target_stroke_id": "marker-stroke"},
    })
    assert event_type == "stroke_restore"
    assert stroke_id is None
    assert payload["target_stroke_id"] == "marker-stroke"


def test_websocket_clients_receive_restore_event():
    from fastapi.testclient import TestClient
    from app.main import app

    with TestClient(app) as client:
        room = client.post("/api/rooms").json()["room_code"]
        with client.websocket_connect(f"/ws/rooms/{room}?device_id=author") as author:
            author.receive_json()  # history
            author.receive_json()  # presence
            with client.websocket_connect(f"/ws/rooms/{room}?device_id=peer") as peer:
                peer.receive_json()
                author.receive_json()  # updated presence
                peer.receive_json()   # presence

                author.send_json({
                    "type": "stroke_begin",
                    "stroke_id": "redo-stroke",
                    "payload": {
                        "point": [0.1, 0.1], "color": "#EF4444",
                        "width": 0.01, "tool": "marker"
                    }
                })
                author.receive_json()
                peer.receive_json()

                author.send_json({"type": "stroke_delete", "payload": {"target_stroke_id": "redo-stroke"}})
                assert author.receive_json()["event"]["type"] == "stroke_delete"
                assert peer.receive_json()["event"]["type"] == "stroke_delete"

                author.send_json({"type": "stroke_restore", "payload": {"target_stroke_id": "redo-stroke"}})
                restored_author = author.receive_json()
                restored_peer = peer.receive_json()
                assert restored_author["event"]["type"] == "stroke_restore"
                assert restored_peer["event"]["payload"]["target_stroke_id"] == "redo-stroke"



def test_chat_message_validation_limits_text_and_requires_content():
    from app.main import validate_chat_message
    text, image_id = validate_chat_message({"text": "Selam oda!", "sender": "Web / PC"})
    assert text == "Selam oda!"
    assert image_id is None

    try:
        validate_chat_message({"text": "x" * 2001, "sender": "PC"})
    except ValueError as exc:
        assert "2000" in str(exc)
    else:
        raise AssertionError("Oversized chat message should be rejected")

    try:
        validate_chat_message({"text": "   ", "sender": "PC"})
    except ValueError as exc:
        assert "Write a message" in str(exc)
    else:
        raise AssertionError("Empty chat message should be rejected")


def test_room_chat_persists_and_broadcasts_over_room_websocket():
    from fastapi.testclient import TestClient
    from app.main import app

    with TestClient(app) as client:
        room = client.post("/api/rooms").json()["room_code"]
        with client.websocket_connect(f"/ws/rooms/{room}?device_id=web-pc") as pc:
            history = pc.receive_json()
            assert history["type"] == "history"
            assert history["messages"] == []
            pc.receive_json()  # presence
            with client.websocket_connect(f"/ws/rooms/{room}?device_id=android-phone") as android:
                android.receive_json()
                pc.receive_json()  # presence update
                android.receive_json()

                pc.send_json({"type": "chat_message", "payload": {"sender": "Web / PC", "text": "Selam!"}})
                pc_msg = pc.receive_json()
                android_msg = android.receive_json()
                assert pc_msg["type"] == "chat_message"
                assert pc_msg["message"]["text"] == "Selam!"
                assert android_msg["message"]["sender"] == "Web / PC"

                android.send_json({"type": "chat_message", "payload": {"sender": "Android", "text": "Merhaba!"}})
                assert pc.receive_json()["message"]["text"] == "Merhaba!"
                assert android.receive_json()["message"]["text"] == "Merhaba!"

        with client.websocket_connect(f"/ws/rooms/{room}?device_id=viewer&role=viewer") as viewer:
            history = viewer.receive_json()
            assert [message["text"] for message in history["messages"]] == ["Selam!", "Merhaba!"]
            viewer.receive_json()  # presence
            viewer.send_json({"type": "chat_message", "payload": {"sender": "Viewer", "text": "not allowed"}})
            assert viewer.receive_json()["code"] == "read_only"


def test_image_add_event_is_validated_and_room_scoped():
    from fastapi.testclient import TestClient
    from app.main import app, validate_canvas_message

    event_type, stroke_id, payload = validate_canvas_message({
        "type": "image_add",
        "payload": {"image_id": 1, "x": 0.2, "y": 0.3, "width": 0.4, "height": 0.5},
    })
    assert event_type == "image_add"
    assert stroke_id is None
    assert payload["image_id"] == 1

    try:
        validate_canvas_message({"type": "image_add", "payload": {"image_id": 1, "x": 0.9, "y": 0.9, "width": 0.5, "height": 0.5}})
    except ValueError as exc:
        assert "outside" in str(exc)
    else:
        raise AssertionError("Out-of-bounds image placement should be rejected")

    with TestClient(app) as client:
        room = client.post("/api/rooms").json()["room_code"]
        with client.websocket_connect(f"/ws/rooms/{room}?device_id=editor") as ws:
            ws.receive_json(); ws.receive_json()
            ws.send_json({"type": "image_add", "payload": {"image_id": 999999, "x": 0.1, "y": 0.1, "width": 0.3, "height": 0.3}})
            response = ws.receive_json()
            assert response["type"] == "error"
            assert "not uploaded" in response["message"]


def _make_one_pixel_png():
    import struct
    import zlib

    def chunk(tag, data):
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


def test_uploaded_image_can_be_added_to_shared_canvas_and_replayed():
    from fastapi.testclient import TestClient
    from app.main import app

    with TestClient(app) as client:
        room = client.post("/api/rooms").json()["room_code"]
        upload = client.post(
            f"/api/rooms/{room}/drawings",
            files={"file": ("paste.png", _make_one_pixel_png(), "image/png")},
            data={"device_id": "web-editor"},
        )
        assert upload.status_code == 200
        image_id = upload.json()["id"]

        with client.websocket_connect(f"/ws/rooms/{room}?device_id=web-editor") as editor:
            editor.receive_json()  # history
            editor.receive_json()  # presence
            with client.websocket_connect(f"/ws/rooms/{room}?device_id=android-editor") as android:
                android.receive_json()  # history
                editor.receive_json()  # presence update
                android.receive_json()  # presence

                event = {
                    "type": "image_add",
                    "payload": {
                        "image_id": image_id,
                        "x": 0.2,
                        "y": 0.2,
                        "width": 0.4,
                        "height": 0.3,
                    },
                }
                editor.send_json(event)
                one = editor.receive_json()
                two = android.receive_json()
                assert one["type"] == "event"
                assert one["event"]["type"] == "image_add"
                assert one["event"]["payload"]["image_url"].endswith(f"/{image_id}/image")
                assert two["event"]["payload"]["image_id"] == image_id

        history = client.get(f"/api/rooms/{room}/canvas").json()
        assert history["events"][-1]["type"] == "image_add"
        image = client.get(f"/api/rooms/{room}/drawings/{image_id}/image")
        assert image.status_code == 200
        assert image.content.startswith(b"\x89PNG\r\n\x1a\n")


def test_chat_can_share_uploaded_image_and_replay_attachment_url():
    from fastapi.testclient import TestClient
    from app.main import app

    with TestClient(app) as client:
        room = client.post("/api/rooms").json()["room_code"]
        upload = client.post(
            f"/api/rooms/{room}/drawings",
            files={"file": ("chat-photo.png", _make_one_pixel_png(), "image/png")},
            data={"device_id": "desktop"},
        )
        image_id = upload.json()["id"]

        with client.websocket_connect(f"/ws/rooms/{room}?device_id=desktop") as editor:
            history = editor.receive_json()
            assert history["messages"] == []
            editor.receive_json()  # presence
            editor.send_json({
                "type": "chat_message",
                "payload": {
                    "sender": "Web / PC",
                    "text": "Bak buna!",
                    "attachment_id": image_id,
                },
            })
            message = editor.receive_json()["message"]
            assert message["text"] == "Bak buna!"
            assert message["attachment_id"] == image_id
            assert message["attachment_url"].endswith(f"/{image_id}/image")

        with client.websocket_connect(f"/ws/rooms/{room}?device_id=android") as peer:
            history = peer.receive_json()
            assert len(history["messages"]) == 1
            assert history["messages"][0]["attachment_id"] == image_id
            assert history["messages"][0]["attachment_url"].endswith(f"/{image_id}/image")


def test_image_update_event_is_normalized():
    event_type, stroke_id, payload = validate_canvas_message({
        "type": "image_update", "payload": {"image_id": 12, "x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4}
    })
    assert event_type == "image_update" and stroke_id is None
    assert payload == {"image_id": 12, "x": 0.1, "y": 0.2, "width": 0.3, "height": 0.4}


def test_image_delete_event_is_validated():
    event_type, stroke_id, payload = validate_canvas_message({"type": "image_delete", "payload": {"image_id": 12}})
    assert event_type == "image_delete" and payload == {"image_id": 12}


def test_invalid_image_transform_is_rejected():
    try:
        validate_canvas_message({"type": "image_update", "payload": {"image_id": 1, "x": 0.9, "y": 0.9, "width": 0.4, "height": 0.4}})
    except ValueError as exc:
        assert "outside" in str(exc)
    else:
        raise AssertionError("Out-of-bounds image transforms must be rejected")
