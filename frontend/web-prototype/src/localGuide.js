// Phone-only guidance from on-device detections (no Mac, no depth).
// The Mac engine (Member B: server/engine) is better: it has metric depth and catches unnamed
// obstacles. This keeps the app useful offline / when the Mac link drops, and follows the SAME
// speaking rules as server/engine/announcer.py:
//   * a stop always comes with a way out (step left/right, or step back)
//   * "stop" is said once per danger episode; repeats give only the direction
//   * step-count / label flicker is not a new message; guidance refreshes every 4 s until clear
//
//   import { createLocalGuide } from "./localGuide.js";
//   const guide = createLocalGuide(composer, { classes: manifest.classes, aspect: 4 / 3 });
//   const reply = guide.update(detections);    // null or {clips, urgent, interrupt, haptic}
//   if (reply) audio.play(reply.clips, { interrupt: reply.interrupt });
//
// Distance: pinhole on box height (Z = H_real * fy / h); if the box is cut off top/bottom, on box
// width with a typical object width; otherwise a coarse size-based guess.
// fxNorm = fx / image_width (~0.78 for a typical phone main camera, ~65° horizontal FOV).

const STEP_M = 0.6;
// background-type boxes (often the whole frame) have no usable size -> ignored without depth
const IGNORE = new Set(["wall", "window", "projector_screen", "whiteboard"]);
const WIDTH_M = { person: 0.45, chair: 0.5, table: 1.2, desk: 1.2, sofa: 1.8, door: 0.9, glass_door: 0.9,
  bed: 1.4, cupboard: 1.0, shelf: 0.9, trash_bin: 0.4, plant_pot: 0.4, laptop: 0.35, monitor: 0.5,
  tv_led: 1.0, whiteboard: 1.5, bag: 0.35, box: 0.4, bottle: 0.08, bench: 1.2, water_cooler: 0.35,
  fan_stand: 0.45 };

