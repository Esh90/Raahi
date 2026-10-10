import numpy as np

from fastapi.testclient import TestClient

from server.main import app


def test_health():
    with TestClient(app) as client:
        r = client.get("/health")
        assert r.status_code == 200
        assert r.json()["ok"] is True


def test_dashboard_html():
    with TestClient(app) as client:
        r = client.get("/dashboard")
        assert r.status_code == 200
        assert b"Raahi" in r.content
        assert "راہی".encode("utf-8") in r.content


def test_ws_jpeg_roundtrip():
    import cv2

    frame = np.zeros((312, 416, 3), np.uint8)
    ok, buf = cv2.imencode(".jpg", frame)
    assert ok
    with TestClient(app) as client:
        with client.websocket_connect("/ws") as ws:
            ws.send_bytes(buf.tobytes())
            data = ws.receive_json()
            assert data["type"] == "result"
            assert "clips" in data
            assert "latency_ms" in data
            assert set(data["latency_ms"]) >= {"detect", "depth", "engine", "total"}
            ws.send_json({"type": "hello", "client": "test", "version": "0.1"})
            hello = ws.receive_json()
            assert "sys_connected" in hello["clips"]
            ws.send_json({"type": "command", "intent": "find", "target": "door"})
            cmd = ws.receive_json()
            assert cmd["mode"] == "find"
            assert "cmd_finding_door" in cmd["clips"]


def test_calibrate_needs_a_frame():
    import server.main as m

    prev = m._last_depth
    m._last_depth = None
    try:
        with TestClient(app) as client:
            r = client.get("/calibrate", params={"tape_m": 0.89})
            assert r.status_code == 400
            assert r.json()["ok"] is False
    finally:
        m._last_depth = prev
