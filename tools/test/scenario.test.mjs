import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  CAMERA_TYPES,
  CAUTION_POINT_TYPES,
  LABEL,
  STEP_S,
  TURN_TYPES,
  accelerations,
  detectManeuvers,
  distances,
} from '../scenario/common.mjs';
import {
  CAMERAS,
  CAUTION,
  DEMO,
  DRIVER,
  HANDS,
  ROUTES,
  SHORT,
  STOPS,
  TRAFFIC,
  chooseDemoWindow,
  drivenPath,
  formatScenario,
  generate,
  holdWheel,
  leaveAtM,
  outputPath,
  parseRoute,
  placeCameras,
  placeCautionPoints,
  placeSlowdowns,
  placeStops,
  routePath,
  seededRandom,
  steeringDegFor,
  turnType,
} from '../scenario/from-route.mjs';
import { fitRoute } from '../scenario/projection.mjs';
import { validateScenario } from '../scenario/validate.mjs';

const built = new Map(ROUTES.map((entry) => [entry.id, generate(entry)]));
const scenarios = [...built.values()];
const fresh = () => structuredClone(built.get('gangnam-pangyo'));
const errorsOf = (scenario) => validateScenario(scenario).errors;
const readRoute = (path) => parseRoute(JSON.parse(readFileSync(path, 'utf8')));
const realRoute = (entry) => readRoute(drivenPath(entry));
const plannedRoute = (entry) => readRoute(routePath(entry));
const offRoute = ROUTES.find((entry) => entry.drivenFile);

function expectError(name, mutate, pattern, source = fresh) {
  test(`rejects ${name}`, () => {
    const scenario = source();
    mutate(scenario);
    const errors = errorsOf(scenario);
    assert.ok(errors.some((error) => pattern.test(error)), `no ${pattern} in:\n${errors.slice(0, 5).join('\n')}`);
  });
}

