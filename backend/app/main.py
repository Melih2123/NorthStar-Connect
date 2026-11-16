from __future__ import annotations

import asyncio
import json
import hashlib
import logging
import os
import re
import secrets
import sqlite3
import string
import time
import uuid
from collections import defaultdict
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from argon2 import PasswordHasher, Type
from argon2.exceptions import VerifyMismatchError, VerificationError

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
ACCOUNT_TOKEN_DAYS = 30
SIGNALING_TICKET_SECONDS = 60
SIGNALING_MESSAGE_LIMIT = 16 * 1024
password_hasher = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=2, hash_len=32, salt_len=16, type=Type.ID)
ALPHABET = string.ascii_uppercase + string.digits
ROOM_CODE_RE = re.compile(r"^[A-Z0-9]{12}$")
HEX_COLOR_RE = re.compile(r"^#[0-9A-Fa-f]{6}$")

DATA_DIR.mkdir(parents=True, exist_ok=True)
DRAWING_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(
    title="NorthStar Connect API",
    version="0.6.0",
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
        con.execute(
            """CREATE TABLE IF NOT EXISTS accounts (
                account_id TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                username_normalized TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                created_at TEXT NOT NULL
            )"""
        )
        con.execute(
            """CREATE TABLE IF NOT EXISTS account_sessions (
                token_hash TEXT PRIMARY KEY,
                account_id TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )"""
        )
        con.execute("CREATE INDEX IF NOT EXISTS idx_account_sessions_account ON account_sessions(account_id)")
        con.execute(
            """CREATE TABLE IF NOT EXISTS account_devices (
                account_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                fcm_token TEXT,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (account_id, device_id),
                FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )"""
        )
        con.execute(
            """CREATE TABLE IF NOT EXISTS friend_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                requester_id TEXT NOT NULL,
                target_id TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                UNIQUE(requester_id, target_id),
                FOREIGN KEY (requester_id) REFERENCES accounts(account_id) ON DELETE CASCADE,
                FOREIGN KEY (target_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )"""
        )
        con.execute("CREATE INDEX IF NOT EXISTS idx_friend_requests_target ON friend_requests(target_id, status)")
        con.execute(
            """CREATE TABLE IF NOT EXISTS friend_invitations (
                id TEXT PRIMARY KEY,
                sender_id TEXT NOT NULL,
                target_id TEXT NOT NULL,
                invite_type TEXT NOT NULL,
                room_code TEXT,
                status TEXT NOT NULL DEFAULT 'pending',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY (sender_id) REFERENCES accounts(account_id) ON DELETE CASCADE,
                FOREIGN KEY (target_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )"""
        )
        con.execute("CREATE INDEX IF NOT EXISTS idx_friend_invites_target ON friend_invitations(target_id, status)")


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

class AccountCredentials(BaseModel):
    username: str = Field(min_length=3, max_length=24)
    password: str = Field(min_length=10, max_length=128)


class AccountDeviceRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    fcm_token: str | None = Field(default=None, max_length=4096)


class FriendRequestBody(BaseModel):
    username: str = Field(min_length=3, max_length=24)


class FriendInviteBody(BaseModel):
    invite_type: str = Field(default="direct", pattern="^(direct|room)$")
    room_code: str | None = Field(default=None, max_length=12)


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


def account_public(row: sqlite3.Row) -> dict:
    return {
        "account_id": row["account_id"],
        "username": row["username"],
        "created_at": row["created_at"],
    }


def create_session(account_id: str) -> tuple[str, str]:
    token = secrets.token_urlsafe(40)
    token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
    created = datetime.now(timezone.utc)
    expires = (created + timedelta(days=ACCOUNT_TOKEN_DAYS)).isoformat()
    with db() as con:
        con.execute(
            "INSERT INTO account_sessions(token_hash, account_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
            (token_hash, account_id, expires, created.isoformat()),
        )
    return token, expires


def require_account(authorization: str = Header(default="")) -> dict:
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=401, detail="Sign in to a NorthStar account first.")
    token_hash = hashlib.sha256(token.strip().encode("utf-8")).hexdigest()
    with db() as con:
        row = con.execute(
            """SELECT a.account_id, a.username, a.username_normalized, s.expires_at
               FROM account_sessions s JOIN accounts a ON a.account_id = s.account_id
               WHERE s.token_hash = ?""",
            (token_hash,),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=401, detail="Invalid or expired account session.")
        if row["expires_at"] <= utc_now():
            con.execute("DELETE FROM account_sessions WHERE token_hash = ?", (token_hash,))
            raise HTTPException(status_code=401, detail="Account session expired. Please sign in again.")
    return dict(row)


