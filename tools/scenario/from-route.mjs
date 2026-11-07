// Generates driving scenarios from real OSRM routes with one driver model.
// Usage: node tools/scenario/from-route.mjs [id ...]
//
// Every route goes through the same code and the same constants below. Nothing here may be
// keyed by scenario id except ROUTES (which file, which title) and the PRNG seed. Never add a
// per-route parameter, and never tune a constant by looking at prediction results.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { LABEL, LIMITS, STEP_S, VEHICLE, accelerations, curvature as curvatureOf, detectManeuvers, kphToMps, polylineLength } from './common.mjs';
import { fitRoute } from './projection.mjs';
import { formatReport, validateScenario } from './validate.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = resolve(HERE, '..', '..');

export const ROUTES = [
  { id: 'gangnam-pangyo', title: '판교', file: 'osrm_gangnam_pangyo.json' },
  { id: 'pangyo-dongpangyo', title: '판교 동판교로', file: 'osrm_pangyo_dongpangyo.json' },
  { id: 'seohyeon-yanghyeon', title: '서현 양현로', file: 'osrm_seohyeon_yanghyeon.json' },
  { id: 'gangnam-teheran', title: '강남 테헤란로', file: 'osrm_gangnam_teheran.json' },
  { id: 'seocho-jungang', title: '서초 서초중앙로', file: 'osrm_seocho_jungang.json' },
  { id: 'seoulstation-cheongpa', title: '서울역 청파로', file: 'osrm_seoulstation_cheongpa.json' },
];

// How the driver handles the road. All values are generic, none comes from a particular route.
export const DRIVER = {
  gridM: 1, // spatial resolution of the speed and curvature profiles
  lateralMps2: 2, // comfortable lateral acceleration, caps corner speed
  accelMps2: 1.5, // comfortable acceleration
  brakeMps2: 2, // comfortable braking
  jerkWindowS: 1, // speed is box-averaged over this span, which bounds jerk at Δa / window
  previewS: 1.2, // a driver cuts a polyline vertex over about this much travel time
  minSmoothingM: 6, // Gaussian sigma floor, about a 10 m radius for a 90 degree junction
  maxSmoothingM: 60,
  smoothingRounds: 6, // sigma and speed depend on each other; iterate to the fixed point
  centringM: 5, // steering eases to zero over this distance at both route ends
  steeringRateDegS: LIMITS.maxSteeringRateDegS * 0.95, // margin for rounding
  holdS: 2, // stopped at origin and at destination
};

// How the driver holds the wheel around the ideal steering. Generic, set before any prediction run.
export const HANDS = {
  // Each corner is wound in by a slightly wrong amount, which the driver then notices and fixes.
  cornerFromDeg: LABEL.lateralDeg,
  minGain: 0.92,
  maxGain: 1.08,
  disturbanceTauS: 3, // road camber and wind, a slowly wandering curvature
  disturbancePerM: 0.0005, // its standard deviation, about 1 degree at the wheel
  checkMinS: 0.6, // the driver judges the lane position this often
  checkMaxS: 1.4,
  offsetDeadbandM: 0.2,
  headingDeadbandDeg: 1.5,
  previewS: 2, // a correction aims to be back on the line this far ahead
  minPreviewM: 8,
  // Off a corner the correction stays under the 15 degree label threshold, so it is never a corner
  // itself. In a corner it may grow with the steering, enough to undo a wrong gain.
  maxCorrectionDeg: 10,
  maxCorrectionShare: 0.15,
  correctionLateralMps2: 0.5, // and it stays a nudge, so it shrinks with speed
  correctionRateDegS: 20, // a small correction takes a few tenths of a second
  marginDeg: 0.5, // keeps rounding to 0.1 degree off the label threshold
  minSpeedMps: 1, // no correcting while stopped; the correction is let go instead
};

export const SIGNAL = {
  urbanLeadM: 30,
  motorwayLeadM: 100,
  offBelowDeg: 5, // cancelled once the wheel is back near centre after the maneuver
  minAfterM: 10,
  maxAfterM: 80,
};

// OSRM reports no road class here, so class comes from the routed speed of the approach step.
export const MOTORWAY_KPH = 70;

export const STOPS = {
  probability: 0.5,
  minWaitS: 10,
  maxWaitS: 40,
  stopLineM: 15, // stop line before the maneuver vertex
  minSpacingM: 100,
  endMarginM: 60, // no red light right at the origin or destination
};

export const TRAFFIC = {
  congestionPerKm: 0.15, // announced with a CONGESTION front event
  unannouncedPerKm: 0.15, // same slowdown, no event: honest unpredictable cases
  hardShare: 0.3, // share of announced slowdowns entered with a hard brake
  minHardBrakeMps2: 3.5,
  maxHardBrakeMps2: 5,
  minLengthM: 150,
  maxLengthM: 800,
  minSpeedFraction: 0.25, // of the free speed where the slowdown begins
  maxSpeedFraction: 0.6,
  minSpeedMps: 3,
  minEventOffsetM: 20, // front event sits this far past where the slowdown speed is reached
  maxEventOffsetM: 150,
  endMarginM: 300,
  minGapM: 100,
};

export const DEMO = {
  windowS: 150,
  // Starts this long before pull-away, or when the car stops, whichever is later.
  leadBeforeGoS: 3,
  minAccelS: 1, // shorter accelerate runs are not counted as an acceleration
  cornerMergeS: 3, // same-direction corners closer than this count as one
};

// A route this short plays whole, so it carries its own maneuvers instead of a window of PRNG
// traffic: no lights, a hard launch at the origin, and one hard brake on the longest straight.
export const SHORT = {
  maxLengthM: 1500,
  speedCapKph: 50, // urban limit
  launchMps2: 3,
  launchM: 40,
  brakeMps2: 4.5,
  brakeAtFraction: 0.6, // of the longest straight, leaving room to get up to speed first
  slowLengthM: 60,
  slowSpeedFraction: 0.3,
  eventOffsetM: 30,
};