for (const entry of ROUTES) {
  const scenario = built.get(entry.id);

  test(`${entry.id}: passes validation`, () => {
    assert.deepEqual(errorsOf(scenario), []);
  });

  test(`${entry.id}: file on disk is the current generator output`, () => {
    assert.equal(readFileSync(outputPath(entry.id), 'utf8'), formatScenario(scenario));
  });

  test(`${entry.id}: generation is deterministic`, () => {
    assert.deepEqual(generate(entry), scenario);
  });

  test(`${entry.id}: keeps the real length and the title`, () => {
    const route = realRoute(entry);
    assert.equal(scenario.title, entry.title);
    assert.equal(scenario.mapImage, `maps/${entry.id}.webp`);
    assert.equal(scenario.routeLengthM, Math.round(route.lengthM));
    const total = distances(scenario.timeline).at(-1);
    assert.ok(Math.abs(total - route.lengthM) / route.lengthM <= 0.01);
  });

  test(`${entry.id}: stays parseable and adds only demoWindow, seats, cameras, caution points and a planned route`, () => {
    const parsed = JSON.parse(formatScenario(scenario));
    const keys = [
      'cameras', 'cautionPoints', 'demoWindow', 'frontEvents', 'id', 'mapImage', 'mapSize', 'occupiedSeats', 'originRoad',
      'routeLengthM', 'routePx', 'timeline', 'title', 'turns',
    ];
    if (entry.drivenFile) keys.push('plannedRoute');
    assert.deepEqual(Object.keys(parsed).sort(), keys.sort());
    assert.deepEqual(parsed.plannedRoute, scenario.plannedRoute);
    assert.deepEqual(parsed.occupiedSeats, ['driver', 'passenger', 'rear_left']);
    assert.deepEqual(parsed.cameras, scenario.cameras);
    assert.deepEqual(parsed.cautionPoints, scenario.cautionPoints);
    assert.ok(parsed.routePx.every((point) => point.every(Number.isInteger)));
  });

  test(`${entry.id}: cameras and caution points use NaviHelper types and unique ids`, () => {
    assert.ok(scenario.cameras.every((camera) => CAMERA_TYPES.includes(camera.type)));
    assert.ok(scenario.cautionPoints.every((point) => CAUTION_POINT_TYPES.includes(point.type)));
    for (const list of [scenario.cameras, scenario.cautionPoints]) {
      assert.equal(new Set(list.map((item) => item.id)).size, list.length);
    }
  });

  test(`${entry.id}: every bump is passed at 25 km/h or less`, () => {
    const travelled = distances(scenario.timeline);
    for (const point of scenario.cautionPoints.filter((item) => item.type === 'SPEED_BUMP')) {
      const i = travelled.findIndex((metres) => metres >= point.atM);
      assert.ok(scenario.timeline[i].speedKph <= 25, `${point.id}: ${scenario.timeline[i].speedKph}`);
    }
  });

  if (scenario.routeLengthM <= SHORT.maxLengthM) {
    test(`${entry.id}: one camera, approached speeding and passed at or below the limit`, () => {
      assert.equal(scenario.cameras.length, 1);
      const [camera] = scenario.cameras;
      const travelled = distances(scenario.timeline);
      const from = travelled.findIndex((metres) => metres >= camera.atM - 300);
      const at = travelled.findIndex((metres) => metres >= camera.atM);
      const peak = Math.max(...scenario.timeline.slice(from, at + 1).map((row) => row.speedKph));
      assert.ok(peak >= camera.speedLimit + 10, `peak ${peak}`);
      assert.ok(scenario.timeline[at].speedKph <= camera.speedLimit, `at camera ${scenario.timeline[at].speedKph}`);
    });
  }

  test(`${entry.id}: polyline fractions follow true distance`, () => {
    const lengths = [0];
    for (let i = 1; i < scenario.routePx.length; i++) {
      const [a, b] = [scenario.routePx[i - 1], scenario.routePx[i]];
      lengths.push(lengths[i - 1] + Math.hypot(b[0] - a[0], b[1] - a[1]));
    }
    const fractions = lengths.map((length) => length / lengths.at(-1));
    for (const turn of scenario.turns) {
      const wanted = turn.atM / scenario.routeLengthM;
      assert.ok(Math.min(...fractions.map((f) => Math.abs(f - wanted))) < 0.005, turn.description);
    }
  });

  test(`${entry.id}: only documented NaviHelper types and CONGESTION appear`, () => {
    assert.ok(scenario.turns.every((turn) => TURN_TYPES.includes(turn.type)));
    assert.ok(scenario.frontEvents.every((event) => event.type === 'CONGESTION'));
  });

  test(`${entry.id}: stops last 10 to 40 s plus the origin and destination holds`, () => {
    const { timeline } = scenario;
    let run = 0;
    const runs = [];
    timeline.forEach((row, i) => {
      if (row.speedKph === 0) run++;
      if ((row.speedKph !== 0 || i === timeline.length - 1) && run > 0) {
        runs.push(run * STEP_S);
        run = 0;
      }
    });
    for (const seconds of runs.slice(1, -1)) assert.ok(seconds >= STOPS.minWaitS - 1 && seconds <= STOPS.maxWaitS + 1.5, String(seconds));
  });

  test(`${entry.id}: demo window opens stopped, spans 150 s or the whole route, and is the same rule's best`, () => {
    const { fromSec, toSec } = scenario.demoWindow;
    assert.equal(scenario.timeline[Math.round(fromSec / STEP_S)].speedKph, 0);
    assert.equal(toSec - fromSec, Math.min(DEMO.windowS, scenario.timeline.at(-1).t));
    assert.deepEqual(chooseDemoWindow(scenario.timeline), scenario.demoWindow);
  });

  test(`${entry.id}: every hard brake sits in the scripted range`, () => {
    const accel = accelerations(scenario.timeline);
    const peakAccel = scenario.routeLengthM <= SHORT.maxLengthM ? SHORT.launchMps2 : DRIVER.accelMps2;
    assert.ok(Math.min(...accel) >= -TRAFFIC.maxHardBrakeMps2 - 0.05);
    assert.ok(Math.max(...accel) <= peakAccel + 0.05);
  });

  if (entry.id !== 'gangnam-pangyo') {
    // A driven route that leaves the guided one has only the corners its own turns make.
    const sides = entry.drivenFile
      ? [...new Set(scenario.turns.map((turn) => (/_L$|LEFT/.test(turn.type) ? 'left' : /_R$|RIGHT/.test(turn.type) ? 'right' : null)).filter(Boolean))]
      : ['left', 'right'];
    test(`${entry.id}: holds a corner each way it turns (${sides.join(', ')}), a hard brake and a hard launch within 2 minutes`, () => {
      const { corners, hardAccels, hardBrakes } = detectManeuvers(scenario.timeline);
      assert.ok(scenario.timeline.at(-1).t <= 120);
      for (const side of sides) assert.ok(corners.some((corner) => corner.direction === side), side);
      assert.equal(hardBrakes.length, 1);
      assert.equal(hardAccels.length, 1);
    });
  }
}

