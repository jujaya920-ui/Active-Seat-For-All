// Replays a scenario offline through live Jev and compares predictions with ground truth labels.
// Inputs hold only what the app would see: past signals plus navigation distances.
// Usage: AI_GATEWAY_API_KEY=... node tools/scenario/eval-predict.mjs [--runs 1] [--offsets 0,0.6]
//   [--ranges 16-28,78-88] [--horizons 2,2.4] [--front 300] [--parallel 4] [--save file.json]
//   [--scenario file.json] [--rtt 0.5]
// Re-score saved calls without spending: node tools/scenario/eval-predict.mjs --replay file.json [--on 0.6] [--off 0.45] [--hold 1]
import { readFileSync, writeFileSync } from 'node:fs';
import { buildJevRequest, callJev, mapJevResponse, validateInput } from '../../server/lib/jev.js';
import { HINT, WINDOW, falseSpans, hintSpans, listManeuvers, nearestPrediction, scoreManeuver } from './events.mjs';
import { deriveLabels } from './labels.mjs';
import { STEP_S } from './common.mjs';

const text = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i < 0 ? fallback : process.argv[i + 1];
};
const num = (name, fallback) => Number(text(name, fallback));
const list = (name, fallback) => text(name, fallback).split(',').map(Number);

const RUNS = num('runs', 1);
const OFFSETS = list('offsets', '0,0.6');
const RANGES = text('ranges', '0-999').split(',').map((r) => r.split('-').map(Number));
const FRONT_RANGE_M = num('front', 300);
const PARALLEL = num('parallel', 4);
const SIGNAL = { Off: 'off', BlinkLeft: 'left', BlinkRight: 'right' };
const FIRST_I = 4;
const hint = { ...HINT, onP: num('on', HINT.onP), offP: num('off', HINT.offP), minHoldS: num('hold', HINT.minHoldS), rttS: num('rtt', HINT.rttS) };
const DEFAULT_SCENARIO = new URL('../../app/src/main/assets/scenarios/pangyo-gangnam.json', import.meta.url);

const scenario = JSON.parse(readFileSync(text('scenario') ?? DEFAULT_SCENARIO, 'utf8'));
const { timeline } = scenario;
const labels = deriveLabels(scenario, { horizonS: 2 });
const maneuvers = listManeuvers(scenario);
const endT = timeline.at(-1).t;

const distance = [0];
for (let i = 1; i < timeline.length; i++) distance[i] = distance[i - 1] + (timeline[i - 1].speedKph / 3.6) * STEP_S;

function inputAt(i, horizonsSec) {
  const signals = [];
  for (let k = 4; k >= 0; k--) {
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
    horizonsSec,
  };
}

async function callAt(i, horizonsSec) {
  const input = inputAt(i, horizonsSec);
  const invalid = validateInput(input);
  if (invalid) throw new Error(invalid);
  const t0 = performance.now();
  try {
    const jev = await callJev(buildJevRequest(input), process.env.AI_GATEWAY_API_KEY);
    return {
      i,
      t: timeline[i].t,
      ms: performance.now() - t0,
      cost: Number(jev.providerMetadata?.gateway?.cost ?? 0),
      predictions: mapJevResponse(jev, horizonsSec),
    };
  } catch (e) {
    return { i, t: timeline[i].t, failed: String(e.message || e) };
  }
}

async function runPass(offsetS, horizonsSec) {
  const stride = Math.round(1 / STEP_S);
  const indices = [];
  for (let i = FIRST_I + Math.round(offsetS / STEP_S); i < labels.length; i += stride) {
    if (RANGES.some(([from, to]) => timeline[i].t >= from && timeline[i].t <= to)) indices.push(i);
  }
  const out = [];
  for (let k = 0; k < indices.length; k += PARALLEL) {
    out.push(...(await Promise.all(indices.slice(k, k + PARALLEL).map((i) => callAt(i, horizonsSec)))));
  }
  return out;
}

const fmt = (x) => (x === null ? '-' : `${x >= 0 ? '+' : ''}${x.toFixed(1)}`);
const median = (values) => {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[sorted.length >> 1];
};

