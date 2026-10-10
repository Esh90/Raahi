// Voice commands: ElevenLabs Scribe v2 Realtime (Urdu) -> command_text over our WebSocket.
// Esha owns the logic; Member C wires it into main.js / UI.
//
//   import { createVoice } from "./voice.js";
//   const voice = createVoice({
//     serverHttp: "http://192.168.43.10:8000",          // Mac (token comes from /scribe-token)
//     send: (msg) => socket.send(JSON.stringify(msg)),  // existing /ws socket
//     isPlaying: () => audio.isPlaying(),               // echo guard
//     onState: (s) => ui.setListening(s),               // "off"|"connecting"|"listening"|"armed"|"offline"|"error"
//     onPartial: (t) => ui.showSubtitle(t),             // optional live text
//   });
//   await voice.start();      // always-on wake word mode ("راہی، دروازہ ڈھونڈو")
//   voice.listenOnce();       // double-tap: next sentence is a command, no wake word needed
//   voice.stop();
//
// Sends: {"type":"command_text","text":"...","lang":"ur","wake_required":true|false}
// (server: intents.parse(text, require_wake=wake_required))
//
// npm i @elevenlabs/client

import { Scribe, RealtimeEvents, CommitStrategy } from "@elevenlabs/client";

const ECHO_TAIL_MS = 600;      // ignore speech that ends this soon after our own audio
const ARMED_WINDOW_MS = 7000;  // double-tap: how long we wait for the command
const RECONNECT_MS = [1000, 2000, 4000, 8000];

export function createVoice({ serverHttp, send, isPlaying = () => false, onState = () => {}, onPartial = () => {} }) {
  let conn = null;
  let running = false;
  let armedUntil = 0;
  let lastPlayingAt = 0;
  let attempt = 0;
  let config = { language_code: "ur", keyterms: [] };
  let echoTimer = null;

  const setState = (s) => { try { onState(s); } catch (_) {} };
  const armed = () => Date.now() < armedUntil;

  // Track when our own audio was last playing (for the echo tail).
  function startEchoWatch() {
    stopEchoWatch();
    echoTimer = setInterval(() => { if (isPlaying()) lastPlayingAt = Date.now(); }, 100);
  }
  function stopEchoWatch() { if (echoTimer) clearInterval(echoTimer); echoTimer = null; }
  const echoBlocked = () => isPlaying() || Date.now() - lastPlayingAt < ECHO_TAIL_MS;

  async function fetchJson(path) {
    const r = await fetch(`${serverHttp}${path}`, { cache: "no-store" });
    if (!r.ok) throw new Error(`${path} ${r.status}`);
    return r.json();
  }

  async function connect() {
    setState("connecting");
    try { config = { ...config, ...(await fetchJson("/voice/scribe-config")) }; } catch (_) {}
    let token;
    try {
      token = (await fetchJson("/scribe-token")).token;
    } catch (e) {
      setState("offline");          // no internet / no key -> gestures still work
      scheduleReconnect();
      return;
    }

    conn = Scribe.connect({
      token,
      modelId: "scribe_v2_realtime",
      languageCode: config.language_code || "ur",
      keyterms: config.keyterms,
      commitStrategy: CommitStrategy.VAD,
      vadSilenceThresholdSecs: 0.6,
      microphone: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });

    conn.on(RealtimeEvents.OPEN, () => { attempt = 0; setState(armed() ? "armed" : "listening"); });
    conn.on(RealtimeEvents.PARTIAL_TRANSCRIPT, (data) => {
      if (!echoBlocked() && data && data.text) onPartial(data.text);
    });
    conn.on(RealtimeEvents.COMMITTED_TRANSCRIPT, (data) => {
      const text = ((data && data.text) || "").trim();
      if (!text) return;
      if (echoBlocked()) return;                 // the app heard itself
      const wakeRequired = !armed();
      armedUntil = 0;
      send({ type: "command_text", text, lang: "ur", wake_required: wakeRequired });
      setState("listening");
    });
    conn.on(RealtimeEvents.ERROR, (err) => { console.warn("scribe error", err); setState("error"); });
    conn.on(RealtimeEvents.CLOSE, () => { conn = null; if (running) scheduleReconnect(); });
  }

  function scheduleReconnect() {
    if (!running) return;
    const delay = RECONNECT_MS[Math.min(attempt++, RECONNECT_MS.length - 1)];
    setTimeout(() => { if (running && !conn) connect(); }, delay);
  }

  return {
    async start() {
      if (running) return;
      running = true;
      startEchoWatch();
      await connect();
    },
    // Double-tap: treat the next sentence as a command without the wake word.
    listenOnce() {
      armedUntil = Date.now() + ARMED_WINDOW_MS;
      setState("armed");
      if (!running) this.start();
      setTimeout(() => { if (!armed() && running) setState(conn ? "listening" : "offline"); }, ARMED_WINDOW_MS + 50);
    },
    stop() {
      running = false;
      armedUntil = 0;
      stopEchoWatch();
      if (conn) { try { conn.close(); } catch (_) {} conn = null; }
      setState("off");
    },
    get connected() { return !!conn; },
  };
}
