import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { buildScenario, formatScenario, loadDefinition, outputPath } from '../scenario/build.mjs';
import { STEP_S, detectManeuvers, distances } from '../scenario/common.mjs';
import { deriveLabels, labelSpans, lateralLabel, longitudinalLabel } from '../scenario/labels.mjs';
import { validateScenario } from '../scenario/validate.mjs';

const def = await loadDefinition();
const fresh = () => structuredClone(buildScenario(def));
const errorsOf = (scenario) => validateScenario(scenario).errors;

function expectError(name, mutate, pattern) {
  test(`rejects ${name}`, () => {
    const scenario = fresh();
    mutate(scenario);
    const errors = errorsOf(scenario);
    assert.ok(errors.some((error) => pattern.test(error)), `no ${pattern} in:\n${errors.join('\n')}`);
  });
}

test('generated scenario passes validation', () => {
  assert.deepEqual(errorsOf(fresh()), []);
});

test('scenario file on disk is the current build output', () => {
  assert.equal(readFileSync(outputPath(def.id), 'utf8'), formatScenario(buildScenario(def)));
});

test('build is deterministic', () => {
  assert.deepEqual(buildScenario(def), buildScenario(def));
});

test('scenario meets the brief', () => {
  const scenario = fresh();
  const { timeline, turns, frontEvents } = scenario;
  const { corners, hardAccels, hardBrakes } = detectManeuvers(timeline);
  const duration = timeline[timeline.length - 1].t;
  assert.ok(duration >= 90 && duration <= 150);
  assert.deepEqual(
    turns.map((turn) => [turn.type, turn.description]),
    [['TURN_RIGHT', '대왕판교IC 방면'], ['TURN_RIGHT', '양재 방면'], ['TURN_LEFT', '강남역 방면']],
  );
  assert.deepEqual(corners.map((corner) => corner.direction), ['right', 'right', 'left']);
  assert.equal(hardAccels.length, 1);
  assert.equal(hardBrakes.length, 1);
  assert.deepEqual(frontEvents.map((event) => event.type), ['HIGHWAY_MERGE', 'CONGESTION', 'CONGESTION_END', 'DESTINATION']);
  assert.equal(scenario.mapImage, 'maps/pangyo-gangnam.webp');
  const total = distances(timeline).at(-1);
  assert.ok(Math.abs(total - scenario.routeLengthM) / scenario.routeLengthM <= 0.01);
});

test('turn signal starts at least 3 s before every corner', () => {
  const { timeline } = fresh();
  for (const corner of detectManeuvers(timeline).corners) {
    const mode = corner.direction === 'left' ? 'BlinkLeft' : 'BlinkRight';
    const before = timeline[corner.moveI - Math.round(3 / STEP_S)];
    assert.equal(before.turnSignal, mode);
  }
});

expectError('a wrong timeline step', (s) => { s.timeline[10].t += 0.1; }, /t must be/);
expectError('an extra timeline key', (s) => { s.timeline[3].gear = 'D'; }, /keys must be exactly/);
expectError('an extra top-level key', (s) => { s.notes = 'x'; }, /top-level keys/);
expectError('an unknown turn signal', (s) => { s.timeline[3].turnSignal = 'Left'; }, /turnSignal must be/);
expectError('a too short scenario', (s) => { s.timeline.length = 200; }, /duration/);
expectError('routePx outside the map', (s) => { s.routePx[1] = [1700, 10]; }, /inside 1600x1000/);
expectError('routePx vertex off its turn', (s) => { s.routePx[1] = [s.routePx[0][0] + 5, s.routePx[0][1] + 5]; }, /polyline vertex/);
expectError('a wrong route length', (s) => { s.routeLengthM *= 1.02; }, /differs from routeLengthM/);
expectError('steering beyond the range', (s) => { s.timeline[300].steeringDeg = 460; }, /steering beyond/);
expectError('a steering jump', (s) => { s.timeline[300].steeringDeg = 60; }, /steering rate/);
expectError('a speed jump', (s) => { s.timeline[300].speedKph += 8; }, /acceleration .* above/);
expectError('both pedals pressed', (s) => { s.timeline[300].brakePct = 10; s.timeline[300].acceleratorPct = 10; }, /pressed together/);
expectError('excess lateral acceleration', (s) => {
  for (const row of s.timeline) row.steeringDeg = Math.sign(row.steeringDeg) * Math.min(450, Math.abs(row.steeringDeg) * 2);
}, /lateral acceleration/);
expectError('flipped steering sign', (s) => {
  for (const row of s.timeline) row.steeringDeg = -row.steeringDeg + 0;
}, /steering goes/);
expectError('turns out of order', (s) => { s.turns.reverse(); }, /increasing atM/);
expectError('wrong turn labels', (s) => { s.turns[1].description = '서초 방면'; }, /turns must be/);
const congestion = (s) => s.frontEvents.find((event) => event.type === 'CONGESTION');
expectError('a missing front event', (s) => { s.frontEvents = []; }, /no front event/);
expectError('a front event behind the brake', (s) => { s.frontEvents = [{ ...congestion(s), atM: 1 }]; }, /no front event/);
expectError('a non-congestion front event', (s) => { congestion(s).type = 'CAUTION'; }, /expected CONGESTION/);
expectError('turn atM away from the apex', (s) => { s.turns[0].atM += 30; }, /from the steering apex/);

expectError('a late turn signal', (s) => {
  const { moveI } = detectManeuvers(s.timeline).corners[0];
  for (let i = moveI - 25; i < moveI - 5; i++) s.timeline[i].turnSignal = 'Off';
}, /signal leads steering/);

expectError('a missing turn signal', (s) => {
  for (const row of s.timeline) if (row.turnSignal === 'BlinkLeft') row.turnSignal = 'Off';
}, /no BlinkLeft/);

expectError('a hard acceleration with no build-up', (s) => {
  const { startI } = detectManeuvers(s.timeline).hardAccels[0];
  // Flatten the gentle phase; shift the speed so only the precursor changes.
  const held = s.timeline[startI - 12].speedKph;
  for (let i = startI - 11; i < startI; i++) s.timeline[i].speedKph = held;
}, /gentle acceleration/);

expectError('a hard brake with no lift-off', (s) => {
  const { startI } = detectManeuvers(s.timeline).hardBrakes[0];
  const held = s.timeline[startI].speedKph;
  for (let i = startI - 11; i < startI; i++) s.timeline[i].speedKph = held;
}, /lift-off/);

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

test('every maneuver appears 2 s early in the labels, in order', () => {
  const scenario = fresh();
  const { timeline } = scenario;
  const rows = deriveLabels(scenario);
  const { corners, hardAccels, hardBrakes } = detectManeuvers(timeline);
  const lateral = labelSpans(rows, 'lateral').filter((span) => span.value !== 'straight');
  assert.deepEqual(lateral.map((span) => span.value), ['right', 'right', 'left']);
  lateral.forEach((span, i) => {
    assert.ok(Math.abs(span.fromT + 2 - timeline[corners[i].startI].t) < 1e-6);
  });
  const accelerateAt = rows.find((row) => row.targetT === timeline[hardAccels[0].startI].t);
  const brakeAt = rows.find((row) => row.targetT === timeline[hardBrakes[0].startI].t);
  assert.equal(accelerateAt.longitudinal, 'accelerate');
  assert.equal(brakeAt.longitudinal, 'brake');
  // One sample earlier the car is still in its precursor phase.
  assert.equal(rows[rows.indexOf(accelerateAt) - 1].longitudinal, 'steady');
  assert.equal(rows[rows.indexOf(brakeAt) - 1].longitudinal, 'steady');
});
