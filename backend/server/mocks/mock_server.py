"""Hour-2 fake server for Member C. Random valid 5.2 replies, plus a visual console.

    python -m server.mocks.mock_server
    # page:  http://<mac-ip>:8001/
    # phone: ws://<mac-ip>:8001/ws
"""
from __future__ import annotations

import json
import random
import time
from collections import deque
from pathlib import Path

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

PORT = 8001
CONSOLE = Path(__file__).resolve().parent / "console.html"
LOGO = Path(__file__).resolve().parent.parent / "dashboard" / "logo.png"

SEQUENCES = [
    [],
    [],
    ["obj_chair", "pos_ahead", "steps_2", "move_left_2"],
    ["sys_path_clear"],
    ["urg_stop"],
    ["obj_box", "pos_ahead", "steps_1", "move_right_1"],
    ["cmd_finding_door"],
    ["turn_left_slight"],
    ["obj_door", "pos_ahead", "steps_3"],
    ["find_arrived_door"],
    ["obj_chair", "pos_left", "steps_2", "obj_door", "pos_ahead", "steps_4"],
    ["sys_nothing_ahead"],
]

EN = {
    (): "",
    ("obj_chair", "pos_ahead", "steps_2", "move_left_2"): "Chair ahead, 2 steps, move 2 left",
    ("sys_path_clear",): "Path clear",
    ("urg_stop",): "Stop",
    ("obj_box", "pos_ahead", "steps_1", "move_right_1"): "Box ahead, 1 step, move 1 right",
    ("cmd_finding_door",): "Looking for the door",
    ("turn_left_slight",): "Turn a little left",
    ("obj_door", "pos_ahead", "steps_3"): "Door straight ahead, 3 steps",
    ("find_arrived_door",): "You have reached the door",
    ("sys_nothing_ahead",): "Nothing ahead",
}

ZONE_PRESETS = {
    ("obj_chair", "pos_ahead", "steps_2", "move_left_2"): {
        "left": {"m": 3.1, "color": "green"},
        "centre": {"m": 1.4, "color": "yellow"},
        "right": {"m": 2.8, "color": "green"},
    },
    ("sys_path_clear",): {
        "left": {"m": 3.4, "color": "green"},
        "centre": {"m": 3.2, "color": "green"},
        "right": {"m": 3.0, "color": "green"},
    },
    ("urg_stop",): {
        "left": {"m": 1.6, "color": "yellow"},
        "centre": {"m": 0.55, "color": "red"},
        "right": {"m": 1.5, "color": "yellow"},
    },
}

DEFAULT_ZONES = {
    "left": {"m": 2.9, "color": "green"},
    "centre": {"m": 2.4, "color": "green"},
    "right": {"m": 2.8, "color": "green"},
}

app = FastAPI(title="Raahi mock server")
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"]
)

_dash: list[WebSocket] = []
_phone = False
_last: dict | None = None


@app.get("/health")
def health():
    return {"ok": True, "mock": True, "phone": _phone}


@app.get("/")
@app.get("/dashboard")
def console():
    return FileResponse(CONSOLE)


@app.get("/logo.png")
def logo():
    return FileResponse(LOGO, media_type="image/png")


@app.get("/scribe-token")
def scribe_token():
    return JSONResponse(
        {"error": "mock server — Esha's /scribe-token is on the real app (:8000)"},
        status_code=501,
    )


async def _broadcast(payload: dict) -> None:
    global _last
    _last = payload
    dead = []
    for ws in list(_dash):
        try:
            await ws.send_json(payload)
        except Exception:
            dead.append(ws)
    for ws in dead:
        if ws in _dash:
            _dash.remove(ws)


def _dash_payload(reply: dict) -> dict:
    clips = tuple(reply.get("clips") or [])
    return {
        "type": "frame",
        "phone": _phone,
        "mode": reply.get("mode"),
        "clips": reply.get("clips") or [],
        "spoken_en": reply.get("english") or "",
        "english": reply.get("english") or "",
        "urgent": reply.get("urgent"),
        "latency_ms": reply.get("latency_ms") or {},
        "zones": ZONE_PRESETS.get(clips, DEFAULT_ZONES),
        "overlay": reply.get("overlay") or [],
        "fps": 5,
        "latency_avg_ms": (reply.get("latency_ms") or {}).get("total"),
    }


