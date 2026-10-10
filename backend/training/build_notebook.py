"""Builds training/raahi_detector.ipynb (edit the cells here, then run this file).

    python training/build_notebook.py
"""
import json
from pathlib import Path

CELLS: list[tuple[str, str]] = []


def md(s):
    CELLS.append(("markdown", s.strip("\n")))


def code(s):
    CELLS.append(("code", s.strip("\n")))


md("""
# Raahi detector: export for Android + optional fine-tune

Runs on **Kaggle** (recommended: free T4, 30 h/week, survives tab close with *Save Version*) or **Colab** (T4).
Read `training/HOW_TO_RUN.md` first.

* **Part A, export only (≈10 min, no training).** YOLOE-26 with our class list baked in → `.tflite` (Android) + `.onnx`.
  This can't run on Windows (LiteRT export is Linux/macOS only), which is why it lives here.
* **Part B, fine-tune (≈1.5–3 h, optional).** YOLO26n trained on public data (COCO + Open Images) for our classes.
  Optionally add your own photos (venue/uni) auto-labelled by a big YOLOE teacher.
  Export → `.tflite` / `.onnx` / labels.

Everything you might change is in the **CONFIG** cell.
""")

md("## 0 · Setup")
code("""
!nvidia-smi -L || echo "NO GPU - in Kaggle: Settings > Accelerator > GPU T4 x1 ; in Colab: Runtime > Change runtime type > T4"
%pip install -q -U ultralytics fiftyone
import os, json, shutil, glob, random, zipfile
from pathlib import Path
import torch, ultralytics
print("ultralytics", ultralytics.__version__, "| torch", torch.__version__, "| cuda", torch.cuda.is_available())
ON_KAGGLE = os.path.exists("/kaggle/working")
WORK = Path("/kaggle/working" if ON_KAGGLE else "/content")
os.chdir(WORK)
""")

md("## CONFIG (edit here)")
code("""
# --- where to get shared/classes.json --------------------------------------------
REPO_URL = "https://github.com/Esh90/Rahi.git"   # public repo with shared/classes.json
CLASSES_JSON_URL = ""                            # OR a raw URL to classes.json
# (if neither works: upload classes.json in the file panel to WORK/classes.json)

# --- Part A ------------------------------------------------------------------------
EXPORT_YOLOE = [("n", 320), ("n", 416), ("s", 416)]   # (size, imgsz) pairs to export

# --- Part B ------------------------------------------------------------------------
RUN_FINETUNE      = True
BASE_MODEL        = "yolo26n.pt"    # small = fast on phone. "yolo26s.pt" = more accurate, ~2.5x slower
IMGSZ             = 416
EPOCHS            = 60
BATCH             = 32              # lower to 16 if CUDA out of memory
MAX_PER_CLASS     = 400             # images downloaded per class per source (more = better, slower)
OI_SPLITS         = ["validation", "test"]   # Open Images splits (small CSVs; 'train' is huge)
INCLUDE_CHILD     = False           # True: also train 'child' (Open Images Boy/Girl) - needs care
TEACHER_FILL      = True            # big YOLOE adds boxes for our classes that datasets forgot to label
TEACHER_MODEL     = "yoloe-26l-seg.pt"
TEACHER_CONF      = 0.45
MY_PHOTOS_ZIP     = ""              # e.g. "/kaggle/input/raahi-venue/photos.zip" - your own photos (optional)
MY_PHOTOS_REPEAT  = 3               # oversample your photos (they match the real room best)
""")

code("""
# ---- load class list --------------------------------------------------------------
cj = WORK / "classes.json"
if not cj.exists() and REPO_URL:
    os.system(f"git clone -q --depth 1 {REPO_URL} raahi_repo")
    if (WORK / "raahi_repo/backend/shared/classes.json").exists():
        shutil.copy(WORK / "raahi_repo/backend/shared/classes.json", cj)
if not cj.exists() and CLASSES_JSON_URL:
    os.system(f"wget -q -O {cj} {CLASSES_JSON_URL}")
assert cj.exists(), "classes.json not found - upload it to " + str(cj)
ALL = json.loads(cj.read_text(encoding="utf-8"))["classes"]
CLASSES = [c for c in ALL if c.get("enabled", True) or (INCLUDE_CHILD and c["id"] == "child")]
IDS = [c["id"] for c in CLASSES]
PROMPTS = [p for c in CLASSES for p in c["prompts"]]
PROMPT2ID = {p: c["id"] for c in CLASSES for p in c["prompts"]}
print(len(IDS), "classes:", IDS)
OUT = WORK / "raahi_models"; OUT.mkdir(exist_ok=True)

def labels_json(names, path):
    m = {**PROMPT2ID, **{i: i for i in IDS}}
    payload = {"names": [names[i] for i in range(len(names))],
               "class_ids": [m.get(names[i]) for i in range(len(names))],
               "min_conf": {c["id"]: c["min_conf"] for c in ALL}}
    Path(path).write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")

def move_export(src, dest):
    src = Path(src)
    if src.is_dir():
        src = sorted(src.rglob("*.tflite"))[0]
    shutil.copy(src, dest); print("->", dest)
""")

