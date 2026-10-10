import numpy as np

from server.engine.pipeline import GuidanceEngine
from server.perception.detector import Detection
from server.voice.intents import Intent


def _frame(centre=3.0):
    h, w = 240, 416
    depth = np.zeros((h, w), np.float32)
    depth[:, : w // 3] = 3.0
    depth[:, w // 3 : 2 * w // 3] = centre
    depth[:, 2 * w // 3 :] = 3.0
    return (h, w, 3), depth


def test_danger_overrides_find_mode():
    e = GuidanceEngine()
    e.handle_intent(Intent("find", "door"))
    shape, depth = _frame(centre=0.5)
    door = Detection("door", 0.9, (300, 40, 400, 220))
    # smoother: danger needs 2 frames
    e.process(shape, [door], depth, now=1.0)
    decision, reply = e.process(shape, [door], depth, now=1.1)
    assert decision.kind == "urgent_stop"
    assert "urg_stop" in reply.clips
    assert e.state.mode == "find"
