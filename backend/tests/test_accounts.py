from fastapi.testclient import TestClient

from app.main import app, db


def register(client: TestClient, username: str):
    response = client.post(
        "/api/accounts/register",
        json={"username": username, "password": "NorthStar2026Secure"},
    )
    assert response.status_code == 200, response.text
    return response.json()


def auth(token: str):
    return {"Authorization": f"Bearer {token}"}


def test_account_register_login_uses_stable_random_account_id_and_argon2_hash():
    with TestClient(app) as client:
        username = "tester_" + __import__("uuid").uuid4().hex[:8]
        created = register(client, username)
        account = created["account"]
        assert len(account["account_id"]) == 32
        assert created["token_type"] == "bearer"
        assert client.get("/api/accounts/me", headers=auth(created["access_token"])).json()["account_id"] == account["account_id"]

        with db() as con:
            row = con.execute("SELECT password_hash FROM accounts WHERE account_id=?", (account["account_id"],)).fetchone()
        assert row["password_hash"].startswith("$argon2id$")
        assert "NorthStar2026Secure" not in row["password_hash"]

        login = client.post("/api/accounts/login", json={"username": username.upper(), "password": "NorthStar2026Secure"})
        assert login.status_code == 200
        assert login.json()["account"]["account_id"] == account["account_id"]
        assert client.post("/api/accounts/logout", headers=auth(login.json()["access_token"])).status_code == 200
        assert client.get("/api/accounts/me", headers=auth(login.json()["access_token"])).status_code == 401


def test_account_registration_validates_duplicate_username_and_password():
    with TestClient(app) as client:
        username = "duplicate_" + __import__("uuid").uuid4().hex[:6]
        register(client, username)
        duplicate = client.post("/api/accounts/register", json={"username": username.upper(), "password": "NorthStar2026Secure"})
        assert duplicate.status_code == 409
        weak = client.post("/api/accounts/register", json={"username": "shortname", "password": "weak"})
        assert weak.status_code == 422


def test_friend_request_accept_friend_list_and_remove():
    with TestClient(app) as client:
        suffix = __import__("uuid").uuid4().hex[:8]
        a = register(client, "alpha_" + suffix)
        b = register(client, "beta_" + suffix)

        unauth = client.get("/api/friends")
        assert unauth.status_code == 401

        sent = client.post("/api/friends/requests", headers=auth(a["access_token"]), json={"username": b["account"]["username"]})
        assert sent.status_code == 200
        request_id = sent.json()["request_id"]
        inbox = client.get("/api/friends/requests", headers=auth(b["access_token"])).json()
        assert inbox["incoming"][0]["request_id"] == request_id

        accepted = client.post(f"/api/friends/requests/{request_id}/accept", headers=auth(b["access_token"]))
        assert accepted.status_code == 200
        friends_a = client.get("/api/friends", headers=auth(a["access_token"])).json()["friends"]
        friends_b = client.get("/api/friends", headers=auth(b["access_token"])).json()["friends"]
        assert friends_a[0]["account_id"] == b["account"]["account_id"]
        assert friends_b[0]["account_id"] == a["account"]["account_id"]

        removed = client.delete(f"/api/friends/{b['account']['account_id']}", headers=auth(a["access_token"]))
        assert removed.status_code == 200
        assert client.get("/api/friends", headers=auth(a["access_token"])).json()["friends"] == []


def test_self_friend_and_unknown_user_rejected():
    with TestClient(app) as client:
        a = register(client, "solo_" + __import__("uuid").uuid4().hex[:8])
        self_request = client.post("/api/friends/requests", headers=auth(a["access_token"]), json={"username": a["account"]["username"]})
        missing = client.post("/api/friends/requests", headers=auth(a["access_token"]), json={"username": "no_such_user_123"})
        assert self_request.status_code == 400
        assert missing.status_code == 404


def test_direct_friend_invitation_is_persisted_and_can_be_accepted():
    with TestClient(app) as client:
        suffix = __import__("uuid").uuid4().hex[:8]
        a = register(client, "caller_" + suffix)
        b = register(client, "receiver_" + suffix)
        request = client.post("/api/friends/requests", headers=auth(a["access_token"]), json={"username": b["account"]["username"]}).json()
        assert client.post(f"/api/friends/requests/{request['request_id']}/accept", headers=auth(b["access_token"])).status_code == 200

        invite = client.post(f"/api/friends/{b['account']['account_id']}/invite", headers=auth(a["access_token"]), json={"invite_type": "direct"})
        assert invite.status_code == 200
        pending = client.get("/api/invitations", headers=auth(b["access_token"])).json()["invitations"]
        assert pending[0]["invitation_id"] == invite.json()["invitation_id"]
        assert pending[0]["sender_username"] == a["account"]["username"]

        accepted = client.post(f"/api/invitations/{invite.json()['invitation_id']}/accept", headers=auth(b["access_token"]))
        assert accepted.status_code == 200
        assert accepted.json()["invite_type"] == "direct"