// Fixed speed cameras. The limit follows the road class. A camera passed at or below the limit
// tells a prediction nothing, so the driver speeds before some cameras and brakes for them.
export const CAMERAS = {
  urbanLimitKph: 50,
  motorwayLimitKph: 100,
  perKm: 0.3, // long routes: a Poisson process along the route
  speedingShare: 0.5, // of long-route cameras; the rest are passed at or below the limit
  overLimitKph: 15, // target speed over the limit on the stretch before a speeding approach
  speedingM: 250,
  brakeMps2: 3,
  underLimitKph: 5, // speed at the camera after the brake
  holdM: 30, // held past the camera, then normal speed again
  endMarginM: 300,
  minGapM: 100, // to other cameras, caution points, slowdowns, stops and turns
};

// Caution points. Bumps and school zones only on urban roads.
export const CAUTION = {
  bumpPerKm: 0.2, // long routes, one Poisson process for both types
  schoolZonePerKm: 0.05,
  bumpKph: 20,
  bumpBrakeMps2: 2.5,
  bumpCapM: 5, // bump speed held this far either side
  bumpApproachKph: 35, // short routes: a bump only where the car is faster than this before it
  schoolZoneKph: 30,
  schoolZoneM: 300, // from the event atM, which is the zone start
  approachM: 100, // room for the braking before a point
  endMarginM: 300,
  minGapM: 100,
};

export const ROUTE_PX = { tolerancePx: 0.1 };
const FINE_DT_S = 0.02;
const EARTH_M = 6371008.8;
const RAD = Math.PI / 180;

const round = (value, digits) => Number(value.toFixed(digits)) + 0;
const clamp = (value, low, high) => Math.min(high, Math.max(low, value));

