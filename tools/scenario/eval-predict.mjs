// Replays a scenario offline through live Jev and compares the push prediction with ground truth.
// Inputs hold only what the app would see: past signals plus navigation distances.
// Usage: AI_GATEWAY_API_KEY=... node tools/scenario/eval-predict.mjs [--scenario gangnam-pangyo|file.json]
//   [--window] [--ranges 16-28,78-88] [--stride 1] [--offsets 0] [--runs 1] [--parallel 4] [--front 300] [--save file.json]
// Re-score saved calls without spending: node tools/scenario/eval-predict.mjs --replay file.json [--scenario ...] [--on 0.55] [--off 0.45] [--hold 1]
// Timing follows the app: a call at t answers for t + 2 s, is applied at t + 1 s, and is dropped
// when its round trip passed 1 s.
import { readFileSync, writeFileSync } from 'node:fs';
import { DIRECTIONS, SAMPLE_COUNT } from '../../server/lib/describe.js';
import { buildJevRequest, callJev, mapJevResponse, validateInput } from '../../server/lib/jev.js';
import { STEP_S } from './common.mjs';
import { pushLevels, pushSpans } from './labels.mjs';

const text = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i < 0 ? fallback : process.argv[i + 1];
};
const num = (name, fallback) => Number(text(name, fallback));
const list = (name, fallback) => text(name, fallback).split(',').map(Number);

const RUNS = num('runs', 1);
const OFFSETS = list('offsets', '0');
const RANGES = text('ranges', '0-999999').split(',').map((r) => r.split('-').map(Number));
const FRONT_RANGE_M = num('front', 300);
const PARALLEL = num('parallel', 4);
const STRIDE_S = num('stride', 1);
const WINDOW_ONLY = process.argv.includes('--window');
const HINT = { onP: num('on', 0.55), offP: num('off', 0.45), minHoldS: num('hold', 1), staleS: 2, applyS: 1, maxRttS: 1 };
// A hint shown from this long before onset up to just after it counts as on time.
const WINDOW = { earliestS: -2.5, latestS: 0.5, tailS: 1 };
const SEARCH_BEFORE_S = 8;
const HORIZON_STEPS = Math.round(2 / STEP_S);
const SIGNAL = { Off: 'off', BlinkLeft: 'left', BlinkRight: 'right' };
const SCENARIOS = new URL('../../app/src/main/assets/scenarios/', import.meta.url);

const named = text('scenario', 'gangnam-pangyo');
const scenario = JSON.parse(readFileSync(/[\\/.]/.test(named) ? named : new URL(`${named}.json`, SCENARIOS), 'utf8'));
const { timeline } = scenario;
if (WINDOW_ONLY && !scenario.demoWindow) throw new Error('scenario has no demoWindow');
const scope = WINDOW_ONLY ? [[scenario.demoWindow.fromSec, scenario.demoWindow.toSec]] : RANGES;
const inScope = (t) => scope.some(([from, to]) => t >= from && t <= to);
const endT = WINDOW_ONLY ? scenario.demoWindow.toSec : timeline.at(-1).t;
const levels = pushLevels(timeline);

const distance = [0];
for (let i = 1; i < timeline.length; i++) distance[i] = distance[i - 1] + (timeline[i - 1].speedKph / 3.6) * STEP_S;

function inputAt(i) {
  const signals = [];
  for (let k = SAMPLE_COUNT - 1; k >= 0; k--) {
    const r = timeline[i - k];
    signals.push({ ageMs: k * 200, speedKph: r.speedKph, steeringDeg: r.steeringDeg, brake: r.brakePct > 0, turnSignal: SIGNAL[r.turnSignal] });
  }
  const at = distance[i];
  const turn = scenario.turns.find((t) => t.atM > at);
  const front = scenario.frontEvents.find((e) => e.atM > at && e.atM - at <= FRONT_RANGE_M);
  return {
    signals,
    nextTurn: turn ? { type: turn.type, distanceM: Math.round(turn.atM - at) } : null,
    frontEvent: front ? { type: front.type, distanceM: Math.round(front.atM - at) } : null,
  };
}

