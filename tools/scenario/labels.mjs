// Ground truth for the push prediction: the level of each body push at t + horizon.
// Uses the server's own preprocessing, so truth and the words Jev reads share one definition.
// Usage: node tools/scenario/labels.mjs [scenario.json] [--horizon 2] [--json]
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DIRECTIONS, SMOOTH, directionLevels, physics } from '../../server/lib/describe.js';
import { STEP_S } from './common.mjs';

const SIGNAL = { Off: 'off', BlinkLeft: 'left', BlinkRight: 'right' };

// Rows in the server's input shape. Accelerator pedal is left out: the app cannot read it.
export const toRows = (timeline) =>
  timeline.map((r) => ({ speedKph: r.speedKph, steeringDeg: r.steeringDeg, brake: r.brakePct > 0, turnSignal: SIGNAL[r.turnSignal] }));

// levels[i] is the push at timeline[i]; the first SMOOTH samples have no history and read as none.
export function pushLevels(timeline) {
  const none = { forward: 0, backward: 0, left: 0, right: 0 };
  return [...Array(SMOOTH).fill(none), ...directionLevels(physics(toRows(timeline)))];
}

// Rows run until t + horizon would pass the end of the timeline.
export function deriveLabels(scenario, { horizonS = 2 } = {}) {
  const levels = pushLevels(scenario.timeline);
  const offset = Math.round(horizonS / STEP_S);
  const rows = [];
  for (let i = 0; i + offset < scenario.timeline.length; i++) {
    rows.push({ t: scenario.timeline[i].t, targetT: scenario.timeline[i + offset].t, ...levels[i + offset] });
  }
  return rows;
}

// Spans where a direction is pushed at all, with their peak level.
export function pushSpans(timeline, levels = pushLevels(timeline)) {
  const spans = [];
  for (const dir of DIRECTIONS) {
    let open = null;
    levels.forEach((l, i) => {
      if (l[dir] > 0 && !open) open = { dir, fromT: timeline[i].t, peak: 0 };
      if (open) open.peak = Math.max(open.peak, l[dir]);
      if (l[dir] === 0 && open) {
        spans.push({ ...open, toT: timeline[i - 1].t });
        open = null;
      }
    });
    if (open) spans.push({ ...open, toT: timeline.at(-1).t });
  }
  return spans.sort((a, b) => a.fromT - b.fromT);
}

function main(argv) {
  const HERE = dirname(fileURLToPath(import.meta.url));
  const fallback = resolve(HERE, '..', '..', 'app/src/main/assets/scenarios/gangnam-pangyo.json');
  const flag = argv.indexOf('--horizon');
  const horizonS = flag >= 0 ? Number(argv[flag + 1]) : 2;
  const file = argv.find((arg, i) => !arg.startsWith('--') && (flag < 0 || i !== flag + 1)) ?? fallback;
  const scenario = JSON.parse(readFileSync(file, 'utf8'));
  if (argv.includes('--json')) {
    console.log(JSON.stringify(deriveLabels(scenario, { horizonS })));
    return;
  }
  for (const s of pushSpans(scenario.timeline)) console.log(`${s.fromT.toFixed(1)} to ${s.toT.toFixed(1)}  ${s.dir} peak ${s.peak}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2));
}
