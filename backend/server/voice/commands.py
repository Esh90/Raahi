"""Glue for Member B's /ws handler: phone command messages -> Intent + confirmation clips.

    from server.voice.commands import handle_message
    out = handle_message(msg)        # msg = parsed JSON from the phone
    if out:                          # None = not a command / no wake word -> ignore
        engine.set_mode(out.intent)  # B: find / describe / stop / pause / resume / repeat
        reply["clips"] = out.clips   # instant confirmation ("ٹھیک ہے، دروازہ ڈھونڈ رہی ہوں")
        reply["interrupt"] = True

Handles both:
    {"type":"command_text","text":"راہی دروازہ ڈھونڈو","lang":"ur","wake_required":true}
    {"type":"command","intent":"find","target":"door"}          (gestures / buttons)
"""
from __future__ import annotations

from dataclasses import dataclass, field

from server.voice.intents import Intent, confirmation_clips, parse

VALID = {"find", "describe", "stop", "pause", "resume", "repeat"}


@dataclass
class CommandResult:
    intent: Intent
    clips: list[str] = field(default_factory=list)
    text: str = ""            # transcript (for the dashboard)


def handle_message(msg: dict) -> CommandResult | None:
    t = msg.get("type")
    if t == "command_text":
        text = msg.get("text", "")
        intent = parse(text, require_wake=bool(msg.get("wake_required", True)))
        if intent is None:
            return None
        return CommandResult(intent, confirmation_clips(intent), text)
    if t == "command":
        name = msg.get("intent")
        if name not in VALID:
            return CommandResult(Intent("unknown"), ["sys_not_understood"])
        intent = Intent(name, msg.get("target"))
        return CommandResult(intent, confirmation_clips(intent))
    return None
