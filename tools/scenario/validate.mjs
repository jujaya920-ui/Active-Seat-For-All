// Checks a scenario JSON against the plan's file format and the demo constraints.
// Usage: node tools/scenario/validate.mjs [scenario.json ...]
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  LIMITS,
  STEP_S,
  accelerations,
  detectManeuvers,
  distances,
  lateralAccel,
  polylineLength,
  signalRuns,
} from './common.mjs';

const TOP_KEYS = ['id', 'title', 'mapImage', 'routePx', 'routeLengthM', 'turns', 'frontEvents', 'timeline'];
const ROW_KEYS = ['t', 'speedKph', 'steeringDeg', 'brakePct', 'acceleratorPct', 'turnSignal'];
const TURN_SIGNALS = ['Off', 'BlinkLeft', 'BlinkRight'];
const TURN_DIRECTION = { TURN_LEFT: 'left', TURN_RIGHT: 'right' };
const MAP = { width: 1600, height: 1000 };

// The real NaviHelper labels this scenario must reproduce, in order.
const REQUIRED_TURNS = {
  'pangyo-gangnam': [
    ['TURN_RIGHT', '대왕판교IC 방면'],
    ['TURN_RIGHT', '양재 방면'],
    ['TURN_LEFT', '강남역 방면'],
  ],
};

const isNumber = (value) => typeof value === 'number' && Number.isFinite(value);
const sameKeys = (object, keys) =>
  Object.keys(object).length === keys.length && keys.every((key) => key in object);

function checkFormat(s, errors) {
  if (!sameKeys(s, TOP_KEYS)) errors.push(`top-level keys must be exactly: ${TOP_KEYS.join(', ')}`);
  if (typeof s.id !== 'string' || !/^[a-z0-9-]+$/.test(s.id)) errors.push('id must be a kebab-case string');
  if (typeof s.title !== 'string' || s.title === '') errors.push('title must be a non-empty string');
  if (s.mapImage !== `maps/${s.id}.webp`) errors.push(`mapImage must be maps/${s.id}.webp`);
  if (!isNumber(s.routeLengthM) || s.routeLengthM <= 0) errors.push('routeLengthM must be positive');

  if (!Array.isArray(s.routePx) || s.routePx.length < 2) errors.push('routePx needs at least 2 points');
  else if (!s.routePx.every((p) => Array.isArray(p) && p.length === 2 && p.every(isNumber))) {
    errors.push('routePx points must be [x, y] numbers');
  } else if (s.routePx.some(([x, y]) => x < 0 || x > MAP.width || y < 0 || y > MAP.height)) {
    errors.push(`routePx must stay inside ${MAP.width}x${MAP.height}`);
  }

  for (const [name, list] of [['turns', s.turns], ['frontEvents', s.frontEvents]]) {
    if (!Array.isArray(list)) {
      errors.push(`${name} must be an array`);
      continue;
    }
    list.forEach((item, i) => {
      if (!sameKeys(item, ['atM', 'type', 'description'])) errors.push(`${name}[${i}] keys must be atM, type, description`);
      if (!isNumber(item.atM) || item.atM < 0 || item.atM > s.routeLengthM) errors.push(`${name}[${i}].atM is off the route`);
      if (typeof item.type !== 'string' || item.type === '') errors.push(`${name}[${i}].type is missing`);
      if (typeof item.description !== 'string' || item.description === '') errors.push(`${name}[${i}].description is missing`);
      if (i > 0 && item.atM <= list[i - 1].atM) errors.push(`${name} must be in increasing atM order`);
    });
  }

  if (!Array.isArray(s.timeline) || s.timeline.length < 2) {
    errors.push('timeline needs at least 2 rows');
    return false;
  }
  let rowsOk = true;
  s.timeline.forEach((row, i) => {
    const bad = (message) => {
      errors.push(`timeline[${i}]: ${message}`);
      rowsOk = false;
    };
    if (!sameKeys(row, ROW_KEYS)) bad(`keys must be exactly: ${ROW_KEYS.join(', ')}`);
    for (const key of ROW_KEYS.slice(0, 5)) if (!isNumber(row[key])) bad(`${key} must be a number`);
    if (!TURN_SIGNALS.includes(row.turnSignal)) bad(`turnSignal must be one of ${TURN_SIGNALS.join(', ')}`);
    if (Math.abs(row.t - i * STEP_S) > 1e-6) bad(`t must be ${(i * STEP_S).toFixed(1)}`);
  });
  return rowsOk;
}