md("""
## Part A · Export YOLOE with our classes (no training)
`set_classes()` bakes the text embeddings into the head, so the result is a normal fixed-class detector.
Output: `raahi_yoloe_<size>_<imgsz>.tflite` (fp32, use with the GPU delegate), `_int8.tflite` (CPU phones), `.onnx`, `.labels.json`.
""")
code("""
from ultralytics import YOLOE
for size, imgsz in EXPORT_YOLOE:
    stem = f"raahi_yoloe_{size}_{imgsz}"
    m = YOLOE(f"yoloe-26{size}-seg.pt"); m.set_classes(PROMPTS)
    labels_json(m.names, OUT / f"{stem}.labels.json")
    move_export(m.export(format="onnx", imgsz=imgsz, simplify=True), OUT / f"{stem}.onnx")
    m = YOLOE(f"yoloe-26{size}-seg.pt"); m.set_classes(PROMPTS)
    move_export(m.export(format="litert", imgsz=imgsz), OUT / f"{stem}.tflite")
    try:
        m = YOLOE(f"yoloe-26{size}-seg.pt"); m.set_classes(PROMPTS)
        move_export(m.export(format="litert", imgsz=imgsz, quantize=8, data="coco8.yaml"), OUT / f"{stem}_int8.tflite")
    except Exception as e:
        print("int8 export skipped:", e)
!ls -la {OUT}
""")

md("""
## Part B · Fine-tune YOLO26n on public data (optional)
Skip this part (set `RUN_FINETUNE = False`) if the Part A models are good enough on your test photos.
""")
md("### B1 · Class mapping: public dataset names → our ids")
code("""
COCO_MAP = {n: c["id"] for c in CLASSES for n in c.get("coco", [])}
OI_MAP_WANTED = {
    "person":    ["Person", "Man", "Woman"] + ([] if INCLUDE_CHILD else ["Boy", "Girl"]),
    "child":     ["Boy", "Girl"] if INCLUDE_CHILD else [],
    "chair":     ["Chair"],
    "table":     ["Table", "Kitchen & dining room table", "Coffee table"],
    "desk":      ["Desk"],
    "sofa":      ["Couch", "Sofa bed", "Studio couch", "Loveseat"],
    "bench":     ["Bench"],
    "door":      ["Door"],
    "window":    ["Window"],
    "stairs":    ["Stairs"],
    "pillar":    ["Column"],
    "trash_bin": ["Waste container"],
    "plant_pot": ["Houseplant", "Flowerpot"],
    "laptop":    ["Laptop"],
    "monitor":   ["Computer monitor"],
    "tv_led":    ["Television"],
    "whiteboard":["Whiteboard"],
    "cupboard":  ["Cupboard", "Cabinetry", "Wardrobe", "Filing cabinet", "Chest of drawers"],
    "shelf":     ["Shelf", "Bookcase"],
    "bag":       ["Backpack", "Handbag", "Suitcase", "Luggage and bags"],
    "box":       ["Box"],
    "bottle":    ["Bottle"],
    "fan_stand": ["Mechanical fan"],
    "bed":       ["Bed"],
}
import fiftyone.utils.openimages as fouo
OI_AVAILABLE = set(fouo.get_classes())
OI_MAP = {n: cid for cid, names in OI_MAP_WANTED.items() if cid in IDS for n in names if n in OI_AVAILABLE}
missing = [n for cid, names in OI_MAP_WANTED.items() for n in names if n not in OI_AVAILABLE]
print("Open Images classes used:", len(OI_MAP), "| not in OI (ignored):", missing)
no_public = [i for i in IDS if i not in COCO_MAP.values() and i not in OI_MAP.values()]
print("classes with NO public data (teacher / your photos only):", no_public)
""")

