from voice_assets import sentence as s
from voice_assets.library import clip_ids


def test_obstacle_sentence():
    assert s.obstacle("chair", "ahead", 2, "left", 2) == ["obj_chair", "pos_ahead", "steps_2", "move_left_2"]


def test_plural_and_unknown():
    assert s.obstacle("stairs", "left", 3)[:2] == ["obj_stairs", "pos_left_pl"]
    assert s.obstacle(None, "ahead", 1)[0] == "obj_unknown"
    assert s.obstacle("spaceship", "ahead", 1)[0] == "obj_unknown"
    assert s.obstacle("spaceship", "ahead", 1, live_ids={"live_spaceship"})[0] == "live_spaceship"


def test_clamping():
    assert s.steps_clip(20) == "steps_far"
    assert s.steps_clip(0) == "steps_1"
    assert s.move_clip("right", 9) == "move_right_4"


def test_describe():
    ids = s.describe([("chair", "ahead", 2), ("door", "left", 5)])
    assert ids == ["desc_intro", "obj_chair", "pos_ahead", "steps_2", "conj_and", "obj_door", "pos_left", "steps_5"]
    assert s.describe([]) == ["sys_nothing_ahead"]


def test_find():
    assert s.find_guidance("door", -20, 3)[-1] == "turn_left_slight"
    assert s.find_guidance("door", 0, 3)[-1] == "go_straight"
    assert s.find_guidance("door", 50, 3) == ["turn_right"]
    assert s.find_arrived("door") == ["find_arrived_door"]


def test_every_composed_id_exists():
    """Every id the composer can produce must be in clips.json."""
    produced = set()
    for side in s.SIDES:
        for pl in (False, True):
            produced.add(s.pos_clip(side, pl))
    for n in range(0, 12):
        produced.add(s.steps_clip(n))
    for d in ("left", "right"):
        for n in range(1, 5):
            produced.add(s.move_clip(d, n))
    for t in s.FIND_TARGETS:
        produced |= set(s.find_confirm(t)) | set(s.find_arrived(t))
        produced |= set(s.find_guidance(t, -20, 2)) | set(s.find_guidance(t, 0, 2))
    produced |= {"go_straight", "turn_left", "turn_right", "desc_intro", "conj_and",
                 "urg_stop", "urg_stop_obstacle", "urg_stop_person", "sys_nothing_ahead"}
    missing = produced - clip_ids()
    assert not missing, missing


def test_every_class_has_clip():
    from voice_assets.library import classes
    missing = [c["id"] for c in classes() if c["clip"] not in clip_ids()]
    assert not missing, missing