def are_friends(account_a: str, account_b: str) -> bool:
    with db() as con:
        row = con.execute(
            """SELECT 1 FROM friend_requests WHERE status='accepted' AND
               ((requester_id=? AND target_id=?) OR (requester_id=? AND target_id=?))""",
            (account_a, account_b, account_b, account_a),
        ).fetchone()
    return row is not None


def send_account_push(account_id: str, data: dict) -> int:
    if not init_firebase():
        return 0
    with db() as con:
        rows = con.execute(
            "SELECT fcm_token FROM account_devices WHERE account_id=? AND fcm_token IS NOT NULL",
            (account_id,),
        ).fetchall()
    tokens = [r["fcm_token"] for r in rows if r["fcm_token"]]
    if not tokens:
        return 0
    try:
        result = messaging.send_each_for_multicast(
            messaging.MulticastMessage(
                tokens=tokens,
                data={k: str(v) for k, v in data.items()},
                android=messaging.AndroidConfig(priority="high"),
            )
        )
        return len(tokens) - result.failure_count
    except Exception:
        log.exception("Account push notification failed.")
        return 0


@app.post("/api/accounts/register")
def register_account(payload: AccountCredentials):
    username = payload.username.strip()
    normalized = username.casefold()
    if not re.fullmatch(r"[A-Za-z0-9_.-]{3,24}", username):
        raise HTTPException(status_code=400, detail="Username must be 3-24 characters: letters, numbers, dot, dash or underscore.")
    if len(payload.password) < 10 or not any(c.isalpha() for c in payload.password) or not any(c.isdigit() for c in payload.password):
        raise HTTPException(status_code=400, detail="Password must be at least 10 characters and contain letters and numbers.")
    account_id = uuid.uuid4().hex
    created = utc_now()
    password_hash = password_hasher.hash(payload.password)
    try:
        with db() as con:
            con.execute(
                "INSERT INTO accounts(account_id, username, username_normalized, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
                (account_id, username, normalized, password_hash, created),
            )
    except sqlite3.IntegrityError:
        raise HTTPException(status_code=409, detail="This username is already taken.")
    token, expires = create_session(account_id)
    return {"account": {"account_id": account_id, "username": username, "created_at": created}, "access_token": token, "token_type": "bearer", "expires_at": expires}


@app.post("/api/accounts/login")
def login_account(payload: AccountCredentials):
    normalized = payload.username.strip().casefold()
    with db() as con:
        row = con.execute("SELECT * FROM accounts WHERE username_normalized=?", (normalized,)).fetchone()
    if not row:
        raise HTTPException(status_code=401, detail="Username or password is incorrect.")
    try:
        password_hasher.verify(row["password_hash"], payload.password)
    except (VerifyMismatchError, VerificationError):
        raise HTTPException(status_code=401, detail="Username or password is incorrect.")
    if password_hasher.check_needs_rehash(row["password_hash"]):
        with db() as con:
            con.execute("UPDATE accounts SET password_hash=? WHERE account_id=?", (password_hasher.hash(payload.password), row["account_id"]))
    token, expires = create_session(row["account_id"])
    return {"account": account_public(row), "access_token": token, "token_type": "bearer", "expires_at": expires}


@app.get("/api/accounts/me")
def get_my_account(account: dict = Depends(require_account)):
    return {"account_id": account["account_id"], "username": account["username"]}


@app.post("/api/accounts/logout")
def logout_account(authorization: str = Header(default=""), account: dict = Depends(require_account)):
    _, _, token = authorization.partition(" ")
    token_hash = hashlib.sha256(token.strip().encode("utf-8")).hexdigest()
    with db() as con:
        con.execute("DELETE FROM account_sessions WHERE token_hash=?", (token_hash,))
    return {"logged_out": True}


@app.post("/api/accounts/devices")
def register_account_device(payload: AccountDeviceRequest, account: dict = Depends(require_account)):
    with db() as con:
        con.execute(
            """INSERT INTO account_devices(account_id, device_id, fcm_token, updated_at) VALUES (?, ?, ?, ?)
               ON CONFLICT(account_id, device_id) DO UPDATE SET
                 fcm_token=COALESCE(excluded.fcm_token, account_devices.fcm_token),
                 updated_at=excluded.updated_at""",
            (account["account_id"], payload.device_id.strip(), payload.fcm_token, utc_now()),
        )
    return {"registered": True, "account_id": account["account_id"]}


