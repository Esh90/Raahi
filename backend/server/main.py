"""FastAPI app: /ws phone loop, /dashboard judges, /health.

Member B. Depth every 2nd frame. Detector + depth via asyncio.to_thread.
"""
from __future__ import annotations

import asyncio
import json
import math
import time
from pathlib import Path

import cv2
import numpy as np
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

try:
    from starlette.websockets import WebSocketDisconnected as _WSGone
except ImportError:  # pragma: no cover
    _WSGone = WebSocketDisconnect

from server.config import HOST, MOCK, PORT, ROOT
from server.dashboard.hub import hub
from server.dashboard.render import annotate, colorize_depth, encode_jpeg
from server.engine.pipeline import GuidanceEngine
from server.perception.depth import estimate, get_estimator, status as depth_status, warmup
from server.perception.detector import detect
from server.voice.intents import Intent, parse
from server.voice.token import router as voice_router

app = FastAPI(title="Raahi", version="0.1")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(voice_router)   # Esha: /scribe-token, /voice/scribe-config, /clips/manifest.json, /clips/<id>.mp3

DASH_DIR = Path(__file__).resolve().parent / "dashboard"

_engine = GuidanceEngine()
_last_depth: np.ndarray | None = None
_depth_ms_last = 0
_ready = False


@app.on_event("startup")
async def _startup() -> None:
    async def _warm() -> None:
        global _ready
        loop = asyncio.get_event_loop()
        backend = await loop.run_in_executor(None, warmup)
        _ready = True
        print(f"[raahi] depth backend={backend} mock={MOCK} {depth_status()}")

    asyncio.create_task(_warm())


@app.get("/health")
def health():
    return {
        "ok": True,
        "ready": _ready,
        "mock": MOCK,
        "phone": hub.phone_connected,
        "mode": _engine.state.mode,
        "depth": depth_status(),
    }


@app.api_route("/calibrate", methods=["GET", "POST"])
def calibrate_depth(tape_m: float):
    """Fit a global metre scale from one tape reading vs the current centre zone.

    Stand on the tape mark, fill the frame with that wall/chair, then:
      curl "http://127.0.0.1:8000/calibrate?tape_m=0.89"
    """
    global _last_depth
    if tape_m < 0.3 or tape_m > 6.0:
        return JSONResponse(
            {"ok": False, "error": "tape_m must be metres between 0.3 and 6"},
            400,
        )
    if _last_depth is None:
        return JSONResponse(
            {
                "ok": False,
                "error": "no live frame yet — keep send_test_frame.py --webcam running",
            },
            400,
        )
    from server.engine.geometry import zone_depths

    centre = zone_depths(_last_depth)[1]
    if not math.isfinite(centre) or centre < 0.2:
        return JSONResponse({"ok": False, "error": f"bad centre depth {centre}"}, 400)
    est = get_estimator()
    factor = tape_m / centre
    new_scale = est.set_user_scale(est.user_scale * factor)
    _last_depth = (_last_depth * np.float32(factor)).astype(np.float32)
    return {
        "ok": True,
        "tape_m": tape_m,
        "model_m_before": round(centre, 3),
        "scale": round(new_scale, 4),
        "hint": "CENTRE on the dashboard should now read near the tape. One shot is enough.",
    }


_NO_CACHE = {"Cache-Control": "no-store, no-cache, must-revalidate", "Pragma": "no-cache"}


@app.get("/")
@app.get("/dashboard")
def dashboard_page():
    return FileResponse(DASH_DIR / "index.html", headers=_NO_CACHE)


@app.get("/logo.png")
def dashboard_logo():
    return FileResponse(DASH_DIR / "logo.png", media_type="image/png", headers=_NO_CACHE)


def _intent_from_message(data: dict) -> Intent | None:
    typ = data.get("type")
    if typ == "command":
        return Intent(type=str(data.get("intent") or "unknown"), target=data.get("target"))
    if typ == "command_text":
        # wake_required=false after a double-tap (no "Raahi" needed); None = no wake word -> ignore
        return parse(str(data.get("text") or ""), require_wake=bool(data.get("wake_required", True)))
    return None


