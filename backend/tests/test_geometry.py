import numpy as np

from server.engine import geometry as g
from server.engine.geometry import lateral_metres, move_steps, steps_clip, steps_from_metres


def test_steps_ceil_point_six():
    assert steps_from_metres(0.1) == 1
    assert steps_from_metres(0.6) == 1
    assert steps_from_metres(0.61) == 2
    assert steps_from_metres(1.2) == 2
    assert steps_from_metres(1.21) == 3
    assert steps_from_metres(4.8) == 8
    assert steps_from_metres(4.81) == 9


def test_steps_clip_far():
    assert steps_clip(1) == "steps_1"
    assert steps_clip(8) == "steps_8"
    assert steps_clip(9) == "steps_far"
    assert steps_clip(steps_from_metres(float("inf"))) == "steps_far"


def test_move_steps_clamped_1_to_4():
    assert move_steps(0.1) == 1
    assert move_steps(0.6) == 1
    assert move_steps(1.2) == 2
    assert move_steps(10.0) == 4


def test_lateral_adds_margin():
    lat = lateral_metres((100, 10, 250, 200), "left", 1.2, fx=320, width=416)
    assert abs(lat - (abs(100 - 208) * 1.2 / 320 + 0.35)) < 1e-6


def test_zone_depths_tenth_percentile():
    h, w = 100, 300
    depth = np.ones((h, w), np.float32) * 3.0
    depth[:, 100:200] = 1.0
    left, centre, right = g.zone_depths(depth)
    assert centre < 1.2
    assert left > 2.5
    assert right > 2.5


def test_zone_depths_ignore_floor_ramp():
    h, w = 100, 300
    depth = np.ones((h, w), np.float32) * 3.0
    for y in range(55, 100):
        depth[y, :] = 3.0 - (y - 55) * 0.05
    _left, centre, _right = g.zone_depths(depth)
    assert centre > 1.8


def test_zone_depths_keep_near_person():
    h, w = 100, 300
    depth = np.ones((h, w), np.float32) * 3.0
    depth[70:88, 120:180] = 0.9
    _left, centre, _right = g.zone_depths(depth)
    assert centre < 1.15


def test_zone_depths_object_in_front_of_wall():
    """A small near object must beat the wall behind it (0.3 m vs 1.2 m)."""
    h, w = 100, 300
    depth = np.ones((h, w), np.float32) * 1.2
    depth[48:82, 128:168] = 0.3
    _left, centre, _right = g.zone_depths(depth)
    assert centre < 0.5
    assert centre > 0.2


def test_fuse_metres_blends_when_close():
    z = g.fuse_metres(1.2, 1.4)
    assert 1.2 < z < 1.4
    assert g.fuse_metres(1.2, 4.0) == 1.2
    assert g.fuse_metres(float("nan"), 2.0) == 2.0
