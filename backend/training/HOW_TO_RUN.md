# Running `raahi_detector.ipynb` (Kaggle recommended)

**Why Kaggle:** a free T4 GPU for 30 h/week, sessions that keep running after you close the tab ("Save Version"), and fast downloads. Colab also works, but free sessions disconnect after a while and the GPU isn't always available.

The notebook has two parts:

| Part | What it does | Time on T4 | When you need it |
|---|---|---|---|
| **A, Export** | YOLOE-26 with our classes → `.tflite` (Android) + `.onnx` | ~10 min | **Now.** The Android app needs `.tflite`, and Windows can't export it |
| **B, Fine-tune** | Trains YOLO26n on COCO + Open Images (+ your photos) | 1.5–3 h | Later, only if Part A misses things in tests |

---

## Kaggle, step by step

1. **Account:** sign in at kaggle.com. Verify your phone number (Settings → Phone verification). Without it you get no GPU and no internet.
2. **Make the class list reachable.** Do one of:
   - (easiest) push this repo to GitHub, public (`https://github.com/Esh90/Rahi`). The notebook clones it.
   - or upload `shared/classes.json` later (see step 6).
3. **New notebook:** kaggle.com → *Create* → *New Notebook* → *File* → *Import Notebook* → upload `training/raahi_detector.ipynb`.
4. **Settings** (right panel → *Session options*):
   - *Accelerator* → **GPU T4 x1**
   - *Internet* → **On**
   - *Persistence* → Files only (optional)
5. **Edit the CONFIG cell:**
   - Part A only: set `RUN_FINETUNE = False`, then run the cells up to and including **Part A**. Skip everything after it.
   - Full run: leave `RUN_FINETUNE = True`.
   - If the repo is private or not pushed: set `REPO_URL = ""` and do step 6.
6. *(only if not cloning)* Right panel → *Upload* (or `+ Add Input` → *Upload*) → upload `classes.json`, then copy it: in a new cell run `!cp /kaggle/input/*/classes.json /kaggle/working/`.
7. **Run:**
   - Part A: *Run All* (with `RUN_FINETUNE=False` it still runs the B cells, but they are quick to interrupt), or run cells one by one with Shift+Enter.
   - Long fine-tune: click **Save Version → Save & Run All (Commit)**. It runs in the background, so you can close the tab. Open it later from *Your Work*.
8. **Download:** the last cell creates `/kaggle/working/raahi_models.zip`. In an interactive session use the right panel → *Output* → download. In a committed version, open it → *Output* tab → download.
9. **Put the files in the repo:**
   ```
   raahi_models/*.tflite        → mobile/public/models/   (the Android plugin loads them from there)
   raahi_models/*.labels.json   → mobile/public/models/  and  models/
   raahi_models/*.onnx          → models/   (server / web fallback; also mobile/public/models/ for the web backend)
   raahi_models/raahi_ft_*.pt    → models/   (server: RAAHI_MODEL=models/raahi_ft_n_416.pt)
   ```

## Colab instead

*File → Upload notebook* → `raahi_detector.ipynb`. *Runtime → Change runtime type → T4 GPU*. Run the cells in order. The zip downloads automatically at the end. Upload `classes.json` with the folder icon on the left (into `/content/`) if you don't clone the repo. Keep the tab open while it trains.

## What to change

| Variable | Default | Change it when |
|---|---|---|
| `EXPORT_YOLOE` | n@320, n@416, s@416 | the phone is fast → try s@416. Slow phone → n@320 only |
| `BASE_MODEL` | `yolo26n.pt` | you want more accuracy and the phone can handle it → `yolo26s.pt` |
| `EPOCHS` | 60 | quick test → 15. Best quality → 100 |
| `MAX_PER_CLASS` | 400 | a class is weak → raise it (more download time) |
| `BATCH` | 32 | "CUDA out of memory" → 16 |
| `TEACHER_FILL` | True | you're short on time → False (saves ~5–10 min) |
| `MY_PHOTOS_ZIP` | "" | **at the hackathon:** photograph the demo room (100–300 photos, chest height, walk around, include doors/glass/screens) → zip → upload → path here. This is the biggest accuracy boost |
| `INCLUDE_CHILD` | False | you really want بچہ. It trains on Open Images Boy/Girl, so expect confusion with person |
| `shared/classes.json` | – | add or remove classes. Every class needs an `obj_*` clip in `voice_assets/clips.json` |

## After it runs

- Check the per-class AP50 printout. Anything under ~0.3 is weak: add your own photos for it or raise `MAX_PER_CLASS`.
- Test the new model locally (any of these):
  ```bash
  python -m tools.try_detector samples/ --model models/raahi_ft_n_416.onnx --imgsz 416
  python -m tools.benchmark --models models/raahi_ft_n_416.onnx --sizes 416
  ```
- Classes with **no public data** (glass door, wall, projector screen, water cooler) only improve through `MY_PHOTOS_ZIP` + teacher fill. That's expected.

## Troubleshooting

| Problem | Fix |
|---|---|
| `NO GPU` | Accelerator not set, or the phone isn't verified on Kaggle |
| `git clone` fails / classes.json not found | repo is private → set `REPO_URL=""` and upload `classes.json` |
| fiftyone / MongoDB error on Kaggle | Restart the session and run again. If it still fails, run the notebook on Colab (fiftyone is most reliable there) |
| Open Images download is slow | lower `MAX_PER_CLASS`, or use `OI_SPLITS=["validation"]` |
| `litert` export error | `%pip install -U ultralytics` and restart; the ONNX file still works for the server/web backend |
