// Generates a scenario JSON from a compact definition.
// Usage: node tools/scenario/build.mjs [def.mjs] [--out file.json]
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { STEP_S, curvature, distances, kphToMps } from './common.mjs';
import { formatReport, validateScenario } from './validate.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = resolve(HERE, '..', '..');
export const DEFAULT_DEF = resolve(HERE, 'pangyo-gangnam.def.mjs');

const SIM_DT = 0.01;
const SIM_PER_STEP = Math.round(STEP_S / SIM_DT);
const DEFAULT_ACCEL = 1.5;
const DEFAULT_JERK = 1.5;
const MAP = { width: 1600, height: 1000, margin: 100 };

const round = (value, digits) => Number(value.toFixed(digits));
const clamp = (value, low, high) => Math.min(high, Math.max(low, value));

// Jerk-limited speed follower. Acceleration approaches the target along a sqrt(2·J·dv)
// profile, so the speed settles without overshoot and without a step in acceleration.
function simulateSpeed(segments) {
  const speeds = [];
  let speed = 0;
  let accel = 0;
  let tick = 0;
  for (const segment of segments) {
    const target = kphToMps(segment.kph);
    const accelLimit = segment.accel ?? DEFAULT_ACCEL;
    const jerk = segment.jerk ?? DEFAULT_JERK;
    const samples = Math.round(segment.durS / SIM_DT);
    for (let i = 0; i < samples; i++) {
      if (tick++ % SIM_PER_STEP === 0) speeds.push(speed);
      const dv = target - speed;
      const wanted = clamp(Math.sign(dv) * Math.sqrt(2 * jerk * Math.abs(dv)), -accelLimit, accelLimit);
      accel += clamp(wanted - accel, -jerk * SIM_DT, jerk * SIM_DT);
      speed = Math.max(0, speed + accel * SIM_DT);
      if (speed === 0 && target === 0) accel = 0;
    }
  }
  speeds.push(speed);
  return speeds;
}

// Raised cosine up, plateau, raised cosine down, so the steering rate starts from zero.
function cornerSteering(corner, t) {
  const fromApex = Math.abs(t - corner.tApex);
  const halfHold = corner.holdS / 2;
  const sign = corner.type === 'TURN_LEFT' ? 1 : -1;
  if (fromApex <= halfHold) return sign * corner.peakDeg;
  const intoRamp = (fromApex - halfHold) / corner.rampS;
  if (intoRamp >= 1) return 0;
  return (sign * corner.peakDeg * (1 + Math.cos(Math.PI * intoRamp))) / 2;
}

function signalAt(corners, t) {
  for (const corner of corners) {
    const halfSpan = corner.holdS / 2 + corner.rampS;
    if (t >= corner.tApex - halfSpan - corner.signalLeadS && t <= corner.tApex + halfSpan + 0.4) {
      return corner.type === 'TURN_LEFT' ? 'BlinkLeft' : 'BlinkRight';
    }
  }
  return 'Off';
}

const smoothstep = (x) => {
  const u = clamp(x, 0, 1);
  return u * u * (3 - 2 * u);
};

// Plausible pedals for the speed trace. Gentle deceleration is coasting, so no brake.
function pedals(speedKph, accel) {
  let brake = accel > 0.05 ? 0 : 12 * Math.max(0, -accel - 0.6);
  if (accel <= 0.05) brake = Math.max(brake, 30 * (1 - speedKph / 3));
  const cruise = (5 + 0.15 * speedKph) * smoothstep((accel + 0.3) / 0.3) * Math.min(1, speedKph / 3);
  const throttle = cruise + 18 * Math.max(accel, 0);
  return {
    brakePct: round(clamp(brake, 0, 100), 1),
    acceleratorPct: brake > 0 ? 0 : round(clamp(throttle, 0, 100), 1),
  };
}

function buildTimeline(def) {
  const speeds = simulateSpeed(def.segments);
  return speeds.map((speed, i) => {
    const t = round(i * STEP_S, 1);
    const speedKph = speed * 3.6;
    const accel = i + 1 < speeds.length ? (speeds[i + 1] - speed) / STEP_S : 0;
    const steering = def.corners.reduce((sum, corner) => sum + cornerSteering(corner, t), 0);
    return {
      t,
      speedKph: round(speedKph, 2),
      steeringDeg: round(steering, 1) + 0,
      ...pedals(speedKph, accel),
      turnSignal: signalAt(def.corners, t),
    };
  });
}

const indexAt = (t) => Math.round(t / STEP_S);

