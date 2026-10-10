"""Builds training/raahi_export.ipynb: export-only (no training), class list embedded.
Re-run after editing shared/classes.json:

    python training/build_export_notebook.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
classes = json.loads((ROOT / "shared" / "classes.json").read_text(encoding="utf-8-sig"))["classes"]
enabled = [c for c in classes if c.get("enabled", True)]
PROMPTS = [p for c in enabled for p in c["prompts"]]
PROMPT2ID = {p: c["id"] for c in enabled for p in c["prompts"]}
MIN_CONF = {c["id"]: c["min_conf"] for c in classes}

cells = []


def md(s):
    cells.append({"cell_type": "markdown", "metadata": {}, "source": s.strip("\n").splitlines(keepends=True)})


def code(s):
    cells.append({"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [],
                  "source": s.strip("\n").splitlines(keepends=True)})


md(f"""
# Raahi: export the Android detector (no training)

YOLOE-26 with our **{len(enabled)} classes** baked in → `.tflite` for the phone (+ `.onnx`, `.labels.json`).
Runs in ~5–10 min. **No GPU needed** (CPU runtime is fine). Just *Runtime → Run all*.
At the end a `raahi_models.zip` downloads (Colab) or appears in Output (Kaggle).

Class list generated from `shared/classes.json`; to change it, edit that file and run
`python training/build_export_notebook.py` again.
""")

code("""
%pip install -q -U ultralytics
import os, json, shutil
from pathlib import Path
import ultralytics
print("ultralytics", ultralytics.__version__)
WORK = Path("/kaggle/working" if os.path.exists("/kaggle/working") else "/content")
os.chdir(WORK)
OUT = WORK / "raahi_models"; OUT.mkdir(exist_ok=True)
""")

code(f"""
# ---- generated from shared/classes.json (don't edit here) ----
PROMPTS = {json.dumps(PROMPTS, ensure_ascii=False)}
PROMPT2ID = {json.dumps(PROMPT2ID, ensure_ascii=False)}
MIN_CONF = {json.dumps(MIN_CONF)}

# ---- what to export: (model size, input size). n@320 = fastest; s@416 = more accurate ----
EXPORTS = [("n", 320), ("n", 416), ("s", 416)]
MAKE_INT8 = True     # extra *_int8.tflite for phones without a usable GPU
print(len(PROMPTS), "prompts ->", len(set(PROMPT2ID.values())), "classes")
""")

code("""
from ultralytics import YOLOE

def fresh(size):
    m = YOLOE(f"yoloe-26{size}-seg.pt")
    m.set_classes(PROMPTS)
    return m

def save(src, dest):
    src = Path(src)
    if src.is_dir():                                  # litert export returns a folder
        src = sorted(src.rglob("*.tflite"))[0]
    shutil.copy(src, dest)
    print("  ->", dest.name, f"{dest.stat().st_size/1e6:.1f} MB")

results = {}
for size, imgsz in EXPORTS:
    stem = f"raahi_yoloe_{size}_{imgsz}"
    print(f"\\n=== {stem}")
    m = fresh(size)
    names = [m.names[i] for i in range(len(m.names))]
    (OUT / f"{stem}.labels.json").write_text(json.dumps(
        {"names": names, "class_ids": [PROMPT2ID.get(n) for n in names], "min_conf": MIN_CONF},
        ensure_ascii=False, indent=1), encoding="utf-8")
    save(fresh(size).export(format="litert", imgsz=imgsz), OUT / f"{stem}.tflite")
    save(fresh(size).export(format="onnx", imgsz=imgsz, simplify=True), OUT / f"{stem}.onnx")
    if MAKE_INT8:
        try:
            save(fresh(size).export(format="litert", imgsz=imgsz, quantize=8, data="coco8.yaml"),
                 OUT / f"{stem}_int8.tflite")
        except Exception as e:
            print("  int8 skipped:", e)
""")

md("## Check: run the exported `.tflite` on a test image (input/output shapes are what the Android plugin expects)")
code("""
from ultralytics import YOLO
import numpy as np
try:
    from ai_edge_litert.interpreter import Interpreter
except ImportError:
    import tensorflow as tf; Interpreter = tf.lite.Interpreter

img = "https://ultralytics.com/images/bus.jpg"
for f in sorted(OUT.glob("*.tflite")):
    it = Interpreter(model_path=str(f)); it.allocate_tensors()
    ins = [d["shape"].tolist() for d in it.get_input_details()]
    outs = [d["shape"].tolist() for d in it.get_output_details()]
    print(f"{f.name:32s} in {ins}  out {outs}")
    try:
        r = YOLO(str(f), task="segment").predict(img, imgsz=int(f.stem.split("_")[3]), verbose=False)[0]
        print("    detections:", [(r.names[int(c)], round(float(s), 2)) for c, s in zip(r.boxes.cls, r.boxes.conf)][:6])
    except Exception as e:
        print("    (predict check skipped:", e, ")")
""")

md("## Download")
code("""
shutil.make_archive(str(WORK / "raahi_models"), "zip", OUT)
for f in sorted(OUT.iterdir()): print(f"{f.name:34s} {f.stat().st_size/1e6:6.1f} MB")
try:
    from google.colab import files; files.download(str(WORK / "raahi_models.zip"))
except ImportError:
    print("Kaggle: download raahi_models.zip from the Output panel on the right")
""")

nb = {"nbformat": 4, "nbformat_minor": 5,
      "metadata": {"kernelspec": {"name": "python3", "display_name": "Python 3"}}, "cells": cells}
out = Path(__file__).with_name("raahi_export.ipynb")
out.write_text(json.dumps(nb, indent=1, ensure_ascii=False), encoding="utf-8")
print("wrote", out)