function checkPhysics(s, errors) {
  const tl = s.timeline;
  const at = (i) => `t=${tl[i].t}`;
  const accel = accelerations(tl);
  const last = tl[tl.length - 1];
  const collect = (rule, message) => {
    const i = tl.findIndex(rule);
    if (i >= 0) errors.push(`${at(i)}: ${message}`);
  };

  if (last.t < LIMITS.minDurationS || last.t > LIMITS.maxDurationS) {
    errors.push(`duration ${last.t} s is outside ${LIMITS.minDurationS} to ${LIMITS.maxDurationS} s`);
  }
  if (tl[0].speedKph !== 0 || last.speedKph !== 0) errors.push('must start and end stopped');
  if (tl[0].steeringDeg !== 0 || last.steeringDeg !== 0) errors.push('must start and end with centred steering');
  if (last.turnSignal !== 'Off') errors.push('turn signal must end Off');

  collect((r) => Math.abs(r.steeringDeg) > LIMITS.maxSteeringDeg, `steering beyond ±${LIMITS.maxSteeringDeg}`);
  collect((r) => r.speedKph < 0 || r.speedKph > LIMITS.maxSpeedKph, `speed outside 0 to ${LIMITS.maxSpeedKph}`);
  collect((r) => r.brakePct < 0 || r.brakePct > 100 || r.acceleratorPct < 0 || r.acceleratorPct > 100, 'pedal outside 0 to 100');
  collect((r) => r.brakePct > 0 && r.acceleratorPct > 0, 'brake and accelerator pressed together');
  collect((r) => lateralAccel(r) > LIMITS.maxLateralMps2, `lateral acceleration above ${LIMITS.maxLateralMps2} m/s²`);

  accel.forEach((a, i) => {
    if (a > LIMITS.maxAccelMps2 + 1e-9) errors.push(`${at(i)}: acceleration ${a.toFixed(2)} above ${LIMITS.maxAccelMps2}`);
    if (a < LIMITS.maxBrakeMps2 - 1e-9) errors.push(`${at(i)}: deceleration ${a.toFixed(2)} beyond ${LIMITS.maxBrakeMps2}`);
    if (i > 0) {
      const jerk = (a - accel[i - 1]) / STEP_S;
      if (Math.abs(jerk) > LIMITS.maxJerkMps3) errors.push(`${at(i)}: jerk ${jerk.toFixed(1)} above ${LIMITS.maxJerkMps3}`);
      const rate = (tl[i].steeringDeg - tl[i - 1].steeringDeg) / STEP_S;
      if (Math.abs(rate) > LIMITS.maxSteeringRateDegS) {
        errors.push(`${at(i)}: steering rate ${rate.toFixed(0)} above ${LIMITS.maxSteeringRateDegS} deg/s`);
      }
    }
  });
  return accel;
}

function checkRoute(s, travelled, errors) {
  const totalM = travelled[travelled.length - 1];
  const gap = Math.abs(totalM - s.routeLengthM) / s.routeLengthM;
  if (gap > LIMITS.routeLengthTolerance) {
    errors.push(`integrated ${totalM.toFixed(1)} m differs from routeLengthM ${s.routeLengthM} by ${(gap * 100).toFixed(2)}%`);
  }
  // Each turn must sit on a polyline vertex at the same fraction of the route.
  if (s.routePx.length !== s.turns.length + 2) {
    errors.push(`routePx needs ${s.turns.length + 2} points: start, one per turn, end`);
    return;
  }
  const pxTotal = polylineLength(s.routePx);
  s.turns.forEach((turn, i) => {
    const fraction = polylineLength(s.routePx.slice(0, i + 2)) / pxTotal;
    if (Math.abs(fraction - turn.atM / totalM) > LIMITS.polylineTolerance) {
      errors.push(`turn ${i}: polyline vertex at ${(fraction * 100).toFixed(1)}% but atM at ${((turn.atM / totalM) * 100).toFixed(1)}%`);
    }
  });
}

