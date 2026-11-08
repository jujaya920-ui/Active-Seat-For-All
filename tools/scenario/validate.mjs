// Checks a scenario JSON against the plan's file format and the demo constraints.
// Usage: node tools/scenario/validate.mjs [scenario.json ...]
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  CAMERA_TYPES,
  CAUTION_POINT_TYPES,
  FRONT_EVENT_TYPES,
  LIMITS,
  STEP_S,
  TURN_TYPES,
  accelerations,
  curvature,
  detectManeuvers,
  distances,
  kphToMps,
  lateralAccel,
  polylineLength,
} from './common.mjs';

const TOP_KEYS = [
  'id', 'title', 'mapImage', 'mapSize', 'originRoad', 'occupiedSeats', 'routePx', 'routeLengthM', 'turns', 'frontEvents', 'cameras',
  'cautionPoints', 'timeline',
];
const CONSOLE_SEATS = ['driver', 'passenger', 'rear_left', 'rear_right'];
const POINT_LISTS = {
  cameras: { keys: ['atM', 'id', 'type', 'speedLimit', 'sectionAvrSpeed'], types: CAMERA_TYPES },
  cautionPoints: { keys: ['atM', 'id', 'type'], types: CAUTION_POINT_TYPES },
};
const OPTIONAL_TOP_KEYS = ['demoWindow', 'plannedRoute'];
const PLANNED_KEYS = ['routePx', 'routeLengthM', 'turns', 'leaveAtM'];
// The car leaves the guided route where it should have turned, without signalling for that turn.
const PLANNED = { leaveNearTurnM: 15, noSignalBeforeM: 150 };
const ROW_KEYS = ['t', 'speedKph', 'steeringDeg', 'brakePct', 'acceleratorPct', 'turnSignal'];
const TURN_SIGNALS = ['Off', 'BlinkLeft', 'BlinkRight'];
const FIRM_TURNS = { TURN_LEFT: 1, TURN_SHARP_L: 1, TURN_RIGHT: -1, TURN_SHARP_R: -1 };
const SIGNALLED = {
  TURN_LEFT: 'BlinkLeft',
  TURN_SHARP_L: 'BlinkLeft',
  TURN_KEEP_L: 'BlinkLeft',
  TURN_RIGHT: 'BlinkRight',
  TURN_SHARP_R: 'BlinkRight',
  TURN_KEEP_R: 'BlinkRight',
};

const isNumber = (value) => typeof value === 'number' && Number.isFinite(value);
const sameKeys = (object, keys) =>
  Object.keys(object).length === keys.length && keys.every((key) => key in object);

function checkRoutePx(name, points, mapWidth, mapHeight, errors) {
  if (!Array.isArray(points) || points.length < 2) errors.push(`${name} needs at least 2 points`);
  else if (!points.every((p) => Array.isArray(p) && p.length === 2 && p.every(isNumber))) {
    errors.push(`${name} points must be [x, y] numbers`);
  } else if (points.some(([x, y]) => x < 0 || x > mapWidth || y < 0 || y > mapHeight)) {
    errors.push(`${name} must stay inside ${mapWidth}x${mapHeight}`);
  }
  if (Array.isArray(points) && points.length > LIMITS.maxRoutePxPoints) {
    errors.push(`${name} has ${points.length} points, limit ${LIMITS.maxRoutePxPoints}`);
  }
}

function checkEvents(name, list, lengthM, errors) {
  if (!Array.isArray(list)) {
    errors.push(`${name} must be an array`);
    return;
  }
  list.forEach((item, i) => {
    if (item === null || typeof item !== 'object' || !sameKeys(item, ['atM', 'type', 'description'])) {
      errors.push(`${name}[${i}] keys must be atM, type, description`);
      return;
    }
    if (!isNumber(item.atM) || item.atM < 0 || item.atM > lengthM) errors.push(`${name}[${i}].atM is off the route`);
    if (typeof item.type !== 'string' || item.type === '') errors.push(`${name}[${i}].type is missing`);
    if (typeof item.description !== 'string' || item.description === '') errors.push(`${name}[${i}].description is missing`);
    if (i > 0 && item.atM <= list[i - 1].atM) errors.push(`${name} must be in increasing atM order`);
  });
}

