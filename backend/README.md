# Raahi (راہی) — backend (Python server)

Urdu voice + computer vision indoor guidance. This tree is **depth, the decision engine, FastAPI, and the judge dashboard**.

Esha owns `server/perception/detector.py`, `server/voice/`, `voice_assets/`.
Member C owns `mobile/`.

Contracts: `../docs/contracts/protocol.md`. Do not change without telling the team.

## Quick test on the Mac (detection + depth + engine + Urdu voice, laptop webcam)

All backend commands run **inside `backend/`** (the repo was split into `backend/`, `frontend/`, `docs/`).

```bash
git pull
cd backend
source .venv/bin/activate
pip install -r requirements.txt          # new: ultralytics, onnxruntime, sounddevice, imageio-ffmpeg, audioop-lts
```
Then add Esha's keys to `.env`: `ELEVENLABS_API_KEY=...` and `ELEVENLABS_VOICE_ID=...`. Get them from Esha privately; never commit them.
```bash
python -m pytest                          # all green
python -m tools.live_demo                 # webcam window + Urdu clips through the speakers
```
- Keys: `SPACE` talk (e.g. "darwaza dhoondo"), `d` describe, `1` find door, `p` pause, `c` calibrate (point at a wall exactly 1.5 m away), `[`/`]` fine-tune the scale, `q` quit.
- The demo keeps your `server/depth_scale.txt` on MPS. If distances look off, press `c` at a known distance.
- Each spoken line is printed with its latency and logged to `runs/live_log.csv`.
- The phone path is unchanged: `python -m server`, then `/dashboard`.
- What changed in the engine and why: `../docs/ENGINE_CHANGES.md`. Detection + voice: `../docs/ESHA_TRACK.md`.
- `python -m tools.live_demo --lang en` uses the English voice.

## Setup (once)

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
python tools/download_models.py
```

Weights live in `models/` (gitignored). Later starts load from disk — they do not re-download.

## Run (real depth)

```bash
source .venv/bin/activate
unset RAAHI_MOCK
caffeinate -d python -m server
```

- Dashboard: http://127.0.0.1:8000/dashboard
- Health: http://127.0.0.1:8000/health (`mock` must be `false`, `device` `mps`)
- Phone: `ws://<mac-ip>:8000/ws`

`caffeinate -d` keeps the Mac awake during the demo.

## Test without the phone

```bash
python tools/send_test_frame.py --webcam --sleep 0.05
python -m pytest
```

Tape scale (optional):

```bash
python tools/calibrate.py --save-scale --tape-m 0.89 --model-m 1.40
# or live, while webcam is streaming:
curl "http://127.0.0.1:8000/calibrate?tape_m=0.89"
```

## Mock only (no weights)

```bash
export RAAHI_MOCK=1
python -m server
```

Member C fake replies:

```bash
python -m server.mocks.mock_server
# http://<mac-ip>:8001/   phone: ws://<mac-ip>:8001/ws
```

## Per frame

1. Decode JPEG  
2. YOLOE `detect()` (Esha; unnamed depth until YOLO lands) **and** Depth Anything every 2nd frame  
3. Navigator: L/C/R zones, stop, then move toward the more open side  
4. Find + describe  
5. 3-frame smoother (2 for danger)  
6. Announcer  
7. JSON 5.2 to the phone + dashboard  

Named objects (`chair`, `door`, `wall`, …) appear only after Esha wires YOLO. Until then labels are `unknown`.
