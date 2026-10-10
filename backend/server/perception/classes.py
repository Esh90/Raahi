"""Class list loader (single source: shared/classes.json)."""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

CLASSES_JSON = Path(__file__).resolve().parents[2] / "shared" / "classes.json"


@lru_cache
def all_classes() -> list[dict]:
    return json.loads(CLASSES_JSON.read_text(encoding="utf-8-sig"))["classes"]


def enabled() -> list[dict]:
    return [c for c in all_classes() if c.get("enabled", True)]


@lru_cache
def by_id() -> dict[str, dict]:
    return {c["id"]: c for c in all_classes()}


def prompts() -> list[str]:
    """Flat YOLOE prompt list in model order (what set_classes() receives)."""
    return [p for c in enabled() for p in c["prompts"]]


@lru_cache
def name_to_class() -> dict[str, str]:
    """Any model output name -> our class id. Covers YOLOE prompts, COCO names,
    and our own ids (fine-tuned model)."""
    m: dict[str, str] = {}
    for c in all_classes():
        m[c["id"]] = c["id"]
        for p in c["prompts"]:
            m[p] = c["id"]
        for n in c.get("coco", []):
            m[n] = c["id"]
    return m


def min_conf(cls_id: str, default: float = 0.35) -> float:
    c = by_id().get(cls_id)
    return c["min_conf"] if c else default
