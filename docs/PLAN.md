# Raahi — Hackathon build plan

Working name: **Raahi** (راہی, “traveller / path”). Wake word: *Raahi*.

Phone (Capacitor Android) streams 416 px JPEGs to a MacBook M2. Mac runs YOLOE-26 (what) + Depth Anything V2 Small (how far), decides stop / N steps left or right / straight, replies with **pre-recorded Urdu clip IDs**. Phone plays clips and vibrates for danger.

**Day 1:** dumb skeleton end to end. **Day 2:** real intelligence. **Day 3 noon:** feature freeze.

## Team

| Person | Owns |
| --- | --- |
| Esha | `server/perception/detector.py`, `server/voice/`, `voice_assets/`, Scribe logic in `mobile/src/voice.js` |
| **Member B (this tree)** | `server/main.py`, `server/perception/depth.py`, `server/engine/`, `server/dashboard/`, `tools/calibrate.py`, `mocks/mock_server.py` |
| Member C | `mobile/` (except Scribe logic inside `voice.js`) |

Contracts: `contracts/protocol.md`. Do not change without telling everyone.

## In scope

- F1 Obstacle guidance (named + unnamed via depth)
- F2 Urdu clip playback (~0 ms TTS in the loop)
- F3 Orientation gate (phone)
- F4 Danger haptics &lt; 0.8 m
- F5 Find mode (“Raahi, darwaza dhoondo”)
- F6 What’s ahead?
- F7 Wake word + double-tap fallback
- F8 Judge dashboard

Out of scope / roadmap: stairs, outdoor GPS, OCR, on-device inference, LLM captions.

## Live loop (Member B)

Phone sends one JPEG, waits for JSON. Mac:

1. `cv2.imdecode`
2. YOLOE boxes (Esha) + Depth Anything metres (B, every 2nd frame)
3. Engine: zones, nearest obstacle, side + steps, find/describe
4. Smooth 3 frames (danger: 2)
5. Announcer: speak on change, cooldown, stop interrupts
6. Reply 5.2 JSON + dashboard push

Latency budget ≤ 300 ms frame-to-voice. Log `latency_ms` every reply.

## Navigator rules

- ROI = rows 30–95%. Zones = left / centre / right thirds.
- `zone_d` = 10th percentile depth in zone ∩ ROI.
- DANGER = 0.8 m → `urg_stop`, haptic long, interrupt.
- WARN = 2.0 m → name obstacle (or unknown), move toward the clearer side, `ceil(Z/0.6)` steps away, lateral steps `clamp(ceil((|edge−cx|·Z/fx)+0.35)/0.6, 1, 4)`.
- Both sides &lt; WARN → stop.
- Else path clear (announcer says it **once** after an obstacle).

Find mode is overridden by DANGER/WARN. Arrived at &lt; 1 m and aligned → haptic `arrived`, back to navigate.

## Clip IDs

See `contracts/protocol.md`. Code uses IDs only; Esha generates MP3s.

## Day-1 B checklist

- [x] FastAPI `/ws` + `/health` + `/dashboard`
- [x] `mock_server.py` for C
- [x] Depth module (transformers metric indoor, mock fallback)
- [x] `calibrate.py` + `benchmark.py`
- [x] Navigator v1 + steps + smoother + announcer
- [x] Find mode + describe
- [x] Tape-measure tool ready (`tools/calibrate.py --depth-check`) — run 1/2/3 m in the demo room
- [x] Real Depth Anything V2 Small metric indoor on MPS (~67 ms)
- [x] CoreML Neural Engine fallback auto-downloaded