// Each corner's map angle comes from the steering trace, so map and steering agree.
function headingChanges(def, timeline) {
  return def.corners.map((corner) => {
    const halfSpan = corner.holdS / 2 + corner.rampS;
    let yawRad = 0;
    for (let i = indexAt(corner.tApex - halfSpan); i <= indexAt(corner.tApex + halfSpan); i++) {
      yawRad += kphToMps(timeline[i].speedKph) * curvature(timeline[i].steeringDeg) * STEP_S;
    }
    return (yawRad * 180) / Math.PI;
  });
}

// One straight leg per stretch between corners, with a vertex at each turn's atM, so that
// walking the polyline by distance fraction reaches each vertex exactly at its turn.
function buildRoutePx(def, timeline, turns, totalM) {
  const changes = headingChanges(def, timeline);
  const stops = [0, ...turns.map((turn) => turn.atM), totalM];
  let heading = def.startHeadingDeg;
  const points = [[0, 0]];
  for (let leg = 0; leg + 1 < stops.length; leg++) {
    const lengthM = stops[leg + 1] - stops[leg];
    const rad = (heading * Math.PI) / 180;
    const [x, y] = points[points.length - 1];
    points.push([x + lengthM * Math.sin(rad), y - lengthM * Math.cos(rad)]);
    // Left-positive steering turns the heading counter-clockwise.
    if (leg < changes.length) heading -= changes[leg];
  }
  const xs = points.map((p) => p[0]);
  const ys = points.map((p) => p[1]);
  const [minX, maxX, minY, maxY] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const scale = Math.min(
    (MAP.width - 2 * MAP.margin) / (maxX - minX),
    (MAP.height - 2 * MAP.margin) / (maxY - minY),
  );
  const offsetX = (MAP.width - scale * (maxX - minX)) / 2;
  const offsetY = (MAP.height - scale * (maxY - minY)) / 2;
  return points.map(([x, y]) => [
    Math.round(offsetX + scale * (x - minX)),
    Math.round(offsetY + scale * (y - minY)),
  ]);
}

function buildFrontEvents(def, timeline, travelled) {
  const events = [];
  let segmentStartS = 0;
  for (const segment of def.segments) {
    if (segment.frontEvent) {
      const firstI = indexAt(segmentStartS);
      const lastI = indexAt(segmentStartS + segment.durS);
      let settledI = firstI;
      while (settledI < lastI && timeline[settledI].speedKph > segment.kph + 0.5) settledI++;
      const { type, description, offsetM } = segment.frontEvent;
      events.push({ atM: Math.round(travelled[settledI] + offsetM), type, description });
    }
    segmentStartS += segment.durS;
  }
  return events;
}

export function buildScenario(def) {
  const timeline = buildTimeline(def);
  const travelled = distances(timeline);
  const totalM = travelled[travelled.length - 1];
  const turns = def.corners.map((corner) => ({
    atM: Math.round(travelled[indexAt(corner.tApex)]),
    type: corner.type,
    description: corner.description,
  }));
  return {
    id: def.id,
    title: def.title,
    mapImage: def.mapImage,
    routePx: buildRoutePx(def, timeline, turns, totalM),
    routeLengthM: def.routeLengthM,
    turns,
    frontEvents: buildFrontEvents(def, timeline, travelled),
    timeline,
  };
}

// Standard JSON with one line per array element, so timeline diffs stay readable.
export function formatScenario(scenario) {
  const { routePx, turns, frontEvents, timeline, ...head } = scenario;
  const list = (items) => `[\n    ${items.map((item) => JSON.stringify(item)).join(',\n    ')}\n  ]`;
  const headLines = Object.entries(head).map(([key, value]) => `  ${JSON.stringify(key)}: ${JSON.stringify(value)}`);
  const body = [
    ...headLines,
    `  "routePx": ${JSON.stringify(routePx)}`,
    `  "turns": ${list(turns)}`,
    `  "frontEvents": ${list(frontEvents)}`,
    `  "timeline": ${list(timeline)}`,
  ];
  return `{\n${body.join(',\n')}\n}\n`;
}

export const outputPath = (id) => resolve(REPO_ROOT, 'app/src/main/assets/scenarios', `${id}.json`);

export async function loadDefinition(path = DEFAULT_DEF) {
  return (await import(pathToFileURL(resolve(path)).href)).default;
}

async function main(argv) {
  const outFlag = argv.indexOf('--out');
  const out = outFlag >= 0 ? argv[outFlag + 1] : undefined;
  const defPath = argv.find((arg, i) => !arg.startsWith('--') && (outFlag < 0 || i !== outFlag + 1));
  const def = await loadDefinition(defPath);
  const scenario = buildScenario(def);
  const path = out ? resolve(out) : outputPath(def.id);
  const report = validateScenario(scenario);
  console.log(formatReport(report));
  if (report.errors.length > 0) {
    console.error(`not written: ${path}`);
    process.exitCode = 1;
    return;
  }
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, formatScenario(scenario));
  console.log(`wrote ${path}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main(process.argv.slice(2));
}
