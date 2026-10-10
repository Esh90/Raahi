# Engine changes after merging detection + voice (for Member B)

Found during laptop testing with real YOLOE detections + Depth Anything. The user heard
"رُک جائیں، سامنے رکاوٹ ہے" looping with no direction, and left/right flip-flopping.

| File | Change | Why |
|---|---|---|
| `engine/navigator.py` | Danger always comes with a way out: `urg_stop` + `move_<side>_<n>`, or `urg_stop` + `move_back` when everything is blocked | A bare "stop" left the user stuck |
| | Both sides tight (not dangerous): steer to the roomier side (`_roomier_side`) instead of `urg_stop_obstacle` | Same |
| | Hysteresis: danger exits at 0.8+0.25 m, warn at 2.0+0.3 m | stop/obstacle chatter when distance hovers at a threshold |
| | Sticky side: keep the previous left/right unless the other is ≥ 0.4 m roomier | Facing a wall, L≈R and the advice flipped every 1.5 s |
| | Flat wall (all zones < WARN and within 0.35 m) → `turn_left/right` instead of a sidestep | Sidestepping a wall doesn't help |
| | `Decision.direction` set ("left"/"right"/"back"); plural nouns use `pos_ahead_pl` | announcer + grammar |
| `engine/types.py` | `Decision.direction` field and `Decision.topic` (= key without numbers) | |
| `engine/smoother.py` | Compares **topics**, not keys | "3 steps" vs "2 steps" never held for 3 frames, so nothing new was announced |
| `engine/announcer.py` | Stop said once per danger episode; repeats every 3 s are **direction only**; obstacle guidance with the same direction refreshes every 4 s (fresh step count) until the path is clear; label flicker (person ↔ obstacle) with the same direction is silent; 1.5 s minimum gap | Loops / silence while still blocked |
| `engine/geometry.py` | `_trim_floor_rows` vectorised (sort + index) | 240 ms → 31 ms per frame on CPU (faster on the Mac too) |
| `perception/depth.py` | `RAAHI_DEPTH_FAST=1` really runs at `RAAHI_DEPTH_SHORT_SIDE` (`do_resize=False`). Default unchanged | The HF processor resized back to 518 px, so short side had no effect. **Re-calibrate the tape scale if you enable it** |
| `config.py` | Class → clip maps generated from `shared/classes.json` + `voice_assets/clips.json`; FIND_TARGETS + sofa, trash_bin | `obj_plant` didn't exist; the fan was "obstacle"; detector ids like `trash_bin`/`plant_pot` |
| `main.py` | Mounts Esha's voice router (`/scribe-token`, `/voice/scribe-config`, `/clips/manifest.json`, `/clips/<id>.mp3`); `command_text` honours `wake_required` | real token + processed clip manifest |
| `tests/test_navigator.py` | `test_both_sides_almost_equal_is_stop` → `test_both_sides_tight_still_gives_a_direction`, plus new tests for step-back, hysteresis, flat wall and sticky side | intended behaviour change |

Esha's files replaced the stubs: `perception/detector.py` (exported YOLOE, mock when `RAAHI_MOCK=1`),
`perception/sizes.py` (`height_m()` from classes.json), `voice/intents.py` (`parse(text, require_wake=True)`),
`voice/token.py` (`create_scribe_token()` kept). `tools/benchmark.py` is yours; the detector benchmark is
`tools/benchmark_detector.py`.

Laptop test of the whole loop: `python -m tools.live_demo` (webcam + depth + engine + Urdu audio).