// The guided route of an off-route scenario: same map, same turn rules as the driven route.
// Returns whether the shape is sound enough for checkPlannedRoute.
function checkPlannedFormat(s, errors) {
  const planned = s.plannedRoute;
  if (planned === null || typeof planned !== 'object' || !sameKeys(planned, PLANNED_KEYS)) {
    errors.push(`plannedRoute keys must be exactly: ${PLANNED_KEYS.join(', ')}`);
    return false;
  }
  const [mapWidth, mapHeight] = Array.isArray(s.mapSize) ? s.mapSize : [];
  const before = errors.length;
  if (!isNumber(planned.routeLengthM) || planned.routeLengthM <= 0) errors.push('plannedRoute.routeLengthM must be positive');
  checkRoutePx('plannedRoute.routePx', planned.routePx, mapWidth, mapHeight, errors);
  checkEvents('plannedRoute.turns', planned.turns, planned.routeLengthM, errors);
  (Array.isArray(planned.turns) ? planned.turns : []).forEach((turn, i) => {
    if (!TURN_TYPES.includes(turn?.type)) errors.push(`plannedRoute.turns[${i}].type ${turn?.type} is not a NaviHelper TurnType`);
  });
  const { leaveAtM } = planned;
  if (!isNumber(leaveAtM) || leaveAtM <= 0 || leaveAtM >= planned.routeLengthM || leaveAtM >= s.routeLengthM) {
    errors.push('plannedRoute.leaveAtM must lie inside both routes');
  }
  return errors.length === before;
}