export function createLocalGuide(S, {
  fxNorm = 0.78, aspect = 4 / 3,         // aspect = width / height of the frames sent to the detector
  dangerM = 0.8, warnM = 2.0, hysteresisM = 0.25,
  holdFrames = 3, dangerHoldFrames = 2,
  urgentRepeatMs = 3000, urgentEpisodeMs = 6000, obstacleRepeatMs = 4000, minGapMs = 1500,
  clearAfterMs = 2500,
  classes = {},                          // manifest.classes (height_m)
} = {}) {
  const fyNormH = fxNorm * aspect;       // fy / image_height (square pixels)
  let topicHist = [], held = null;
  let inDanger = false, inWarn = false;
  let last = { kind: null, dir: null, topic: null, at: 0 };
  let hadObstacle = false, lastObstacleAt = 0;

  function distance(d) {
    const [x1, y1, x2, y2] = d.box;
    const h = classes[d.cls]?.height_m;
    if (h && y1 > 0.01 && y2 < 0.99) return (h * fyNormH) / Math.max(0.02, y2 - y1);
    const wm = WIDTH_M[d.cls];
    if (wm && x1 > 0.01 && x2 < 0.99) return (wm * fxNorm) / Math.max(0.02, x2 - x1);
    return (x2 - x1) * (y2 - y1) > 0.45 ? 0.7 : 1.6;
  }
  const side = (cx) => (cx < 0.33 ? "left" : cx > 0.67 ? "right" : "ahead");

  // nearest distance per zone (left / centre / right) from the boxes
  function zones(dets) {
    const z = [Infinity, Infinity, Infinity];
    for (const d of dets) {
      const [x1, y1, x2, y2] = d.box;
      if (y2 < 0.3) continue;                     // above head height
      const dist = distance(d);
      [[0, 0.33], [0.33, 0.67], [0.67, 1]].forEach(([a, b], i) => {
        if (x2 > a && x1 < b) z[i] = Math.min(z[i], dist);
      });
    }
    return z;
  }

  function decide(dets) {
    const [L, C, R] = zones(dets);
    inDanger = C < (inDanger ? dangerM + hysteresisM : dangerM);
    inWarn = inDanger || C < (inWarn ? warnM + 0.3 : warnM);
    if (!inWarn) return { kind: "clear", topic: "clear" };
    const centre = dets.filter((d) => d.box[2] > 0.33 && d.box[0] < 0.67)
      .map((d) => ({ d, z: distance(d) })).sort((a, b) => a.z - b.z)[0];
    const z = Math.min(C, centre ? centre.z : C);
    const roomL = Number.isFinite(L) ? L : 9, roomR = Number.isFinite(R) ? R : 9;
    const dir = Math.max(roomL, roomR) < dangerM ? "back" : roomL >= roomR ? "left" : "right";
    let moveSteps = 1;
    if (centre && dir !== "back") {
      const [x1, , x2] = centre.d.box;
      const edge = dir === "left" ? x1 : x2;
      moveSteps = Math.min(4, Math.max(1, Math.ceil((Math.abs(edge - 0.5) * z / fxNorm + 0.35) / STEP_M)));
    }
    const way = dir === "back" ? ["move_back"] : [S.moveClip(dir, moveSteps)];
    const cls = centre ? centre.d.cls : null;
    if (inDanger) {
      const stop = cls === "person" ? "urg_stop_person" : "urg_stop";
      return { kind: "urgent", dir, topic: `urgent:${dir}`, clips: [stop, ...way] };
    }
    const steps = Math.max(1, Math.ceil(z / STEP_M));
    const clips = [...S.obstacle(cls, "ahead", steps).slice(0, 3), ...way];
    return { kind: "obstacle", dir, topic: `obstacle:${cls}:${dir}`, clips };
  }

  function shouldSpeak(dec, now) {
    const dt = now - last.at;
    if (dec.kind === "urgent") {
      if (last.kind !== "urgent" || dt >= urgentEpisodeMs) return "full";
      if (dec.dir !== last.dir && dt >= minGapMs) return "way";
      if (dt >= urgentRepeatMs) return "way";
      return null;
    }
    if (dec.kind === "obstacle") {
      if (last.kind === "urgent" && dt < minGapMs) return null;
      const sameWay = (last.kind === "obstacle" || last.kind === "urgent") && dec.dir === last.dir;
      if (sameWay && dt < obstacleRepeatMs) return null;
      if (last.kind !== "obstacle" && last.kind !== "urgent") return "full";
      if (dec.topic !== last.topic && dt >= minGapMs) return "full";
      if (dt >= obstacleRepeatMs) return "full";
      return null;
    }
    return null;
  }

  return {
    distance,
    update(detections, now = Date.now()) {
      const dec = decide(detections.filter((d) => !IGNORE.has(d.cls)));
      // smoother: same topic for N frames (2 for danger)
      topicHist.push(dec.topic);
      const need = dec.kind === "urgent" ? dangerHoldFrames : holdFrames;
      topicHist = topicHist.slice(-need);
      const stable = topicHist.length >= need && topicHist.every((t) => t === dec.topic);
      if (stable) held = dec;
      const cur = stable ? dec : held;
      if (!cur) return null;

      if (cur.kind === "clear") {
        if (hadObstacle && now - lastObstacleAt > clearAfterMs) {
          hadObstacle = false;
          last = { kind: "clear", dir: null, topic: "clear", at: now };
          return { clips: ["sys_path_clear"], urgent: false, interrupt: false, haptic: "none" };
        }
        return null;
      }
      hadObstacle = true; lastObstacleAt = now;
      const how = shouldSpeak(cur, now);
      if (!how) return null;
      const repeatStop = cur.kind === "urgent" && how === "way";
      const clips = repeatStop ? cur.clips.filter((c) => !c.startsWith("urg_")) : cur.clips;
      const interrupt = cur.kind === "urgent" && (how === "full" || cur.dir !== last.dir);
      last = { kind: cur.kind, dir: cur.dir, topic: cur.topic, at: now };
      return { clips, urgent: cur.kind === "urgent", interrupt,
               haptic: cur.kind === "urgent" && !repeatStop ? "long" : "short" };
    },
    describe(detections) {
      const items = detections.map((d) => ({ d, z: distance(d) })).sort((a, b) => a.z - b.z)
        .map(({ d, z }) => [d.cls, side((d.box[0] + d.box[2]) / 2), Math.ceil(z / STEP_M)]);
      return S.describe(items);
    },
    reset() { topicHist = []; held = null; inDanger = inWarn = false; last = { kind: null, dir: null, topic: null, at: 0 }; },
  };
}
