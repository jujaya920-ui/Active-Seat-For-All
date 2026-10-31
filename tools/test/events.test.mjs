import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ROUTES, SHORT, generate } from '../scenario/from-route.mjs';
import { HINT, falseSpans, hintSpans, listManeuvers, nearestPrediction, scoreManeuver, stabilize } from '../scenario/events.mjs';

const on = (at, value, p) => ({ at, value, p });

test('hint turns on at the on threshold and survives down to the off threshold', () => {
  const spans = stabilize([on(1, 'left', 0.59), on(2, 'left', 0.6), on(3, 'left', 0.46), on(4, 'left', 0.44)], { ...HINT, onP: 0.6 }, 9);
  assert.deepEqual(spans, [{ value: 'left', fromT: 2, toT: 4 }]);
});

test('hint turns off when the class becomes neutral, after the minimum hold', () => {
  const held = stabilize([on(1, 'brake', 0.9), on(1.5, 'steady', 0.9), on(2, 'steady', 0.9)], HINT, 9);
  assert.deepEqual(held, [{ value: 'brake', fromT: 1, toT: 2 }]);
});

test('a direct reversal switches at once, even inside the minimum hold', () => {
  const spans = stabilize([on(1, 'left', 0.8), on(1.5, 'right', 0.8), on(3, 'straight', 0.9)], HINT, 9);
  assert.deepEqual(spans, [
    { value: 'left', fromT: 1, toT: 1.5 },
    { value: 'right', fromT: 1.5, toT: 3 },
  ]);
});

test('a weak opposite class ends the hint without starting the other one', () => {
  const spans = stabilize([on(1, 'left', 0.8), on(3, 'right', 0.5), on(4, 'right', 0.7)], HINT, 9);
  assert.deepEqual(spans, [
    { value: 'left', fromT: 1, toT: 3 },
    { value: 'right', fromT: 4, toT: 9 },
  ]);
});

test('a failed call ends the hint despite the minimum hold', () => {
  const spans = stabilize([on(1, 'left', 0.8), { at: 1.5, failed: true }], HINT, 9);
  assert.deepEqual(spans, [{ value: 'left', fromT: 1, toT: 1.5 }]);
});

test('a hint still on at the end closes there', () => {
  assert.deepEqual(stabilize([on(8, 'brake', 0.8)], HINT, 9), [{ value: 'brake', fromT: 8, toT: 9 }]);
});

test('the prediction nearest the target horizon is used, the first on a tie', () => {
  assert.equal(nearestPrediction([{ horizonSec: 2 }, { horizonSec: 2.4 }], 2.3).horizonSec, 2.4);
  assert.equal(nearestPrediction([{ horizonSec: 2 }, { horizonSec: 2.4 }], 2.1).horizonSec, 2);
  assert.equal(nearestPrediction([{ horizonSec: 2 }, { horizonSec: 2.5 }], 2.25).horizonSec, 2);
});

test('a response is shown one round trip after its call', () => {
  const calls = [{ t: 10, predictions: [{ horizonSec: 2, lateral: { value: 'left', probability: 0.9 } }] }];
  assert.deepEqual(hintSpans(calls, 'lat', { endT: 20 }), [{ value: 'left', fromT: 10.5, toT: 20 }]);
});

test('maneuvers score by onset', () => {
  const maneuver = { value: 'left', fromT: 20, toT: 24 };
  const score = (fromT) => scoreManeuver(maneuver, [{ value: 'left', fromT, toT: 25 }]).verdict;
  assert.equal(score(14), 'early');
  assert.equal(score(17.5), 'ok');
  assert.equal(score(20.5), 'ok');
  assert.equal(score(21), 'late');
  assert.equal(scoreManeuver(maneuver, [{ value: 'right', fromT: 19, toT: 25 }]).verdict, 'miss');
});

test('false hint time excludes the window around a same-class maneuver', () => {
  const maneuvers = [{ value: 'left', fromT: 20, toT: 24 }];
  const inside = { value: 'left', fromT: 18, toT: 25 };
  const outside = { value: 'left', fromT: 15, toT: 16 };
  assert.deepEqual(falseSpans(maneuvers, [inside]), []);
  assert.deepEqual(falseSpans(maneuvers, [outside]), [{ ...outside, seconds: 1 }]);
  assert.equal(falseSpans(maneuvers, [{ value: 'right', fromT: 20, toT: 22 }])[0].seconds, 2);
  assert.equal(falseSpans(maneuvers, [{ value: 'left', fromT: 16.5, toT: 26 }])[0].seconds, 2);
});

test('every real route lists its corners, and hard launches appear only on short routes', () => {
  for (const entry of ROUTES) {
    const scenario = generate(entry);
    const maneuvers = listManeuvers(scenario);
    const expected = scenario.routeLengthM <= SHORT.maxLengthM ? ['accelerate', 'brake'] : ['brake'];
    assert.ok(maneuvers.some((m) => m.axis === 'lat'), entry.id);
    assert.deepEqual([...new Set(maneuvers.filter((m) => m.axis === 'lon' && m.hard).map((m) => m.value))].sort(), expected, entry.id);
  }
});
