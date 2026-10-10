# Shared test video (everyone uses the same file)

Record on the demo phone, held **upright**, walking the demo room.

- 3–4 runs, different paths, ~2 minutes each
- Resolution close to what the app sends (416 px wide JPEG)
- Put the file in Drive **and** (if small enough) `testdata/room.mp4`

Member B replay (prints decisions, no phone needed):

```bash
RAAHI_MOCK=1 python -m server.replay testdata/room.mp4
```

With real depth (after the model is cached):

```bash
python -m server.replay testdata/room.mp4 --max-frames 200
```

Tape-measure stills for the pitch slide (1 m / 2 m / 3 m from a wall or chair):

```bash
python tools/calibrate.py --depth-check --image at1m.jpg --distance-m 1 --box x1,y1,x2,y2
python tools/calibrate.py --depth-check --image at2m.jpg --distance-m 2 --box x1,y1,x2,y2
python tools/calibrate.py --depth-check --image at3m.jpg --distance-m 3 --box x1,y1,x2,y2
```
