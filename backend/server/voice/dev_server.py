"""Stand-alone server for testing Esha's part without Member B's main.py.

    uvicorn server.voice.dev_server:app --host 0.0.0.0 --port 8000 --reload

    GET  /scribe-token, /voice/scribe-config, /clips/manifest.json, /clips/<id>.mp3
    POST /voice/parse      {"text":"raahi darwaza dhoondo","wake_required":true}
    POST /voice/compose    {"kind":"obstacle","cls":"chair","side":"ahead","steps":2,"move_dir":"left","move_steps":2}
    POST /detect           raw JPEG body -> detections + a composed sentence (uses the real detector)
    WS   /ws               phone protocol subset: JPEG frames -> result JSON, command messages -> clips
"""
from __future__ import annotations

import asyncio
import time

import cv2
import numpy as np
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware

from server.voice import tts_live
from server.voice.commands import handle_message
from server.voice.token import router as voice_router
from voice_assets import sentence as S

app = FastAPI(title="Raahi voice/detector dev server")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
app.include_router(voice_router)


@app.get("/health")
async def health():
    return {"ok": True}


@app.post("/voice/parse")
async def voice_parse(body: dict):
    out = handle_message({"type": "command_text", "text": body.get("text", ""),
                          "wake_required": body.get("wake_required", True)})
    if out is None:
        return {"ignored": True}
    return {"intent": out.intent.type, "target": out.intent.target, "clips": out.clips,
            "urdu": S.to_text(out.clips), "en": S.to_text(out.clips, "en")}


@app.post("/voice/compose")
async def voice_compose(b: dict):
    kind = b.get("kind", "obstacle")
    if kind == "obstacle":
        ids = S.obstacle(b.get("cls"), b.get("side", "ahead"), b.get("steps"), b.get("move_dir"),
                         b.get("move_steps", 1), tts_live.cached_ids())
    elif kind == "stop":
        ids = S.urgent_stop(b.get("cls"))
    elif kind == "describe":
        ids = S.describe([tuple(i) for i in b.get("items", [])], tts_live.cached_ids())
    else:
        ids = S.find_guidance(b.get("cls", "door"), b.get("angle", 0), b.get("steps"))
    return {"clips": ids, "urdu": S.to_text(ids), "en": S.to_text(ids, "en")}


def _decode(data: bytes):
    return cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)


def _quick_sentence(dets, w):
    """Very small stand-in for B's engine: name the biggest box."""
    if not dets:
        return []
    d = max(dets, key=lambda d: (d.box[2] - d.box[0]) * (d.box[3] - d.box[1]))
    cx = (d.box[0] + d.box[2]) / 2 / w
    side = "left" if cx < 0.33 else "right" if cx > 0.67 else "ahead"
    return S.obstacle(d.cls, side, None)


@app.post("/detect")
async def detect_route(request: Request):
    from server.perception.detector import detect
    frame = _decode(await request.body())
    t = time.perf_counter()
    dets = await asyncio.to_thread(detect, frame)
    ms = (time.perf_counter() - t) * 1000
    h, w = frame.shape[:2]
    ids = _quick_sentence(dets, w)
    return {"overlay": [d.to_overlay(w, h) for d in dets], "clips": ids, "urdu": S.to_text(ids),
            "latency_ms": {"detect": round(ms, 1)}}


@app.websocket("/ws")
async def ws(sock: WebSocket):
    from server.perception.detector import detect
    await sock.accept()
    last = None
    try:
        while True:
            msg = await sock.receive()
            if msg.get("bytes"):
                frame = _decode(msg["bytes"])
                t = time.perf_counter()
                dets = await asyncio.to_thread(detect, frame)
                ms = (time.perf_counter() - t) * 1000
                h, w = frame.shape[:2]
                ids = _quick_sentence(dets, w)
                key = tuple(ids[:2])
                clips = ids if key != last else []
                last = key
                await sock.send_json({"type": "result", "clips": clips, "urgent": False, "interrupt": False,
                                      "haptic": "none", "mode": "navigate",
                                      "overlay": [d.to_overlay(w, h) for d in dets],
                                      "latency_ms": {"detect": round(ms, 1), "total": round(ms, 1)}})
            elif msg.get("text"):
                import json
                out = handle_message(json.loads(msg["text"]))
                if out:
                    await sock.send_json({"type": "result", "clips": out.clips, "urgent": False,
                                          "interrupt": True, "haptic": "short", "mode": "navigate",
                                          "overlay": [], "intent": out.intent.type,
                                          "target": out.intent.target, "latency_ms": {}})
            elif msg.get("type") == "websocket.disconnect":
                break
    except WebSocketDisconnect:
        pass
