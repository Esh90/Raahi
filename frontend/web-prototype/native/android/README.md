# RaahiDetector: on-device Android detector (for Member C)

A Capacitor plugin that runs our YOLO `.tflite` with LiteRT + GPU delegate. JS wrapper: `mobile/src/detector.js`.
Without the plugin (browser / Render link), `detector.js` falls back to onnxruntime-web automatically.

## 1. Models
Get the `.tflite` files from the Kaggle notebook (Part A, see `training/HOW_TO_RUN.md`) and put them in:
```
mobile/public/models/raahi_yoloe_n_320.tflite
mobile/public/models/raahi_yoloe_n_320.labels.json
mobile/public/models/raahi_yoloe_n_320.onnx          (web fallback; already in the repo)
```
Vite copies `public/` into `dist/`, and `npx cap sync` copies that into `android/app/src/main/assets/public/`. That's where the plugin loads from.

## 2. Add the plugin to the Android project (after `npx cap add android`)
1. Copy `RaahiDetectorPlugin.kt` to `android/app/src/main/java/<your/package/path>/RaahiDetectorPlugin.kt`
   and change the first line `package com.raahi.app` to your app id from `capacitor.config.ts`.
2. Register it in `MainActivity` (same package):
   ```kotlin
   class MainActivity : BridgeActivity() {
       override fun onCreate(savedInstanceState: Bundle?) {
           registerPlugin(RaahiDetectorPlugin::class.java)
           super.onCreate(savedInstanceState)
       }
   }
   ```
3. `android/app/build.gradle`:
   ```gradle
   android {
       // tflite files must NOT be compressed or openFd() fails
       androidResources { noCompress += ["tflite"] }
   }
   dependencies {
       implementation "com.google.ai.edge.litert:litert:1.4.0"
       implementation "com.google.ai.edge.litert:litert-gpu:1.4.0"
       implementation "com.google.ai.edge.litert:litert-gpu-api:1.4.0"
   }
   ```
   (Use the latest 1.x from Maven Central if 1.4.0 has moved on. The package names stay `org.tensorflow.lite.*`.)
   If Kotlin isn't enabled yet: add `id 'org.jetbrains.kotlin.android'` to the app plugins.
4. `npx cap sync android` → build.

## 3. Use from JS
```js
import { createDetector } from "./detector.js";
const det = await createDetector({ backend: "auto", model: "raahi_yoloe_n_320" });
console.log(det.backend);                      // "native-gpu" | "native-cpu" | "web-webgpu" | "web-wasm"
const { detections, ms } = await det.detect(videoElementOrCanvas);
```
Then speak with `localGuide.js` + `sentence.js` (no Mac needed), and/or send the frame to the Mac as usual.

## Notes
- If the GPU delegate fails for an op, the plugin silently falls back to 4 CPU threads (`det.info.gpu === false`).
- Slow phone: use `raahi_yoloe_n_320_int8.tflite` with `gpu: false`.
- Model output formats handled: YOLO26 end-to-end `[1,300,6+]` and classic `[1,4+nc(+32),N]` (YOLOE-seg export = classic `[1,76,2100]` at 320, boxes normalised cx,cy,w,h). Input layout is auto-detected: the LiteRT export is NCHW `[1,3,320,320]`.
- I couldn't compile this here (no Android SDK on Esha's machine). Expect small fixes on the first build.
