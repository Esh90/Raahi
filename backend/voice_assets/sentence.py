"""Sentence composer: turns a decision into an ordered list of clip ids.

Mirrors mobile/src/sentence.js exactly (same function names, same output), so the
Mac engine and the on-device detector speak identical sentences.

Pattern: [obj] + [pos] + [steps] + [move]
    "کرسی" + "سامنے ہے،" + "دو قدم پر۔" + "دو قدم بائیں ہٹیں۔"
"""
from __future__ import annotations

from voice_assets.library import class_info, clip_ids

MAX_STEPS = 8
MAX_MOVE_STEPS = 4
SIDES = ("left", "ahead", "right")


def obj_clip(cls_id: str | None, live_ids: set[str] | None = None) -> str:
    """Clip id for an object class. Unknown/None -> obj_unknown (رکاوٹ).

    live_ids: ids of cached live-TTS clips ("live_<label>") that the phone has.
    """
    if not cls_id:
        return "obj_unknown"
    info = class_info(cls_id)
    if info and info["clip"] in clip_ids():
        return info["clip"]
    live = f"live_{cls_id.replace(' ', '_')}"
    if live_ids and live in live_ids:
        return live
    return "obj_unknown"


def is_plural(cls_id: str | None) -> bool:
    info = class_info(cls_id) if cls_id else None
    return bool(info and info.get("plural"))


def pos_clip(side: str, plural: bool = False) -> str:
    if side not in SIDES:
        side = "ahead"
    return f"pos_{side}{'_pl' if plural else ''}"


def steps_clip(steps: int | None) -> str:
    if steps is None or steps > MAX_STEPS:
        return "steps_far"
    return f"steps_{max(1, int(steps))}"


def move_clip(direction: str, steps: int) -> str:
    """direction: 'left' | 'right'. steps clamped to 1..4."""
    steps = min(max(1, int(steps)), MAX_MOVE_STEPS)
    return f"move_{direction}_{steps}"


def obstacle(cls_id: str | None, side: str, steps: int | None,
             move_dir: str | None = None, move_steps: int = 1,
             live_ids: set[str] | None = None) -> list[str]:
    """'کرسی سامنے ہے، دو قدم پر۔ دو قدم بائیں ہٹیں۔'"""
    ids = [obj_clip(cls_id, live_ids), pos_clip(side, is_plural(cls_id)), steps_clip(steps)]
    if move_dir in ("left", "right"):
        ids.append(move_clip(move_dir, move_steps))
    elif move_dir == "straight":
        ids.append("go_straight")
    return ids


def urgent_stop(cls_id: str | None = None) -> list[str]:
    if cls_id == "person" or cls_id == "child":
        return ["urg_stop_person"]
    if cls_id is None:
        return ["urg_stop"]
    return ["urg_stop_obstacle"]


def describe(items: list[tuple[str | None, str, int | None]],
             live_ids: set[str] | None = None, limit: int = 3) -> list[str]:
    """items: [(cls_id, side, steps)] nearest first.
    'آپ کے سامنے، کرسی سامنے ہے، دو قدم پر۔ اور دروازہ بائیں طرف ہے، پانچ قدم پر۔'"""
    items = items[:limit]
    if not items:
        return ["sys_nothing_ahead"]
    ids = ["desc_intro"]
    for i, (cls_id, side, steps) in enumerate(items):
        if i > 0:
            ids.append("conj_and")
        ids += [obj_clip(cls_id, live_ids), pos_clip(side, is_plural(cls_id)), steps_clip(steps)]
    return ids


FIND_TARGETS = ("door", "chair", "table", "person", "sofa", "trash_bin")


def find_confirm(target: str) -> list[str]:
    return [f"cmd_finding_{target}"] if target in FIND_TARGETS else ["find_not_supported"]


def find_arrived(target: str) -> list[str]:
    return [f"find_arrived_{target}"] if target in FIND_TARGETS else ["sys_ok"]


def find_guidance(target: str, angle_deg: float, steps: int | None,
                  slight_deg: float = 10.0, turn_deg: float = 35.0) -> list[str]:
    """Target visible: 'دروازہ سامنے ہے، تین قدم پر۔ سیدھا چلیں۔' or a turn instruction."""
    if angle_deg <= -turn_deg:
        return ["turn_left"]
    if angle_deg >= turn_deg:
        return ["turn_right"]
    if angle_deg < -slight_deg:
        return [obj_clip(target), pos_clip("left", is_plural(target)), steps_clip(steps), "turn_left_slight"]
    if angle_deg > slight_deg:
        return [obj_clip(target), pos_clip("right", is_plural(target)), steps_clip(steps), "turn_right_slight"]
    return [obj_clip(target), pos_clip("ahead", is_plural(target)), steps_clip(steps), "go_straight"]


def to_text(ids: list[str], lang: str = "urdu_text") -> str:
    """Debug / dashboard subtitle: join the clip texts. lang = 'urdu_text' | 'en'."""
    from voice_assets.library import clip
    out = []
    for i in ids:
        try:
            out.append(clip(i)[lang])
        except KeyError:
            out.append(f"<{i}>")
    return " ".join(t for t in out if t)