// 60 s at a steady speed over a straight road, 0.02 s samples.
const steadyDrive = (speedMps) => new Float64Array(3000).fill(speedMps);
const straightRoad = (speedMps) => new Array(Math.ceil(60 * speedMps) + 1).fill(0);

test('on a straight road the driver nudges the wheel, gentler at speed', () => {
  for (const speedMps of [8, 14, 25]) {
    const wheels = holdWheel({ fineSpeed: steadyDrive(speedMps), steeringByCell: straightRoad(speedMps), random: seededRandom('straight') });
    const peak = Math.max(...wheels.map(Math.abs));
    const nudge = steeringDegFor(HANDS.correctionLateralMps2 / speedMps ** 2);
    assert.ok(peak > 0.5, `${speedMps} m/s: the wheel never moves`);
    assert.ok(peak <= Math.min(HANDS.maxCorrectionDeg, nudge) + 1e-9, `${speedMps} m/s: ${peak} degrees`);
  }
});

test('a correction never makes or breaks a corner', () => {
  const speedMps = 6;
  const road = straightRoad(speedMps).map((_, cell) => 200 * Math.exp(-0.5 * ((cell - 180) / 12) ** 2));
  const wheels = holdWheel({ fineSpeed: steadyDrive(speedMps), steeringByCell: road, random: seededRandom('corner') });
  let differs = false;
  wheels.forEach((wheel, k) => {
    const ideal = road[Math.min(Math.round((k * 0.02 * speedMps) / DRIVER.gridM), road.length - 1)];
    if (Math.abs(wheel - ideal) > 1) differs = true;
    if (Math.abs(ideal) < LABEL.lateralDeg - 1) assert.ok(Math.abs(wheel) < LABEL.lateralDeg, `k=${k}: ${wheel}`);
    if (Math.abs(ideal) > LABEL.lateralDeg + 1) assert.ok(wheel > LABEL.lateralDeg, `k=${k}: ${wheel}`);
  });
  assert.ok(differs, 'the wheel traces the ideal steering exactly');
});

test('routePx starts and ends on the route ends', () => {
  const entry = ROUTES.find(({ id }) => id === 'pangyo-dongpangyo');
  const route = realRoute(entry);
  const { toPx } = fitRoute(route.coordinates);
  const { routePx } = built.get(entry.id);
  assert.deepEqual(routePx[0], toPx(route.coordinates[0]));
  assert.deepEqual(routePx.at(-1), toPx(route.coordinates.at(-1)));
});

