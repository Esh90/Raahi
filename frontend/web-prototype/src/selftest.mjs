// node mobile/src/selftest.mjs   - checks sentence.js matches voice_assets/sentence.py
// behaviour, and that localGuide follows the speaking rules. Run after process_clips.
import { readFileSync } from "node:fs";
import assert from "node:assert/strict";
import { createComposer } from "./sentence.js";
import { createLocalGuide } from "./localGuide.js";

const manifest = JSON.parse(readFileSync(new URL("../../../backend/voice_assets/clips/manifest.json", import.meta.url), "utf8"));
const S = createComposer(manifest);
const ids = new Set(manifest.clips.map((c) => c.id));

assert.deepEqual(S.obstacle("chair", "ahead", 2, "left", 2), ["obj_chair", "pos_ahead", "steps_2", "move_left_2"]);
assert.deepEqual(S.obstacle("stairs", "left", 3).slice(0, 2), ["obj_stairs", "pos_left_pl"]);
assert.equal(S.obstacle("spaceship", "ahead", 1)[0], "obj_unknown");
assert.equal(S.stepsClip(20), "steps_far");
assert.equal(S.moveClip("right", 9), "move_right_4");
assert.deepEqual(S.describe([]), ["sys_nothing_ahead"]);
assert.deepEqual(S.findGuidance("door", 50, 3), ["turn_right"]);
assert.deepEqual(S.urgentStop("person"), ["urg_stop_person"]);

const run = (guide, dets, t0, n, dt = 100) => {
  const out = [];
  for (let i = 0; i < n; i++) { const r = guide.update(dets, t0 + i * dt); if (r) out.push(r); }
  return out;
};

// obstacle ahead at ~1.7 m with free space on the left -> named + direction
let guide = createLocalGuide(S, { classes: manifest.classes });
const chair = [{ cls: "chair", conf: 0.8, box: [0.45, 0.4, 0.62, 0.95] }];
let said = run(guide, chair, 1000, 3);
assert.equal(said.length, 1);
assert.equal(said[0].clips[0], "obj_chair");
assert.ok(said[0].clips.at(-1).startsWith("move_left_"), JSON.stringify(said[0]));
for (const c of said[0].clips) assert.ok(ids.has(c), c);
// stays silent for flicker, then repeats guidance after 4 s
assert.equal(run(guide, chair, 1300, 30).length, 0);
assert.equal(run(guide, chair, 5300, 1).length, 1);

// person very close (cut off at the bottom): stop ONCE + direction, then only direction
guide = createLocalGuide(S, { classes: manifest.classes });
const person = [{ cls: "person", conf: 0.9, box: [0.2, 0.05, 0.75, 1.0] }];  // width 0.55 -> ~0.65 m
said = run(guide, person, 0, 70);                                           // 7 s
assert.ok(said.length >= 2 && said.length <= 3, JSON.stringify(said));
assert.equal(said[0].clips[0], "urg_stop_person");
assert.ok(said[0].clips[1].startsWith("move_") , JSON.stringify(said[0]));
for (const r of said.slice(1)) assert.ok(!r.clips.some((c) => c.startsWith("urg_")), JSON.stringify(r));

// blocked everywhere -> step back
guide = createLocalGuide(S, { classes: manifest.classes });
const wall = [{ cls: "cupboard", conf: 0.9, box: [0.0, 0.0, 1.0, 1.0] }];
said = run(guide, wall, 0, 3);
assert.deepEqual(said[0].clips, ["urg_stop", "move_back"]);

// clear after the obstacle goes
said = run(guide, [], 10000, 40);
assert.deepEqual(said.at(-1).clips, ["sys_path_clear"]);
console.log("sentence.js + localGuide.js OK:", S.toText(S.obstacle("chair", "ahead", 2, "left", 2)));
