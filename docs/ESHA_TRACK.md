# Esha's track: Detection + Voice (status & how to use)

> **Layout update:** the repo is now `backend/` (everything Python: server, voice_assets, shared, models, training,
> tools, tests - run commands from inside `backend/`), `frontend/android/` (the app), `frontend/web-prototype/`
> (old JS prototype), `docs/`. Paths below are relative to `backend/` unless they start with `frontend/`.
>
> **New since:** English voice (`voice_assets/clips_en.json`, `batches_en/`, `english.py`, served as `/clips/en/<id>.mp3`,
> manifest `en_file`/`en_text`), on-phone voice navigation (`frontend/android/.../core/voice/VoiceCommands.kt`,
> `CommandDispatcher.kt`, `HandsFreeListener.kt`, `assets/voice_commands.json`; reference + tests in
> `server/voice/app_commands.py`, `tests/test_app_commands.py`).


## What exists

| Area | Files | Status |
|---|---|---|
| Class list (single source) | `shared/classes.json` | 28 classes enabled (child off). Each has YOLOE prompts, COCO names, clip, height, min_conf |
| Detector (server/Mac) | `server/perception/detector.py`, `classes.py`, `sizes.py` | ✅ works. YOLOE `.pt` (open-vocab) or any exported `.onnx/.tflite/.mlpackage/.pt` |
| Mock detector for B | `server/mocks/mock_detector.py` | ✅ |
| Tools | `tools/try_detector.py`, `tools/benchmark.py`, `tools/export_models.py` | ✅ |
| Exported models | `models/raahi_yoloe_n_320.onnx` (+ `s_416`), `.labels.json` | ✅ ONNX. **`.tflite` must come from the Kaggle notebook** (LiteRT export doesn't run on Windows) |
| Fine-tune / TFLite notebook | `training/raahi_detector.ipynb`, `training/HOW_TO_RUN.md` | ✅ written. Not run yet (needs Kaggle GPU) |
| Clip library | `voice_assets/clips.json` (96 clips), `PHRASES_TO_RECORD.txt` | ✅. **Esha records the clips** |
| Clip pipeline | `voice_assets/process_clips.py`, `make_placeholder_clips.py`, `generate_clips.py` (optional API), `preview_sentence.py` | ✅. English placeholders are in place so everything runs today |
| Sentence composer | `voice_assets/sentence.py` ⇄ `mobile/src/sentence.js` (identical) | ✅ tested |
| Intents | `server/voice/intents.py`, `server/voice/commands.py` | ✅ 40+ test sentences (Urdu / Roman / English / typos) |
| Voice routes | `server/voice/token.py`: `/scribe-token`, `/voice/scribe-config`, `/clips/*` | ✅ (token needs the API key) |
| Live TTS fallback | `server/voice/tts_live.py` | ✅ code. Needs key + voice id to test |
| Phone: Scribe | `mobile/src/voice.js` | ✅ code (needs `@elevenlabs/client`, test on device) |
| Phone: detector | `mobile/src/detector.js` + `mobile/native/android/RaahiDetectorPlugin.kt` | ✅ code. Web backend works with the ONNX model. Native plugin not compiled yet |
| Phone: offline guide | `mobile/src/localGuide.js` | ✅ tested (`node mobile/src/selftest.mjs`) |
| Dev server | `server/voice/dev_server.py` | ✅ runs everything of mine without B's `main.py` |

Measured on Esha's laptop (i5-8350U CPU, no GPU), 25 indoor COCO photos:
- `raahi_yoloe_n_320.onnx`: **~29 ms** per frame. `raahi_yoloe_s_416.onnx`: ~138 ms. `yoloe-26s-seg.pt` (PyTorch): ~270 ms.
- Zero-shot YOLOE finds person, chair, sofa, bed, bottle, desk and door well. It misses windows behind curtains and very close dining tables. Fine-tuning (Part B) is for those.

## What Esha needs to do

1. **Record clips**: open `voice_assets/PHRASES_TO_RECORD.txt` and follow the steps at the top (ElevenLabs → Eleven v3 → one female voice → each line → download → rename to the filename → put in `voice_assets/raw/`). Then:
   ```bash
   python -m voice_assets.process_clips
   python -m voice_assets.preview_sentence --demo --play
   ```
   `process_clips` lists anything missing. Placeholders fill any gaps, so you can record in batches.
2. **`.env`**: copy `.env.example` → `.env`, then add `ELEVENLABS_API_KEY` and `ELEVENLABS_VOICE_ID` (the voice you recorded with). This enables `/scribe-token` and live TTS.
3. **Kaggle Part A** (~10 min) to get the `.tflite` for Android: `training/HOW_TO_RUN.md`. Part B (fine-tune) only if testing shows it's needed.

## Run it

```bash
.venv\Scripts\activate
pytest -q                                              # 61 tests
python -m tools.try_detector samples/coco_indoor       # annotated images in runs/try
python -m tools.try_detector 0 --show --model models/raahi_yoloe_n_320.onnx --imgsz 320   # laptop webcam, live
uvicorn server.voice.dev_server:app --host 0.0.0.0 --port 8000
```

## For Member B (server)

```python
from server.perception.detector import detect, Detection      # contract 5.4
from server.mocks.mock_detector import get_detect               # RAAHI_MOCK_DETECTOR=1 -> fake boxes
from server.perception.sizes import pinhole_distance_m
from server.voice.token import router as voice_router           # app.include_router(voice_router)
from server.voice.commands import handle_message                # phone command msgs -> intent + clips
from voice_assets import sentence as S                          # S.obstacle(...), S.describe(...), S.find_guidance(...)
from server.voice import tts_live                               # tts_live.ensure_async(label); S.obstacle(..., live_ids=tts_live.cached_ids())
```
Detector env: `RAAHI_MODEL` (default `yoloe-26s-seg.pt`; on the Mac export CoreML with `python -m tools.export_models --sizes s:416 --formats coreml` and use `models/raahi_yoloe_s_416.mlpackage`), `RAAHI_IMGSZ`, `RAAHI_DEVICE=mps`.

## For Member C (mobile)
- `mobile/public/clips/` (mp3 + `manifest.json`) is refreshed by `process_clips`. Load the manifest, then `createComposer(manifest)`.
- `voice.js`, `detector.js`, `localGuide.js`, `sentence.js`: usage is in each file's header.
- Native plugin setup: `mobile/native/android/README.md`.
- `npm i @elevenlabs/client onnxruntime-web`

## Contract additions (please agree as a team)
1. `command_text` gets an optional field `"wake_required": true|false`. Double-tap sends `false`, so the command works without saying "Raahi". The server already honours it (`commands.handle_message`).
2. Clip ids beginning with `live_` can appear in `clips`. The phone fetches `GET /clips/live/<id>.mp3` if it doesn't have it cached, and the manifest lists cached live clips.
3. *(proposal, on-device mode)* the phone may send `{"type":"frame_meta","detections":[{"cls","conf","box"}]}` right before a binary frame. The Mac can then skip YOLO and run depth only. Not implemented on the server side yet (it's in B's `main.py`).
4. Wake words: **"راہی / Raahi"** and **"بصیرت / Basirat"** both work (`WAKE_WORDS` in `intents.py`). The welcome clip says "میں راہی ہوں"; change it in `clips.json` if the name changes.