async function callAt(i) {
  const input = inputAt(i);
  const invalid = validateInput(input);
  if (invalid) throw new Error(invalid);
  const t0 = performance.now();
  try {
    const jev = await callJev(buildJevRequest(input), process.env.AI_GATEWAY_API_KEY);
    const ms = performance.now() - t0;
    return { i, t: timeline[i].t, ms, cost: Number(jev.providerMetadata?.gateway?.cost ?? 0), push: mapJevResponse(jev).push };
  } catch (e) {
    return { i, t: timeline[i].t, failed: String(e.message || e) };
  }
}

async function runPass(offsetS) {
  const stride = Math.max(1, Math.round(STRIDE_S / STEP_S));
  const indices = [];
  for (let i = SAMPLE_COUNT - 1 + Math.round(offsetS / STEP_S); i + HORIZON_STEPS < timeline.length; i += stride) {
    if (inScope(timeline[i].t)) indices.push(i);
  }
  const out = [];
  for (let k = 0; k < indices.length; k += PARALLEL) {
    out.push(...(await Promise.all(indices.slice(k, k + PARALLEL).map(callAt))));
  }
  return out;
}

const median = (values) => [...values].sort((a, b) => a - b)[values.length >> 1];
const ratio = (a, b) => `${a}/${b}`;

// Point check: each call against the truth at t + 2 s, with the on threshold alone.
function pointReport(calls) {
  const ok = calls.filter((c) => !c.failed);
  for (const dir of DIRECTIONS) {
    let tp = 0, fp = 0, fn = 0, levelHit = 0, levelErr = 0;
    for (const c of ok) {
      const truth = levels[c.i + HORIZON_STEPS][dir];
      const on = c.push[dir].pOn >= HINT.onP;
      if (on && truth) {
        tp++;
        if (c.push[dir].level === truth) levelHit++;
        levelErr += Math.abs(c.push[dir].level - truth);
      } else if (on) fp++;
      else if (truth) fn++;
    }
    const level = tp ? `, level exact ${ratio(levelHit, tp)} mean error ${(levelErr / tp).toFixed(2)}` : '';
    console.log(`  ${dir.padEnd(8)} recall ${ratio(tp, tp + fn)} precision ${ratio(tp, tp + fp)}${level}`);
  }
}

const AXES = [['forward', 'backward'], ['left', 'right']];

// Same as the app: the higher latest pOn, then the higher level; a full tie shows nothing.
function winner(a, b) {
  if (a.on && b.on) {
    if (a.pOn !== b.pOn) return a.pOn > b.pOn ? a : b;
    if (a.level !== b.level) return a.level > b.level ? a : b;
    return null;
  }
  return a.on ? a : b.on ? b : null;
}

// The app's hint, replayed: each direction turns on and off by itself (applied at t + 1 s,
// dropped past 1 s round trip, all off after 2 s stale or a failure), then each axis shows one side.
function hintSpans(calls) {
  const applied = calls
    .filter((c) => c.failed || c.ms <= HINT.maxRttS * 1000)
    .map((c) => ({ at: c.failed ? c.t : c.t + HINT.applyS, failed: Boolean(c.failed), push: c.push }))
    .sort((a, b) => a.at - b.at);
  const state = Object.fromEntries(DIRECTIONS.map((d) => [d, { on: false, since: 0, pOn: 0, level: 0 }]));
  const shownSince = Object.fromEntries(DIRECTIONS.map((d) => [d, null]));
  const spans = [];
  const show = (t) => {
    const winners = new Set();
    for (const [a, b] of AXES) {
      const w = winner(state[a], state[b]);
      if (w) winners.add(w === state[a] ? a : b);
    }
    for (const d of DIRECTIONS) {
      if (winners.has(d) && shownSince[d] === null) shownSince[d] = t;
      if (!winners.has(d) && shownSince[d] !== null) {
        spans.push({ dir: d, fromT: shownSince[d], toT: t });
        shownSince[d] = null;
      }
    }
  };
  const allOff = () => DIRECTIONS.forEach((d) => Object.assign(state[d], { on: false, pOn: 0, level: 0 }));
  let lastAt = null;
  for (const r of applied) {
    if (lastAt !== null && r.at - lastAt > HINT.staleS) {
      allOff();
      show(lastAt + HINT.staleS);
    }
    lastAt = r.at;
    if (r.failed) {
      allOff();
      show(r.at);
      continue;
    }
    for (const d of DIRECTIONS) {
      const s = state[d];
      const p = r.push[d];
      s.pOn = p.pOn;
      if (!s.on && p.pOn >= HINT.onP) Object.assign(s, { on: true, since: r.at, level: p.level });
      else if (s.on && p.pOn >= HINT.offP) s.level = p.level;
      else if (!(s.on && r.at - s.since < HINT.minHoldS)) Object.assign(s, { on: false, pOn: 0, level: 0 });
    }
    show(r.at);
  }
  if (lastAt !== null) {
    allOff();
    show(Math.min(endT, lastAt + HINT.staleS));
  }
  return spans;
}

