from server.engine.smoother import Smoother
from server.engine.types import KIND_DESCRIBE, PRI_DESCRIBE, PRI_OBSTACLE, PRI_URGENT, Decision


def _obs(key="obstacle:chair:left:2:2") -> Decision:
    return Decision(
        kind="obstacle",
        key=key,
        clips=("obj_chair", "pos_ahead", "steps_2", "move_left_2"),
        priority=PRI_OBSTACLE,
        english="chair",
    )


def _stop() -> Decision:
    return Decision(
        kind="urgent_stop",
        key="urgent_stop",
        clips=("urg_stop",),
        priority=PRI_URGENT,
        urgent=True,
        interrupt=True,
    )


def test_obstacle_needs_three_matching_frames():
    s = Smoother()
    a = _obs()
    r1 = s.update(a)
    r2 = s.update(a)
    r3 = s.update(a)
    assert r1.kind == "idle"
    assert r2.kind == "idle"
    assert r3.kind == "obstacle"
    assert r3.key == a.key


def test_danger_needs_two_frames():
    s = Smoother()
    d = _stop()
    assert s.update(d).kind == "idle"
    assert s.update(d).kind == "urgent_stop"


def test_holds_previous_while_flickering():
    s = Smoother()
    a = _obs("a")
    b = _obs("b")
    s.update(a)
    s.update(a)
    held = s.update(a)
    assert held.kind == "obstacle"
    flicker = s.update(b)
    assert flicker.key == "a"


def test_describe_is_immediate():
    s = Smoother()
    d = Decision(
        kind=KIND_DESCRIBE,
        key="describe:x",
        clips=("sys_nothing_ahead",),
        priority=PRI_DESCRIBE,
    )
    assert s.update(d).kind == KIND_DESCRIBE
