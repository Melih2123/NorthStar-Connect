from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import secrets
import sqlite3
import string
import uuid
from collections import defaultdict
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, File, Form, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

try:
    import firebase_admin
    from firebase_admin import credentials, messaging
except Exception:  # Firebase is optional during local development.
    firebase_admin = None
    credentials = None
    messaging = None

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("northstar-connect")

ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = Path(os.getenv("NORTHSTAR_DATA_DIR", ROOT / "data"))
DRAWING_DIR = DATA_DIR / "drawings"
DB_PATH = DATA_DIR / "northstar.sqlite3"
MAX_UPLOAD_BYTES = 5 * 1024 * 1024
MAX_WS_MESSAGE_BYTES = 16 * 1024
MAX_CHAT_TEXT_CHARS = 2000
ALPHABET = string.ascii_uppercase + string.digits
ROOM_CODE_RE = re.compile(r"^[A-Z0-9]{12}$")
HEX_COLOR_RE = re.compile(r"^#[0-9A-Fa-f]{6}$")

DATA_DIR.mkdir(parents=True, exist_ok=True)
DRAWING_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(
    title="NorthStar Connect API",
    version="0.5.7",
    description="Room-based realtime collaborative canvas and chat",
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


@contextmanager
def db():
    con = sqlite3.connect(DB_PATH, timeout=20)
    con.row_factory = sqlite3.Row
    try:
        yield con
        con.commit()
    except Exception:
        con.rollback()
        raise
    finally:
        con.close()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def init_db() -> None:
    with db() as con:
        con.execute(
            """CREATE TABLE IF NOT EXISTS rooms (
                room_code TEXT PRIMARY KEY,
                created_at TEXT NOT NULL
            )"""
        )
        con.execute(
            """CREATE TABLE IF NOT EXISTS devices (
                room_code TEXT NOT NULL,
                device_id TEXT NOT NULL,
                fcm_token TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (room_code, device_id),
                FOREIGN KEY (room_code) REFERENCES rooms(room_code)
            )"""
        )
        # Legacy image upload table remains for backward compatibility.
        con.execute(
            """CREATE TABLE IF NOT EXISTS drawings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                room_code TEXT NOT NULL,
                filename TEXT NOT NULL,
                media_type TEXT NOT NULL,
                device_id TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY (room_code) REFERENCES rooms(room_code)
            )"""
        )
        # Every stroke operation receives a server-side sequence number.
        # All participants replay the same ordered event stream.
        con.execute(
            """CREATE TABLE IF NOT EXISTS canvas_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                room_code TEXT NOT NULL,
                event_type TEXT NOT NULL,
                stroke_id TEXT,
                payload_json TEXT NOT NULL,
                device_id TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY (room_code) REFERENCES rooms(room_code)
            )"""
        )
        con.execute(
            "CREATE INDEX IF NOT EXISTS idx_canvas_events_room_id ON canvas_events(room_code, id)"
        )
        con.execute(
            """CREATE TABLE IF NOT EXISTS room_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                room_code TEXT NOT NULL,
                device_id TEXT NOT NULL,
                sender_label TEXT NOT NULL,
                message_text TEXT NOT NULL,
                attachment_id INTEGER,
                created_at TEXT NOT NULL,
                FOREIGN KEY (room_code) REFERENCES rooms(room_code)
            )"""
        )
        con.execute(
            "CREATE INDEX IF NOT EXISTS idx_room_messages_room_id ON room_messages(room_code, id)"
        )


init_db()
WEB_DIR = Path(__file__).resolve().parents[2] / "web"


def room_exists(room_code: str) -> bool:
    with db() as con:
        row = con.execute(
            "SELECT 1 FROM rooms WHERE room_code = ?", (room_code,)
        ).fetchone()
    return row is not None


def init_firebase() -> bool:
    if firebase_admin is None:
        return False
    if firebase_admin._apps:
        return True
    raw = os.getenv("FIREBASE_SERVICE_ACCOUNT_JSON")
    if raw:
        try:
            firebase_admin.initialize_app(credentials.Certificate(json.loads(raw)))
            return True
        except Exception:
            log.exception("Could not initialize Firebase from service-account JSON.")
            return False
    try:
        firebase_admin.initialize_app()
        return True
    except Exception:
        log.info("Firebase credentials not configured; push notifications are disabled.")
        return False


def send_board_updated_push(room_code: str, sender_device_id: str, event_id: int) -> None:
    """Send data-only push. Android displays the requested generic notification text."""
    if not init_firebase():
        return
    with db() as con:
        rows = con.execute(
            "SELECT fcm_token FROM devices WHERE room_code = ? AND device_id != ?",
            (room_code, sender_device_id),
        ).fetchall()
    tokens = [row["fcm_token"] for row in rows if row["fcm_token"]]
    if not tokens:
        return
    try:
        result = messaging.send_each_for_multicast(
            messaging.MulticastMessage(
                tokens=tokens,
                data={
                    "type": "board_updated",
                    "room_code": room_code,
                    "event_id": str(event_id),
                },
                android=messaging.AndroidConfig(priority="high"),
            )
        )
        log.info("Board push sent to %s devices; failures=%s", len(tokens), result.failure_count)
    except Exception:
        log.exception("Firebase Cloud Messaging send failed.")


class CreateRoomResponse(BaseModel):
    room_code: str
    created_at: str


class RegisterDeviceRequest(BaseModel):
    device_id: str
    fcm_token: str


def event_for_client(row: sqlite3.Row) -> dict:
    return {
        "event_id": row["id"],
        "type": row["event_type"],
        "stroke_id": row["stroke_id"],
        "payload": json.loads(row["payload_json"]),
        "device_id": row["device_id"],
        "created_at": row["created_at"],
    }


def load_canvas_history(room_code: str) -> list[dict]:
    with db() as con:
        clear_row = con.execute(
            "SELECT MAX(id) AS clear_id FROM canvas_events WHERE room_code = ? AND event_type = 'clear'",
            (room_code,),
        ).fetchone()
        clear_id = int(clear_row["clear_id"] or 0)
        rows = con.execute(
            "SELECT * FROM canvas_events WHERE room_code = ? AND id >= ? ORDER BY id ASC",
            (room_code, clear_id or 1),
        ).fetchall()
    return [event_for_client(row) for row in rows]


def load_room_messages(room_code: str, limit: int = 100) -> list[dict]:
    with db() as con:
        rows = con.execute(
            "SELECT * FROM (SELECT * FROM room_messages WHERE room_code = ? ORDER BY id DESC LIMIT ?) ORDER BY id ASC",
            (room_code, max(1, min(limit, 100))),
        ).fetchall()
    return [chat_row_for_client(row) for row in rows]


def chat_row_for_client(row: sqlite3.Row) -> dict:
    attachment_id = row["attachment_id"]
    return {
        "id": row["id"],
        "device_id": row["device_id"],
        "sender": row["sender_label"],
        "text": row["message_text"],
        "attachment_id": attachment_id,
        "attachment_url": f"/api/rooms/{row['room_code']}/drawings/{attachment_id}/image" if attachment_id else None,
        "created_at": row["created_at"],
    }


def validate_chat_message(raw: object) -> tuple[str, Optional[int]]:
    if not isinstance(raw, dict):
        raise ValueError("Chat message must be a JSON object.")
    text = raw.get("text", "")
    sender = raw.get("sender", "Web")
    attachment_id = raw.get("attachment_id")
    if not isinstance(text, str) or len(text.strip()) > MAX_CHAT_TEXT_CHARS:
        raise ValueError(f"Message text must be at most {MAX_CHAT_TEXT_CHARS} characters.")
    if not isinstance(sender, str) or not sender.strip() or len(sender.strip()) > 24:
        raise ValueError("Sender label must be between 1 and 24 characters.")
    if not text.strip() and attachment_id is None:
        raise ValueError("Write a message or attach an image.")
    if attachment_id is not None:
        try:
            attachment_id = int(attachment_id)
        except (TypeError, ValueError):
            raise ValueError("Invalid image attachment.")
        if attachment_id <= 0:
            raise ValueError("Invalid image attachment.")
    return text.strip(), attachment_id


def persist_chat_message(room_code: str, device_id: str, sender: str, text: str, attachment_id: Optional[int]) -> dict:
    if attachment_id is not None:
        with db() as con:
            exists = con.execute(
                "SELECT 1 FROM drawings WHERE id = ? AND room_code = ?", (attachment_id, room_code)
            ).fetchone()
        if not exists:
            raise ValueError("Image attachment does not belong to this room.")
    created = utc_now()
    with db() as con:
        cursor = con.execute(
            "INSERT INTO room_messages(room_code, device_id, sender_label, message_text, attachment_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            (room_code, device_id, sender.strip()[:24], text, attachment_id, created),
        )
        message_id = int(cursor.lastrowid)
        row = con.execute("SELECT * FROM room_messages WHERE id = ?", (message_id,)).fetchone()
    return chat_row_for_client(row)


def validate_canvas_message(raw: object) -> tuple[str, Optional[str], dict]:
    if not isinstance(raw, dict):
        raise ValueError("Message must be a JSON object.")
    event_type = raw.get("type")
    if event_type not in {"stroke_begin", "stroke_points", "stroke_end", "stroke_delete", "stroke_restore", "image_add", "image_update", "image_delete", "clear"}:
        raise ValueError("Unsupported canvas event type.")

    stroke_id = raw.get("stroke_id")
    if event_type in {"stroke_begin", "stroke_points", "stroke_end"}:
        if not isinstance(stroke_id, str) or not (1 <= len(stroke_id) <= 80):
            raise ValueError("A valid stroke_id is required.")
    else:
        stroke_id = None

    payload = raw.get("payload", {})
    if not isinstance(payload, dict):
        raise ValueError("Payload must be an object.")

    def check_point(point: object) -> list[float]:
        if not isinstance(point, (list, tuple)) or len(point) != 2:
            raise ValueError("Each point must be [x, y].")
        x, y = float(point[0]), float(point[1])
        if not (0.0 <= x <= 1.0 and 0.0 <= y <= 1.0):
            raise ValueError("Point coordinates must be normalized between 0 and 1.")
        return [x, y]

    if event_type == "stroke_begin":
        color = payload.get("color", "#122632")
        tool = payload.get("tool", "pen")
        width = float(payload.get("width", 0.006))
        if not isinstance(color, str) or not HEX_COLOR_RE.fullmatch(color):
            raise ValueError("Color must be a six-digit HEX color.")
        if tool not in {"pen", "marker", "eraser"}:
            raise ValueError("Tool must be pen, marker or eraser.")
        if not (0.0005 <= width <= 0.05):
            raise ValueError("Brush width is out of range.")
        payload = {
            "color": color.upper(),
            "tool": tool,
            "width": width,
            "point": check_point(payload.get("point")),
        }
    elif event_type == "stroke_points":
        points = payload.get("points")
        if not isinstance(points, list) or not (1 <= len(points) <= 64):
            raise ValueError("Send between 1 and 64 points per update.")
        payload = {"points": [check_point(p) for p in points]}
    elif event_type in {"stroke_delete", "stroke_restore"}:
        target_id = payload.get("target_stroke_id")
        if not isinstance(target_id, str) or not (1 <= len(target_id) <= 80):
            raise ValueError("target_stroke_id is required.")
        payload = {"target_stroke_id": target_id}
    elif event_type in {"image_add", "image_update"}:
        try:
            image_id = int(payload.get("image_id"))
            x, y = float(payload.get("x", 0.2)), float(payload.get("y", 0.2))
            width, height = float(payload.get("width", 0.35)), float(payload.get("height", 0.35))
        except (TypeError, ValueError):
            raise ValueError("A valid image_id and image bounds are required.")
        if image_id <= 0 or not (0 <= x <= 1 and 0 <= y <= 1):
            raise ValueError("Invalid image position.")
        if not (0.02 <= width <= 1 and 0.02 <= height <= 1 and x + width <= 1.0001 and y + height <= 1.0001):
            raise ValueError("Image size or position is outside the shared canvas.")
        payload = {"image_id": image_id, "x": x, "y": y, "width": width, "height": height}
    elif event_type == "image_delete":
        try: image_id = int(payload.get("image_id"))
        except (TypeError, ValueError): raise ValueError("A valid image_id is required.")
        if image_id <= 0: raise ValueError("Invalid image_id.")
        payload = {"image_id": image_id}
    else:
        payload = {}
    return event_type, stroke_id, payload


def persist_canvas_event(room_code: str, event_type: str, stroke_id: Optional[str], payload: dict, device_id: str) -> dict:
    created = utc_now()
    with db() as con:
        cursor = con.execute(
            """INSERT INTO canvas_events(room_code, event_type, stroke_id, payload_json, device_id, created_at)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (room_code, event_type, stroke_id, json.dumps(payload, separators=(",", ":")), device_id, created),
        )
        event_id = int(cursor.lastrowid)
    return {
        "event_id": event_id,
        "type": event_type,
        "stroke_id": stroke_id,
        "payload": payload,
        "device_id": device_id,
        "created_at": created,
    }


@app.get("/health")
def health():
    return {"status": "ok", "service": "NorthStar Connect", "version": "0.5.7"}


@app.post("/api/rooms", response_model=CreateRoomResponse)
def create_room():
    for _ in range(10):
        code = "".join(secrets.choice(ALPHABET) for _ in range(12))
        created = utc_now()
        try:
            with db() as con:
                con.execute("INSERT INTO rooms(room_code, created_at) VALUES (?, ?)", (code, created))
            return {"room_code": code, "created_at": created}
        except sqlite3.IntegrityError:
            continue
    raise HTTPException(status_code=503, detail="Could not allocate a room code.")


@app.get("/api/rooms/{room_code}")
def get_room(room_code: str):
    code = room_code.upper()
    if not room_exists(code):
        raise HTTPException(status_code=404, detail="Room not found.")
    with db() as con:
        count = con.execute(
            "SELECT COUNT(*) AS n FROM drawings WHERE room_code = ?", (code,)
        ).fetchone()["n"]
        events = con.execute(
            "SELECT COUNT(*) AS n FROM canvas_events WHERE room_code = ?", (code,)
        ).fetchone()["n"]
        messages = con.execute(
            "SELECT COUNT(*) AS n FROM room_messages WHERE room_code = ?", (code,)
        ).fetchone()["n"]
    return {"room_code": code, "drawing_count": count, "canvas_event_count": events, "message_count": messages}


@app.post("/api/rooms/{room_code}/devices")
def register_device(room_code: str, payload: RegisterDeviceRequest):
    code = room_code.upper()
    if not room_exists(code):
        raise HTTPException(status_code=404, detail="Room not found.")
    device_id, token = payload.device_id.strip(), payload.fcm_token.strip()
    if not device_id or len(device_id) > 128 or not token or len(token) > 4096:
        raise HTTPException(status_code=400, detail="Invalid device registration.")
    with db() as con:
        con.execute(
            """INSERT INTO devices(room_code, device_id, fcm_token, updated_at)
               VALUES (?, ?, ?, ?)
               ON CONFLICT(room_code, device_id) DO UPDATE SET
                 fcm_token=excluded.fcm_token, updated_at=excluded.updated_at""",
            (code, device_id, token, utc_now()),
        )
    return {"registered": True}


@app.get("/api/rooms/{room_code}/canvas")
def get_canvas(room_code: str):
    code = room_code.upper()
    if not room_exists(code):
        raise HTTPException(status_code=404, detail="Room not found.")
    events = load_canvas_history(code)
    return {"room_code": code, "events": events, "last_event_id": events[-1]["event_id"] if events else 0}


@app.get("/api/rooms/{room_code}/updates")
def get_room_updates(
    room_code: str,
    after_event_id: int = 0,
    after_message_id: int = 0,
    limit: int = 100,
):
    """Small incremental feed for Android's non-Firebase notification fallback."""
    code = room_code.upper()
    if not room_exists(code):
        raise HTTPException(status_code=404, detail="Room not found.")
    after_event_id = max(0, after_event_id)
    after_message_id = max(0, after_message_id)
    limit = max(1, min(limit, 100))
    with db() as con:
        event_rows = con.execute(
            "SELECT * FROM canvas_events WHERE room_code = ? AND id > ? ORDER BY id ASC LIMIT ?",
            (code, after_event_id, limit),
        ).fetchall()
        message_rows = con.execute(
            "SELECT * FROM room_messages WHERE room_code = ? AND id > ? ORDER BY id ASC LIMIT ?",
            (code, after_message_id, limit),
        ).fetchall()
        latest_event = con.execute(
            "SELECT COALESCE(MAX(id), 0) AS id FROM canvas_events WHERE room_code = ?", (code,)
        ).fetchone()["id"]
        latest_message = con.execute(
            "SELECT COALESCE(MAX(id), 0) AS id FROM room_messages WHERE room_code = ?", (code,)
        ).fetchone()["id"]
    return {
        "room_code": code,
        "events": [event_for_client(row) for row in event_rows],
        "messages": [chat_row_for_client(row) for row in message_rows],
        "latest_event_id": int(latest_event),
        "latest_message_id": int(latest_message),
    }


# Legacy image upload endpoints are kept for compatibility with older v0.4 clients.
@app.post("/api/rooms/{room_code}/drawings")
async def upload_drawing(room_code: str, file: UploadFile = File(...), device_id: str = Form(default="web")):
    code = room_code.upper()
    if not room_exists(code):
        raise HTTPException(status_code=404, detail="Room not found.")
    media_type = (file.content_type or "").lower()
    allowed = {
        "image/png": (".png", b"\x89PNG\r\n\x1a\n"),
        "image/jpeg": (".jpg", b"\xff\xd8\xff"),
        "image/webp": (".webp", b"RIFF"),
    }
    if media_type not in allowed:
        raise HTTPException(status_code=415, detail="Only PNG, JPEG or WebP images are allowed.")
    content = await file.read(MAX_UPLOAD_BYTES + 1)
    if not content or len(content) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="Image must be between 1 byte and 5 MB.")
    extension, signature = allowed[media_type]
    if not content.startswith(signature) or (media_type == "image/webp" and content[8:12] != b"WEBP"):
        raise HTTPException(status_code=400, detail="Image signature does not match its media type.")
    filename = f"{uuid.uuid4().hex}{extension}"
    (DRAWING_DIR / filename).write_bytes(content)
    created = utc_now()
    with db() as con:
        cursor = con.execute(
            "INSERT INTO drawings(room_code, filename, media_type, device_id, created_at) VALUES (?, ?, ?, ?, ?)",
            (code, filename, media_type, device_id[:128], created),
        )
        drawing_id = int(cursor.lastrowid)
    return {"id": drawing_id, "room_code": code, "created_at": created, "media_type": media_type,
            "image_path": f"/api/rooms/{code}/drawings/{drawing_id}/image"}


@app.get("/api/rooms/{room_code}/drawings")
def list_drawings(room_code: str, after_id: int = 0, limit: int = 50):
    code = room_code.upper()
    if not room_exists(code):
        raise HTTPException(status_code=404, detail="Room not found.")
    limit = max(1, min(limit, 100))
    with db() as con:
        rows = con.execute(
            "SELECT id, media_type, device_id, created_at FROM drawings WHERE room_code = ? AND id > ? ORDER BY id ASC LIMIT ?",
            (code, max(0, after_id), limit),
        ).fetchall()
    return {"items": [{"id": row["id"], "media_type": row["media_type"], "created_at": row["created_at"],
                       "image_path": f"/api/rooms/{code}/drawings/{row['id']}/image"} for row in rows]}


@app.get("/api/rooms/{room_code}/drawings/{drawing_id}/image")
def get_drawing_image(room_code: str, drawing_id: int):
    code = room_code.upper()
    with db() as con:
        row = con.execute("SELECT filename, media_type FROM drawings WHERE id = ? AND room_code = ?", (drawing_id, code)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="Drawing not found.")
    path = DRAWING_DIR / row["filename"]
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Drawing file is missing.")
    return FileResponse(path, media_type=row["media_type"], filename=f"northstar-{drawing_id}{path.suffix}")


# Active connections and one per-room lock keep snapshot delivery and event sequencing consistent.
ROOM_CLIENTS: dict[str, set[WebSocket]] = defaultdict(set)
ROOM_LOCKS: dict[str, asyncio.Lock] = defaultdict(asyncio.Lock)
LAST_ROOM_PUSH_AT: dict[str, float] = {}
PUSH_COOLDOWN_SECONDS = 12.0


async def broadcast_event(room_code: str, event: dict) -> None:
    dead: list[WebSocket] = []
    for socket in tuple(ROOM_CLIENTS[room_code]):
        try:
            await socket.send_json({"type": "event", "event": event})
        except Exception:
            dead.append(socket)
    for socket in dead:
        ROOM_CLIENTS[room_code].discard(socket)


async def broadcast_chat_message(room_code: str, message: dict) -> None:
    dead: list[WebSocket] = []
    for socket in tuple(ROOM_CLIENTS[room_code]):
        try:
            await socket.send_json({"type": "chat_message", "message": message})
        except Exception:
            dead.append(socket)
    for socket in dead:
        ROOM_CLIENTS[room_code].discard(socket)


async def broadcast_presence(room_code: str) -> None:
    # A stable installation/device is one participant, not one per socket.
    roles_by_device: dict[str, str] = {}
    for socket in tuple(ROOM_CLIENTS[room_code]):
        device_id = getattr(socket.state, "device_id", "unknown")
        roles_by_device[device_id] = getattr(socket.state, "role", "editor")
    viewers = sum(1 for role in roles_by_device.values() if role == "viewer")
    payload = {
        "type": "presence",
        "participants": len(roles_by_device),
        "viewers": viewers,
        "editors": len(roles_by_device) - viewers,
    }
    dead: list[WebSocket] = []
    for socket in tuple(ROOM_CLIENTS[room_code]):
        try:
            await socket.send_json(payload)
        except Exception:
            dead.append(socket)
    for socket in dead:
        ROOM_CLIENTS[room_code].discard(socket)


@app.websocket("/ws/rooms/{room_code}")
async def collaborative_canvas(websocket: WebSocket, room_code: str):
    code = room_code.upper()
    device_id = websocket.query_params.get("device_id", "web")[:128]
    role = websocket.query_params.get("role", "editor").lower()
    if role not in {"editor", "viewer"}:
        await websocket.close(code=4400, reason="Invalid client role")
        return
    if not ROOM_CODE_RE.fullmatch(code) or not room_exists(code):
        await websocket.close(code=4404, reason="Room not found")
        return

    await websocket.accept()
    websocket.state.role = role
    websocket.state.device_id = device_id
    lock = ROOM_LOCKS[code]
    async with lock:
        # Replace a stale socket from this same installation on reconnect.
        for existing in tuple(ROOM_CLIENTS[code]):
            if getattr(existing.state, "device_id", None) == device_id:
                ROOM_CLIENTS[code].discard(existing)
                try:
                    await existing.close(code=4001, reason="Replaced by newer connection from this device")
                except Exception:
                    pass

        # Send authoritative history before registering the new peer.
        await websocket.send_json({
            "type": "history",
            "events": load_canvas_history(code),
            "messages": load_room_messages(code),
        })
        ROOM_CLIENTS[code].add(websocket)
        await broadcast_presence(code)

    try:
        while True:
            text = await websocket.receive_text()
            if role == "viewer":
                await websocket.send_json({
                    "type": "error",
                    "code": "read_only",
                    "message": "VIEWER ONLY: this connection cannot edit the shared canvas.",
                })
                continue
            if len(text.encode("utf-8")) > MAX_WS_MESSAGE_BYTES:
                await websocket.send_json({"type": "error", "message": "Message is too large."})
                continue
            try:
                raw = json.loads(text)
                if not isinstance(raw, dict):
                    raise ValueError("Message must be a JSON object.")
                if raw.get("type") == "chat_message":
                    chat_text, attachment_id = validate_chat_message(raw.get("payload", {}))
                    message = persist_chat_message(
                        code, device_id, str(raw.get("payload", {}).get("sender", "Web")), chat_text, attachment_id
                    )
                    await broadcast_chat_message(code, message)
                    now = asyncio.get_running_loop().time()
                    last_push = LAST_ROOM_PUSH_AT.get(code, 0.0)
                    if now - last_push >= PUSH_COOLDOWN_SECONDS:
                        LAST_ROOM_PUSH_AT[code] = now
                        try:
                            await asyncio.to_thread(send_board_updated_push, code, device_id, int(message["id"]))
                        except Exception:
                            log.exception("Could not send room chat notification.")
                    continue
                event_type, stroke_id, payload = validate_canvas_message(raw)
                if event_type in {"image_add", "image_update", "image_delete"}:
                    with db() as con:
                        image_row = con.execute(
                            "SELECT 1 FROM drawings WHERE id = ? AND room_code = ?",
                            (payload["image_id"], code),
                        ).fetchone()
                    if not image_row:
                        raise ValueError("This image is not uploaded to the current room.")
                    if event_type == "image_add":
                        payload["image_url"] = f"/api/rooms/{code}/drawings/{payload['image_id']}/image"
            except (json.JSONDecodeError, ValueError, TypeError) as exc:
                await websocket.send_json({"type": "error", "message": str(exc)})
                continue

            should_push = False
            async with lock:
                event = persist_canvas_event(code, event_type, stroke_id, payload, device_id)
                await broadcast_event(code, event)
                if event_type in {"stroke_end", "clear", "image_add", "image_update", "image_delete"}:
                    now = asyncio.get_running_loop().time()
                    last_push = LAST_ROOM_PUSH_AT.get(code, 0.0)
                    if now - last_push >= PUSH_COOLDOWN_SECONDS:
                        LAST_ROOM_PUSH_AT[code] = now
                        should_push = True

            if should_push:
                try:
                    await asyncio.to_thread(send_board_updated_push, code, device_id, event["event_id"])
                except Exception:
                    log.exception("Could not send board update notification.")

    except WebSocketDisconnect:
        pass
    except Exception:
        log.exception("Collaborative canvas socket failed for room %s", code)
    finally:
        async with lock:
            ROOM_CLIENTS[code].discard(websocket)
            await broadcast_presence(code)


# Serve the shared drawing page from the same Funnel HTTPS origin.
# This mount is intentionally last so it cannot shadow API or WebSocket routes.
if WEB_DIR.is_dir():
    app.mount("/", StaticFiles(directory=WEB_DIR, html=True), name="web")