function eventReport(passes) {
  const truth = pushSpans(timeline, levels).filter((s) => inScope(s.fromT) && (s.toT - s.fromT) >= 2 * STEP_S);
  const covered = (s) => passes.every((p) => p.calls.some((c) => c.t >= s.fromT - 4 && c.t <= s.fromT));
  const events = truth.filter(covered);
  let ok = 0, total = 0, okStrong = 0, totalStrong = 0, falseSeconds = 0;
  for (const pass of passes) {
    const shown = hintSpans(pass.calls);
    for (const dir of DIRECTIONS) {
      const spans = shown.filter((s) => s.dir === dir);
      const sameDir = events.filter((e) => e.dir === dir);
      for (const e of sameDir) {
        const span = spans.find((s) => s.toT > e.fromT - SEARCH_BEFORE_S && s.fromT <= e.toT);
        const rel = span ? span.fromT - e.fromT : null;
        const hit = rel !== null && rel >= WINDOW.earliestS && rel <= WINDOW.latestS;
        total++;
        if (hit) ok++;
        if (e.peak >= 2) {
          totalStrong++;
          if (hit) okStrong++;
        }
      }
      const allowed = sameDir.map((e) => [e.fromT + WINDOW.earliestS, e.toT + WINDOW.tailS]);
      for (const s of spans) {
        for (let t = s.fromT; t < s.toT; t += STEP_S) {
          if (!allowed.some(([a, b]) => t >= a && t <= b)) falseSeconds += STEP_S;
        }
      }
    }
  }
  const minutes = passes.reduce((sum, p) => sum + p.calls.length, 0) * STRIDE_S / 60;
  console.log(`events on time (${WINDOW.earliestS} to +${WINDOW.latestS} s of onset): moderate or stronger ${ratio(okStrong, totalStrong)}, all ${ratio(ok, total)}`);
  console.log(`false hint: ${(falseSeconds / passes.length).toFixed(1)} s per pass, ${(falseSeconds / minutes).toFixed(1)} s per minute`);
}

let passes;
if (text('replay')) {
  passes = JSON.parse(readFileSync(text('replay'), 'utf8'));
} else {
  passes = [];
  for (let run = 0; run < RUNS; run++) {
    for (const offset of OFFSETS) {
      const calls = await runPass(offset);
      passes.push({ label: `run${run}+${offset}`, offset, calls });
      const ok = calls.filter((c) => !c.failed);
      const ms = ok.map((c) => c.ms);
      const late = ok.filter((c) => c.ms > HINT.maxRttS * 1000).length;
      console.log(`${passes.at(-1).label}: calls ${calls.length}, failed ${calls.length - ok.length}, over 1 s ${late}, ms median ${Math.round(median(ms))} max ${Math.round(Math.max(...ms))}, cost $${ok.reduce((s, c) => s + c.cost, 0).toFixed(5)}`);
      for (const f of calls.filter((c) => c.failed).slice(0, 3)) console.log('  failure', f.t, f.failed);
    }
  }
  if (text('save')) writeFileSync(text('save'), JSON.stringify(passes));
}

console.log(`\nhint on ${HINT.onP} off ${HINT.offP} hold ${HINT.minHoldS} s`);
for (const pass of passes) {
  console.log(`${pass.label} per call vs truth at t + 2 s:`);
  pointReport(pass.calls);
}
eventReport(passes);
