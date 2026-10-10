#!/usr/bin/env python3
"""Focal length + tape-measure depth check.

fx = (pixel_width * distance_m) / real_width_m

Examples:
  python tools/calibrate.py --width-px 180 --width-m 0.45 --distance-m 2.0
  python tools/calibrate.py --image shot.jpg --box 120,80,300,400 --width-m 0.9 --distance-m 2.0
  python tools/calibrate.py --image shot.jpg --width-m 0.9 --distance-m 2.0
      (click two opposite edges of the object, then q)

  python tools/calibrate.py --depth-check --image at2m.jpg --box 100,80,300,400 --distance-m 2.0
  python tools/calibrate.py --save-scale --tape-m 0.89 --model-m 1.40
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from server.config import STREAM_WIDTH  # noqa: E402


def fx_from(width_px: float, width_m: float, distance_m: float) -> float:
    if width_m <= 0:
        raise ValueError("width_m must be > 0")
    return float(width_px) * float(distance_m) / float(width_m)


def click_width(image_bgr: np.ndarray) -> float:
    pts: list[tuple[int, int]] = []
    vis = image_bgr.copy()

    def on_mouse(event, x, y, _flags, _param):
        if event != cv2.EVENT_LBUTTONDOWN:
            return
        pts.append((x, y))
        cv2.circle(vis, (x, y), 5, (0, 255, 255), -1)
        if len(pts) >= 2:
            cv2.line(vis, pts[0], pts[1], (0, 255, 255), 2)
        cv2.imshow("calibrate", vis)

    cv2.imshow("calibrate", vis)
    cv2.setMouseCallback("calibrate", on_mouse)
    print("Click two points spanning the known width, then press any key.")
    cv2.waitKey(0)
    cv2.destroyAllWindows()
    if len(pts) < 2:
        raise SystemExit("need two clicks")
    (x1, y1), (x2, y2) = pts[0], pts[1]
    return float(np.hypot(x2 - x1, y2 - y1))


def parse_box(s: str) -> tuple[int, int, int, int]:
    x1, y1, x2, y2 = (int(v) for v in s.split(","))
    return x1, y1, x2, y2


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--image", help="photo of an object of known width at a known distance")
    p.add_argument("--box", help="x1,y1,x2,y2 of that object in the image")
    p.add_argument("--width-px", type=float, help="measured pixel width (skip image)")
    p.add_argument("--width-m", type=float, default=0.45, help="real object width in metres")
    p.add_argument("--distance-m", type=float, default=2.0, help="tape-measure distance")
    p.add_argument("--depth-check", action="store_true", help="compare estimate() vs tape")
    p.add_argument("--save-scale", action="store_true", help="write tape/model scale to disk")
    p.add_argument("--tape-m", type=float, help="true tape metres (with --save-scale)")
    p.add_argument("--model-m", type=float, help="dashboard/model metres (with --save-scale)")
    args = p.parse_args()

    if args.save_scale:
        tape = args.tape_m if args.tape_m is not None else args.distance_m
        model = args.model_m
        if model is None or model <= 0.05 or tape is None or tape <= 0:
            raise SystemExit("need --tape-m and --model-m (both metres, > 0)")
        from server.perception.depth import SCALE_FILE, write_saved_scale

        saved = write_saved_scale(tape / model)
        print(f"tape {tape:.2f} m / model {model:.2f} m")
        print(f"saved scale {saved:.4f} → {SCALE_FILE}")
        print("restart: python -m server")
        return

    frame = None
    width_px = args.width_px
    box = parse_box(args.box) if args.box else None
    if args.image:
        frame = cv2.imread(args.image)
        if frame is None:
            raise SystemExit(f"cannot read {args.image}")
        if box:
            width_px = float(box[2] - box[0])
        elif width_px is None:
            width_px = click_width(frame)

    if width_px is None:
        raise SystemExit("provide --width-px, or --image plus clicks/--box")

    # Scale pixel width to the streamed width the engine actually sees
    if frame is not None:
        scale = STREAM_WIDTH / frame.shape[1]
        width_at_stream = width_px * scale
        fy_note = f"(image {frame.shape[1]}px wide → stream {STREAM_WIDTH}px, scaled w={width_at_stream:.1f}px)"
    else:
        width_at_stream = width_px
        fy_note = "(assuming pixels already at stream width)"

    fx = fx_from(width_at_stream, args.width_m, args.distance_m)
    print()
    print(f"object {args.width_m} m wide at {args.distance_m} m  {fy_note}")
    print(f"fx = {fx:.1f} px   (put this in .env as RAAHI_FX={fx:.1f})")
    print()

    if args.depth_check:
        if frame is None:
            raise SystemExit("--depth-check needs --image")
        from server.perception.depth import estimate
        from server.engine.geometry import median_box_depth

        depth = estimate(frame)
        if box is None:
            h, w = depth.shape[:2]
            box = (w // 3, int(h * 0.3), 2 * w // 3, int(h * 0.95))
        z = median_box_depth(depth, box)
        err = abs(z - args.distance_m) / args.distance_m * 100 if np.isfinite(z) else float("nan")
        print(f"depth median in box {box}: {z:.2f} m")
        print(f"tape: {args.distance_m:.2f} m   error: {err:.1f}%   (target ≤ 15–20%)")
        if np.isfinite(z) and z > 0.2:
            suggested = args.distance_m / z
            print(f"suggested scale: {suggested:.3f}")
            print(
                "  save it:  python tools/calibrate.py --save-scale "
                f"--tape-m {args.distance_m} --model-m {z:.3f}"
            )
            print(
                f'  or live:  curl "http://127.0.0.1:8000/calibrate?tape_m={args.distance_m}"'
            )
        print("Record 1 m / 2 m / 3 m for the pitch slide.")


if __name__ == "__main__":
    main()
