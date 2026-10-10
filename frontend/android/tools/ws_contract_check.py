"""Replays the exact message sequence the Android app uses against a running Raahi server (python -m server).

    python android/tools/ws_contract_check.py ws://127.0.0.1:8000/ws

Prints every reply so field names / modes / clips can be compared with contracts/protocol.md.
Needs: websockets, numpy, opencv-python (all in requirements.txt / .venv).
"""
import json
import sys

import cv2
import numpy as np
from websockets.sync.client import connect


def jpeg(w: int = 416, h: int = 554) -> bytes:
    """Upright (portrait) 416 px wide JPEG, quality 60: what FrameEncoder produces on the phone."""
    img = np.full((h, w, 3), 90, np.uint8)
    cv2.rectangle(img, (120, 200), (300, 520), (60, 110, 170), -1)
    return cv2.imencode(".jpg", img, [int(cv2.IMWRITE_JPEG_QUALITY), 60])[1].tobytes()


def main() -> None:
    url = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:8000/ws"
    with connect(url, max_size=2**23) as ws:
        def say(label: str, msg: dict | bytes, expect_reply: bool = True) -> None:
            ws.send(msg if isinstance(msg, bytes) else json.dumps(msg))
            if not expect_reply:
                return
            try:
                print(f"{label:10} ->", str(ws.recv(timeout=2))[:420])
            except TimeoutError:
                print(f"{label:10} -> (no reply)")

        say("hello", {"type": "hello", "client": "android", "version": "0.1"})
        say("stop", {"type": "command", "intent": "stop"})
        for i in range(3):
            say(f"frame {i + 1}", jpeg())
        say("find", {"type": "command", "intent": "find", "target": "door"})
        say("cmd_text", {"type": "command_text", "text": "darwaza dhoondo", "lang": "ur", "wake_required": False})
        say("describe", {"type": "command", "intent": "describe"})
        say("pause", {"type": "command", "intent": "pause"})
        say("resume", {"type": "command", "intent": "resume"})
        say("stop", {"type": "command", "intent": "stop"})
        say("repeat", {"type": "command_text", "text": "dobara bolo", "wake_required": False})


if __name__ == "__main__":
    main()
