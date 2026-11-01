import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { LABEL, STEP_S, TURN_TYPES, accelerations, detectManeuvers, distances } from '../scenario/common.mjs';
import {
  DEMO,
  DRIVER,
  ROUTES,
  SHORT,
  STOPS,
  TRAFFIC,
  chooseDemoWindow,
  formatScenario,
  generate,
  outputPath,
  parseRoute,
  placeSlowdowns,
  placeStops,
  routePath,
  seededRandom,
  turnType,
} from '../scenario/from-route.mjs';
import { deriveLabels, labelSpans, lateralLabel, longitudinalLabel } from '../scenario/labels.mjs';
import { fitRoute } from '../scenario/projection.mjs';
import { validateScenario } from '../scenario/validate.mjs';

const built = new Map(ROUTES.map((entry) => [entry.id, generate(entry)]));
const scenarios = [...built.values()];
const fresh = () => structuredClone(built.get('gangnam-pangyo'));
const errorsOf = (scenario) => validateScenario(scenario).errors;
const realRoute = (entry) => parseRoute(JSON.parse(readFileSync(routePath(entry), 'utf8')));

function expectError(name, mutate, pattern) {
  test(`rejects ${name}`, () => {
    const scenario = fresh();
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

  test(`${entry.id}: stays parseable and adds only demoWindow`, () => {
    const parsed = JSON.parse(formatScenario(scenario));
    assert.deepEqual(Object.keys(parsed).sort(), [
      'demoWindow', 'frontEvents', 'id', 'mapImage', 'mapSize', 'originRoad', 'routeLengthM', 'routePx', 'timeline', 'title', 'turns',
    ]);
    assert.ok(parsed.routePx.every((point) => point.every(Number.isInteger)));
  });

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
    test(`${entry.id}: holds a left, a right, a hard brake and a hard launch within 2 minutes`, () => {
      const { corners, hardAccels, hardBrakes } = detectManeuvers(scenario.timeline);
      assert.ok(scenario.timeline.at(-1).t <= 120);
      assert.ok(corners.some((corner) => corner.direction === 'left'));
      assert.ok(corners.some((corner) => corner.direction === 'right'));
      assert.equal(hardBrakes.length, 1);
      assert.equal(hardAccels.length, 1);
    });
  }
}

test('routePx starts and ends on the route ends', () => {
  const entry = ROUTES.find(({ id }) => id === 'pangyo-dongpangyo');
  const route = realRoute(entry);
  const { toPx } = fitRoute(route.coordinates);
  const { routePx } = built.get(entry.id);
  assert.deepEqual(routePx[0], toPx(route.coordinates[0]));
  assert.deepEqual(routePx.at(-1), toPx(route.coordinates.at(-1)));
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

test('reports an absent map image as a warning, not an error', () => {
  const report = validateScenario(fresh(), { assetsDir: 'does-not-exist' });
  assert.deepEqual(report.errors, []);
  assert.match(report.warnings.join(), /map image/);
});

test('label thresholds use left-positive steering', () => {
  assert.equal(lateralLabel(100), 'left');
  assert.equal(lateralLabel(-100), 'right');
  assert.equal(lateralLabel(5), 'straight');
  assert.equal(longitudinalLabel(2), 'accelerate');
  assert.equal(longitudinalLabel(-2), 'brake');
  assert.equal(longitudinalLabel(0.1), 'steady');
});

test('labels look exactly horizon seconds ahead', () => {
  const row = (t, speedKph, steeringDeg) => ({ t, speedKph, steeringDeg });
  const timeline = [row(0, 36, 0), row(0.2, 36, 0), row(0.4, 36, 90), row(0.6, 43.2, -90), row(0.8, 43.2, 0)];
  const rows = deriveLabels({ timeline }, { horizonS: 0.4 });
  assert.deepEqual(rows, [
    { t: 0, targetT: 0.4, lateral: 'left', longitudinal: 'accelerate' },
    { t: 0.2, targetT: 0.6, lateral: 'right', longitudinal: 'steady' },
    { t: 0.4, targetT: 0.8, lateral: 'straight', longitudinal: 'steady' },
  ]);
});

test('labels stop where t + horizon leaves the timeline', () => {
  const scenario = fresh();
  const rows = deriveLabels(scenario);
  assert.equal(rows.length, scenario.timeline.length - 10);
  assert.equal(rows.at(-1).targetT, scenario.timeline.at(-1).t);
});

test('every corner appears 2 s early in the labels, in order', () => {
  const scenario = fresh();
  const { timeline } = scenario;
  const rows = deriveLabels(scenario);
  const lateral = labelSpans(rows, 'lateral').filter((span) => span.value !== 'straight');
  const { corners } = detectManeuvers(timeline);
  assert.equal(lateral.length, corners.length);
  lateral.forEach((span, i) => {
    assert.equal(span.value, corners[i].direction);
    assert.ok(Math.abs(span.fromT + LABEL.horizonS - timeline[corners[i].startI].t) < 1e-6);
  });
});
