// On-device object detection for the phone.
//   backend "native": Capacitor plugin RaahiDetector (LiteRT + GPU)  ~20-40 ms   (APK only)
//   backend "web"   : onnxruntime-web in the WebView/browser (WebGPU -> WASM) ~80-200 ms
//   backend "auto"  : native if the plugin exists, else web
//
//   import { createDetector } from "./detector.js";
//   const det = await createDetector({ backend: "auto", model: "raahi_yoloe_n_320" });
//   const { detections, ms } = await det.detect(canvas);   // canvas / video / ImageBitmap
//   // detections: [{cls:"chair", conf:0.8, box:[x1,y1,x2,y2] normalised 0-1}]
//
// Files expected in public/models/: <model>.onnx (web), <model>.tflite (native), <model>.labels.json
// npm i onnxruntime-web   (only needed for the web backend)

const BASE_CONF = 0.25;
const MIN_AREA_FRAC = 0.02;
const NMS_IOU = 0.5;
const MERGE_IOU = 0.6;

export async function createDetector({ backend = "auto", model = "raahi_yoloe_n_320", base = "models/" } = {}) {
  const labels = await (await fetch(`${base}${model}.labels.json`)).json();
  const imgsz = parseInt((model.match(/_(\d{3})(?:_|$)/) || [])[1] || "320", 10);
  const plugin = globalThis.Capacitor?.Plugins?.RaahiDetector;

  if (backend === "native" || (backend === "auto" && plugin && globalThis.Capacitor?.isNativePlatform?.())) {
    const info = await plugin.load({ model: `${model}.tflite`, labels: `${model}.labels.json`, imgsz });
    return nativeDetector(plugin, info, labels);
  }
  return webDetector(model, base, imgsz, labels);
}

// ---------------------------------------------------------------- native

function nativeDetector(plugin, info, labels) {
  const canvas = document.createElement("canvas");
  return {
    backend: info.gpu ? "native-gpu" : "native-cpu",
    info,
    async detect(source) {
      const b64 = toJpegBase64(source, canvas);
      const r = await plugin.detect({ image: b64 });
      return { detections: mergeSameClass(r.detections), ms: r.ms };
    },
  };
}

function toJpegBase64(source, canvas, width = 416) {
  const [sw, sh] = sourceSize(source);
  canvas.width = width;
  canvas.height = Math.round((sh * width) / sw);
  canvas.getContext("2d").drawImage(source, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL("image/jpeg", 0.7).split(",")[1];
}

// ---------------------------------------------------------------- web (onnxruntime-web)

async function webDetector(model, base, imgsz, labels) {
  const ort = await import("onnxruntime-web");
  let session, ep;
  for (const providers of [["webgpu"], ["wasm"]]) {
    try {
      session = await ort.InferenceSession.create(`${base}${model}.onnx`, { executionProviders: providers,
        graphOptimizationLevel: "all" });
      ep = providers[0];
      break;
    } catch (e) { console.warn(`ort ${providers[0]} unavailable`, e); }
  }
  if (!session) throw new Error("onnxruntime-web could not load the model");

  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = imgsz;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  const input = new Float32Array(3 * imgsz * imgsz);
  const classIds = labels.class_ids;
  const minConf = labels.min_conf || {};

  return {
    backend: `web-${ep}`,
    async detect(source) {
      const t0 = performance.now();
      const [sw, sh] = sourceSize(source);
      const scale = Math.min(imgsz / sw, imgsz / sh);
      const nw = Math.round(sw * scale), nh = Math.round(sh * scale);
      const padX = (imgsz - nw) / 2, padY = (imgsz - nh) / 2;
      ctx.fillStyle = "rgb(114,114,114)";
      ctx.fillRect(0, 0, imgsz, imgsz);
      ctx.drawImage(source, padX, padY, nw, nh);
      const px = ctx.getImageData(0, 0, imgsz, imgsz).data;
      const plane = imgsz * imgsz;
      for (let i = 0, j = 0; i < plane; i++, j += 4) {     // HWC uint8 -> CHW float 0..1
        input[i] = px[j] / 255;
        input[i + plane] = px[j + 1] / 255;
        input[i + 2 * plane] = px[j + 2] / 255;
      }
      const feeds = { [session.inputNames[0]]: new ort.Tensor("float32", input, [1, 3, imgsz, imgsz]) };
      const out = (await session.run(feeds))[session.outputNames[0]];
      const raw = parseOutput(out.data, out.dims, classIds.length);
      const dets = [];
      for (const d of raw) {
        const cls = classIds[d.cls];
        if (!cls || d.conf < (minConf[cls] ?? 0)) continue;
        const x1 = clamp01((d.x1 - padX) / scale / sw), y1 = clamp01((d.y1 - padY) / scale / sh);
        const x2 = clamp01((d.x2 - padX) / scale / sw), y2 = clamp01((d.y2 - padY) / scale / sh);
        if ((x2 - x1) * (y2 - y1) < MIN_AREA_FRAC) continue;
        dets.push({ cls, conf: d.conf, box: [x1, y1, x2, y2] });
      }
      return { detections: mergeSameClass(dets), ms: performance.now() - t0 };
    },
  };
}

// YOLO26 end2end [1,N,6+] (x1,y1,x2,y2,score,cls) or classic [1,4+nc(+32),N] (cx,cy,w,h,scores)
function parseOutput(data, dims, nc) {
  const res = [];
  if (dims[2] >= 6 && dims[2] <= 70 && dims[1] >= dims[2]) {
    const stride = dims[2];
    for (let i = 0; i < dims[1]; i++) {
      const o = i * stride;
      if (data[o + 4] >= BASE_CONF)
        res.push({ x1: data[o], y1: data[o + 1], x2: data[o + 2], y2: data[o + 3], conf: data[o + 4], cls: data[o + 5] | 0 });
    }
    return res;
  }
  const n = dims[2];
  for (let i = 0; i < n; i++) {
    let best = 0, bestS = 0;
    for (let k = 0; k < nc; k++) {
      const s = data[(4 + k) * n + i];
      if (s > bestS) { bestS = s; best = k; }
    }
    if (bestS < BASE_CONF) continue;
    const cx = data[i], cy = data[n + i], w = data[2 * n + i], h = data[3 * n + i];
    res.push({ x1: cx - w / 2, y1: cy - h / 2, x2: cx + w / 2, y2: cy + h / 2, conf: bestS, cls: best });
  }
  return nms(res, NMS_IOU, (a, b) => a.cls === b.cls);
}

// ---------------------------------------------------------------- helpers

function nms(dets, thr, same, boxOf = (d) => [d.x1, d.y1, d.x2, d.y2]) {
  dets.sort((a, b) => b.conf - a.conf);
  const keep = [];
  for (const d of dets) if (!keep.some((k) => same(k, d) && iou(boxOf(k), boxOf(d)) > thr)) keep.push(d);
  return keep;
}

// several prompts map to one class ("chair" + "office chair") -> drop duplicates
function mergeSameClass(dets) {
  return nms(dets.slice(), MERGE_IOU, (a, b) => a.cls === b.cls, (d) => d.box);
}

export function iou(a, b) {
  const ix = Math.max(0, Math.min(a[2], b[2]) - Math.max(a[0], b[0]));
  const iy = Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
  const inter = ix * iy;
  const u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter;
  return u > 0 ? inter / u : 0;
}

const clamp01 = (v) => Math.min(1, Math.max(0, v));

function sourceSize(s) {
  return [s.videoWidth || s.naturalWidth || s.width, s.videoHeight || s.naturalHeight || s.height];
}