// Two routes along one straight east from the origin; planned turns north at 200 m.
const RAD = Math.PI / 180;
const toLon = (metres, lat) => 127 + metres / (6371008.8 * Math.cos(lat * RAD) * RAD);
const toLat = (metres) => 37.5 + metres / (6371008.8 * RAD);
const synthetic = (points) => {
  const atVertex = [0];
  for (let i = 1; i < points.length; i++) atVertex.push(atVertex[i - 1] + Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]));
  return { coordinates: points.map(([x, y]) => [toLon(x, 37.5), toLat(y)]), atVertex, lengthM: atVertex.at(-1) };
};

test('leaveAtM is the length of the shared stretch', () => {
  const planned = synthetic([[0, 0], [100, 0], [200, 0], [200, 300]]);
  const driven = synthetic([[0, 0], [100, 0], [450, 0], [450, 300]]);
  assert.ok(Math.abs(leaveAtM(driven, planned) - 200) <= 0.5, String(leaveAtM(driven, planned)));
  const early = synthetic([[0, 0], [60, 0], [60, -400]]);
  assert.ok(Math.abs(leaveAtM(early, planned) - 60) <= 0.5);
  assert.equal(leaveAtM(planned, planned), null);
});

test('the off-route scenario leaves at the turn it misses, on the guided route\'s map', () => {
  const scenario = built.get(offRoute.id);
  const planned = plannedRoute(offRoute);
  const { toPx, width, height } = fitRoute(planned.coordinates);
  const { plannedRoute: guided } = scenario;
  assert.deepEqual(scenario.mapSize, [width, height]);
  assert.deepEqual(guided.routePx[0], toPx(planned.coordinates[0]));
  assert.deepEqual(guided.routePx.at(-1), toPx(planned.coordinates.at(-1)));
  assert.deepEqual(scenario.routePx[0], guided.routePx[0]);
  assert.equal(guided.routeLengthM, Math.round(planned.lengthM));
  assert.equal(guided.turns.at(-1).description, `${offRoute.title} 도착`);
  const missed = guided.turns.find((turn) => turn.type !== 'TURN_ARRIVE');
  assert.ok(Math.abs(guided.leaveAtM - missed.atM) <= 2, `${guided.leaveAtM} vs ${missed.atM}`);
  assert.ok(Math.abs(guided.leaveAtM - leaveAtM(realRoute(offRoute), planned)) < 1e-9);
});

test('the generator holds no per-route parameter', () => {
  const source = readFileSync(new URL('../scenario/from-route.mjs', import.meta.url), 'utf8');
  const code = source.slice(source.indexOf('export const DRIVER'));
  for (const { id, title } of ROUTES) {
    assert.ok(!code.includes(id), id);
    assert.ok(!code.includes(title), title);
  }
});

test('invented front event types are gone', () => {
  for (const scenario of scenarios) {
    assert.deepEqual([...new Set(scenario.frontEvents.map((event) => event.type))].filter((type) => type !== 'CONGESTION'), []);
  }
});

test('different ids draw different traffic, the same id the same', () => {
  const free = () => 20;
  const draw = (seed) => placeSlowdowns(50000, free, seededRandom(seed));
  assert.deepEqual(draw('a:traffic'), draw('a:traffic'));
  assert.notDeepEqual(draw('a:traffic'), draw('b:traffic'));
});

test('slowdown rate matches the constants over a long distance', () => {
  const lengthM = 4_000_000;
  const placed = placeSlowdowns(lengthM, () => 20, seededRandom('rate'));
  const rate = placed.length / (lengthM / 1000);
  assert.ok(rate > 0.2 && rate <= TRAFFIC.congestionPerKm + TRAFFIC.unannouncedPerKm, String(rate));
  assert.ok(placed.every((slowdown) => slowdown.announced || slowdown.brakeMps2 === null), 'hard brake without an event');
});

