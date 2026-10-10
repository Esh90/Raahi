import numpy as np

from server.engine.describe import compose
from server.perception.detector import Detection


def test_describe_three_nearest_with_side_and_steps():
    h, w = 240, 416
    depth = np.full((h, w), 4.0, np.float32)
    chair = Detection("chair", 0.9, (20, 80, 90, 200))
    door = Detection("door", 0.8, (180, 40, 250, 220))
    bag = Detection("bag", 0.7, (330, 120, 400, 200))
    depth[80:200, 20:90] = 1.2
    depth[40:220, 180:250] = 2.0
    depth[120:200, 330:400] = 1.5
    d = compose([chair, door, bag], depth, (h, w))
    assert d.kind == "describe"
    assert d.clips[0] == "obj_chair"
    assert "pos_left" in d.clips
    assert "obj_bag" in d.clips
    assert "obj_door" in d.clips
    assert "Ahead:" in d.english


def test_describe_unknown_when_centre_close_unlabelled():
    h, w = 240, 416
    depth = np.full((h, w), 4.0, np.float32)
    depth[:, w // 3 : 2 * w // 3] = 1.1
    d = compose([], depth, (h, w))
    assert "obj_unknown" in d.clips


def test_describe_two_unlabelled_objects():
    h, w = 240, 416
    depth = np.full((h, w), 4.0, np.float32)
    depth[80:190, 10:90] = 1.0
    depth[80:190, 320:400] = 1.6
    d = compose([], depth, (h, w))
    assert d.clips.count("obj_unknown") >= 2
    assert "pos_left" in d.clips
    assert "pos_right" in d.clips


def test_describe_empty():
    h, w = 240, 416
    depth = np.full((h, w), 4.0, np.float32)
    d = compose([], depth, (h, w))
    assert d.clips == ("sys_nothing_ahead",)
