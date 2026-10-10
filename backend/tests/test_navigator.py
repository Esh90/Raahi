import numpy as np

from server.engine.navigator import Navigator
from server.perception.detector import Detection


def _depth(h=240, w=416, left=3.0, centre=2.5, right=3.0) -> np.ndarray:
    d = np.zeros((h, w), np.float32)
    d[:, : w // 3] = left
    d[:, w // 3 : 2 * w // 3] = centre
    d[:, 2 * w // 3 :] = right
    return d


def test_danger_is_urgent_stop():
    nav = Navigator()
    depth = _depth(centre=0.5)
    decision = nav.step((240, 416, 3), [], depth)
    assert decision.kind == "urgent_stop"
    assert "urg_stop" in decision.clips
    assert any(c.startswith("move_left_") for c in decision.clips)
    assert decision.urgent and decision.interrupt
    assert decision.haptic == "long"


def test_warn_names_chair_and_moves_to_clear_side():
    nav = Navigator()
    depth = _depth(left=3.5, centre=1.3, right=1.1)
    chair = Detection("chair", 0.9, (150, 80, 270, 220))
    decision = nav.step((240, 416, 3), [chair], depth)
    assert decision.kind == "obstacle"
    assert decision.clips[0] == "obj_chair"
    assert "pos_ahead" in decision.clips
    assert any(c.startswith("move_left_") for c in decision.clips)
    assert "move" in decision.english.lower() and "left" in decision.english.lower()


def test_both_sides_tight_still_gives_a_direction():
    # Was a bare "stop, obstacle ahead" (user left puzzled). Now: steer to the roomier side.
    nav = Navigator()
    depth = _depth(left=1.0, centre=1.4, right=0.9)
    decision = nav.step((240, 416, 3), [], depth)
    assert decision.kind == "obstacle"
    assert any(c.startswith("move_left_") for c in decision.clips)
    assert decision.direction == "left"


def test_everything_blocked_says_step_back():
    nav = Navigator()
    depth = _depth(left=0.6, centre=0.5, right=0.6)
    decision = nav.step((240, 416, 3), [], depth)
    assert decision.kind == "urgent_stop"
    assert decision.clips == ("urg_stop", "move_back")
    assert decision.direction == "back"


def test_danger_hysteresis_no_flip_flop():
    nav = Navigator()
    assert nav.step((240, 416, 3), [], _depth(left=2.5, centre=0.75)).kind == "urgent_stop"
    # 0.85 m is above DANGER (0.8) but below the exit threshold (1.05) -> still danger
    assert nav.step((240, 416, 3), [], _depth(left=2.5, centre=0.85)).kind == "urgent_stop"
    assert nav.step((240, 416, 3), [], _depth(left=2.5, centre=1.3)).kind == "obstacle"


def test_both_under_warn_moves_to_larger_side():
    nav = Navigator()
    depth = _depth(left=1.8, centre=1.3, right=1.0)
    decision = nav.step((240, 416, 3), [], depth)
    assert decision.kind == "obstacle"
    assert any(c.startswith("move_left_") for c in decision.clips)
    assert "Stop" in decision.english
    assert "left" in decision.english


def test_danger_then_moves_to_open_side():
    nav = Navigator()
    depth = _depth(left=2.4, centre=0.5, right=0.9)
    decision = nav.step((240, 416, 3), [], depth)
    assert decision.kind == "urgent_stop"
    assert "urg_stop" in decision.clips
    assert any(c.startswith("move_left_") for c in decision.clips)
    assert "move" in decision.english.lower()


def test_clear_path():
    nav = Navigator()
    depth = _depth(left=3.0, centre=3.2, right=3.1)
    decision = nav.step((240, 416, 3), [], depth)
    assert decision.kind == "clear"
    assert decision.clips == ("sys_path_clear",)


def test_unknown_obstacle_when_no_label():
    nav = Navigator()
    depth = _depth(left=3.0, centre=1.2, right=3.0)
    decision = nav.step((240, 416, 3), [], depth)
    assert decision.kind == "obstacle"
    assert decision.clips[0] == "obj_unknown"
    assert any(o.get("dist_m") is not None and o["dist_m"] < 1.4 for o in decision.overlay)


def test_overlay_has_separate_depth_per_object():
    nav = Navigator()
    h, w = 240, 416
    depth = np.full((h, w), 4.0, np.float32)
    depth[90:200, 10:90] = 0.9
    depth[90:200, 320:400] = 1.55
    decision = nav.step((h, w, 3), [], depth)
    dists = [o["dist_m"] for o in decision.overlay if o.get("dist_m") is not None]
    assert len(dists) >= 2
    assert min(dists) < 1.15
    assert any(d > 1.3 for d in dists)


def test_flat_wall_says_turn():
    nav = Navigator()
    d = nav.step((240, 416, 3), [], _depth(left=1.6, centre=1.5, right=1.7))
    assert d.kind == "obstacle"
    assert d.clips[0] == "obj_wall"
    assert d.clips[-1] in ("turn_left", "turn_right")


def test_side_is_sticky_on_noise():
    nav = Navigator()
    first = nav.step((240, 416, 3), [], _depth(left=3.0, centre=1.2, right=2.8))
    assert first.direction == "left"
    # right now slightly roomier (noise) -> keep saying left
    again = nav.step((240, 416, 3), [], _depth(left=2.8, centre=1.2, right=3.0))
    assert again.direction == "left"
    # right clearly roomier -> switch
    switched = nav.step((240, 416, 3), [], _depth(left=1.0, centre=1.2, right=3.0))
    assert switched.direction == "right"