test('stops happen only at turns off non-motorway roads', () => {
  const steps = [
    { type: 'depart', atM: 0, kph: 50 },
    { type: 'turn', modifier: 'right', atM: 500, kph: 50 },
    { type: 'turn', modifier: 'left', atM: 1000, kph: 90 },
    { type: 'turn', modifier: 'left', atM: 2000, kph: 50 },
    { type: 'fork', modifier: 'slight left', atM: 3000, kph: 50 },
    { type: 'turn', modifier: 'slight right', atM: 4000, kph: 50 },
    { type: 'arrive', atM: 5000, kph: 0 },
  ];
  const always = placeStops(steps, 5000, () => 0, { ...STOPS });
  // The left at 2000 m follows a 90 km/h step, so only the right at 500 m and left at 1000 m remain.
  assert.deepEqual(always.map((stop) => stop.atM), [485, 985]);
});

test('OSRM maneuvers map to NaviHelper values', () => {
  const type = (t, modifier) => turnType({ type: t, modifier });
  assert.equal(type('turn', 'left'), 'TURN_LEFT');
  assert.equal(type('turn', 'sharp right'), 'TURN_SHARP_R');
  assert.equal(type('fork', 'slight left'), 'TURN_KEEP_L');
  assert.equal(type('merge', 'slight left'), 'TURN_KEEP_L');
  assert.equal(type('on ramp', 'straight'), 'TURN_ENTER_IC_C');
  assert.equal(type('off ramp', 'slight right'), 'TURN_EXIT_IC_R');
  assert.equal(type('arrive', 'right'), 'TURN_ARRIVE');
  assert.equal(type('end of road', 'left'), 'TURN_LEFT');
  assert.equal(type('turn', undefined), 'TURN_UNKNOWN');
});

test('the urban turn signal starts about 30 m before a left or right turn', () => {
  const scenario = fresh();
  const travelled = distances(scenario.timeline);
  const turn = scenario.turns.find((item) => item.type === 'TURN_LEFT' && item.atM > 1000);
  const firstOn = scenario.timeline.findIndex((row, i) => travelled[i] > turn.atM - 60 && row.turnSignal === 'BlinkLeft');
  assert.ok(Math.abs(turn.atM - travelled[firstOn] - 30) < 8);
});

test('a demo window prefers more events and starts on a stop', () => {
  const row = (t, speedKph, steeringDeg = 0) => ({ t, speedKph, steeringDeg });
  const rows = [];
  for (let i = 0; i < 2000; i++) {
    const t = Number((i * STEP_S).toFixed(1));
    if (i < 20) rows.push(row(t, 0));
    else if (i < 1000) rows.push(row(t, 30));
    else if (i < 1020) rows.push(row(t, 0));
    else rows.push(row(t, Math.min(40, (i - 1020) * 0.5), i % 100 > 80 ? 90 : 0));
  }
  rows[999].speedKph = 0;
  assert.equal(chooseDemoWindow(rows).fromSec, 201);
});