@app.delete("/api/accounts/devices/{device_id}")
def unregister_account_device(device_id: str, account: dict = Depends(require_account)):
    with db() as con:
        con.execute("DELETE FROM account_devices WHERE account_id=? AND device_id=?", (account["account_id"], device_id))
    return {"unregistered": True}


@app.post("/api/friends/requests")
def send_friend_request(payload: FriendRequestBody, account: dict = Depends(require_account)):
    target_name = payload.username.strip()
    with db() as con:
        target = con.execute("SELECT account_id, username FROM accounts WHERE username_normalized=?", (target_name.casefold(),)).fetchone()
        if not target:
            raise HTTPException(status_code=404, detail="NorthStar account not found.")
        if target["account_id"] == account["account_id"]:
            raise HTTPException(status_code=400, detail="You cannot add yourself as a friend.")
        existing = con.execute(
            """SELECT * FROM friend_requests WHERE
               (requester_id=? AND target_id=?) OR (requester_id=? AND target_id=?)""",
            (account["account_id"], target["account_id"], target["account_id"], account["account_id"]),
        ).fetchone()
        if existing and existing["status"] == "accepted":
            raise HTTPException(status_code=409, detail="You are already friends.")
        if existing and existing["status"] == "pending":
            # If the other person already requested us, accept reciprocally.
            if existing["requester_id"] == target["account_id"]:
                con.execute("UPDATE friend_requests SET status='accepted', updated_at=? WHERE id=?", (utc_now(), existing["id"]))
                return {"status": "accepted", "friend": {"account_id": target["account_id"], "username": target["username"]}}
            raise HTTPException(status_code=409, detail="Friend request is already pending.")
        created = utc_now()
        if existing and existing["status"] == "removed":
            con.execute("UPDATE friend_requests SET requester_id=?, target_id=?, status='pending', created_at=?, updated_at=? WHERE id=?",
                        (account["account_id"], target["account_id"], created, created, existing["id"]))
            request_id = int(existing["id"])
        else:
            cursor = con.execute(
                "INSERT INTO friend_requests(requester_id, target_id, status, created_at, updated_at) VALUES (?, ?, 'pending', ?, ?)",
                (account["account_id"], target["account_id"], created, created),
            )
            request_id = int(cursor.lastrowid)
    send_account_push(target["account_id"], {"type": "friend_request", "request_id": request_id, "title": "NorthStar Connect", "body": "Yeni arkadaşlık isteğin var."})
    return {"request_id": request_id, "status": "pending", "target": {"account_id": target["account_id"], "username": target["username"]}}


@app.get("/api/friends/requests")
def list_friend_requests(account: dict = Depends(require_account)):
    with db() as con:
        incoming = con.execute(
            """SELECT r.id, a.account_id, a.username, r.created_at FROM friend_requests r
               JOIN accounts a ON a.account_id=r.requester_id WHERE r.target_id=? AND r.status='pending' ORDER BY r.id DESC""",
            (account["account_id"],),
        ).fetchall()
        outgoing = con.execute(
            """SELECT r.id, a.account_id, a.username, r.created_at FROM friend_requests r
               JOIN accounts a ON a.account_id=r.target_id WHERE r.requester_id=? AND r.status='pending' ORDER BY r.id DESC""",
            (account["account_id"],),
        ).fetchall()
    return {
        "incoming": [{"request_id": r["id"], "account_id": r["account_id"], "username": r["username"], "created_at": r["created_at"]} for r in incoming],
        "outgoing": [{"request_id": r["id"], "account_id": r["account_id"], "username": r["username"], "created_at": r["created_at"]} for r in outgoing],
    }