def test_signaling_ticket_is_single_use_and_friend_offer_is_forwarded():
    with TestClient(app) as client:
        suffix = __import__("uuid").uuid4().hex[:8]
        a = register(client, "peer_a_" + suffix)
        b = register(client, "peer_b_" + suffix)
        req = client.post("/api/friends/requests", headers=auth(a["access_token"]), json={"username": b["account"]["username"]}).json()
        client.post(f"/api/friends/requests/{req['request_id']}/accept", headers=auth(b["access_token"]))

        ticket_a = client.post("/api/signaling/ticket", headers=auth(a["access_token"])).json()["ticket"]
        ticket_b = client.post("/api/signaling/ticket", headers=auth(b["access_token"])).json()["ticket"]
        with client.websocket_connect(f"/ws/signaling?ticket={ticket_a}") as ws_a:
            assert ws_a.receive_json()["type"] == "signaling_ready"
            ws_a.send_json({"type": "ping"})
            assert ws_a.receive_json()["type"] == "pong"
            with client.websocket_connect(f"/ws/signaling?ticket={ticket_b}") as ws_b:
                assert ws_b.receive_json()["type"] == "signaling_ready"
                ws_a.send_json({
                    "type": "offer",
                    "target_account_id": b["account"]["account_id"],
                    "payload": {"sdp": "fake-offer-for-test"},
                })
                received = ws_b.receive_json()
                assert received["type"] == "offer"
                assert received["from_account_id"] == a["account"]["account_id"]
                assert received["payload"]["sdp"] == "fake-offer-for-test"

        reused = client.websocket_connect(f"/ws/signaling?ticket={ticket_a}")
        try:
            reused.__enter__()
            assert False, "A signaling ticket must be one-use"
        except Exception:
            pass


def test_signaling_is_blocked_for_non_friends():
    with TestClient(app) as client:
        suffix = __import__("uuid").uuid4().hex[:8]
        a = register(client, "sender_" + suffix)
        b = register(client, "stranger_" + suffix)
        ticket_a = client.post("/api/signaling/ticket", headers=auth(a["access_token"])).json()["ticket"]
        with client.websocket_connect(f"/ws/signaling?ticket={ticket_a}") as ws:
            assert ws.receive_json()["type"] == "signaling_ready"
            ws.send_json({"type": "offer", "target_account_id": b["account"]["account_id"], "payload": {"sdp": "x"}})
            error = ws.receive_json()
            assert error["type"] == "error"
            assert "accepted friends" in error["message"]


def test_account_notification_transport_requires_registered_device_and_auth():
    with TestClient(app) as client:
        account = register(client, "notify_" + __import__("uuid").uuid4().hex[:8])
        headers = auth(account["access_token"])
        no_device = client.get("/api/accounts/notifications/transport?device_id=device-a", headers=headers)
        assert no_device.status_code == 200
        assert no_device.json()["fcm_enabled"] is False
        assert no_device.json()["fallback_polling"] is True
        unauthorized = client.get("/api/accounts/notifications/transport?device_id=device-a")
        assert unauthorized.status_code == 401


def test_room_notification_transport_requires_valid_room_and_registered_device():
    with TestClient(app) as client:
        room = client.post("/api/rooms").json()["room_code"]
        response = client.get(f"/api/rooms/{room}/notifications/transport?device_id=device-a")
        assert response.status_code == 200
        assert response.json()["fcm_enabled"] is False
        assert response.json()["fallback_polling"] is True


def test_account_device_registration_does_not_erase_existing_fcm_token():
    with TestClient(app) as client:
        suffix = __import__("uuid").uuid4().hex[:8]
        account = register(client, "device_" + suffix)
        headers = auth(account["access_token"])
        first = client.post(
            "/api/accounts/devices",
            headers=headers,
            json={"device_id": "test-device", "fcm_token": "fcm-token-123"},
        )
        assert first.status_code == 200
        second = client.post(
            "/api/accounts/devices",
            headers=headers,
            json={"device_id": "test-device"},
        )
        assert second.status_code == 200
        with db() as con:
            row = con.execute(
                "SELECT fcm_token FROM account_devices WHERE account_id=? AND device_id=?",
                (account["account"]["account_id"], "test-device"),
            ).fetchone()
        assert row["fcm_token"] == "fcm-token-123"