md("### B2 · Download COCO + Open Images subsets (only the images we need)")
code("""
import fiftyone as fo, fiftyone.zoo as foz
from fiftyone import ViewField as F

parts = []
coco = foz.load_zoo_dataset("coco-2017", splits=["train"], label_types=["detections"],
                            classes=sorted(COCO_MAP), max_samples=MAX_PER_CLASS * len(set(COCO_MAP.values())),
                            dataset_name="raahi_coco", drop_existing_dataset=True, shuffle=True, seed=0)
coco = coco.map_labels("ground_truth", COCO_MAP).filter_labels("ground_truth", F("label").is_in(IDS))
parts.append(coco)

for oi_cls in sorted(OI_MAP):
    try:
        ds = foz.load_zoo_dataset("open-images-v7", splits=OI_SPLITS, label_types=["detections"],
                                  classes=[oi_cls], max_samples=MAX_PER_CLASS, shuffle=True, seed=0,
                                  dataset_name=f"raahi_oi_{oi_cls}", drop_existing_dataset=True)
        ds = ds.map_labels("ground_truth", OI_MAP).filter_labels("ground_truth", F("label").is_in(IDS))
        parts.append(ds); print(oi_cls, len(ds))
    except Exception as e:
        print("skip", oi_cls, e)
print("total samples:", sum(len(p) for p in parts))
""")

md("### B3 · Write a YOLO dataset (90% train / 10% val)")
code("""
DS = WORK / "raahi_ds"
if DS.exists(): shutil.rmtree(DS)
for p in parts:
    view = p.shuffle(seed=1)
    n_val = max(1, int(0.1 * len(view)))
    view.limit(n_val).export(export_dir=str(DS), dataset_type=fo.types.YOLOv5Dataset, label_field="ground_truth",
                             split="val", classes=IDS)
    view.skip(n_val).export(export_dir=str(DS), dataset_type=fo.types.YOLOv5Dataset, label_field="ground_truth",
                            split="train", classes=IDS)
(DS / "dataset.yaml").write_text(json.dumps({"path": str(DS), "train": "images/train", "val": "images/val",
                                              "names": {i: n for i, n in enumerate(IDS)}}))
print(len(list((DS/"images/train").glob("*"))), "train /", len(list((DS/"images/val").glob("*"))), "val")
""")

md("""
### B4 · (optional) Add your own photos, auto-labelled by the YOLOE teacher
Zip a folder of JPGs (venue/uni corridors, classrooms, labs: any angle, phone held at chest height),
upload it (Kaggle: *Add data → Upload* → it appears under `/kaggle/input/...`; Colab: file panel),
and set `MY_PHOTOS_ZIP`. 100–300 photos is plenty. Then review labels in Roboflow if you have time (optional).
""")
code("""
from ultralytics import YOLOE
teacher = None
def get_teacher():
    global teacher
    if teacher is None:
        teacher = YOLOE(TEACHER_MODEL); teacher.set_classes(PROMPTS)
    return teacher

def teacher_boxes(img_path):
    r = get_teacher().predict(str(img_path), imgsz=640, conf=TEACHER_CONF, verbose=False)[0]
    out = []
    for (cx, cy, w, h), c in zip(r.boxes.xywhn.tolist(), r.boxes.cls.tolist()):
        cid = PROMPT2ID.get(r.names[int(c)])
        if cid in IDS: out.append((IDS.index(cid), cx, cy, w, h))
    return out

if MY_PHOTOS_ZIP:
    tmp = WORK / "my_photos"; shutil.rmtree(tmp, ignore_errors=True)
    zipfile.ZipFile(MY_PHOTOS_ZIP).extractall(tmp)
    imgs = [p for p in tmp.rglob("*") if p.suffix.lower() in (".jpg", ".jpeg", ".png")]
    n = 0
    for p in imgs:
        boxes = teacher_boxes(p)
        for k in range(MY_PHOTOS_REPEAT):
            split = "val" if (hash(p.name) % 10 == 0) else "train"
            if split == "val" and k > 0: continue
            dst = DS / f"images/{split}/my_{k}_{p.stem}{p.suffix}"
            shutil.copy(p, dst)
            (DS / f"labels/{split}/my_{k}_{p.stem}.txt").write_text(
                "".join(f"{c} {x:.6f} {y:.6f} {w:.6f} {h:.6f}\\n" for c, x, y, w, h in boxes))
        n += 1
    print("added", n, "own photos")
""")