// FNV-1a over the id, then mulberry32. Same id, same drive.
export function seededRandom(text) {
  let hash = 0x811c9dc5;
  for (const char of text) {
    hash ^= char.codePointAt(0);
    hash = Math.imul(hash, 0x01000193);
  }
  let state = hash >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const uniform = (random, low, high) => low + random() * (high - low);

// Flattens one OSRM response into vertices and steps with cumulative positions.
export function parseRoute(osrm) {
  const leg = osrm.routes[0].legs[0];
  const { coordinates } = osrm.routes[0].geometry;
  const { distance, speed } = leg.annotation;
  const atVertex = [0];
  distance.forEach((metres, i) => atVertex.push(atVertex[i] + metres));

  let vertex = 0;
  const steps = leg.steps.map((step) => {
    const [lon, lat] = step.maneuver.location;
    const found = coordinates.findIndex(
      (point, i) => i >= vertex && Math.abs(point[0] - lon) < 2e-6 && Math.abs(point[1] - lat) < 2e-6,
    );
    if (found < 0) throw new Error(`maneuver at ${lon},${lat} is not on the geometry`);
    vertex = found;
    return {
      type: step.maneuver.type,
      modifier: step.maneuver.modifier,
      name: step.name ?? '',
      destinations: step.destinations ?? '',
      vertex,
      atM: atVertex[vertex],
      kph: step.duration > 0 ? (step.distance / step.duration) * 3.6 : 0,
    };
  });
  return { coordinates, atVertex, speedMps: speed, steps, lengthM: atVertex[atVertex.length - 1] };
}

// Heading changes along the route, one per grid cell where the polyline bends.
function headingSpikes(route, gridM) {
  const { coordinates, atVertex } = route;
  const midLat = (coordinates.reduce((sum, p) => sum + p[1], 0) / coordinates.length) * RAD;
  const spikes = [];
  let previous = null;
  for (let i = 0; i + 1 < coordinates.length; i++) {
    const dx = (coordinates[i + 1][0] - coordinates[i][0]) * Math.cos(midLat);
    const dy = coordinates[i + 1][1] - coordinates[i][1];
    if (dx === 0 && dy === 0) continue;
    const heading = Math.atan2(dy, dx); // counter-clockwise, so a left turn is positive
    if (previous !== null) {
      const change = Math.atan2(Math.sin(heading - previous), Math.cos(heading - previous));
      spikes.push({ cell: Math.round(atVertex[i] / gridM), change });
    }
    previous = heading;
  }
  return spikes;
}

function gaussian(sigmaCells) {
  const reach = Math.ceil(3 * sigmaCells);
  const weights = [];
  for (let k = -reach; k <= reach; k++) weights.push(Math.exp(-0.5 * (k / sigmaCells) ** 2));
  const total = weights.reduce((sum, w) => sum + w, 0);
  return { reach, weights: weights.map((w) => w / total) };
}

// Each polyline vertex spreads its heading change over a Gaussian whose width follows the speed
// through that vertex. Spreading the change rather than averaging the heading conserves the
// total turn, so timing and amount of steering come from the real corner shape. A sharp junction
// stays tight because the car is slow there; a digitising kink on a motorway disappears.
export function smoothCurvature(route, baseMps, cells, driver = DRIVER) {
  const spikes = headingSpikes(route, driver.gridM);
  const kernels = new Map();
  const kernelFor = (sigmaCells) => {
    if (!kernels.has(sigmaCells)) kernels.set(sigmaCells, gaussian(sigmaCells));
    return kernels.get(sigmaCells);
  };
  const scatter = (sigmaOf) => {
    const out = new Float64Array(cells + 1);
    for (const spike of spikes) {
      const { reach, weights } = kernelFor(Math.round(sigmaOf(spike) / driver.gridM));
      for (let k = -reach; k <= reach; k++) {
        const cell = spike.cell + k;
        if (cell >= 0 && cell <= cells) out[cell] += (spike.change * weights[k + reach]) / driver.gridM;
      }
    }
    return out;
  };

  let curvature = scatter(() => driver.minSmoothingM);
  for (let round_ = 1; round_ < driver.smoothingRounds; round_++) {
    const current = curvature;
    curvature = scatter((spike) => {
      const cell = clamp(spike.cell, 0, cells);
      const speed = Math.min(baseMps[cell], Math.sqrt(driver.lateralMps2 / Math.max(Math.abs(current[cell]), 1e-6)));
      return clamp(driver.previewS * speed, driver.minSmoothingM, driver.maxSmoothingM);
    });
  }

  const maxCurvature = Math.tan((LIMITS.maxSteeringDeg / VEHICLE.steeringRatio) * RAD) / VEHICLE.wheelbaseM;
  const lengthM = cells * driver.gridM;
  return curvature.map((value, cell) => {
    const s = cell * driver.gridM;
    return clamp(value, -maxCurvature, maxCurvature) * clamp(Math.min(s, lengthM - s) / driver.centringM, 0, 1);
  });
}

export const steeringDegFor = (curvature) =>
  Math.atan(VEHICLE.wheelbaseM * curvature) / RAD * VEHICLE.steeringRatio;

const sideOf = (modifier) => {
  if (modifier?.includes('left')) return 'left';
  if (modifier?.includes('right')) return 'right';
  return null;
};

// OSRM to NaviHelper TurnType (navi-data 3.0.4). OSRM has no equivalent of the enum's tunnel, toll
// or service area values, so a merge or fork reads as the keep direction it asks of the driver.
const BY_MODIFIER = {
  left: 'TURN_LEFT',
  right: 'TURN_RIGHT',
  'slight left': 'TURN_KEEP_L',
  'slight right': 'TURN_KEEP_R',
  'sharp left': 'TURN_SHARP_L',
  'sharp right': 'TURN_SHARP_R',
  uturn: 'TURN_UTURN',
  straight: 'TURN_STRAIGHT',
};
const SIDE_LETTER = { left: 'L', right: 'R' };

export function turnType({ type, modifier }) {
  if (type === 'arrive') return 'TURN_ARRIVE';
  if (type === 'on ramp') return `TURN_ENTER_IC_${SIDE_LETTER[sideOf(modifier)] ?? 'C'}`;
  if (type === 'off ramp') return `TURN_EXIT_IC_${SIDE_LETTER[sideOf(modifier)] ?? 'C'}`;
  return BY_MODIFIER[modifier] ?? 'TURN_UNKNOWN';
}

// Steps a navigation app would speak. A pure name change or a plain continue is not a maneuver.
const isAnnounced = ({ type, modifier }) =>
  !['depart', 'new name', 'notification'].includes(type) && !(type === 'continue' && modifier === 'straight');

function turnDescription(step, title) {
  if (step.type === 'arrive') return `${title} 도착`;
  if (step.name) return `${step.name} 방면`;
  if (step.destinations) return `${step.destinations} 방면`;
  return step.type === 'on ramp' || step.type === 'off ramp' ? 'IC 방면' : '경로 안내';
}

function buildTurns(steps, title) {
  const turns = [];
  for (const step of steps.filter(isAnnounced)) {
    const atM = Math.round(step.atM);
    if (turns.length > 0 && atM <= turns[turns.length - 1].atM) continue;
    turns.push({ atM, type: turnType(step), description: turnDescription(step, title) });
  }
  return turns;
}

const isMotorwayApproach = (steps, i) => i > 0 && steps[i - 1].kph >= MOTORWAY_KPH;

// A junction that could have a signal: a real turn off a non-motorway road.
function signalLikeSteps(steps) {
  const turning = ['left', 'right', 'sharp left', 'sharp right', 'uturn'];
  return steps.filter(
    (step, i) => ['turn', 'end of road'].includes(step.type) && turning.includes(step.modifier) && !isMotorwayApproach(steps, i),
  );
}

// Real light states are unknown, so a seeded coin decides each junction. Every candidate draws
// from the PRNG even when skipped, so one rule change cannot reshuffle the rest of the route.
export function placeStops(steps, lengthM, random, stops = STOPS) {
  const out = [];
  for (const step of signalLikeSteps(steps)) {
    const red = random() < stops.probability;
    const waitS = uniform(random, stops.minWaitS, stops.maxWaitS);
    const atM = step.atM - stops.stopLineM;
    const inside = atM >= stops.endMarginM && lengthM - atM >= stops.endMarginM;
    const clear = out.length === 0 || atM - out[out.length - 1].atM >= stops.minSpacingM;
    if (red && inside && clear) out.push({ atM, waitS });
  }
  return out;
}

// Slowdowns form a Poisson process along the route, drawn with no knowledge of the maneuvers.
export function placeSlowdowns(lengthM, freeSpeedAt, random, traffic = TRAFFIC) {
  const ratePerM = (traffic.congestionPerKm + traffic.unannouncedPerKm) / 1000;
  const announcedShare = traffic.congestionPerKm / (traffic.congestionPerKm + traffic.unannouncedPerKm);
  const out = [];
  let s = traffic.endMarginM;
  for (;;) {
    s += -Math.log(1 - random()) / ratePerM;
    const announced = random() < announcedShare;
    const hard = random() < traffic.hardShare;
    const decel = uniform(random, traffic.minHardBrakeMps2, traffic.maxHardBrakeMps2);
    const length = uniform(random, traffic.minLengthM, traffic.maxLengthM);
    const fraction = uniform(random, traffic.minSpeedFraction, traffic.maxSpeedFraction);
    const eventOffsetM = uniform(random, traffic.minEventOffsetM, traffic.maxEventOffsetM);
    if (s >= lengthM - traffic.endMarginM) return out;
    const toM = Math.min(s + length, lengthM - traffic.endMarginM);
    const previous = out[out.length - 1];
    if (previous && s - previous.toM < traffic.minGapM) continue;
    out.push({
      fromM: s,
      toM,
      speedMps: Math.max(traffic.minSpeedMps, freeSpeedAt(s) * fraction),
      announced,
      brakeMps2: announced && hard ? decel : null,
      eventOffsetM,
    });
  }
}

// Announced maneuvers cut the route into straights, longest first, ties to the earliest.
export function straights(steps, lengthM) {
  const marks = [0, ...steps.filter(isAnnounced).map((step) => step.atM), lengthM];
  const out = [];
  for (let i = 1; i < marks.length; i++) out.push({ fromM: marks[i - 1], toM: marks[i] });
  return out.sort((a, b) => b.toM - b.fromM - (a.toM - a.fromM));
}

// The hard brake of a short route, on its longest straight.
export function placeShortBrake(steps, lengthM, freeSpeedAt, short = SHORT, traffic = TRAFFIC) {
  const { fromM, toM } = straights(steps, lengthM)[0];
  const atM = fromM + (toM - fromM) * short.brakeAtFraction;
  return {
    fromM: atM,
    toM: Math.min(atM + short.slowLengthM, toM),
    speedMps: Math.max(traffic.minSpeedMps, freeSpeedAt(atM) * short.slowSpeedFraction),
    announced: true,
    brakeMps2: short.brakeMps2,
    eventOffsetM: short.eventOffsetM,
  };
}

// Road class follows the routed speed of the step the position lies on, as for the approach.
export function speedLimitKphAt(steps, atM, cameras = CAMERAS) {
  let kph = 0;
  for (const step of steps) if (step.atM <= atM) kph = step.kph;
  return kph >= MOTORWAY_KPH ? cameras.motorwayLimitKph : cameras.urbanLimitKph;
}

const clashes = ([fromM, toM], taken, gapM) => taken.some(([a, b]) => fromM < b + gapM && toM > a - gapM);

// A camera's span is the stretch its approach, brake and hold take.
const cameraSpan = (atM, cameras = CAMERAS) => [atM - cameras.speedingM, atM + cameras.holdM];

// A short route plays whole, so its one camera sits on the second longest straight (the longest
// holds the hard brake) and is always approached speeding. It goes as late on the straight as
// leaves room for the hold and a stop from the camera speed before the next maneuver.
export function placeShortCamera(steps, lengthM, cameras = CAMERAS, driver = DRIVER) {
  const straight = straights(steps, lengthM)[1];
  if (!straight) return [];
  const limitKph = speedLimitKphAt(steps, straight.fromM, cameras);
  const slowMps = kphToMps(limitKph - cameras.underLimitKph);
  const atM = straight.toM - cameras.holdM - slowMps ** 2 / (2 * driver.brakeMps2);
  if (atM <= straight.fromM) return [];
  return [{ atM, limitKph, speeding: true, speedFromM: Math.max(straight.fromM, atM - cameras.speedingM) }];
}

// Long routes: a Poisson process of cameras clear of the other events. Every candidate draws the
// same values whether or not it is kept.
export function placeCameras(steps, lengthM, taken, random, cameras = CAMERAS) {
  const ratePerM = cameras.perKm / 1000;
  const out = [];
  let s = cameras.endMarginM;
  for (;;) {
    s += -Math.log(1 - random()) / ratePerM;
    const speeding = random() < cameras.speedingShare;
    if (s >= lengthM - cameras.endMarginM) return out;
    const span = cameraSpan(s, cameras);
    const limitKph = speedLimitKphAt(steps, s, cameras);
    const oneClass = speedLimitKphAt(steps, span[0], cameras) === limitKph;
    if (!oneClass || clashes(span, [...taken, ...out.map((camera) => camera.span)], cameras.minGapM)) continue;
    out.push({ atM: s, limitKph, speeding, speedFromM: span[0], span });
  }
}

// A short route gets one bump in the middle of its third longest straight; whether the car is
// fast enough before it is checked on the speed profile.
export function placeShortBump(steps, lengthM) {
  const straight = straights(steps, lengthM)[2];
  return straight && straight.toM > straight.fromM ? { atM: (straight.fromM + straight.toM) / 2, type: 'SPEED_BUMP' } : null;
}

const cautionSpan = ({ atM, type }, caution = CAUTION) => [
  atM - caution.approachM,
  atM + (type === 'SCHOOL_ZONE' ? caution.schoolZoneM : caution.bumpCapM),
];

// Long routes: bumps and school zones on urban roads only, one Poisson process for both.
export function placeCautionPoints(steps, lengthM, taken, random, caution = CAUTION) {
  const perKm = caution.bumpPerKm + caution.schoolZonePerKm;
  const schoolShare = caution.schoolZonePerKm / perKm;
  const urban = (atM) => speedLimitKphAt(steps, atM) === CAMERAS.urbanLimitKph;
  const out = [];
  let s = caution.endMarginM;
  for (;;) {
    s += -Math.log(1 - random()) / (perKm / 1000);
    const type = random() < schoolShare ? 'SCHOOL_ZONE' : 'SPEED_BUMP';
    if (s >= lengthM - caution.endMarginM) return out;
    const point = { atM: s, type };
    const span = cautionSpan(point, caution);
    if (!urban(span[0]) || !urban(span[1]) || span[1] >= lengthM - caution.endMarginM) continue;
    if (clashes(span, [...taken, ...out.map(cautionSpan)], caution.minGapM)) continue;
    out.push(point);
  }
}

// Fixed cameras carry their limit and no section average. Types are NaviHelper enum names.
const buildCameras = (cameras) =>
  cameras.map((camera, i) => ({
    atM: Math.round(camera.atM),
    id: `cam-${i + 1}`,
    type: 'SPEED_CAMERA_FIX',
    speedLimit: camera.limitKph,
    sectionAvrSpeed: 0,
  }));

const buildCautionPoints = (points) =>
  points.map((point, i) => ({ atM: Math.round(point.atM), id: `cp-${i + 1}`, type: point.type }));

function roadNameAt(steps, atM) {
  let name = '';
  for (const step of steps) if (step.atM <= atM) name = step.name;
  return name;
}

// Shaped like NaviHelper TrafficCongestionInfo: road name, distance, average speed.
function buildFrontEvents(slowdowns, steps) {
  return slowdowns
    .filter((slowdown) => slowdown.announced)
    .map((slowdown) => {
      const road = roadNameAt(steps, slowdown.fromM);
      const lengthM = Math.round((slowdown.toM - slowdown.fromM) / 10) * 10;
      const kph = Math.round(slowdown.speedMps * 3.6);
      return {
        atM: Math.round(slowdown.fromM + slowdown.eventOffsetM),
        type: 'CONGESTION',
        description: `${road || '전방'} 정체 ${lengthM}m, 평균 ${kph}km/h`,
      };
    });
}

// Forward and backward acceleration passes over a target speed on a 1 m grid.
// Cameras and caution points change only the target speed; curvature stays from the base speed.
function speedProfile(
  { cells, baseMps, curvature, stops, slowdowns, cameras = [], cautionPoints = [], launchM = 0 },
  driver = DRIVER,
) {
  const { gridM } = driver;
  const target = new Float64Array(cells + 1);
  const brake = new Float64Array(cells + 1).fill(driver.brakeMps2);
  const cornerCap = new Float64Array(cells + 1);
  for (let i = 0; i <= cells; i++) {
    cornerCap[i] = Math.sqrt(driver.lateralMps2 / Math.max(Math.abs(curvature[i]), 1e-6));
    target[i] = Math.min(baseMps[i], cornerCap[i]);
  }
  const cell = (m) => clamp(Math.round(m / gridM), 0, cells);
  const capOver = (fromM, toM, mps) => {
    for (let i = cell(fromM); i <= cell(toM); i++) target[i] = Math.min(target[i], mps);
  };
  const brakeBefore = (atM, fromMps, toMps, decel) => {
    const reachM = Math.max(0, (fromMps ** 2 - toMps ** 2) / (2 * decel)) + 5;
    for (let i = cell(atM - reachM); i <= cell(atM); i++) brake[i] = Math.max(brake[i], decel);
  };
  for (const camera of cameras) {
    const limitMps = kphToMps(camera.limitKph);
    const holdToM = camera.atM + CAMERAS.holdM;
    if (!camera.speeding) {
      capOver(camera.speedFromM, holdToM, limitMps);
      continue;
    }
    const fastMps = kphToMps(camera.limitKph + CAMERAS.overLimitKph);
    const slowMps = kphToMps(camera.limitKph - CAMERAS.underLimitKph);
    for (let i = cell(camera.speedFromM); i < cell(camera.atM); i++) target[i] = Math.min(fastMps, cornerCap[i]);
    capOver(camera.atM, holdToM, slowMps);
    brakeBefore(camera.atM, fastMps, slowMps, CAMERAS.brakeMps2);
  }
  for (const point of cautionPoints) {
    if (point.type === 'SCHOOL_ZONE') {
      capOver(point.atM, point.atM + CAUTION.schoolZoneM, kphToMps(CAUTION.schoolZoneKph));
      continue;
    }
    const bumpMps = kphToMps(CAUTION.bumpKph);
    const fromM = point.atM - CAUTION.bumpCapM;
    const approachMps = Math.max(...target.subarray(cell(fromM - CAUTION.approachM), cell(fromM) + 1));
    capOver(fromM, point.atM + CAUTION.bumpCapM, bumpMps);
    brakeBefore(fromM, approachMps, bumpMps, CAUTION.bumpBrakeMps2);
  }
  for (const slowdown of slowdowns) {
    const from = Math.round(slowdown.fromM / gridM);
    const to = Math.round(slowdown.toM / gridM);
    for (let i = from; i <= to; i++) target[i] = Math.min(target[i], slowdown.speedMps);
    if (slowdown.brakeMps2) {
      const free = baseMps[from];
      const reachM = Math.max(0, (free ** 2 - slowdown.speedMps ** 2) / (2 * slowdown.brakeMps2)) + 5;
      const start = Math.max(0, from - Math.round(reachM / gridM));
      for (let i = start; i <= from; i++) brake[i] = slowdown.brakeMps2;
    }
  }
  const stopCells = new Map(stops.map((stop) => [Math.round(stop.atM / gridM), stop.waitS]));
  for (const cell of stopCells.keys()) target[cell] = 0;
  target[0] = 0;
  target[cells] = 0;

  const squared = target.map((v) => v * v);
  for (let i = cells - 1; i >= 0; i--) squared[i] = Math.min(squared[i], squared[i + 1] + 2 * brake[i] * gridM);
  for (let i = 1; i <= cells; i++) {
    const accel = i * gridM <= launchM ? SHORT.launchMps2 : driver.accelMps2;
    squared[i] = Math.min(squared[i], squared[i - 1] + 2 * accel * gridM);
  }
  return { speed: squared.map(Math.sqrt), stopCells };
}

// Distance grid to time: within one cell the acceleration is constant, so speed is linear in time.
function speedOverTime({ speed, stopCells }, driver = DRIVER) {
  const times = [0, driver.holdS];
  const speeds = [0, 0];
  for (let i = 1; i < speed.length; i++) {
    const last = times[times.length - 1];
    times.push(last + (2 * driver.gridM) / (speed[i - 1] + speed[i]));
    speeds.push(speed[i]);
    const waitS = stopCells.get(i);
    if (waitS) {
      times.push(times[times.length - 1] + waitS);
      speeds.push(0);
    }
  }
  const endT = times[times.length - 1] + driver.holdS;
  times.push(endT);
  speeds.push(0);

  const fine = new Float64Array(Math.ceil(endT / FINE_DT_S) + 1);
  let node = 0;
  for (let k = 0; k < fine.length; k++) {
    const t = k * FINE_DT_S;
    while (node + 2 < times.length && times[node + 1] <= t) node++;
    const span = times[node + 1] - times[node];
    const f = span > 0 ? clamp((t - times[node]) / span, 0, 1) : 1;
    fine[k] = speeds[node] + (speeds[node + 1] - speeds[node]) * f;
  }
  return boxAverage(fine, Math.round(driver.jerkWindowS / FINE_DT_S / 2));
}

// Symmetric, so distance to every stop is preserved exactly. Acceleration becomes piecewise
// linear, which is the jerk limit: at most (a2 - a1) / window.
function boxAverage(values, half) {
  const prefix = new Float64Array(values.length + 1);
  values.forEach((value, i) => {
    prefix[i + 1] = prefix[i] + value;
  });
  const span = 2 * half + 1;
  return values.map((_, i) => (prefix[Math.min(values.length, i + half + 1)] - prefix[Math.max(0, i - half)]) / span);
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

// Distance-based driver convention, so time lead follows from speed rather than being set.
function signalSpans(steps, steering, driver = DRIVER, signal = SIGNAL) {
  const spans = [];
  steps.forEach((step, i) => {
    const side = sideOf(step.modifier);
    if (!side || step.type === 'arrive' || !isAnnounced(step)) return;
    const leadM = isMotorwayApproach(steps, i) ? signal.motorwayLeadM : signal.urbanLeadM;
    let toM = step.atM + signal.minAfterM;
    const limit = step.atM + signal.maxAfterM;
    while (toM < limit) {
      const cell = Math.round(toM / driver.gridM);
      if (cell >= steering.length || Math.abs(steering[cell]) < signal.offBelowDeg) break;
      toM += driver.gridM;
    }
    spans.push({ fromM: step.atM - leadM, toM, mode: side === 'left' ? 'BlinkLeft' : 'BlinkRight' });
  });
  return spans;
}

function signalAt(spans, s) {
  // The later maneuver wins where two overlap: the driver is already setting up for it.
  for (let i = spans.length - 1; i >= 0; i--) if (s >= spans[i].fromM && s <= spans[i].toM) return spans[i].mode;
  return 'Off';
}

const gaussianSample = (random) => Math.sqrt(-2 * Math.log(1 - random())) * Math.cos(2 * Math.PI * random());

// The driver steers like a person rather than tracing the ideal steering: each corner is wound
// in by a slightly wrong amount, a wandering disturbance pushes the car off the line, and every
// so often the driver judges the offset and heading against the ideal path and steers back.
// Position along the route still comes from speed alone, so none of this moves the car on the map.
export function holdWheel({ fineSpeed, steeringByCell, random }, driver = DRIVER, hands = HANDS) {
  const wheels = new Float64Array(fineSpeed.length);
  const lastCell = steeringByCell.length - 1;
  const maxDelta = driver.steeringRateDegS * FINE_DT_S;
  const correctionDelta = hands.correctionRateDegS * FINE_DT_S;
  const disturbanceStep = hands.disturbancePerM * Math.sqrt((2 * FINE_DT_S) / hands.disturbanceTauS);
  const headingDeadband = hands.headingDeadbandDeg * RAD;
  let travelled = 0;
  let gain = 1;
  let cornering = false;
  let correction = 0;
  let correctionTarget = 0;
  let wheel = 0;
  let offsetM = 0; // left of the ideal path is positive
  let headingRad = 0; // relative to the ideal path, left positive
  let disturbance = 0;
  let nextCheckS = uniform(random, hands.checkMinS, hands.checkMaxS);
  for (let k = 0; k < fineSpeed.length; k++) {
    if (k > 0) travelled += ((fineSpeed[k - 1] + fineSpeed[k]) / 2) * FINE_DT_S;
    const speed = fineSpeed[k];
    const position = Math.min(travelled / driver.gridM, lastCell);
    const low = Math.floor(position);
    const high = Math.min(low + 1, lastCell);
    const ideal = steeringByCell[low] + (steeringByCell[high] - steeringByCell[low]) * (position - low);
    const enters = Math.abs(ideal) >= hands.cornerFromDeg;
    if (enters && !cornering) gain = uniform(random, hands.minGain, hands.maxGain);
    cornering = enters;
    const nudge = steeringDegFor(hands.correctionLateralMps2 / Math.max(speed, 1) ** 2);
    const limit = Math.min(Math.max(hands.maxCorrectionDeg, hands.maxCorrectionShare * Math.abs(ideal)), nudge);

    const t = k * FINE_DT_S;
    if (t >= nextCheckS) {
      nextCheckS = t + uniform(random, hands.checkMinS, hands.checkMaxS);
      const off = Math.abs(offsetM) > hands.offsetDeadbandM || Math.abs(headingRad) > headingDeadband;
      if (speed > hands.minSpeedMps && off) {
        const previewM = Math.max(speed * hands.previewS, hands.minPreviewM);
        // The road's curvature plus what closes offset and heading over the preview, a critically
        // damped path back to the line.
        const back = curvatureOf(ideal) - (2 * offsetM) / previewM ** 2 - (2 * headingRad) / previewM;
        correctionTarget = steeringDegFor(back) - ideal * gain;
      } else {
        correctionTarget = 0;
      }
    }
    if (speed <= hands.minSpeedMps) correctionTarget = 0;
    correction = clamp(correctionTarget, correction - correctionDelta, correction + correctionDelta);
    correction = clamp(correction, -limit, limit);
    // A correction never makes or breaks a corner: the wheel stays on the ideal's side of the
    // label threshold, so corners and labels are the road's, not the driver's.
    const edge = hands.cornerFromDeg;
    let wanted = ideal * gain + correction;
    if (Math.abs(ideal) < edge) wanted = clamp(wanted, hands.marginDeg - edge, edge - hands.marginDeg);
    else wanted = Math.sign(ideal) * Math.max(Math.sign(ideal) * wanted, edge + hands.marginDeg);
    wheel = clamp(wanted, wheel - maxDelta, wheel + maxDelta);
    wheel = clamp(wheel, -LIMITS.maxSteeringDeg, LIMITS.maxSteeringDeg);
    wheels[k] = wheel;

    disturbance += -disturbance * (FINE_DT_S / hands.disturbanceTauS) + disturbanceStep * gaussianSample(random);
    headingRad += speed * (curvatureOf(wheel) + disturbance - curvatureOf(ideal)) * FINE_DT_S;
    offsetM += speed * Math.sin(headingRad) * FINE_DT_S;
  }
  return wheels;
}

function buildTimeline({ fineSpeed, wheels, spans }) {
  const everyFine = Math.round(STEP_S / FINE_DT_S);
  const rows = [];
  let travelled = 0;
  const rowCount = Math.floor((fineSpeed.length - 1) / everyFine) + 1;
  for (let row = 0; row < rowCount; row++) {
    const k = row * everyFine;
    if (row > 0) {
      // Trapezoid over the fine samples of this row.
      for (let j = k - everyFine; j < k; j++) travelled += ((fineSpeed[j] + fineSpeed[j + 1]) / 2) * FINE_DT_S;
    }
    const wheel = wheels[k];
    const speed = fineSpeed[k];
    const nextK = k + everyFine;
    const accel = nextK < fineSpeed.length ? (fineSpeed[nextK] - speed) / STEP_S : 0;
    const speedKph = round(speed * 3.6, 2);
    rows.push({
      t: round(row * STEP_S, 1),
      speedKph,
      steeringDeg: round(wheel, 1),
      ...pedals(speedKph, accel),
      turnSignal: signalAt(spans, travelled),
    });
  }
  const last = rows[rows.length - 1];
  last.speedKph = 0;
  last.steeringDeg = 0;
  return rows;
}

// Douglas-Peucker in metres. `keep` indices survive whatever the tolerance.
function simplifiedIndices(points, keep, toleranceM) {
  const marked = new Uint8Array(points.length);
  const anchors = [...new Set([0, points.length - 1, ...keep])].sort((a, b) => a - b);
  for (const i of anchors) marked[i] = 1;
  const stack = [];
  for (let i = 0; i + 1 < anchors.length; i++) stack.push([anchors[i], anchors[i + 1]]);
  while (stack.length > 0) {
    const [from, to] = stack.pop();
    const [ax, ay] = points[from];
    const [bx, by] = points[to];
    const length2 = (bx - ax) ** 2 + (by - ay) ** 2;
    let worst = -1;
    let worstAt = -1;
    for (let i = from + 1; i < to; i++) {
      const [px, py] = points[i];
      const u = length2 > 0 ? clamp(((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / length2, 0, 1) : 0;
      const gap = Math.hypot(px - ax - u * (bx - ax), py - ay - u * (by - ay));
      if (gap > worst) {
        worst = gap;
        worstAt = i;
      }
    }
    if (worst > toleranceM) {
      marked[worstAt] = 1;
      stack.push([from, worstAt], [worstAt, to]);
    }
  }
  return points.flatMap((_, i) => (marked[i] ? [i] : []));
}

// Kept vertices include every maneuver, so a turn lands on a corner of the drawn line. Cutting a
// curve shortens the line unevenly, which moves distance fractions, so the tolerance is well
// under a pixel and only grows if the point limit forces it.
export function buildRoutePx(route) {
  const { coordinates, steps, lengthM } = route;
  const midLat = coordinates[0][1] * RAD;
  const metres = coordinates.map(([lon, lat]) => [lon * RAD * Math.cos(midLat) * EARTH_M, lat * RAD * EARTH_M]);
  const keep = steps.filter(isAnnounced).map((step) => step.vertex);
  const { toPx } = fitRoute(coordinates);

  const metresPerPx = lengthM / polylineLength(coordinates.map(toPx));
  let low = ROUTE_PX.tolerancePx * metresPerPx;
  let indices = simplifiedIndices(metres, keep, low);
  if (indices.length > LIMITS.maxRoutePxPoints) {
    let high = low * 2;
    while (simplifiedIndices(metres, keep, high).length > LIMITS.maxRoutePxPoints) high *= 2;
    for (let i = 0; i < 24; i++) {
      const middle = (low + high) / 2;
      if (simplifiedIndices(metres, keep, middle).length > LIMITS.maxRoutePxPoints) low = middle;
      else high = middle;
    }
    indices = simplifiedIndices(metres, keep, high);
  }
  const pixels = indices.map((i) => toPx(coordinates[i]));
  return pixels.filter((p, i) => i === 0 || p[0] !== pixels[i - 1][0] || p[1] !== pixels[i - 1][1]);
}

function accelerationRuns(timeline) {
  const accel = accelerations(timeline);
  const out = [];
  let start = -1;
  accel.forEach((a, i) => {
    const on = a >= LABEL.longitudinalMps2;
    if (on && start < 0) start = i;
    if (!on && start >= 0) {
      if ((i - start) * STEP_S >= DEMO.minAccelS) out.push(start);
      start = -1;
    }
  });
  return out;
}

function distinctCorners(timeline) {
  const out = [];
  for (const corner of detectManeuvers(timeline).corners) {
    const previous = out[out.length - 1];
    const gapS = previous ? (corner.startI - previous.endI) * STEP_S : Infinity;
    if (previous && previous.direction === corner.direction && gapS < DEMO.cornerMergeS) previous.endI = corner.endI;
    else out.push({ ...corner });
  }
  return out.map((corner) => corner.startI);
}

// Same rule on every route: a window of DEMO.windowS that opens with the car stopped and holds
// the most corners, hard brakes and accelerations. Ties go to the earliest.
export function chooseDemoWindow(timeline) {
  if (timeline.at(-1).t <= DEMO.windowS) return { fromSec: 0, toSec: timeline.at(-1).t };
  const span = Math.round(DEMO.windowS / STEP_S);
  const events = [
    ...distinctCorners(timeline),
    ...detectManeuvers(timeline).hardBrakes.map((brake) => brake.startI),
    ...accelerationRuns(timeline),
  ];
  const lead = Math.round(DEMO.leadBeforeGoS / STEP_S);
  let best = null;
  for (let i = 0; i + 1 < timeline.length; i++) {
    const pullsAway = timeline[i].speedKph === 0 && timeline[i + 1].speedKph > 0;
    if (!pullsAway) continue;
    let from = i;
    while (from > 0 && i - from < lead && timeline[from - 1].speedKph === 0) from--;
    if (from + span >= timeline.length) continue;
    const count = events.filter((index) => index >= from && index < from + span).length;
    if (!best || count > best.count) best = { from, count };
  }
  if (!best) return null;
  return { fromSec: timeline[best.from].t, toSec: timeline[best.from + span].t };
}

export function buildScenario({ id, title }, osrm) {
  const route = parseRoute(osrm);
  const { width, height } = fitRoute(route.coordinates);
  const { gridM } = DRIVER;
  const cells = Math.round(route.lengthM / gridM);
  const short = route.lengthM <= SHORT.maxLengthM;
  const speedCapKph = short ? SHORT.speedCapKph : LIMITS.maxSpeedKph;

  const baseMps = new Float64Array(cells + 1);
  let segment = 0;
  for (let cell = 0; cell <= cells; cell++) {
    const s = cell * gridM;
    while (segment + 1 < route.speedMps.length && route.atVertex[segment + 1] <= s) segment++;
    baseMps[cell] = Math.min(route.speedMps[segment], kphToMps(speedCapKph));
  }

  const curvature = smoothCurvature(route, baseMps, cells);
  const steeringByCell = Array.from(curvature, steeringDegFor);
  const freeSpeedAt = (s) => baseMps[clamp(Math.round(s / gridM), 0, cells)];
  const stops = short ? [] : placeStops(route.steps, route.lengthM, seededRandom(`${id}:stops`));
  const slowdowns = short
    ? [placeShortBrake(route.steps, route.lengthM, freeSpeedAt)]
    : placeSlowdowns(route.lengthM, freeSpeedAt, seededRandom(`${id}:traffic`));
  const launchM = short ? SHORT.launchM : 0;

  let cameras;
  let cautionPoints;
  if (short) {
    cameras = placeShortCamera(route.steps, route.lengthM);
    const bump = placeShortBump(route.steps, route.lengthM);
    cautionPoints = bump ? [bump] : [];
    if (bump) {
      const { speed } = speedProfile({ cells, baseMps, curvature, stops, slowdowns, cameras, cautionPoints, launchM });
      const from = clamp(Math.round((bump.atM - CAUTION.approachM) / gridM), 0, cells);
      const approachMps = Math.max(...speed.slice(from, Math.round(bump.atM / gridM) + 1));
      if (approachMps < kphToMps(CAUTION.bumpApproachKph)) cautionPoints = [];
    }
  } else {
    const taken = [
      ...stops.map((stop) => [stop.atM, stop.atM]),
      ...route.steps.filter(isAnnounced).map((step) => [step.atM, step.atM]),
      ...slowdowns.map((slowdown) => [slowdown.fromM, slowdown.toM]),
    ];
    cameras = placeCameras(route.steps, route.lengthM, taken, seededRandom(`${id}:cameras`));
    taken.push(...cameras.map((camera) => camera.span));
    cautionPoints = placeCautionPoints(route.steps, route.lengthM, taken, seededRandom(`${id}:caution`));
  }

  const profile = speedProfile({ cells, baseMps, curvature, stops, slowdowns, cameras, cautionPoints, launchM });
  const fineSpeed = speedOverTime(profile);
  const spans = signalSpans(route.steps, steeringByCell);
  const wheels = holdWheel({ fineSpeed, steeringByCell, random: seededRandom(`${id}:steering`) });
  const timeline = buildTimeline({ fineSpeed, wheels, spans });

  return {
    id,
    title,
    mapImage: `maps/${id}.webp`,
    mapSize: [width, height],
    originRoad: osrm.routes[0].legs[0].steps.find((step) => step.name).name,
    demoWindow: chooseDemoWindow(timeline),
    routePx: buildRoutePx(route),
    routeLengthM: Math.round(route.lengthM),
    turns: buildTurns(route.steps, title),
    frontEvents: buildFrontEvents(slowdowns, route.steps),
    cameras: buildCameras(cameras),
    cautionPoints: buildCautionPoints(cautionPoints),
    timeline,
  };
}

export const routePath = (entry) => resolve(HERE, 'routes', entry.file);
export const outputPath = (id) => resolve(REPO_ROOT, 'app/src/main/assets/scenarios', `${id}.json`);

export function generate(entry) {
  return buildScenario(entry, JSON.parse(readFileSync(routePath(entry), 'utf8')));
}

// Standard JSON with one line per array element and no indent: real-time timelines hold up to
// 25000 rows, and the app parser reads named row keys, so this is as small as the format allows.
export function formatScenario(scenario) {
  const { routePx, turns, frontEvents, cameras, cautionPoints, timeline, ...head } = scenario;
  const list = (items) => (items.length === 0 ? '[]' : `[\n${items.map((item) => JSON.stringify(item)).join(',\n')}\n]`);
  const headLines = Object.entries(head).map(([key, value]) => `  ${JSON.stringify(key)}: ${JSON.stringify(value)}`);
  const body = [
    ...headLines,
    `  "routePx": ${JSON.stringify(routePx)}`,
    `  "turns": ${list(turns)}`,
    `  "frontEvents": ${list(frontEvents)}`,
    `  "cameras": ${list(cameras)}`,
    `  "cautionPoints": ${list(cautionPoints)}`,
    `  "timeline": ${list(timeline)}`,
  ];
  return `{\n${body.join(',\n')}\n}\n`;
}

function main(ids) {
  const selected = ids.length > 0 ? ROUTES.filter((entry) => ids.includes(entry.id)) : ROUTES;
  for (const entry of selected) {
    const scenario = generate(entry);
    const path = outputPath(entry.id);
    const report = validateScenario(scenario);
    console.log(entry.id);
    console.log(formatReport(report));
    if (report.errors.length > 0) {
      console.error(`not written: ${path}`);
      process.exitCode = 1;
      continue;
    }
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, formatScenario(scenario));
    console.log(`wrote ${path}`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2));
}