function checkTurns(s, corners, travelled, accel, errors) {
  const tl = s.timeline;
  const required = REQUIRED_TURNS[s.id];
  if (required && JSON.stringify(s.turns.map((turn) => [turn.type, turn.description])) !== JSON.stringify(required)) {
    errors.push(`turns must be, in order: ${required.flat().join(' ')}`);
  }
  if (corners.length < 2) errors.push(`need at least 2 corners, found ${corners.length}`);
  if (corners.length !== s.turns.length) {
    errors.push(`${s.turns.length} turns but ${corners.length} corners in the timeline`);
    return;
  }
  const blinks = signalRuns(tl);
  if (blinks.length !== corners.length) errors.push(`${blinks.length} turn signal runs for ${corners.length} corners`);

  corners.forEach((corner, i) => {
    const turn = s.turns[i];
    const label = `turn ${i} (${turn.description})`;
    if (TURN_DIRECTION[turn.type] !== corner.direction) {
      errors.push(`${label}: ${turn.type} but steering goes ${corner.direction} (left must be positive)`);
    }
    const offsetM = Math.abs(travelled[corner.apexI] - turn.atM);
    if (offsetM > LIMITS.turnPositionToleranceM) errors.push(`${label}: atM is ${offsetM.toFixed(1)} m from the steering apex`);

    const mode = corner.direction === 'left' ? 'BlinkLeft' : 'BlinkRight';
    const blink = blinks.find((run) => run.mode === mode && run.startI <= corner.moveI && run.endI >= corner.moveI);
    if (!blink) {
      errors.push(`${label}: no ${mode} when steering starts`);
    } else {
      const lead = tl[corner.moveI].t - tl[blink.startI].t;
      if (lead < LIMITS.minSignalLeadS - 1e-6) errors.push(`${label}: signal leads steering by ${lead.toFixed(1)} s, need ${LIMITS.minSignalLeadS}`);
      if (blink.endI < corner.endI) errors.push(`${label}: signal stops before the corner ends`);
    }
    const from = Math.max(0, corner.moveI - Math.round(LIMITS.cornerSlowdownWindowS / STEP_S));
    if (Math.min(...accel.slice(from, corner.moveI)) > -0.6) errors.push(`${label}: no slowdown before steering starts`);
  });
}

function precursorWindow(accel, startI) {
  const samples = Math.round(LIMITS.precursorWindowS / STEP_S);
  return startI < samples ? null : accel.slice(startI - samples, startI);
}

function checkLongitudinal(s, hardAccels, hardBrakes, accel, travelled, errors) {
  const tl = s.timeline;
  if (hardAccels.length < 1) errors.push(`need a hard acceleration (at least ${LIMITS.hardAccelMps2} m/s²)`);
  if (hardBrakes.length < 1) errors.push(`need a hard brake (beyond ${LIMITS.normalBrakeMps2} m/s²)`);

  for (const { startI } of hardAccels) {
    const window = precursorWindow(accel, startI);
    if (!window || Math.min(...window) < LIMITS.precursorAccelMps2) {
      errors.push(`t=${tl[startI].t}: hard acceleration lacks ${LIMITS.precursorWindowS} s of gentle acceleration before it`);
    }
  }
  for (const { startI, endI } of hardBrakes) {
    const window = precursorWindow(accel, startI);
    if (!window || Math.max(...window) > -LIMITS.precursorAccelMps2) {
      errors.push(`t=${tl[startI].t}: hard brake lacks ${LIMITS.precursorWindowS} s of lift-off before it`);
    }
    const event = s.frontEvents.find((e) => e.atM > travelled[startI] && e.atM >= travelled[endI]);
    if (!event) errors.push(`t=${tl[startI].t}: hard brake has no front event ahead of where it ends`);
    else if (event.type !== 'CONGESTION') errors.push(`t=${tl[startI].t}: hard brake front event is ${event.type}, expected CONGESTION`);
  }
}