function accuracy(calls) {
  const ok = calls
    .filter((c) => !c.failed)
    .map((c) => {
      const p = nearestPrediction(c.predictions, 2);
      return { lat: p.lateral.value, lon: p.longitudinal.value, trueLat: labels[c.i].lateral, trueLon: labels[c.i].longitudinal };
    });
  const report = (key, truth) => {
    const classes = [...new Set(ok.map((r) => r[truth]).concat(ok.map((r) => r[key])))];
    const parts = classes.map((c) => {
      const tp = ok.filter((r) => r[key] === c && r[truth] === c).length;
      const pred = ok.filter((r) => r[key] === c).length;
      const real = ok.filter((r) => r[truth] === c).length;
      return `${c} recall ${tp}/${real} precision ${tp}/${pred}`;
    });
    return `${key} accuracy ${(ok.filter((r) => r[key] === r[truth]).length / ok.length).toFixed(3)} (${parts.join(', ')})`;
  };
  return [report('lat', 'trueLat'), report('lon', 'trueLon')];
}

function evaluate(passes) {
  const covered = (m) => passes.every((pass) => pass.calls.some((c) => c.t >= m.fromT - 4 && c.t <= m.fromT));
  const rows = maneuvers.filter(covered).map((m) => ({ m, scores: [] }));
  const falses = [];
  for (const pass of passes) {
    const spans = { lat: hintSpans(pass.calls, 'lat', { hint, endT }), lon: hintSpans(pass.calls, 'lon', { hint, endT }) };
    for (const row of rows) row.scores.push(scoreManeuver(row.m, spans[row.m.axis]));
    for (const axis of ['lat', 'lon']) {
      const inScope = maneuvers.filter((m) => m.axis === axis);
      falses.push(...falseSpans(inScope, spans[axis]).map((s) => ({ ...s, pass: pass.label })));
    }
  }
  return { rows, falses };
}

function printEvents(passes) {
  const { rows, falses } = evaluate(passes);
  console.log(`\nhint on ${hint.onP} off ${hint.offP} hold ${hint.minHoldS}s, target ${hint.leadS + hint.rttS}s, window ${WINDOW.earliestS} to +${WINDOW.latestS}s around onset`);
  console.log(`maneuver | onset | ok/n | on relative to onset per pass | median off relative to end`);
  for (const { m, scores } of rows) {
    const shown = scores.filter((s) => s.onRel !== null);
    const ok = scores.filter((s) => s.verdict === 'ok').length;
    const offs = shown.length ? fmt(median(shown.map((s) => s.offRel))) : '-';
    console.log(`${m.hard ? 'HARD ' : ''}${m.value} | ${m.fromT.toFixed(1)}-${m.toT.toFixed(1)} | ${ok}/${scores.length} | ${scores.map((s) => (s.onRel === null ? 'miss' : fmt(s.onRel))).join(' ')} | ${offs}`);
  }
  const hard = rows.filter((r) => r.m.hard).flatMap((r) => r.scores);
  const all = rows.flatMap((r) => r.scores);
  const pct = (scores) => `${scores.filter((s) => s.verdict === 'ok').length}/${scores.length}`;
  console.log(`events ok: hard ${pct(hard)}, all ${pct(all)}`);
  const seconds = falses.reduce((sum, s) => sum + s.seconds, 0);
  console.log(`false hint: ${(seconds / passes.length).toFixed(1)} s per pass, ${(falses.length / passes.length).toFixed(1)} spans per pass`);
  for (const s of falses.filter((f) => f.seconds >= 1).slice(0, 24)) {
    console.log(`  ${s.pass} ${s.value} ${s.fromT.toFixed(1)}-${s.toT.toFixed(1)} (${s.seconds.toFixed(1)} s outside)`);
  }
}

let passes;
if (text('replay')) {
  passes = JSON.parse(readFileSync(text('replay'), 'utf8'));
} else {
  const horizons = list('horizons', '1.6,1.8,2,2.2,2.4');
  passes = [];
  for (let run = 0; run < RUNS; run++) {
    for (const offset of OFFSETS) {
      const calls = await runPass(offset, horizons);
      passes.push({ label: `run${run}+${offset}`, offset, calls });
      const ok = calls.filter((c) => !c.failed);
      const ms = ok.map((c) => c.ms).sort((a, b) => a - b);
      console.log(`${passes.at(-1).label}: calls ${calls.length}, failed ${calls.length - ok.length}, ms median ${Math.round(median(ms))} max ${Math.round(ms.at(-1))}, cost $${ok.reduce((s, c) => s + c.cost, 0).toFixed(5)}`);
      for (const f of calls.filter((c) => c.failed).slice(0, 3)) console.log('  failure', f.t, f.failed);
    }
  }
  if (text('save')) writeFileSync(text('save'), JSON.stringify(passes));
}

for (const pass of passes) {
  for (const line of accuracy(pass.calls)) console.log(`${pass.label} ${line}`);
}
printEvents(passes);
