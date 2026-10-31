// Derives the expected ground truth for each time, to compare with predictions later.
// A row at time t describes what the car is doing at t + horizon, matching the prediction
// API's lateral and longitudinal values.
// Usage: node tools/scenario/labels.mjs [scenario.json] [--horizon 2] [--json]
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { LABEL, STEP_S, accelerations } from './common.mjs';

export function lateralLabel(steeringDeg) {
  if (steeringDeg >= LABEL.lateralDeg) return 'left';
  if (steeringDeg <= -LABEL.lateralDeg) return 'right';
  return 'straight';
}

export function longitudinalLabel(accel) {
  if (accel >= LABEL.longitudinalMps2) return 'accelerate';
  if (accel <= -LABEL.longitudinalMps2) return 'brake';
  return 'steady';
}

// Rows run until t + horizon would pass the end of the timeline.
export function deriveLabels(scenario, { horizonS = LABEL.horizonS } = {}) {
  const { timeline } = scenario;
  const accel = accelerations(timeline);
  const offset = Math.round(horizonS / STEP_S);
  const rows = [];
  for (let i = 0; i + offset < timeline.length; i++) {
    const target = i + offset;
    rows.push({
      t: timeline[i].t,
      targetT: timeline[target].t,
      lateral: lateralLabel(timeline[target].steeringDeg),
      longitudinal: longitudinalLabel(accel[target]),
    });
  }
  return rows;
}

// Merges rows into spans of constant label, for reading and for event-level comparison.
export function labelSpans(rows, key) {
  const spans = [];
  for (const row of rows) {
    const current = spans[spans.length - 1];
    if (current && current.value === row[key]) current.toT = row.t;
    else spans.push({ value: row[key], fromT: row.t, toT: row.t });
  }
  return spans;
}

function main(argv) {
  const HERE = dirname(fileURLToPath(import.meta.url));
  const fallback = resolve(HERE, '..', '..', 'app/src/main/assets/scenarios/pangyo-gangnam.json');
  const flag = argv.indexOf('--horizon');
  const horizonS = flag >= 0 ? Number(argv[flag + 1]) : LABEL.horizonS;
  const file = argv.find((arg, i) => !arg.startsWith('--') && (flag < 0 || i !== flag + 1)) ?? fallback;
  const rows = deriveLabels(JSON.parse(readFileSync(file, 'utf8')), { horizonS });
  if (argv.includes('--json')) {
    console.log(JSON.stringify(rows));
    return;
  }
  for (const key of ['lateral', 'longitudinal']) {
    console.log(`${key} at t+${horizonS}s:`);
    for (const span of labelSpans(rows, key)) console.log(`  ${span.fromT} to ${span.toT}  ${span.value}`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2));
}
