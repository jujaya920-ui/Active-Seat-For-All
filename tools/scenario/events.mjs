// Event-level scoring: replays what the app shows and compares it with each maneuver's onset.
import { LIMITS, accelerations } from './common.mjs';
import { deriveLabels, labelSpans } from './labels.mjs';

export const HINT = { onP: 0.55, offP: 0.45, minHoldS: 1, rttS: 0.5, leadS: 1.5 };
// Hint-visible time relative to onset that counts as "right before or just as it starts".
export const WINDOW = { earliestS: -2.5, latestS: 0.5, tailS: 1 };
const SEARCH_BEFORE_S = 8;
const NEUTRAL = ['straight', 'steady'];

export const nearestPrediction = (predictions, targetS) =>
  predictions.reduce((best, p) => (Math.abs(p.horizonSec - targetS) < Math.abs(best.horizonSec - targetS) ? p : best));

// responses: oldest first { at, value, p }, or { at, failed: true }, which ends a hint at once.
export function stabilize(responses, { onP, offP, minHoldS }, endT) {
  const spans = [];
  let current = null;
  const close = (t) => {
    spans.push({ ...current, toT: t });
    current = null;
  };
  for (const r of responses) {
    if (r.failed) {
      if (current) close(r.at);
      continue;
    }
    const moving = !NEUTRAL.includes(r.value);
    if (current && r.value === current.value && r.p >= offP) continue;
    if (moving && r.p >= onP) {
      // Same as the app's HintStabilizer: a direct reversal skips the minimum hold.
      if (current) close(r.at);
      current = { value: r.value, fromT: r.at };
    } else if (current && r.at - current.fromT >= minHoldS) {
      close(r.at);
    }
  }
  if (current) close(endT);
  return spans;
}

// Every span where the label is non-neutral right now, so hard and gentle ones are both scored.
export function listManeuvers(scenario) {
  const { timeline } = scenario;
  const accel = accelerations(timeline);
  const rows = deriveLabels(scenario, { horizonS: 0 });
  const peakAccel = (span) => {
    const inside = accel.filter((_, i) => timeline[i].t >= span.fromT && timeline[i].t <= span.toT).map(Math.abs);
    return Math.max(...inside);
  };
  const pick = (axis, key, isHard) =>
    labelSpans(rows, key)
      .filter((span) => !NEUTRAL.includes(span.value))
      .map((span) => ({ axis, value: span.value, fromT: span.fromT, toT: span.toT, hard: isHard(span) }));
  return [
    ...pick('lat', 'lateral', () => true),
    ...pick('lon', 'longitudinal', (span) => peakAccel(span) >= (span.value === 'brake' ? -LIMITS.normalBrakeMps2 : LIMITS.hardAccelMps2)),
  ].sort((a, b) => a.fromT - b.fromT);
}

// First same-class span touching the maneuver, relative to its onset and its end.
export function scoreManeuver(maneuver, spans, window = WINDOW) {
  const span = spans.find((s) => s.value === maneuver.value && s.toT > maneuver.fromT - SEARCH_BEFORE_S && s.fromT <= maneuver.toT);
  if (!span) return { verdict: 'miss', onRel: null, offRel: null };
  const onRel = span.fromT - maneuver.fromT;
  const verdict = onRel < window.earliestS ? 'early' : onRel > window.latestS ? 'late' : 'ok';
  return { verdict, onRel, offRel: span.toT - maneuver.toT };
}

// Seconds of each span outside every same-class maneuver's allowed window.
export function falseSpans(maneuvers, spans, window = WINDOW) {
  const out = [];
  for (const span of spans) {
    const allowed = maneuvers
      .filter((m) => m.value === span.value)
      .map((m) => [m.fromT + window.earliestS, m.toT + window.tailS])
      .sort((a, b) => a[0] - b[0]);
    let cursor = span.fromT;
    let seconds = 0;
    for (const [from, to] of allowed) {
      if (from > cursor) seconds += Math.max(0, Math.min(from, span.toT) - cursor);
      cursor = Math.max(cursor, to);
    }
    seconds += Math.max(0, span.toT - cursor);
    if (seconds > 1e-6) out.push({ ...span, seconds });
  }
  return out;
}

// calls: { t, predictions } or { t, failed: true }. Horizon, not delay, selects the prediction.
export function hintSpans(calls, axis, { hint = HINT, endT }) {
  const targetS = hint.leadS + hint.rttS;
  const key = axis === 'lat' ? 'lateral' : 'longitudinal';
  const responses = calls.map((call) => {
    const at = call.t + hint.rttS;
    if (call.failed) return { at, failed: true };
    const { value, probability } = nearestPrediction(call.predictions, targetS)[key];
    return { at, value, p: probability };
  });
  return stabilize(responses, hint, endT);
}