function checkFormat(s, errors) {
  const required = Object.fromEntries(Object.entries(s).filter(([key]) => !OPTIONAL_TOP_KEYS.includes(key)));
  if (!sameKeys(required, TOP_KEYS)) errors.push(`top-level keys must be exactly: ${TOP_KEYS.join(', ')}`);
  if (typeof s.id !== 'string' || !/^[a-z0-9-]+$/.test(s.id)) errors.push('id must be a kebab-case string');
  if (typeof s.title !== 'string' || s.title === '') errors.push('title must be a non-empty string');
  if (s.mapImage !== `maps/${s.id}.webp`) errors.push(`mapImage must be maps/${s.id}.webp`);
  if (typeof s.originRoad !== 'string' || s.originRoad === '') errors.push('originRoad must be a non-empty string');
  const seats = s.occupiedSeats;
  if (!Array.isArray(seats) || !seats.every((seat) => CONSOLE_SEATS.includes(seat)) || new Set(seats).size !== seats.length) {
    errors.push(`occupiedSeats must list distinct seats from: ${CONSOLE_SEATS.join(', ')}`);
  } else if (!seats.some((seat) => seat !== 'driver')) {
    errors.push('occupiedSeats needs a passenger, or the app gives no hint');
  }
  const [mapWidth, mapHeight] = Array.isArray(s.mapSize) ? s.mapSize : [];
  if (!isNumber(mapWidth) || !isNumber(mapHeight)) errors.push('mapSize must be [width, height]');
  if (!isNumber(s.routeLengthM) || s.routeLengthM <= 0) errors.push('routeLengthM must be positive');

  checkRoutePx('routePx', s.routePx, mapWidth, mapHeight, errors);
  checkEvents('turns', s.turns, s.routeLengthM, errors);
  checkEvents('frontEvents', s.frontEvents, s.routeLengthM, errors);
  (Array.isArray(s.turns) ? s.turns : []).forEach((turn, i) => {
    if (!TURN_TYPES.includes(turn.type)) errors.push(`turns[${i}].type ${turn.type} is not a NaviHelper TurnType`);
  });

  (Array.isArray(s.frontEvents) ? s.frontEvents : []).forEach((event, i) => {
    if (!FRONT_EVENT_TYPES.includes(event.type)) errors.push(`frontEvents[${i}].type ${event.type} is not allowed`);
  });

  for (const [name, { keys, types }] of Object.entries(POINT_LISTS)) {
    const list = s[name];
    if (!Array.isArray(list)) {
      errors.push(`${name} must be an array`);
      continue;
    }
    const ids = new Set();
    list.forEach((item, i) => {
      if (item === null || typeof item !== 'object' || !sameKeys(item, keys)) {
        errors.push(`${name}[${i}] keys must be ${keys.join(', ')}`);
        return;
      }
      if (!Number.isInteger(item.atM) || item.atM <= 0 || item.atM >= s.routeLengthM) errors.push(`${name}[${i}].atM must be an integer inside the route`);
      if (typeof item.id !== 'string' || item.id === '') errors.push(`${name}[${i}].id is missing`);
      else if (ids.has(item.id)) errors.push(`${name}[${i}].id ${item.id} is not unique`);
      ids.add(item.id);
      if (!types.includes(item.type)) errors.push(`${name}[${i}].type ${item.type} is not a NaviHelper type`);
      if (i > 0 && item.atM < list[i - 1].atM) errors.push(`${name} must be in atM order`);
      if (name === 'cameras') {
        if (!Number.isInteger(item.speedLimit) || item.speedLimit <= 0) errors.push(`${name}[${i}].speedLimit must be a positive integer`);
        if (!isNumber(item.sectionAvrSpeed) || item.sectionAvrSpeed < 0) errors.push(`${name}[${i}].sectionAvrSpeed must be a number`);
      }
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
  checkPolyline('', s.routePx, s.turns, totalM, errors);
}

// Each turn must sit on a polyline vertex at about the same fraction of the route.
function checkPolyline(prefix, routePx, turns, totalM, errors) {
  const pxTotal = polylineLength(routePx);
  const fractions = [];
  let walked = 0;
  routePx.forEach((point, i) => {
    if (i > 0) walked += polylineLength([routePx[i - 1], point]);
    fractions.push(walked / pxTotal);
  });
  const label = prefix ? `${prefix} turn` : 'turn';
  turns.forEach((turn, i) => {
    const wanted = turn.atM / totalM;
    const nearest = Math.min(...fractions.map((fraction) => Math.abs(fraction - wanted)));
    if (nearest > LIMITS.polylineTolerance) {
      errors.push(`${label} ${i}: no polyline vertex within ${LIMITS.polylineTolerance * 100}% of atM at ${(wanted * 100).toFixed(1)}%`);
    }
  });
}

// Real corners and navigation maneuvers are not one to one, so this checks each announced turn
// against what the car does at that spot, not a corner count.
function checkTurns(s, travelled, errors) {
  const tl = s.timeline;
  s.turns.forEach((turn, i) => {
    const label = `turn ${i} (${turn.type} ${turn.description})`;
    const sign = FIRM_TURNS[turn.type];
    if (sign) {
      let yawRad = 0;
      tl.forEach((row, k) => {
        if (Math.abs(travelled[k] - turn.atM) <= LIMITS.turnCheckRadiusM) {
          yawRad += kphToMps(row.speedKph) * curvature(row.steeringDeg) * STEP_S;
        }
      });
      // Either reading may match: three lefts around a block amount to the right turn OSRM names.
      const yawDeg = (yawRad * 180) / Math.PI;
      const wrappedDeg = (Math.atan2(Math.sin(yawRad), Math.cos(yawRad)) * 180) / Math.PI;
      const matches = Math.sign(yawDeg) === sign || Math.sign(wrappedDeg) === sign;
      if (Math.abs(yawDeg) >= LIMITS.minCheckedYawDeg && !matches) {
        errors.push(`${label}: steering turns ${yawDeg.toFixed(0)} deg the wrong way (left must be positive)`);
      }
    }
    const mode = SIGNALLED[turn.type];
    if (mode) {
      const previousM = i > 0 ? s.turns[i - 1].atM : 0;
      const leadM = Math.min(LIMITS.minSignalLeadM, turn.atM - previousM);
      const fromI = travelled.findIndex((metres) => metres >= turn.atM - leadM);
      const toI = travelled.findIndex((metres) => metres >= turn.atM);
      // A close next maneuver may take over the signal, so one matching sample is enough here.
      if (!tl.slice(fromI, toI + 1).some((row) => row.turnSignal === mode)) {
        errors.push(`${label}: no ${mode} over the last ${leadM.toFixed(0)} m`);
      }
    }
  });
}

// A hard brake is always scripted, and a scripted one always has a CONGESTION event ahead.
function checkHardBrakes(s, hardBrakes, travelled, errors) {
  const tl = s.timeline;
  for (const { startI, endI } of hardBrakes) {
    const event = s.frontEvents.find((e) => e.atM > travelled[startI] && e.atM >= travelled[endI]);
    if (!event) errors.push(`t=${tl[startI].t}: hard brake has no front event ahead of where it ends`);
  }
}

// The car leaves the guided route where it should have turned, and never signals for that turn.
function checkPlannedRoute(s, travelled, errors) {
  const { turns, leaveAtM } = s.plannedRoute;
  checkPolyline('plannedRoute', s.plannedRoute.routePx, turns, s.plannedRoute.routeLengthM, errors);
  const missed = turns.find((turn) => turn.type !== 'TURN_ARRIVE');
  if (!missed) {
    errors.push('plannedRoute has no turn for the car to miss');
    return;
  }
  if (Math.abs(leaveAtM - missed.atM) > PLANNED.leaveNearTurnM) {
    errors.push(`plannedRoute.leaveAtM ${leaveAtM} is not within ${PLANNED.leaveNearTurnM} m of the missed turn at ${missed.atM}`);
  }
  const mode = SIGNALLED[missed.type];
  if (!mode) return;
  const fromM = leaveAtM - PLANNED.noSignalBeforeM;
  const i = s.timeline.findIndex((row, k) => travelled[k] >= fromM && travelled[k] <= leaveAtM && row.turnSignal === mode);
  if (i >= 0) errors.push(`t=${s.timeline[i].t}: ${mode} toward the missed turn at ${missed.atM} m`);
}

function checkDemoWindow(s, errors) {
  if (s.demoWindow === undefined) return;
  const { fromSec, toSec } = s.demoWindow ?? {};
  if (!isNumber(fromSec) || !isNumber(toSec)) {
    errors.push('demoWindow needs fromSec and toSec');
    return;
  }
  const wholeRoute = fromSec === 0 && toSec === s.timeline.at(-1).t && toSec <= LIMITS.demoWindowS;
  if (!wholeRoute && Math.abs(toSec - fromSec - LIMITS.demoWindowS) > 1e-6) {
    errors.push(`demoWindow must span ${LIMITS.demoWindowS} s or a whole shorter route`);
  }
  if (fromSec < 0 || toSec > s.timeline.at(-1).t) errors.push('demoWindow is outside the timeline');
  const start = s.timeline[Math.round(fromSec / STEP_S)];
  if (!start || start.speedKph !== 0) errors.push('demoWindow must start with the car stopped');
}

// Returns { errors, warnings, info }. Errors fail the scenario.
export function validateScenario(s, { assetsDir } = {}) {
  const errors = [];
  const warnings = [];
  const info = {};
  const plannedOk = s.plannedRoute !== undefined && checkPlannedFormat(s, errors);
  if (!checkFormat(s, errors)) return { errors, warnings, info };

  const accel = checkPhysics(s, errors);
  const travelled = distances(s.timeline);
  const { corners, hardAccels, hardBrakes } = detectManeuvers(s.timeline);
  checkRoute(s, travelled, errors);
  checkTurns(s, travelled, errors);
  checkHardBrakes(s, hardBrakes, travelled, errors);
  checkDemoWindow(s, errors);
  if (plannedOk) checkPlannedRoute(s, travelled, errors);

  if (assetsDir && !existsSync(resolve(assetsDir, s.mapImage))) warnings.push(`map image not produced yet: ${s.mapImage}`);

  const tl = s.timeline;
  info.durationS = tl[tl.length - 1].t;
  info.integratedM = Number(travelled[travelled.length - 1].toFixed(1));
  info.routeLengthM = s.routeLengthM;
  info.routeErrorPct = Number(((Math.abs(info.integratedM - s.routeLengthM) / s.routeLengthM) * 100).toFixed(2));
  info.peakAccel = Number(Math.max(...accel).toFixed(2));
  info.peakBrake = Number(Math.min(...accel).toFixed(2));
  info.peakSteeringDeg = Math.max(...tl.map((row) => Math.abs(row.steeringDeg)));
  info.peakSpeedKph = Math.max(...tl.map((row) => row.speedKph));
  info.peakLateral = Number(Math.max(...tl.map(lateralAccel)).toFixed(2));
  info.turns = s.turns.length;
  info.corners = corners.length;
  info.frontEvents = s.frontEvents.length;
  info.cameras = Array.isArray(s.cameras) ? s.cameras.length : 'none';
  info.cautionPoints = Array.isArray(s.cautionPoints) ? s.cautionPoints.length : 'none';
  info.routePxPoints = s.routePx.length;
  info.demoWindow = s.demoWindow ? `${s.demoWindow.fromSec} to ${s.demoWindow.toSec}` : 'none';
  if (plannedOk) {
    const passedAt = (metres) => tl[travelled.findIndex((m) => m >= metres)]?.t;
    info.plannedRouteLengthM = s.plannedRoute.routeLengthM;
    info.plannedTurns = s.plannedRoute.turns.map((turn) => `${turn.atM} ${turn.type}`).join(', ');
    info.leaveAtM = s.plannedRoute.leaveAtM;
    info.leaveAtT = passedAt(s.plannedRoute.leaveAtM);
    info.turnTimes = s.turns.filter((turn) => turn.type !== 'TURN_ARRIVE').map((turn) => `${turn.atM} ${turn.type} t=${passedAt(turn.atM)}`).join(', ');
  }
  info.hardAccels = hardAccels.length;
  info.hardBrakes = hardBrakes.map(({ startI, hardI, endI }) => ({
    start: tl[startI].t,
    hard: tl[hardI].t,
    end: tl[endI].t,
    fromKph: tl[startI].speedKph,
    toKph: tl[endI].speedKph,
    brakeStartM: Math.round(travelled[startI]),
    frontEventAtM: s.frontEvents.find((e) => e.atM > travelled[startI])?.atM,
  }));
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

const DEFAULT_FILES = [
  'gangnam-pangyo',
  'pangyo-dongpangyo',
  'seohyeon-yanghyeon',
  'gangnam-teheran',
  'seocho-jungang',
  'seoulstation-cheongpa',
].map((id) => resolve(ASSETS, `scenarios/${id}.json`));

function main(paths) {
  const files = paths.length > 0 ? paths : DEFAULT_FILES;
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