md("""
### B5 · (optional) Teacher fill
Public datasets label only *some* classes per image (an Open Images "Door" photo has no chair labels even if
there's a chair). The teacher adds confident boxes for our classes that don't overlap existing labels.
This also gives classes with no public data (glass door, wall, water cooler…) some examples.
""")
code("""
def iou(a, b):
    ax1, ay1, ax2, ay2 = a[0]-a[2]/2, a[1]-a[3]/2, a[0]+a[2]/2, a[1]+a[3]/2
    bx1, by1, bx2, by2 = b[0]-b[2]/2, b[1]-b[3]/2, b[0]+b[2]/2, b[1]+b[3]/2
    iw, ih = max(0, min(ax2, bx2)-max(ax1, bx1)), max(0, min(ay2, by2)-max(ay1, by1))
    inter = iw*ih; u = a[2]*a[3] + b[2]*b[3] - inter
    return inter/u if u > 0 else 0

if TEACHER_FILL:
    from tqdm.auto import tqdm
    added = 0
    for img in tqdm(sorted((DS/"images/train").glob("*"))):
        if img.name.startswith("my_"): continue
        lbl = DS/"labels/train"/(img.stem + ".txt")
        gt = [list(map(float, l.split())) for l in (lbl.read_text().splitlines() if lbl.exists() else []) if l.strip()]
        new = []
        for c, x, y, w, h in teacher_boxes(img):
            if all(iou((x, y, w, h), g[1:]) < 0.3 for g in gt):
                new.append(f"{c} {x:.6f} {y:.6f} {w:.6f} {h:.6f}\\n")
        if new:
            with open(lbl, "a") as f: f.writelines(new)
            added += len(new)
    print("teacher added", added, "boxes")
""")

md("### B6 · Train")
code("""
from ultralytics import YOLO
if RUN_FINETUNE:
    model = YOLO(BASE_MODEL)
    model.train(data=str(DS/"dataset.yaml"), epochs=EPOCHS, imgsz=IMGSZ, batch=BATCH, device=0,
                patience=15, cos_lr=True, close_mosaic=10, fliplr=0.5, degrees=5, hsv_v=0.5,
                project=str(WORK/"runs"), name="raahi", exist_ok=True, plots=True, workers=4)
BEST = WORK/"runs/raahi/weights/best.pt"
print(BEST, BEST.exists())
""")

md("### B7 · Validate + look at predictions")
code("""
from IPython.display import display
from PIL import Image as PImage
m = YOLO(str(BEST))
metrics = m.val(data=str(DS/"dataset.yaml"), imgsz=IMGSZ, plots=False)
print("mAP50:", round(metrics.box.map50, 3), "| mAP50-95:", round(metrics.box.map, 3))
for k, ci in enumerate(metrics.box.ap_class_index):
    print(f"  {IDS[int(ci)]:18s} AP50 {metrics.box.ap50[k]:.2f}")
for p in random.sample(sorted((DS/"images/val").glob("*")), 4):
    r = m.predict(str(p), imgsz=IMGSZ, verbose=False)[0]
    display(PImage.fromarray(r.plot()[:, :, ::-1]).resize((420, int(420 * r.orig_shape[0] / r.orig_shape[1]))))
""")

md("### B8 · Export the fine-tuned model")
code("""
stem = f"raahi_ft_{Path(BASE_MODEL).stem.replace('yolo26', '')}_{IMGSZ}"
shutil.copy(BEST, OUT/f"{stem}.pt")
labels_json(YOLO(str(BEST)).names, OUT/f"{stem}.labels.json")
move_export(YOLO(str(BEST)).export(format="onnx", imgsz=IMGSZ, simplify=True), OUT/f"{stem}.onnx")
move_export(YOLO(str(BEST)).export(format="litert", imgsz=IMGSZ), OUT/f"{stem}.tflite")
try:
    move_export(YOLO(str(BEST)).export(format="litert", imgsz=IMGSZ, quantize=8, data=str(DS/"dataset.yaml")),
                OUT/f"{stem}_int8.tflite")
except Exception as e:
    print("int8 skipped:", e)
""")

md("## Download")
code("""
shutil.make_archive(str(WORK/"raahi_models"), "zip", OUT)
print("Download:", WORK/"raahi_models.zip")
!ls -la {OUT}
if not ON_KAGGLE:
    from google.colab import files; files.download(str(WORK/"raahi_models.zip"))
""")


def build():
    nb = {"nbformat": 4, "nbformat_minor": 5,
          "metadata": {"kernelspec": {"name": "python3", "display_name": "Python 3"},
                       "accelerator": "GPU", "colab": {"gpuType": "T4"}},
          "cells": []}
    for kind, src in CELLS:
        cell = {"cell_type": kind, "metadata": {}, "source": src.splitlines(keepends=True)}
        if kind == "code":
            cell.update(outputs=[], execution_count=None)
        nb["cells"].append(cell)
    out = Path(__file__).with_name("raahi_detector.ipynb")
    out.write_text(json.dumps(nb, indent=1, ensure_ascii=False), encoding="utf-8")
    print("wrote", out)


if __name__ == "__main__":
    build()
