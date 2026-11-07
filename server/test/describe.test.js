import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { SAMPLE_COUNT, TURN_NAMES, describeState, directionLevels, physics, segments } from '../lib/describe.js';

// SAMPLE_COUNT rows from per-sample functions of the index, oldest first.
const rows = ({ kph = () => 50, wheel = () => 0, brake = () => false, signal = 'off' } = {}) =>
  Array.from({ length: SAMPLE_COUNT }, (_, i) => ({ speedKph: kph(i), steeringDeg: wheel(i), brake: brake(i), turnSignal: signal }));

const lastLevels = (r) => directionLevels(physics(r)).at(-1);
const lastWindow = (state) => state.windows.at(-1).text;

describe('push directions follow the body, not the car', () => {
  it('braking pushes forward', () => {
    const levels = lastLevels(rows({ kph: (i) => 60 - i }));
    assert.ok(levels.forward > 0);
    assert.equal(levels.backward, 0);
  });

  it('speeding up pushes backward', () => {
    assert.ok(lastLevels(rows({ kph: (i) => 20 + i })).backward > 0);
  });

  it('holding speed pushes nowhere', () => {
    assert.deepEqual(lastLevels(rows()), { forward: 0, backward: 0, left: 0, right: 0 });
  });

  it('turning left (positive wheel) pushes right', () => {
    const levels = lastLevels(rows({ kph: () => 30, wheel: () => 90 }));
    assert.ok(levels.right > 0);
    assert.equal(levels.left, 0);
  });

  it('turning right (negative wheel) pushes left', () => {
    assert.ok(lastLevels(rows({ kph: () => 30, wheel: () => -90 })).left > 0);
  });

  it('a wheel inside the deadband at highway speed pushes nowhere', () => {
    assert.equal(lastLevels(rows({ kph: () => 100, wheel: () => 2.5 })).left + lastLevels(rows({ kph: () => 100, wheel: () => 2.5 })).right, 0);
  });

  it('a stopped car with the wheel turned pushes nowhere', () => {
    const levels = lastLevels(rows({ kph: () => 0, wheel: () => 200 }));
    assert.equal(levels.left + levels.right, 0);
  });

  it('the scale states the opposite-direction pushes', () => {
    const { scale } = describeState(rows(), null, null);
    assert.match(scale, /braking pushes the body forward/);
    assert.match(scale, /turning left pushes it right/);
  });
});

describe('levels', () => {
  it('does not flicker at a boundary inside the hysteresis band', () => {
    // 0.6 m/s2 bound with 0.15 band: wobbling between 0.5 and 0.7 stays at one level.
    let v = 20;
    const speeds = Array.from({ length: SAMPLE_COUNT }, (_, i) => (v -= (i % 2 ? 0.5 : 0.7) * 0.2 * 3.6));
    const levels = directionLevels(physics(rows({ kph: (i) => speeds[i] }))).map((l) => l.forward);
    assert.equal(new Set(levels.slice(3)).size, 1);
  });
});

describe('segments', () => {
  const rank = (k) => Number(k);

  it('folds a run shorter than 0.4 s into the closer neighbour', () => {
    assert.deepEqual(segments(['0', '0', '0', '0', '3', '1', '1', '1', '1', '1'], rank), [
      { key: '0', n: 4 },
      { key: '1', n: 6 },
    ]);
  });

  it('keeps at most three segments', () => {
    const keys = ['0', '0', '1', '1', '2', '2', '3', '3', '4', '4'];
    assert.ok(segments(keys, rank).length <= 3);
    assert.equal(segments(keys, rank).reduce((n, s) => n + s.n, 0), 10);
  });
});

describe('window sentences', () => {
  it('tells durations that add up to 2 s', () => {
    const state = describeState(rows({ kph: (i) => (i < 22 ? 50 : 50 - (i - 22) * 2) }), null, null);
    const frontBack = lastWindow(state).split(' Sideways:')[0];
    const durations = [...frontBack.matchAll(/(\d\.\d) s /g)].map((m) => Number(m[1]));
    assert.ok(durations.length >= 2, frontBack);
    assert.ok(Math.abs(durations.reduce((a, b) => a + b, 0) - 2) < 1e-9, frontBack);
  });

  it('names the side the wheel is turned to', () => {
    const state = describeState(rows({ kph: () => 30, wheel: (i) => Math.min(90, i * 6) }), null, null);
    assert.match(lastWindow(state), /Wheel: .*left/);
    assert.match(state.now, /wheel turned [a-z]+ left/);
  });

  it('tells a wheel coming back as a release', () => {
    const state = describeState(rows({ kph: () => 30, wheel: (i) => Math.max(0, 180 - i * 6) }), null, null);
    assert.match(state.windows[1].text, /released to|returned to centre/);
  });

  it('keeps lane-keeping wobble at centre', () => {
    const state = describeState(rows({ wheel: (i) => (i % 4 < 2 ? 3 : -3) }), null, null);
    for (const w of state.windows) assert.match(w.text, /Wheel: at centre for the whole 2 s/);
  });

  it('tells brake changes as a sequence', () => {
    const state = describeState(rows({ brake: (i) => i >= 26 }), null, null);
    assert.match(lastWindow(state), /Pedal: [0-9.]+ s off the brake, then [0-9.]+ s on the brake/);
  });

  it('says which way the wheel is moving now', () => {
    const state = describeState(rows({ kph: () => 30, wheel: (i) => (i > 28 ? (i - 28) * 4 : 0) }), null, null);
    assert.match(state.now, /starting to turn left|turning further left/);
  });

  it('uses m/s and no degrees', () => {
    const state = describeState(rows({ kph: () => 36, wheel: () => 40 }), null, null);
    assert.match(state.now, /^10\.0 m\/s/);
    assert.doesNotMatch(JSON.stringify(state), /deg|km/);
  });
});

describe('navigation', () => {
  it('turns distance into seconds at the current speed', () => {
    const state = describeState(rows({ kph: () => 36 }), { type: 'TURN_LEFT', distanceM: 30 }, null);
    assert.equal(state.nav.nextGuidance, 'At the current speed the left turn point is 3.0 s ahead (30 m).');
  });

  it('gives distance only when stopped', () => {
    const state = describeState(rows({ kph: () => 0 }), null, { type: 'CONGESTION', distanceM: 80 });
    assert.equal(state.nav.eventAhead, 'The car is stopped; the congestion start point is 80 m ahead.');
  });

  it('passes an unknown type through unchanged', () => {
    const state = describeState(rows({ kph: () => 36 }), { type: 'TURN_NEW', distanceM: 10 }, null);
    assert.match(state.nav.nextGuidance, /TURN_NEW point/);
  });

  it('names every turn type the project can emit', () => {
    assert.equal(Object.keys(TURN_NAMES).length, 17);
  });
});
