# Raahi – native Android app (Kotlin + Jetpack Compose)

Front end for the Raahi server (`../../backend/server/`, contracts in `../../docs/contracts/protocol.md`). Nothing in the backend was changed.

> **Verification status (read first).** This PC has no JDK / Android SDK / Gradle, so **the project has not been compiled, installed or run on a device or emulator**. What *was* verified: the WebSocket/HTTP contract against the real Python server (mock-detector mode) with `tools/ws_contract_check.py`, and the icon font ligatures. Everything else is reviewed by reading only. Expect to fix a few small compile errors on first build (icon/API typos are all isolated and easy). See [Verification](#verification).

## Open and run

1. Install Android Studio (Ladybug or newer, JDK 17 bundled). Open the `android/` folder.
   `gradle/wrapper/gradle-wrapper.properties` pins Gradle 8.9; the wrapper *jar* is not in the repo (Android Studio downloads Gradle itself, or run `gradle wrapper` once).
2. Start the backend on the PC: `cd backend && python -m server` (see `../../backend/README.md`).
3. Server URL (one place, `gradle.properties` + Settings → Connection):
   - emulator: `raahi.debug.serverUrl=http://10.0.2.2:8000` (default)
   - real phone on the same Wi-Fi: change it in the app (Settings → Connection) to `http://<pc-lan-ip>:8000`, then *Test connection*
   - release: `raahi.release.serverUrl` is **empty on purpose** (no production host exists in the repo). Release builds block cleartext, so the URL must be `https://` / `wss://`.
4. Debug builds allow cleartext via `src/debug/res/xml/network_security_config.xml` only. TLS verification is never disabled.

Needs on the phone: camera, microphone. The ElevenLabs key stays on the server; the app only calls `GET /scribe-token`.

## Screen checklist (design file → Compose)

All 38 designed screens/states of `Raahi Screens.html` (sections 00–07) are implemented. Package `com.raahi.app`.

| # | Design | File |
|---|---|---|
| 00 | Design system (colors, type, logo, buttons) | `core/designsystem/*` |
| 01 | Splash | `feature/onboarding/OnboardingScreens.kt` `SplashScreen` |
| 02–05 | Onboarding: Welcome / Voice guidance / Surroundings / Gestures | same file, `OnboardingScreen(page)` |
| 06 | Get started | same file, `GetStartedScreen` |
| 07 | Permissions (+ denied/permanently denied recovery) | same file, `PermissionsScreen` |
| 08 | Home | `feature/home/HomeScreen.kt` |
| 09 / 09d | Active guidance (light + dark theme) | `feature/guidance/GuidanceScreen.kt` |
| 10 | Expanded camera | `GuidanceScreen.kt` (`expanded` state, same screen so the camera stays bound) |
| 11 | Voice command | `feature/voice/VoiceScreen.kt` |
| 12 | Find something | `feature/findobject/FindScreens.kt` |
| 13 | What's ahead? | `FindScreens.kt` |
| 14 | Guidance paused | `feature/guidance/PausedAndDangerScreens.kt` |
| 15 | Danger alert | `PausedAndDangerScreens.kt` (drawn over the live camera screen) |
| 16 | Connection states (+ banner variant) | `feature/errors/ErrorScreens.kt` `ConnectionStatusScreen`, `core/designsystem/ConnectionUi.kt` |
| 17 | Session summary | `feature/history/HistoryScreens.kt` |
| 18 / 18b | History / History empty | same file |
| 19 | Session details | same file |
| 20 | Quick commands | `FindScreens.kt` |
| 21–26 | Settings overview, Accessibility, Voice & audio, Guidance, Connection, Appearance | `feature/settings/SettingsScreens.kt` |
| 27 | Diagnostics | `feature/diagnostics/DiagnosticsScreen.kt` |
| 28–30 | Help, Privacy (+ clear-history dialog), About | `feature/help/InfoScreens.kt` |
| 31a–h | Camera / Mic permission, Server unavailable, Connection error, Camera not working, No sound, Not found, Something went wrong | `feature/errors/ErrorScreens.kt` (`ErrorKind`) |

## Architecture

```
core/designsystem  colors (light/dark/high-contrast), type (DM Sans, Noto Nastaliq Urdu, JetBrains Mono,
                   Material Symbols Rounded ligature icons), components
core/model         ServerResult/OverlayBox/ClipManifest/ConnectionState/FindTarget + Fmt (pure helpers)
core/network       ServerConfig (URL -> http/ws), Protocol (outgoing JSON), GuidanceSocket (OkHttp WS, reconnect,
                   one-frame-in-flight), RaahiApi (/health /scribe-token /voice/scribe-config /clips/*)
core/audio         ClipRepository (manifest + mp3 cache in filesDir), ClipPlayer (queue, interrupt, de-dup, stale drop, audio focus)
core/camera        CameraController (CameraX preview+analysis), FrameEncoder (upright 416 px JPEG q60), UprightMonitor
core/haptics       Haptics (short / long / arrived patterns, de-bounce)
core/voice         ScribeClient (ElevenLabs Scribe v2 Realtime WS), MicStreamer, VoiceController (push-to-talk)
core/permissions   permission state + settings deep link + network check
data               SettingsRepository / HistoryRepository (DataStore; history = JSON, on-device only)
domain             InstructionParser (clip ids -> instruction), SessionRecorder (summary/history), GuidanceSession (orchestrator)
feature/*          screens + view-models (MVVM, StateFlow)
core/navigation    Routes + NavHost
```

## Backend contract as implemented (verified in `server/main.py`, `server/voice/token.py`, `server/engine/*`)

| Client → server | Where |
|---|---|
| binary JPEG per frame, upright, 416 px wide, quality 60; one in flight, 2.5 s timeout | `FrameEncoder`, `GuidanceSocket.sendFrame` |
| `{"type":"hello","client":"android","version":"0.1"}` | `Protocol.hello` |
| `{"type":"command","intent":"find"\|"describe"\|"stop"\|"pause"\|"resume","target":…}` | `Protocol.command` |
| `{"type":"command_text","text","lang":"ur","wake_required":false}` (push-to-talk) | `Protocol.commandText` |

Server → client: one `result` JSON per frame/command (`clips, urgent, interrupt, haptic, mode, overlay, english, latency_ms`), parsed into `ServerResult` (unknown keys such as `overlay[].conf/zone` are ignored). Clips are played from `/clips/<file>` (cached); `live_*` ids come from `/clips/live/<id>.mp3`. Haptics: `short|long|arrived`. Scribe: `GET /scribe-token` (single-use) + `GET /voice/scribe-config` → `wss://api.elevenlabs.io/v1/speech-to-text/realtime` per the ElevenLabs docs.

The server engine is shared and keeps its mode between sessions, so the app sends `command stop` when a session starts/ends.

## Decisions and deviations from the mock-ups (all deliberate, each the smallest change)

1. **Find: "Bag" → "Trash bin".** The server only supports `door, chair, table, person, sofa, trash_bin` (`FIND_TARGETS`).
2. **Help voice phrases** reflect what the server really does: *Ruk jao* = stop searching (not pause); *Roko* = pause; *Shuru karo* = resume. *Dobara bolo* ("repeat") is parsed by the server but never answered, so it is not listed; **Repeat** (button / double-tap) replays the last clips locally.
3. **`sys_welcome` is not played.** That clip says double-tap = talk, long-press = pause, which contradicts the approved gestures (double-tap = repeat, long-press = talk, swipe-left = pause).
4. **Voice control** (updated): push-to-talk (long-press) needs no wake word; **hands-free** "Raahi, <command>" listens while the app is open (Settings › Voice & audio, on by default; Privacy text updated). Sentences are matched ON THE PHONE (`core/voice/VoiceCommands` + `assets/voice_commands.json`) and executed by `core/voice/CommandDispatcher` (navigation via `UiRequest` collected in `RaahiNavHost`, settings via `SettingsRepository`, guidance via `GuidanceSession`). Voice no longer sends "find" sentences to the server (Find stays available as buttons). Original note: The Privacy screen promises the mic listens only when held; Scribe is cloud, so an always-on wake word would stream continuous audio. Sentences go as `command_text` with `wake_required:false`. In guidance the sentence is sent hands-free; the standalone Voice screen keeps the design's Confirm/Cancel and, on Confirm, starts a guidance session and then sends the sentence (the server only acts on find/describe while frames arrive).
5. **Orientation gate is on the phone** (the server has none): frames are withheld and `phone_upright` is spoken when the phone is >~35° from upright (hysteresis 48°, 500 ms debounce).
6. **English language** = English voice: the server serves every clip in Urdu (`file`) and English (`en_file`, `/clips/en/<id>.mp3`); `ClipRepository.language` picks one (Urdu fallback per clip). Both voices are prefetched.
7. **Vibration card:** Android needs no runtime permission, so it shows *Allowed* if the phone has a vibrator and the "audio only" caution if not.
8. **Display setting:** Instruction = approved 09 layout; Camera = starts expanded (10); Split = 50/50.
9. Diagnostics "Inference" shows the server's `latency_ms.total` as **Server**; "Example data"/"placeholder" captions were removed.
10. Not-found screen (31g) appears when the server sends `find_lost` twice in a row (once is normal while scanning).
11. Summary/History counters follow `domain/SessionRecorder` rules (documented in the class) – derived only from real results.
12. Fonts and the Material Symbols icon font were extracted from the design file (woff2 → ttf). They are OFL/Apache licensed.
13. Backgrounding the app **pauses** the session and releases the camera; there is no foreground service (not required).

## Known gaps / not verified

- Not compiled, not run (no toolchain). Not tested on a device: CameraX frames, MediaPlayer playback and speed, audio focus, vibration, Scribe streaming, TalkBack, layout on other screen sizes.
- Release server URL is unset (see above).
- `GET /scribe-token` was reached on the local server (HTTP 200) but a full Scribe round trip was not exercised.
- Server `repeat` intent has no handler; "Dobara bolo" cannot work by voice without a backend change (smallest fix: handle `repeat` in `GuidanceEngine.handle_intent` by replaying `announcer.last_*`).
- No instrumented/UI tests.

## Verification

JVM unit tests (`./gradlew testDebugUnitTest`): `ProtocolAndModelTest` (contract JSON, URL parsing, Scribe URL, manifest), `DomainTest` (InstructionParser, SessionRecorder, formatting, orientation, frame size, haptic patterns), `GuidanceSocketTest` (real `GuidanceSocket` against a local WebSocket server: hello, result parsing, malformed message, one-frame-in-flight, commands, server-unavailable). **These tests were written but not run here.**

Against the real backend: `python android/tools/ws_contract_check.py ws://<host>:8000/ws`.


## Voice commands (added)

| File | Role |
|---|---|
| `assets/voice_commands.json` | the vocabulary (Urdu script, Roman Urdu, English); edit to add phrases |
| `core/voice/VoiceCommands.kt` | normalise → strip wake word → longest phrase wins (typo-tolerant); pure Kotlin, ~1 ms |
| `core/voice/CommandDispatcher.kt` | executes a command id; spoken feedback reuses `sys_ok` / `sys_not_understood` clips |
| `core/voice/HandsFreeListener.kt` | always-on Scribe stream while visible; acts on a stable partial transcript (~350 ms) for low latency; "Raahi" alone arms the next sentence; echo guard |
| `test/.../VoiceCommandsTest.kt` | 75 sentences, same list as `backend/tests/test_app_commands.py` (reference implementation `backend/server/voice/app_commands.py`) |

**Not compiled on the machine where this was written** - run `./gradlew testDebugUnitTest assembleDebug` first.
