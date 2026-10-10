from server.engine.announcer import Announcer
from server.engine.types import PRI_OBSTACLE, PRI_URGENT, Decision


def test_path_clear_only_after_obstacle():
    a = Announcer()
    clear = Decision(kind="clear", key="clear", clips=("sys_path_clear",), english="Path clear")
    r = a.update(clear, now=1.0)
    assert r.clips == []

    obs = Decision(
        kind="obstacle",
        key="obstacle:chair:left:2:2",
        clips=("obj_chair", "pos_ahead", "steps_2", "move_left_2"),
        priority=PRI_OBSTACLE,
        english="chair",
    )
    r = a.update(obs, now=2.0)
    assert "obj_chair" in r.clips
    r = a.update(clear, now=6.0)
    assert r.clips == ["sys_path_clear"]
    r = a.update(clear, now=10.0)
    assert r.clips == []


def test_no_repeat_within_cooldown():
    a = Announcer()
    obs = Decision(
        kind="obstacle",
        key="obstacle:chair:left:2:2",
        clips=("obj_chair",),
        priority=PRI_OBSTACLE,
        english="chair",
    )
    assert a.update(obs, now=1.0).clips == ["obj_chair"]
    assert a.update(obs, now=2.0).clips == []
    assert a.update(obs, now=3.5).clips == []


def test_find_guidance_repeats_after_interval():
    a = Announcer()
    approach = Decision(
        kind="find_approach",
        key="approach:door:3",
        clips=("obj_door", "pos_ahead", "steps_3"),
        priority=60,
        english="Door straight ahead, 3 steps",
    )
    assert a.update(approach, now=1.0).clips == ["obj_door", "pos_ahead", "steps_3"]
    assert a.update(approach, now=2.0).clips == []
    r = a.update(approach, now=4.1)
    assert r.clips == ["obj_door", "pos_ahead", "steps_3"]


def test_higher_priority_interrupts():
    a = Announcer()
    obs = Decision(
        kind="obstacle",
        key="o",
        clips=("obj_chair",),
        priority=PRI_OBSTACLE,
    )
    a.update(obs, now=1.0)
    stop = Decision(
        kind="urgent_stop",
        key="urgent_stop",
        clips=("urg_stop",),
        priority=PRI_URGENT,
        urgent=True,
        interrupt=True,
    )
    r = a.update(stop, now=1.2)
    assert r.interrupt is True
    assert r.clips == ["urg_stop"]


def _urgent(side="left", n=2):
    return Decision(kind="urgent_stop", key=f"urgent_stop:{side}:{n}",
                    clips=("urg_stop", f"move_{side}_{n}"), priority=PRI_URGENT,
                    urgent=True, interrupt=True, english=f"Stop, move {n} {side}", direction=side)


def _obstacle(obj="obj_chair", side="left", steps=3, n=2):
    return Decision(kind="obstacle", key=f"obstacle:{obj}:{side}:{steps}:{n}",
                    clips=(obj, "pos_ahead", f"steps_{steps}", f"move_{side}_{n}"),
                    priority=PRI_OBSTACLE, english="x", direction=side)


def test_stop_is_not_looped():
    """Reported: 'ruk jain' repeated over and over. Now: full stop once, then only the direction."""
    a = Announcer()
    assert a.update(_urgent(), now=1.0).clips == ["urg_stop", "move_left_2"]
    assert a.update(_urgent(n=1), now=1.5).clips == []          # step count flicker: silent
    assert a.update(_urgent(), now=2.5).clips == []
    assert a.update(_urgent(), now=4.2).clips == ["move_left_2"]  # still blocked: direction only
    r = a.update(_urgent(side="right"), now=6.0)                # way out changed
    assert r.clips == ["move_right_2"]


def test_obstacle_label_flicker_is_silent_but_guidance_repeats():
    a = Announcer()
    assert a.update(_obstacle(), now=1.0).clips[0] == "obj_chair"
    assert a.update(_obstacle(obj="obj_person", steps=2), now=2.0).clips == []   # same way: left
    assert a.update(_obstacle(obj="obj_unknown"), now=3.0).clips == []
    r = a.update(_obstacle(steps=2, n=1), now=5.2)                             # keeps guiding
    assert r.clips == ["obj_chair", "pos_ahead", "steps_2", "move_left_1"]
    assert a.update(_obstacle(side="right"), now=7.0).clips[-1] == "move_right_2"  # direction change
