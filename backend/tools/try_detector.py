"""Run the detector on images / a video / webcam and save annotated output.

    python -m tools.try_detector samples/                       # folder of images
    python -m tools.try_detector room.mp4 --every 10            # every 10th video frame
    python -m tools.try_detector 0 --show                       # webcam (laptop camera) live
    python -m tools.try_detector samples/ --model models/raahi_yoloe_s_416.onnx

Output: runs/try/<name>.jpg + a per-class count summary.
"""
from __future__ import annotations

import argparse
import time
from collections import Counter
from pathlib import Path

import cv2

from server.perception.detector import configure

IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def draw(frame, dets):
    for d in dets:
        x1, y1, x2, y2 = d.box
        cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 220, 0), 2)
        label = f"{d.cls} {d.conf:.2f}"
        (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)
        cv2.rectangle(frame, (x1, max(0, y1 - th - 6)), (x1 + tw + 4, y1), (0, 220, 0), -1)
        cv2.putText(frame, label, (x1 + 2, y1 - 4), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 1)
    return frame


def resize_width(frame, width):
    h, w = frame.shape[:2]
    if w == width:
        return frame
    return cv2.resize(frame, (width, int(h * width / w)))


def frames_from(source: str, every: int):
    p = Path(source)
    if p.is_dir():
        for f in sorted(p.iterdir()):
            if f.suffix.lower() in IMG_EXT:
                img = cv2.imread(str(f))
                if img is not None:
                    yield f.stem, img
    elif p.suffix.lower() in IMG_EXT:
        yield p.stem, cv2.imread(str(p))
    else:
        cap = cv2.VideoCapture(int(source) if source.isdigit() else source)
        i = 0
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if i % every == 0:
                yield f"frame_{i:05d}", frame
            i += 1
        cap.release()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("--model", default=None)
    ap.add_argument("--imgsz", type=int, default=None)
    ap.add_argument("--width", type=int, default=416, help="resize like the phone does")
    ap.add_argument("--every", type=int, default=5)
    ap.add_argument("--out", default="runs/try")
    ap.add_argument("--show", action="store_true")
    args = ap.parse_args()

    det = configure(args.model, args.imgsz)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    counts, times = Counter(), []
    for name, frame in frames_from(args.source, args.every):
        frame = resize_width(frame, args.width)
        t = time.perf_counter()
        dets = det(frame)
        times.append((time.perf_counter() - t) * 1000)
        counts.update(d.cls for d in dets)
        vis = draw(frame.copy(), dets)
        cv2.imwrite(str(out / f"{name}.jpg"), vis)
        print(f"{name}: " + ", ".join(f"{d.cls}({d.conf:.2f})" for d in dets))
        if args.show:
            cv2.imshow("raahi", vis)
            if cv2.waitKey(1) & 0xFF == ord("q"):
                break
    if times:
        times_sorted = sorted(times[1:] or times)   # skip warm-up
        print(f"\n{len(times)} frames, mean {sum(times_sorted)/len(times_sorted):.1f} ms, "
              f"p95 {times_sorted[int(0.95*(len(times_sorted)-1))]:.1f} ms")
    print("detections per class:", dict(counts.most_common()))
    print(f"annotated images -> {out}")


if __name__ == "__main__":
    main()
