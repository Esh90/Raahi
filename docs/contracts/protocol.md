# Raahi contracts (Section 5)

Do not change these without telling Esha, B, and C.

## 5.1 WebSocket `/ws` — phone → Mac

| Message | Format |
| --- | --- |
| Camera frame | binary JPEG bytes |
| Command text | `{"type":"command_text","text":"...","lang":"ur"}` |
| Manual command | `{"type":"command","intent":"find","target":"door"}` / `describe` / `stop` / `pause` / `resume` |
| Hello | `{"type":"hello","client":"android","version":"0.1"}` |

## 5.2 WebSocket `/ws` — Mac → phone (one reply per frame; clips may be empty)

```json
{
  "type": "result",
  "clips": ["obj_chair", "pos_ahead", "steps_2", "move_left_2"],
  "urgent": false,
  "interrupt": false,
  "haptic": "none",
  "mode": "navigate",
  "overlay": [{"label":"chair","box":[0.31,0.40,0.62,0.95],"dist_m":1.4}],
  "english": "Chair ahead, 2 steps, move 2 left",
  "latency_ms": {"detect":18,"depth":44,"engine":2,"total":71}
}
```

- `clips` empty → say nothing, send next frame.
- `interrupt: true` → stop current audio, then play.
- `haptic`: `none` | `short` | `long` | `arrived`
- overlay `box` is normalised 0–1
- `mode`: `navigate` | `find` | `paused`
- `english` is a bonus field for on-screen subtitles (C)

## 5.3 HTTP

| Route | Purpose | Owner |
| --- | --- | --- |
| `GET /health` | `{"ok":true}` | B |
| `GET /scribe-token` | ElevenLabs single-use realtime_scribe token | Esha |
| `GET /clips/manifest.json` | clip list | Esha |
| `GET /dashboard` | judge view | B |
| `WS /ws/dashboard` | annotated JPEG + JSON ~5 fps | B |

## 5.4 Python interfaces

```python
# server/perception/detector.py  (Esha)
@dataclass
class Detection:
    cls: str
    conf: float
    box: tuple[int,int,int,int]

def detect(frame_bgr) -> list[Detection]: ...

# server/perception/depth.py  (B)
def estimate(frame_bgr) -> np.ndarray:  # HxW float32 metres

# server/voice/intents.py  (Esha)
@dataclass
class Intent:
    type: str  # find | describe | stop | pause | resume | unknown
    target: str | None = None

def parse(text: str) -> Intent | None:  # None = ignore

# server/engine/navigator.py  (B)
def step(frame_shape, detections, depth_m, state) -> Decision

# server/engine/announcer.py  (B)
def update(decision: Decision) -> Reply
```

## Clip IDs used by the engine

Objects: `obj_door`, `obj_wall`, `obj_chair`, `obj_table`, `obj_desk`, `obj_person`, `obj_sofa`, `obj_bag`, `obj_box`, `obj_trash_bin`, `obj_bottle`, `obj_laptop`, `obj_cupboard`, `obj_glass_door`, `obj_pillar`, `obj_plant`, `obj_stairs`, `obj_bed`, `obj_unknown`

Position: `pos_ahead`, `pos_left`, `pos_right`

Distance: `steps_1` … `steps_8`, `steps_far`

Moves: `move_left_1`…`4`, `move_right_1`…`4`, `go_straight`, `turn_left_slight`, `turn_right_slight`, `turn_slowly`

Urgent: `urg_stop`, `urg_stop_obstacle`

Find: `cmd_finding_{door,chair,table,person}`, `find_searching`, `find_arrived_{door,chair,table,person}`, `find_lost`

System: `sys_welcome`, `phone_upright`, `sys_path_clear`, `sys_paused`, `sys_resumed`, `sys_listening`, `sys_not_understood`, `sys_nothing_ahead`, `sys_connection_lost`, `sys_connected`