@app.post("/api/friends/requests/{request_id}/accept")
def accept_friend_request(request_id: int, account: dict = Depends(require_account)):
    with db() as con:
        row = con.execute(
            """SELECT r.id, r.requester_id, a.username FROM friend_requests r
               JOIN accounts a ON a.account_id=r.requester_id
               WHERE r.id=? AND r.target_id=? AND r.status='pending'""",
            (request_id, account["account_id"]),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Pending friend request not found.")
        con.execute("UPDATE friend_requests SET status='accepted', updated_at=? WHERE id=?", (utc_now(), request_id))
    send_account_push(row["requester_id"], {"type": "friend_accepted", "title": "NorthStar Connect", "body": "Arkadaşlık isteğin kabul edildi."})
    return {"status": "accepted", "friend": {"account_id": row["requester_id"], "username": row["username"]}}


@app.get("/api/friends")
def list_friends(account: dict = Depends(require_account)):
    with db() as con:
        rows = con.execute(
            """SELECT a.account_id, a.username FROM friend_requests r JOIN accounts a
               ON a.account_id = CASE WHEN r.requester_id=? THEN r.target_id ELSE r.requester_id END
               WHERE r.status='accepted' AND (r.requester_id=? OR r.target_id=?) ORDER BY a.username COLLATE NOCASE""",
            (account["account_id"], account["account_id"], account["account_id"]),
        ).fetchall()
    return {"friends": [{"account_id": r["account_id"], "username": r["username"], "online": r["account_id"] in SIGNALING_CLIENTS} for r in rows]}


@app.delete("/api/friends/{friend_account_id}")
def remove_friend(friend_account_id: str, account: dict = Depends(require_account)):
    with db() as con:
        cursor = con.execute(
            """UPDATE friend_requests SET status='removed', updated_at=? WHERE status='accepted' AND
               ((requester_id=? AND target_id=?) OR (requester_id=? AND target_id=?))""",
            (utc_now(), account["account_id"], friend_account_id, friend_account_id, account["account_id"]),
        )
        if cursor.rowcount == 0:
            raise HTTPException(status_code=404, detail="Friend not found.")
    return {"removed": True}


@app.post("/api/friends/{friend_account_id}/invite")
def invite_friend(friend_account_id: str, payload: FriendInviteBody, account: dict = Depends(require_account)):
    if not are_friends(account["account_id"], friend_account_id):
        raise HTTPException(status_code=403, detail="You can invite accepted friends only.")
    if payload.invite_type == "room":
        if not payload.room_code or not ROOM_CODE_RE.fullmatch(payload.room_code.upper()) or not room_exists(payload.room_code.upper()):
            raise HTTPException(status_code=404, detail="A valid existing room code is required.")
    elif payload.room_code:
        raise HTTPException(status_code=400, detail="Direct invitations do not need a room code.")
    invite_id = uuid.uuid4().hex
    now = utc_now()
    with db() as con:
        con.execute(
            "INSERT INTO friend_invitations(id, sender_id, target_id, invite_type, room_code, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?)",
            (invite_id, account["account_id"], friend_account_id, payload.invite_type, payload.room_code.upper() if payload.room_code else None, now, now),
        )
    delivered = send_account_push(friend_account_id, {
        "type": "peer_invitation", "invitation_id": invite_id,
        "invite_type": payload.invite_type, "room_code": payload.room_code or "",
        "title": "NorthStar Connect", "body": "Bir arkadaşın bağlantı daveti gönderdi."
    })
    return {"invitation_id": invite_id, "status": "pending", "online": friend_account_id in SIGNALING_CLIENTS, "push_devices_reached": delivered}


@app.get("/api/invitations")
def list_invitations(account: dict = Depends(require_account)):
    with db() as con:
        rows = con.execute(
            """SELECT i.*, a.username AS sender_username FROM friend_invitations i
               JOIN accounts a ON a.account_id=i.sender_id
               WHERE i.target_id=? AND i.status='pending' ORDER BY i.created_at DESC LIMIT 50""",
            (account["account_id"],),
        ).fetchall()
    return {"invitations": [{"invitation_id": r["id"], "sender_account_id": r["sender_id"], "sender_username": r["sender_username"], "invite_type": r["invite_type"], "room_code": r["room_code"], "created_at": r["created_at"]} for r in rows]}


@app.post("/api/invitations/{invitation_id}/accept")
def accept_invitation(invitation_id: str, account: dict = Depends(require_account)):
    with db() as con:
        row = con.execute(
            "SELECT * FROM friend_invitations WHERE id=? AND target_id=? AND status='pending'",
            (invitation_id, account["account_id"]),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Pending invitation not found.")
        con.execute("UPDATE friend_invitations SET status='accepted', updated_at=? WHERE id=?", (utc_now(), invitation_id))
    return {"accepted": True, "invite_type": row["invite_type"], "room_code": row["room_code"], "sender_account_id": row["sender_id"]}


# One-time tickets avoid placing long-lived bearer tokens in WebSocket URLs.
SIGNALING_TICKETS: dict[str, tuple[str, float]] = {}
SIGNALING_CLIENTS: dict[str, set[WebSocket]] = defaultdict(set)


@app.post("/api/signaling/ticket")
def create_signaling_ticket(account: dict = Depends(require_account)):
    ticket = secrets.token_urlsafe(32)
    SIGNALING_TICKETS[ticket] = (account["account_id"], time.monotonic() + SIGNALING_TICKET_SECONDS)
    return {"ticket": ticket, "expires_in": SIGNALING_TICKET_SECONDS, "websocket_path": "/ws/signaling"}


@app.get("/api/network/ice-config")
def ice_config(account: dict = Depends(require_account)):
    servers = [{"urls": os.getenv("NORTHSTAR_STUN_URL", "stun:stun.l.google.com:19302")}]
    turn_url = os.getenv("NORTHSTAR_TURN_URL", "").strip()
    turn_user = os.getenv("NORTHSTAR_TURN_USERNAME", "").strip()
    turn_credential = os.getenv("NORTHSTAR_TURN_CREDENTIAL", "").strip()
    if turn_url and turn_user and turn_credential:
        servers.append({"urls": turn_url, "username": turn_user, "credential": turn_credential})
    return {"ice_servers": servers, "turn_configured": len(servers) > 1, "preferred_transport": "peer-to-peer", "fallback": "existing_room_websocket_for_shared_canvas_only"}


@app.websocket("/ws/signaling")
async def signaling_socket(websocket: WebSocket):
    ticket = websocket.query_params.get("ticket", "")
    record = SIGNALING_TICKETS.pop(ticket, None)
    if not record or record[1] < time.monotonic():
        await websocket.close(code=4401, reason="Missing, expired or already used signaling ticket")
        return
    account_id = record[0]
    await websocket.accept()
    SIGNALING_CLIENTS[account_id].add(websocket)
    await websocket.send_json({"type": "signaling_ready", "account_id": account_id})
    try:
        while True:
            raw_text = await websocket.receive_text()
            if len(raw_text.encode("utf-8")) > SIGNALING_MESSAGE_LIMIT:
                await websocket.send_json({"type": "error", "message": "Signaling packet too large."})
                continue
            try:
                message = json.loads(raw_text)
                target_id = str(message.get("target_account_id", ""))
                kind = str(message.get("type", ""))
                if kind == "ping":
                    await websocket.send_json({"type": "pong"})
                    continue
                if kind not in {"call_invite", "offer", "answer", "ice_candidate", "call_reject", "call_end"}:
                    raise ValueError("Unsupported signaling packet.")
                if not target_id or not are_friends(account_id, target_id):
                    raise ValueError("Signaling is allowed between accepted friends only.")
                payload = message.get("payload", {})
                if not isinstance(payload, dict):
                    raise ValueError("Signaling payload must be an object.")
                forwarded = {"type": kind, "from_account_id": account_id, "payload": payload}
                peers = tuple(SIGNALING_CLIENTS.get(target_id, set()))
                if not peers:
                    await websocket.send_json({"type": "peer_unavailable", "target_account_id": target_id, "fallback": "room-websocket"})
                else:
                    for peer in peers:
                        try:
                            await peer.send_json(forwarded)
                        except Exception:
                            SIGNALING_CLIENTS[target_id].discard(peer)
            except (ValueError, json.JSONDecodeError, TypeError) as exc:
                await websocket.send_json({"type": "error", "message": str(exc)})
    except WebSocketDisconnect:
        pass
    finally:
        SIGNALING_CLIENTS[account_id].discard(websocket)
        if not SIGNALING_CLIENTS[account_id]:
            SIGNALING_CLIENTS.pop(account_id, None)


@app.get("/api/accounts/notifications/transport")
def account_notification_transport(device_id: str, account: dict = Depends(require_account)):
    """Tell this signed-in device whether its account FCM token is registered and usable."""
    with db() as con:
        row = con.execute(
            "SELECT 1 FROM account_devices WHERE account_id=? AND device_id=? AND fcm_token IS NOT NULL",
            (account["account_id"], device_id),
        ).fetchone()
    return {"fcm_enabled": bool(init_firebase()) and row is not None, "fallback_polling": True}


@app.get("/api/rooms/{room_code}/notifications/transport")
def room_notification_transport(room_code: str, device_id: str):
    """Tell this room/device whether a registered FCM destination can receive pushes."""
    code = room_code.strip().upper()
    if not ROOM_CODE_RE.fullmatch(code) or not room_exists(code):
        raise HTTPException(status_code=404, detail="Room not found.")
    with db() as con:
        row = con.execute(
            "SELECT 1 FROM devices WHERE room_code=? AND device_id=? AND fcm_token IS NOT NULL",
            (code, device_id),
        ).fetchone()
    return {"fcm_enabled": bool(init_firebase()) and row is not None, "fallback_polling": True}


@app.get("/health")
def health():
    return {"status": "ok", "service": "NorthStar Connect", "version": "0.6.0"}


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