@app.websocket("/ws/dashboard")
async def ws_dashboard(ws: WebSocket):
    await ws.accept()
    _dash.append(ws)
    if _last:
        try:
            await ws.send_json(_last)
        except Exception:
            pass
    try:
        while True:
            msg = await ws.receive()
            if msg.get("type") == "websocket.disconnect":
                break
    except WebSocketDisconnect:
        pass
    finally:
        if ws in _dash:
            _dash.remove(ws)


@app.websocket("/ws")
async def ws_phone(ws: WebSocket):
    global _phone
    await ws.accept()
    _phone = True
    i = 0
    lat_hist: deque[int] = deque(maxlen=30)
    try:
        while True:
            msg = await ws.receive()
            if msg.get("type") == "websocket.disconnect":
                break
            t0 = time.perf_counter()
            if msg.get("text"):
                try:
                    data = json.loads(msg["text"])
                except json.JSONDecodeError:
                    continue
                reply = _handle_json(data)
                await ws.send_json(reply)
                await _broadcast(_dash_payload(reply))
                continue
            if msg.get("bytes") is None:
                continue
            clips = SEQUENCES[i % len(SEQUENCES)]
            i += 1
            urgent = clips[:1] == ["urg_stop"]
            total = int((time.perf_counter() - t0) * 1000) + random.randint(40, 90)
            lat_hist.append(total)
            reply = {
                "type": "result",
                "clips": clips,
                "urgent": urgent,
                "interrupt": urgent,
                "haptic": "long" if urgent else ("arrived" if "find_arrived_door" in clips else "none"),
                "mode": "find"
                if any(c.startswith("find_") or c.startswith("cmd_finding") or c.startswith("turn_") for c in clips)
                else "navigate",
                "overlay": [
                    {
                        "label": "chair",
                        "box": [0.31, 0.40, 0.62, 0.95],
                        "dist_m": round(random.uniform(0.9, 2.4), 2),
                    }
                ],
                "english": EN.get(tuple(clips), " ".join(clips)),
                "latency_ms": {
                    "detect": random.randint(12, 28),
                    "depth": random.randint(30, 70),
                    "engine": random.randint(1, 4),
                    "total": total,
                },
            }
            await ws.send_json(reply)
            await _broadcast(_dash_payload(reply))
    except WebSocketDisconnect:
        return
    finally:
        _phone = False


def _handle_json(data: dict) -> dict:
    typ = data.get("type")
    if typ == "hello":
        return {
            "type": "result",
            "clips": ["sys_connected"],
            "urgent": False,
            "interrupt": False,
            "haptic": "none",
            "mode": "navigate",
            "overlay": [],
            "english": "Connected",
            "latency_ms": {"detect": 0, "depth": 0, "engine": 0, "total": 1},
        }
    intent = data.get("intent")
    if typ in {"command", "command_text"}:
        mapping = {
            "find": (["cmd_finding_door"], "Looking for the door", "find"),
            "describe": (["obj_chair", "pos_ahead", "steps_2"], "Chair ahead, 2 steps", "navigate"),
            "pause": (["sys_paused"], "Paused", "paused"),
            "resume": (["sys_resumed"], "Resumed", "navigate"),
            "stop": ([], "", "navigate"),
        }
        if typ == "command_text":
            text = (data.get("text") or "").lower()
            if "dhoond" in text or "door" in text or "darwaz" in text:
                intent = "find"
            elif "saamne" in text or "ahead" in text:
                intent = "describe"
            elif "pause" in text or "roko" in text:
                intent = "pause"
            else:
                intent = "unknown"
        clips, english, mode = mapping.get(
            intent, (["sys_not_understood"], "I didn't catch that", "navigate")
        )
        return {
            "type": "result",
            "clips": clips,
            "urgent": False,
            "interrupt": False,
            "haptic": "arrived" if intent == "find" else "none",
            "mode": mode,
            "overlay": [],
            "english": english,
            "latency_ms": {"detect": 0, "depth": 0, "engine": 1, "total": 2},
        }
    return {
        "type": "result",
        "clips": [],
        "urgent": False,
        "interrupt": False,
        "haptic": "none",
        "mode": "navigate",
        "overlay": [],
        "english": "",
        "latency_ms": {"detect": 0, "depth": 0, "engine": 0, "total": 0},
    }


def main() -> None:
    print(f"Raahi MOCK console → http://0.0.0.0:{PORT}/")
    print(f"Phone WebSocket     → ws://0.0.0.0:{PORT}/ws")
    uvicorn.run(app, host="0.0.0.0", port=PORT)


if __name__ == "__main__":
    main()