// Returns { errors, warnings, info }. Errors fail the scenario.
export function validateScenario(s, { assetsDir } = {}) {
  const errors = [];
  const warnings = [];
  const info = {};
  if (!checkFormat(s, errors)) return { errors, warnings, info };

  const accel = checkPhysics(s, errors);
  const travelled = distances(s.timeline);
  const { corners, hardAccels, hardBrakes } = detectManeuvers(s.timeline);
  checkRoute(s, travelled, errors);
  checkTurns(s, corners, travelled, accel, errors);
  checkLongitudinal(s, hardAccels, hardBrakes, accel, travelled, errors);

  if (assetsDir && !existsSync(resolve(assetsDir, s.mapImage))) warnings.push(`map image not produced yet: ${s.mapImage}`);

  const tl = s.timeline;
  const blinks = signalRuns(tl);
  info.durationS = tl[tl.length - 1].t;
  info.integratedM = Number(travelled[travelled.length - 1].toFixed(1));
  info.routeLengthM = s.routeLengthM;
  info.routeErrorPct = Number(((Math.abs(info.integratedM - s.routeLengthM) / s.routeLengthM) * 100).toFixed(2));
  info.peakAccel = Number(Math.max(...accel).toFixed(2));
  info.peakBrake = Number(Math.min(...accel).toFixed(2));
  info.peakSteeringDeg = Math.max(...tl.map((row) => Math.abs(row.steeringDeg)));
  info.peakSpeedKph = Math.max(...tl.map((row) => row.speedKph));
  info.peakLateral = Number(Math.max(...tl.map(lateralAccel)).toFixed(2));
  info.corners = corners.map((corner, i) => ({
    turn: s.turns[i] ? `${s.turns[i].type} ${s.turns[i].description}` : '(no turn)',
    atM: s.turns[i]?.atM,
    signalOn: tl[blinks.find((run) => run.startI <= corner.moveI && run.endI >= corner.moveI)?.startI]?.t,
    steerStart: tl[corner.moveI].t,
    label: tl[corner.startI].t,
    apex: tl[corner.apexI].t,
    end: tl[corner.endI].t,
    speedKph: tl[corner.apexI].speedKph,
  }));
  info.hardAccels = hardAccels.map(({ startI, hardI, endI }) => ({
    start: tl[startI].t,
    hard: tl[hardI].t,
    end: tl[endI].t,
    fromKph: tl[startI].speedKph,
    toKph: tl[endI].speedKph,
  }));
  info.hardBrakes = hardBrakes.map(({ startI, hardI, endI }) => {
    const samples = Math.round((LIMITS.precursorWindowS + 1) / STEP_S);
    const event = s.frontEvents.find((e) => e.atM > travelled[startI]);
    return {
      start: tl[startI].t,
      hard: tl[hardI].t,
      end: tl[endI].t,
      fromKph: tl[startI].speedKph,
      toKph: tl[endI].speedKph,
      frontEventAtM: event?.atM,
      // How far ahead the app must publish the event to show it 3 s before braking.
      lookaheadNeededM: event ? Math.round(event.atM - travelled[Math.max(0, startI - samples)]) : undefined,
    };
  });
  return { errors, warnings, info };
}

export function formatReport({ errors, warnings, info }) {
  const lines = errors.length > 0 ? errors.map((error) => `ERROR ${error}`) : ['OK'];
  lines.push(...warnings.map((warning) => `WARN  ${warning}`));
  for (const [key, value] of Object.entries(info)) {
    if (Array.isArray(value)) value.forEach((item) => lines.push(`${key}: ${JSON.stringify(item)}`));
    else lines.push(`${key}: ${value}`);
  }
  return lines.join('\n');
}

const HERE = dirname(fileURLToPath(import.meta.url));
const ASSETS = resolve(HERE, '..', '..', 'app/src/main/assets');

function main(paths) {
  const files = paths.length > 0 ? paths : [resolve(ASSETS, 'scenarios/pangyo-gangnam.json')];
  for (const file of files) {
    console.log(file);
    const report = validateScenario(JSON.parse(readFileSync(file, 'utf8')), { assetsDir: ASSETS });
    console.log(formatReport(report));
    if (report.errors.length > 0) process.exitCode = 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2));
}