expectError('a wrong timeline step', (s) => { s.timeline[10].t += 0.1; }, /t must be/);
expectError('an extra timeline key', (s) => { s.timeline[3].gear = 'D'; }, /keys must be exactly/);
expectError('an extra top-level key', (s) => { s.notes = 'x'; }, /top-level keys/);
expectError('an unknown turn signal', (s) => { s.timeline[3].turnSignal = 'Left'; }, /turnSignal must be/);
expectError('a too short scenario', (s) => { s.timeline.length = 200; }, /duration/);
expectError('routePx outside the map', (s) => { s.routePx[1] = [s.mapSize[0] + 1, 10]; }, /must stay inside/);
expectError('too many routePx points', (s) => { s.routePx = Array.from({ length: 400 }, (_, i) => [i, i]); }, /routePx has 400/);
expectError('routePx with no vertex near a turn', (s) => { s.routePx = [s.routePx[0], s.routePx.at(-1)]; }, /no polyline vertex/);
expectError('a wrong route length', (s) => { s.routeLengthM *= 1.02; }, /differs from routeLengthM/);
expectError('steering beyond the range', (s) => { s.timeline[300].steeringDeg = 460; }, /steering beyond/);
expectError('a steering jump', (s) => { s.timeline[300].steeringDeg = 60; }, /steering rate/);
expectError('a speed jump', (s) => { s.timeline[300].speedKph += 8; }, /acceleration .* above/);
expectError('both pedals pressed', (s) => { s.timeline[300].brakePct = 10; s.timeline[300].acceleratorPct = 10; }, /pressed together/);
expectError('excess lateral acceleration', (s) => {
  for (const row of s.timeline) row.steeringDeg = Math.sign(row.steeringDeg) * Math.min(450, Math.abs(row.steeringDeg) * 3);
}, /lateral acceleration/);
expectError('flipped steering sign', (s) => {
  for (const row of s.timeline) row.steeringDeg = -row.steeringDeg + 0;
}, /wrong way/);
expectError('turns out of order', (s) => { s.turns.reverse(); }, /increasing atM/);
expectError('an invented turn type', (s) => { s.turns[0].type = 'TURN_SOMEWHERE'; }, /not a NaviHelper TurnType/);
expectError('an invented front event type', (s) => { s.frontEvents[0].type = 'HIGHWAY_MERGE'; }, /is not allowed/);
expectError('a hard brake with no front event', (s) => { s.frontEvents = []; }, /no front event/);
expectError('a front event behind the brake', (s) => { s.frontEvents = [{ ...s.frontEvents[0], atM: 1 }]; }, /no front event/);
expectError('a missing turn signal', (s) => {
  for (const row of s.timeline) if (row.turnSignal === 'BlinkLeft') row.turnSignal = 'Off';
}, /no BlinkLeft/);
expectError('a demo window of the wrong span', (s) => { s.demoWindow.toSec += 5; }, /must span/);
expectError('a demo window that starts moving', (s) => {
  const moving = s.timeline.find((row) => row.speedKph > 10).t;
  s.demoWindow = { fromSec: moving, toSec: moving + 150 };
}, /start with the car stopped/);

expectError('missing cameras', (s) => { delete s.cameras; }, /top-level keys|cameras must be an array/);
expectError('an invented camera type', (s) => { s.cameras[0].type = 'SPEED_CAMERA_LASER'; }, /not a NaviHelper type/);
expectError('a duplicate camera id', (s) => { s.cameras[1].id = s.cameras[0].id; }, /is not unique/);
expectError('a camera off the route', (s) => { s.cameras.at(-1).atM = s.routeLengthM; }, /inside the route/);
expectError('a fractional camera position', (s) => { s.cameras[0].atM += 0.5; }, /inside the route/);
expectError('cameras out of order', (s) => { s.cameras.reverse(); }, /atM order/);
expectError('a camera with no limit', (s) => { s.cameras[0].speedLimit = 0; }, /speedLimit/);
expectError('an invented caution point type', (s) => {
  s.cautionPoints = [{ atM: 100, id: 'cp-1', type: 'SPEED_HUMP' }];
}, /not a NaviHelper type/);
expectError('a caution point with an extra key', (s) => {
  s.cautionPoints = [{ atM: 100, id: 'cp-1', type: 'SPEED_BUMP', speedLimit: 20 }];
}, /keys must be atM, id, type/);

