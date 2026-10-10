from server.engine.pipeline import GuidanceEngine
from server.voice.intents import Intent


def test_find_command_sets_mode_and_confirm_clip():
    e = GuidanceEngine()
    r = e.handle_intent(Intent("find", "door"))
    assert r is not None
    assert r.mode == "find"
    assert r.clips == ["cmd_finding_door"]


def test_pause_resume():
    e = GuidanceEngine()
    e.handle_intent(Intent("find", "chair"))
    r = e.handle_intent(Intent("pause"))
    assert r.mode == "paused"
    assert "sys_paused" in r.clips
    r = e.handle_intent(Intent("resume"))
    assert r.mode == "find"


def test_stop_exits_find():
    e = GuidanceEngine()
    e.handle_intent(Intent("find", "door"))
    r = e.handle_intent(Intent("stop"))
    assert r.mode == "navigate"
