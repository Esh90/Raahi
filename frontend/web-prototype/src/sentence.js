// Sentence composer - JS mirror of voice_assets/sentence.py (keep them identical).
// Turns a decision into an ordered list of clip ids for audio.js to play back to back.
//
//   import { createComposer } from "./sentence.js";
//   const manifest = await (await fetch("clips/manifest.json")).json();
//   const S = createComposer(manifest);
//   audio.play(S.obstacle("chair", "ahead", 2, "left", 2));
//   // -> ["obj_chair","pos_ahead","steps_2","move_left_2"]  "کرسی سامنے ہے، دو قدم پر۔ دو قدم بائیں ہٹیں۔"

const MAX_STEPS = 8;
const MAX_MOVE_STEPS = 4;
const SIDES = ["left", "ahead", "right"];
export const FIND_TARGETS = ["door", "chair", "table", "person", "sofa", "trash_bin"];

export function createComposer(manifest) {
  const classes = manifest.classes || {};
  const clipIds = new Set((manifest.clips || []).map((c) => c.id));
  const byId = Object.fromEntries((manifest.clips || []).map((c) => [c.id, c]));

  const liveId = (cls) => "live_" + String(cls).toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "");

  function objClip(cls) {
    if (!cls) return "obj_unknown";
    const info = classes[cls];
    if (info && clipIds.has(info.clip)) return info.clip;
    const live = liveId(cls);
    if (clipIds.has(live)) return live;
    return "obj_unknown";
  }
  const isPlural = (cls) => !!(cls && classes[cls] && classes[cls].plural);
  const posClip = (side, plural = false) => `pos_${SIDES.includes(side) ? side : "ahead"}${plural ? "_pl" : ""}`;
  const stepsClip = (steps) =>
    steps == null || steps > MAX_STEPS ? "steps_far" : `steps_${Math.max(1, Math.floor(steps))}`;
  const moveClip = (dir, steps) => `move_${dir}_${Math.min(Math.max(1, Math.floor(steps)), MAX_MOVE_STEPS)}`;

  function obstacle(cls, side, steps, moveDir = null, moveSteps = 1) {
    const ids = [objClip(cls), posClip(side, isPlural(cls)), stepsClip(steps)];
    if (moveDir === "left" || moveDir === "right") ids.push(moveClip(moveDir, moveSteps));
    else if (moveDir === "straight") ids.push("go_straight");
    return ids;
  }

  function urgentStop(cls = null) {
    if (cls === "person" || cls === "child") return ["urg_stop_person"];
    if (cls == null) return ["urg_stop"];
    return ["urg_stop_obstacle"];
  }

  function describe(items, limit = 3) {
    items = items.slice(0, limit);
    if (!items.length) return ["sys_nothing_ahead"];
    const ids = ["desc_intro"];
    items.forEach(([cls, side, steps], i) => {
      if (i > 0) ids.push("conj_and");
      ids.push(objClip(cls), posClip(side, isPlural(cls)), stepsClip(steps));
    });
    return ids;
  }

  const findConfirm = (t) => (FIND_TARGETS.includes(t) ? [`cmd_finding_${t}`] : ["find_not_supported"]);
  const findArrived = (t) => (FIND_TARGETS.includes(t) ? [`find_arrived_${t}`] : ["sys_ok"]);

  function findGuidance(target, angleDeg, steps, slightDeg = 10, turnDeg = 35) {
    if (angleDeg <= -turnDeg) return ["turn_left"];
    if (angleDeg >= turnDeg) return ["turn_right"];
    const pl = isPlural(target);
    if (angleDeg < -slightDeg) return [objClip(target), posClip("left", pl), stepsClip(steps), "turn_left_slight"];
    if (angleDeg > slightDeg) return [objClip(target), posClip("right", pl), stepsClip(steps), "turn_right_slight"];
    return [objClip(target), posClip("ahead", pl), stepsClip(steps), "go_straight"];
  }

  const toText = (ids, lang = "urdu_text") => ids.map((i) => (byId[i] ? byId[i][lang] : `<${i}>`)).filter(Boolean).join(" ");

  return { objClip, posClip, stepsClip, moveClip, obstacle, urgentStop, describe,
           findConfirm, findArrived, findGuidance, toText, liveId };
}