const offRouteScenario = () => structuredClone(built.get(offRoute.id));
expectError('a planned route missing leaveAtM', (s) => { delete s.plannedRoute.leaveAtM; }, /plannedRoute keys/, offRouteScenario);
expectError('a planned route that is not an object', (s) => { s.plannedRoute = []; }, /plannedRoute keys/, offRouteScenario);
expectError('a planned route of no length', (s) => { s.plannedRoute.routeLengthM = 0; }, /plannedRoute.routeLengthM must be positive/, offRouteScenario);
expectError('a planned route outside the map', (s) => { s.plannedRoute.routePx[1] = [-1, 10]; }, /plannedRoute.routePx must stay inside/, offRouteScenario);
expectError('an invented planned turn type', (s) => { s.plannedRoute.turns[0].type = 'TURN_SOMEWHERE'; }, /plannedRoute.turns\[0\].type/, offRouteScenario);
expectError('planned turns out of order', (s) => { s.plannedRoute.turns.reverse(); }, /plannedRoute.turns must be in increasing/, offRouteScenario);
expectError('a planned turn with no polyline vertex', (s) => {
  s.plannedRoute.routePx = [s.plannedRoute.routePx[0], s.plannedRoute.routePx.at(-1)];
}, /plannedRoute turn 0: no polyline vertex/, offRouteScenario);
expectError('a leaveAtM off the routes', (s) => { s.plannedRoute.leaveAtM = s.plannedRoute.routeLengthM; }, /leaveAtM must lie inside/, offRouteScenario);
expectError('a leaveAtM away from the missed turn', (s) => { s.plannedRoute.leaveAtM += 40; }, /not within 15 m of the missed turn/, offRouteScenario);
expectError('a blinker toward the missed turn', (s) => {
  const travelled = distances(s.timeline);
  const missed = s.plannedRoute.turns[0];
  const mode = /RIGHT|_R$/.test(missed.type) ? 'BlinkRight' : 'BlinkLeft';
  s.timeline.forEach((row, k) => {
    if (travelled[k] > s.plannedRoute.leaveAtM - 30 && travelled[k] <= s.plannedRoute.leaveAtM) row.turnSignal = mode;
  });
}, /toward the missed turn/, offRouteScenario);

test('the enum lists cover every type the generator writes', () => {
  assert.ok(CAMERA_TYPES.includes('SPEED_CAMERA_FIX'));
  assert.ok(['SPEED_BUMP', 'SCHOOL_ZONE'].every((type) => CAUTION_POINT_TYPES.includes(type)));
  assert.equal(new Set(CAMERA_TYPES).size, 10);
  assert.equal(new Set(CAUTION_POINT_TYPES).size, 23);
});

test('long-route cameras and caution points follow their seeds and stay clear of each other', () => {
  const steps = [
    { type: 'depart', atM: 0, kph: 50 },
    { type: 'merge', modifier: 'slight left', atM: 20000, kph: 90 },
    { type: 'arrive', atM: 40000, kph: 0 },
  ];
  const cameras = placeCameras(steps, 40000, [], seededRandom('a:cameras'));
  assert.deepEqual(cameras, placeCameras(steps, 40000, [], seededRandom('a:cameras')));
  assert.ok(cameras.some((camera) => camera.speeding) && cameras.some((camera) => !camera.speeding));
  assert.ok(cameras.every((camera) => camera.limitKph === (camera.atM < 20000 ? CAMERAS.urbanLimitKph : CAMERAS.motorwayLimitKph)));
  const points = placeCautionPoints(steps, 40000, cameras.map((camera) => camera.span), seededRandom('a:caution'));
  assert.ok(points.length > 0 && points.every((point) => point.atM + CAUTION.schoolZoneM < 20000));
  const spans = [...cameras.map((c) => c.span), ...points.map((p) => [p.atM - CAUTION.approachM, p.atM])].sort((a, b) => a[0] - b[0]);
  for (let i = 1; i < spans.length; i++) assert.ok(spans[i][0] - spans[i - 1][1] >= Math.min(CAMERAS.minGapM, CAUTION.minGapM) - 1e-9);
});

test('reports an absent map image as a warning, not an error', () => {
  const report = validateScenario(fresh(), { assetsDir: 'does-not-exist' });
  assert.deepEqual(report.errors, []);
  assert.match(report.warnings.join(), /map image/);
});