@app.websocket("/ws")
async def ws_phone(ws: WebSocket):
    global _last_depth, _depth_ms_last
    await ws.accept()
    hub.phone_connected = True
    engine = _engine
    frame_idx = 0
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
                if data.get("type") == "hello":
                    await ws.send_json(
                        {
                            "type": "result",
                            "clips": ["sys_connected"],
                            "urgent": False,
                            "interrupt": False,
                            "haptic": "none",
                            "mode": engine.state.mode,
                            "overlay": [],
                            "english": "Connected",
                            "latency_ms": {"detect": 0, "depth": 0, "engine": 0, "total": 1},
                        }
                    )
                    continue
                intent = _intent_from_message(data)
                reply = engine.handle_intent(intent)
                if reply is None:
                    continue
                payload = reply.to_payload({"detect": 0, "depth": 0, "engine": 1, "total": 2})
                await ws.send_json(payload)
                continue

            raw = msg.get("bytes")
            if raw is None:
                continue

            arr = np.frombuffer(raw, dtype=np.uint8)
            frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            if frame is None:
                await ws.send_json(
                    {
                        "type": "result",
                        "clips": [],
                        "urgent": False,
                        "interrupt": False,
                        "haptic": "none",
                        "mode": engine.state.mode,
                        "overlay": [],
                        "english": "",
                        "latency_ms": {"detect": 0, "depth": 0, "engine": 0, "total": 0},
                    }
                )
                continue

            frame_idx += 1
            run_depth = frame_idx % 2 == 1 or _last_depth is None

            async def _detect():
                t = time.perf_counter()
                dets = await asyncio.to_thread(detect, frame)
                return dets, int((time.perf_counter() - t) * 1000)

            async def _depth():
                t = time.perf_counter()
                d = await asyncio.to_thread(estimate, frame)
                return d, int((time.perf_counter() - t) * 1000)

            if run_depth:
                (detections, detect_ms), (depth_m, depth_ms) = await asyncio.gather(
                    _detect(), _depth()
                )
                depth_m = get_estimator().refine_scale(depth_m, detections)
                _last_depth = depth_m
                _depth_ms_last = depth_ms
            else:
                detections, detect_ms = await _detect()
                depth_m = _last_depth
                depth_ms = 0
                if depth_m is not None:
                    depth_m = get_estimator().refine_scale(depth_m, detections)
                    _last_depth = depth_m

            t_eng = time.perf_counter()
            decision, reply = engine.process(frame.shape, detections, depth_m)
            engine_ms = int((time.perf_counter() - t_eng) * 1000)
            total_ms = int((time.perf_counter() - t0) * 1000)
            latency = {
                "detect": detect_ms,
                "depth": depth_ms if run_depth else _depth_ms_last,
                "engine": engine_ms,
                "total": total_ms,
            }
            payload = reply.to_payload(latency)
            await ws.send_json(payload)

            hub.note_latency(total_ms)
            vis = annotate(
                frame,
                detections,
                reply.overlay,
                decision.zone_colors,
                decision.zone_depths,
                reply.english or decision.english,
            )
            depth_vis = colorize_depth(depth_m, frame.shape[0], frame.shape[1])
            await hub.push(
                encode_jpeg(vis),
                encode_jpeg(depth_vis),
                {
                    "mode": reply.mode,
                    "spoken_en": reply.english or decision.english,
                    "clips": reply.clips,
                    "overlay": reply.overlay,
                    "zones": {
                        "left": {
                            "m": _finite(decision.zone_depths[0] if decision.zone_depths else None),
                            "color": (decision.zone_colors or ("green",) * 3)[0],
                        },
                        "centre": {
                            "m": _finite(decision.zone_depths[1] if decision.zone_depths else None),
                            "color": (decision.zone_colors or ("green",) * 3)[1],
                        },
                        "right": {
                            "m": _finite(decision.zone_depths[2] if decision.zone_depths else None),
                            "color": (decision.zone_colors or ("green",) * 3)[2],
                        },
                    },
                    "latency_ms": latency,
                    "urgent": reply.urgent,
                    "kind": decision.kind,
                },
            )
    except (WebSocketDisconnect, _WSGone):
        hub.phone_connected = False
    except Exception:
        hub.phone_connected = False
        raise
    finally:
        hub.phone_connected = False


@app.websocket("/ws/dashboard")
async def ws_dashboard(ws: WebSocket):
    await hub.register(ws)
    try:
        while True:
            msg = await ws.receive()
            if msg.get("type") == "websocket.disconnect":
                break
            _ = msg
    except (WebSocketDisconnect, _WSGone):
        hub.drop(ws)
    except Exception:
        hub.drop(ws)


def _finite(v):
    if v is None:
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    if f != f or f == float("inf"):
        return None
    return round(f, 2)


def run() -> None:
    uvicorn.run("server.main:app", host=HOST, port=PORT, reload=False)


if __name__ == "__main__":
    run()
