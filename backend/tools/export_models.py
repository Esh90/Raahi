"""Export detectors with OUR class list baked in (no training needed).

    python -m tools.export_models                         # yoloe-26n @320 & 416 -> tflite + onnx
    python -m tools.export_models --sizes s:416 n:320 --formats tflite onnx
    python -m tools.export_models --coco                  # backup: stock COCO yolo26n
    python -m tools.export_models --formats coreml        # on the Mac

YOLOE + set_classes() freezes the text embeddings into the head, so the exported file is an
ordinary fixed-class detector (names = our prompts; server/perception/classes.py maps them
back to class ids, mobile reads models/<name>.labels.json).

Outputs go to models/:
    raahi_yoloe_<size>_<imgsz>.(onnx|tflite|mlpackage)  + .labels.json
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from server.perception import classes as C

MODELS = Path(__file__).resolve().parents[1] / "models"
# ultralytics >= 8.4.83: "tflite" became "litert"; quantize = None (fp32) | 8 | "w8a16" | "w8a32".
# fp32 is what the Android GPU delegate wants (it runs fp16 internally); int8 is for CPU-only phones.
FORMATS = {"tflite": {}, "onnx": {"simplify": True}, "coreml": {"half": True, "nms": True},
           "int8": {"quantize": 8, "data": "coco8.yaml"}}


def labels_payload(names: dict[int, str]) -> dict:
    m = C.name_to_class()
    return {"names": [names[i] for i in range(len(names))],
            "class_ids": [m.get(names[i]) for i in range(len(names))],
            "min_conf": {c["id"]: c["min_conf"] for c in C.all_classes()}}


def build_yoloe(size: str):
    from ultralytics import YOLOE
    model = YOLOE(f"yoloe-26{size}-seg.pt")
    names = C.prompts()
    try:
        model.set_classes(names)
    except TypeError:
        model.set_classes(names, model.get_text_pe(names))
    return model


def export(model, stem: str, imgsz: int, formats: list[str]) -> None:
    MODELS.mkdir(exist_ok=True)
    for fmt in formats:
        kw = dict(FORMATS.get(fmt, {}))
        real_fmt = "litert" if fmt in ("tflite", "int8") else fmt
        print(f"\n=== {stem} {imgsz} -> {fmt}")
        try:
            out = Path(model.export(format=real_fmt, imgsz=imgsz, **kw))
        except Exception as e:
            print(f"!! export {fmt} failed: {e}\n   (TFLite/CoreML can be exported in the Colab/Kaggle "
                  f"notebook or on the Mac instead)")
            continue
        # ultralytics returns a folder for tflite (saved_model) - pick the .tflite inside
        if out.is_dir() and real_fmt == "litert":
            out = sorted(out.rglob("*.tflite"))[0]
        suffix = "_int8.tflite" if fmt == "int8" else out.suffix
        dest = MODELS / f"{stem}_{imgsz}{suffix}"
        if dest.exists():
            shutil.rmtree(dest) if dest.is_dir() else dest.unlink()
        shutil.move(str(out), dest)
        print(f"-> {dest}")
    (MODELS / f"{stem}_{imgsz}.labels.json").write_text(
        json.dumps(labels_payload(model.names), ensure_ascii=False, indent=1), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sizes", nargs="+", default=["n:320", "n:416"], help="model:imgsz, e.g. s:416")
    ap.add_argument("--formats", nargs="+", default=["onnx", "tflite"], choices=list(FORMATS))
    ap.add_argument("--coco", action="store_true", help="export stock COCO yolo26n as the backup model")
    args = ap.parse_args()

    if args.coco:
        from ultralytics import YOLO
        for spec in args.sizes:
            _, imgsz = spec.split(":")
            export(YOLO("yolo26n.pt"), "raahi_coco_n", int(imgsz), args.formats)
        return
    for spec in args.sizes:
        size, imgsz = spec.split(":")
        export(build_yoloe(size), f"raahi_yoloe_{size}", int(imgsz), args.formats)


if __name__ == "__main__":
    main()
