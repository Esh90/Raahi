import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from server.voice.commands import handle_message
from server.voice.dev_server import app

client = TestClient(app)
ROOT = Path(__file__).resolve().parents[1]


def test_parse_route():
    r = client.post("/voice/parse", json={"text": "raahi darwaza dhoondo"}).json()
    assert r["intent"] == "find" and r["target"] == "door" and r["clips"] == ["cmd_finding_door"]
    assert client.post("/voice/parse", json={"text": "darwaza dhoondo"}).json() == {"ignored": True}


def test_compose_route():
    r = client.post("/voice/compose", json={"cls": "chair", "side": "ahead", "steps": 2,
                                            "move_dir": "left", "move_steps": 2}).json()
    assert r["clips"] == ["obj_chair", "pos_ahead", "steps_2", "move_left_2"]
    assert "کرسی" in r["urdu"]


def test_manifest_route():
    if not (ROOT / "voice_assets/clips/manifest.json").exists():
        pytest.skip("run process_clips first")
    m = client.get("/clips/manifest.json").json()
    assert "obj_chair" in {c["id"] for c in m["clips"]}
    assert m["classes"]["chair"]["clip"] == "obj_chair"
    assert client.get("/clips/obj_chair.mp3").status_code == 200
    assert client.get("/clips/../../.env").status_code == 404


def test_token_without_key(monkeypatch):
    monkeypatch.delenv("ELEVENLABS_API_KEY", raising=False)
    assert client.get("/scribe-token").status_code == 503


def test_command_messages():
    assert handle_message({"type": "command", "intent": "describe"}).intent.type == "describe"
    assert handle_message({"type": "command", "intent": "bogus"}).clips == ["sys_not_understood"]
    out = handle_message({"type": "command_text", "text": "کرسی ڈھونڈو", "wake_required": False})
    assert out.intent.target == "chair"
    assert handle_message({"type": "hello"}) is None


@pytest.mark.skipif(not (ROOT / "models/raahi_yoloe_n_320.onnx").exists(), reason="no exported model")
def test_detect_route():
    os.environ["RAAHI_MODEL"] = str(ROOT / "models/raahi_yoloe_n_320.onnx")
    os.environ["RAAHI_IMGSZ"] = "320"
    img = next((ROOT / "samples/coco_indoor").glob("*.jpg"), None)
    if img is None:
        pytest.skip("no sample images")
    r = client.post("/detect", content=img.read_bytes()).json()
    assert "overlay" in r and "latency_ms" in r
    for o in r["overlay"]:
        assert all(0 <= v <= 1 for v in o["box"])
