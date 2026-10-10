import numpy as np

from server.engine.find_mode import FindGuide
from server.perception.detector import Detection


def test_scan_prompt_when_target_missing():
    g = FindGuide()
    g.start("door")
    h, w = 240, 416
    depth = np.full((h, w), 3.0, np.float32)
    d = g.step([], depth, (h, w), now=10.0)
    assert d.kind == "find_scan"
    assert "find_searching" in d.clips
    d2 = g.step([], depth, (h, w), now=11.0)
    assert d2.kind == "idle"


def test_turn_then_approach_then_arrive():
    g = FindGuide()
    g.start("door")
    h, w = 240, 416
    depth = np.full((h, w), 2.5, np.float32)
    left_door = Detection("door", 0.9, (10, 40, 80, 220))
    d = g.step([left_door], depth, (h, w), now=1.0, fx=320)
    assert d.kind == "find_turn"
    assert d.clips == ("turn_left_slight",)

    ahead = Detection("door", 0.9, (180, 40, 250, 220))
    depth[:, :] = 1.8
    d = g.step([ahead], depth, (h, w), now=2.0, fx=320)
    assert d.kind == "find_approach"
    assert d.clips[0] == "obj_door"
    assert "pos_ahead" in d.clips

    depth[:, :] = 0.7
    d = g.step([ahead], depth, (h, w), now=3.0, fx=320)
    assert d.kind == "find_arrived"
    assert d.haptic == "arrived"


def test_lost_after_three_seconds():
    g = FindGuide()
    g.start("door")
    h, w = 240, 416
    depth = np.full((h, w), 2.0, np.float32)
    door = Detection("door", 0.9, (180, 40, 250, 220))
    g.step([door], depth, (h, w), now=1.0)
    d = g.step([], depth, (h, w), now=5.0)
    assert d.kind == "find_lost"
