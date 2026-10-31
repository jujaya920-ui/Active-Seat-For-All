// Input validation, Jev request building and response mapping for /api/predict.

export const GATEWAY_URL = 'https://ai-gateway.vercel.sh/v1/evaluate';
export const JEV_MODEL = 'typesafe-ai/jev';
export const UPSTREAM_TIMEOUT_MS = 1500;

const SIGNAL_COUNT = 5;
const MAX_HORIZONS = 6;
const MAX_LABEL_LENGTH = 64;

const LATERAL_NEUTRAL = 'straight';
const LONGITUDINAL_NEUTRAL = 'steady';
const LATERAL_CLASSES = ['left', 'right', LATERAL_NEUTRAL];
const LONGITUDINAL_CLASSES = ['accelerate', 'brake', LONGITUDINAL_NEUTRAL];

const round = (value, digits) => Number(value.toFixed(digits));

// Spelled out because Jev confused a negative steeringDeg with the name of the next turn.
const describeWheel = (deg) => {
  const size = Math.abs(Math.round(deg));
  return size === 0 ? 'centred' : `${size} degrees ${deg > 0 ? 'left' : 'right'}`;
};

// Criteria only define each class. The instructions hold driving knowledge in words, with no timing
// numbers: when a manoeuvre starts is Jev's judgement from state, on any road.
function lateralQuestion(h, i) {
  return {
    type: 'choice',
    instructions: `Where will the steering wheel be exactly ${h} seconds from now? Judge it from \`projections[${i}]\`, the signals and \`nextTurn\`. A driver keeps the wheel centred until the car is actually at a turn, even if the turn is announced and the turn signal is on, then steers the way the turn goes. The road can also curve with no announced turn, so a wheel already off centre follows the road, and \`nextTurn\` says nothing about it unless the car is at that turn. Read the side from \`wheelNow\`, not from the name of the next turn. Once a turn is under way the wheel returns to centre. If a stopped car is close to a turn, it steers as it pulls away.`,
    criteria: {
      left: 'the wheel is more than 15 degrees left of centre at that moment',
      right: 'the wheel is more than 15 degrees right of centre at that moment',
      [LATERAL_NEUTRAL]: 'the wheel is within 15 degrees of centre at that moment',
    },
  };
}

function longitudinalQuestion(h, i) {
  return {
    type: 'choice',
    instructions: `How will the speed be changing exactly ${h} seconds from now? Judge it from \`projections[${i}]\`, \`motion\`, the signals, \`nextTurn\` and \`frontEvent\`. The current signals only show what the driver is doing now, and braking for something ahead always starts while the speed still looks steady, so steady signals are no evidence that the car will still be steady then. A driver does not wait until only hard braking would be enough: judge whether the driver would already be slowing by then from \`stopDecelAfterMps2\`, the deceleration that would be needed at the horizon if the car had not slowed by then. A car at road speed cannot take a turn or enter congestion without first shedding much of that speed, so on an approach at speed the next thing the driver does is brake, and the only question is whether that has begun by then. Choose the most likely class even when the driver has not begun yet. A car leaving a turn or a stop speeds up, and as the wheel returns to centre the driver goes back on the accelerator. Far from any turn or event, a driver holds speed.`,
    criteria: {
      accelerate: 'speed is rising by more than 0.6 m/s² at that moment',
      brake: 'speed is falling by more than 0.6 m/s² at that moment',
      [LONGITUDINAL_NEUTRAL]: 'speed is changing by less than 0.6 m/s² at that moment',
    },
  };
}

const STATE_NOTES =
  'signals are oldest first. ageMs is how long ago the sample was taken. steeringDeg is positive to the left. ' +
  'wheelNow says where the wheel points now. motion is computed from signals: accelMps2 is the speed change per second, ' +
  'steeringRateDegS the steering change per second. ' +
  'etaSec is distanceM divided by the current speed, null when stopped. ' +
  'nextTurn and frontEvent are null when absent. ' +
  'projections has one entry per horizon: what simple arithmetic gives if speed and steering keep changing at the ' +
  'current rate. It is not a prediction: a driver changes what they do. nextTurnEtaAfterSec and ' +
  'frontEventEtaAfterSec are etaSec minus the horizon, negative when the car gets there before the horizon. ' +
  'stopDecelAfterMps2 is the constant deceleration that would stop the car at the nearest of nextTurn and ' +
  'frontEvent, starting at the horizon at the current speed, null when there is neither or the car would be past it.';

export class UpstreamError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

const isFiniteNumber = (v) => typeof v === 'number' && Number.isFinite(v);
const isObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);
const isLabel = (v) => typeof v === 'string' && v.length > 0 && v.length <= MAX_LABEL_LENGTH;

function signalError(s, i) {
  if (!isObject(s)) return `signals[${i}] must be an object`;
  if (!isFiniteNumber(s.ageMs) || s.ageMs < 0) return `signals[${i}].ageMs must be >= 0`;
  if (!isFiniteNumber(s.speedKph) || s.speedKph < 0) return `signals[${i}].speedKph must be >= 0`;
  if (!isFiniteNumber(s.steeringDeg)) return `signals[${i}].steeringDeg must be a number`;
  if (typeof s.brake !== 'boolean') return `signals[${i}].brake must be a boolean`;
  if (!isLabel(s.turnSignal)) return `signals[${i}].turnSignal must be a short string`;
  return null;
}

function routeItemError(item, name) {
  if (item === null) return null;
  if (!isObject(item)) return `${name} must be an object or null`;
  if (!isLabel(item.type)) return `${name}.type must be a short string`;
  if (!isFiniteNumber(item.distanceM) || item.distanceM < 0) return `${name}.distanceM must be >= 0`;
  return null;
}

// Returns an error message, or null when the body matches the contract.
export function validateInput(body) {
  if (!isObject(body)) return 'body must be a JSON object';

  const { signals, nextTurn, frontEvent, horizonsSec } = body;

  if (!Array.isArray(signals) || signals.length !== SIGNAL_COUNT) {
    return `signals must be an array of ${SIGNAL_COUNT}`;
  }
  for (let i = 0; i < signals.length; i++) {
    const error = signalError(signals[i], i);
    if (error) return error;
    if (i > 0 && signals[i].ageMs >= signals[i - 1].ageMs) {
      return 'signals must be oldest first (ageMs decreasing)';
    }
  }

  const routeError = routeItemError(nextTurn, 'nextTurn') ?? routeItemError(frontEvent, 'frontEvent');
  if (routeError) return routeError;

  if (!Array.isArray(horizonsSec) || horizonsSec.length === 0 || horizonsSec.length > MAX_HORIZONS) {
    return `horizonsSec must have 1 to ${MAX_HORIZONS} items`;
  }
  if (!horizonsSec.every((h) => isFiniteNumber(h) && h > 0)) {
    return 'horizonsSec items must be positive numbers';
  }

  return null;
}

function pickRouteItem(item, speedKph) {
  if (item === null) return null;
  const etaSec = speedKph > 0 ? round(item.distanceM / (speedKph / 3.6), 1) : null;
  return { type: item.type, distanceM: item.distanceM, etaSec };
}

function deriveMotion(signals) {
  const first = signals[0];
  const last = signals[signals.length - 1];
  const spanS = (first.ageMs - last.ageMs) / 1000;
  return {
    accelMps2: round((last.speedKph - first.speedKph) / 3.6 / spanS, 2),
    steeringRateDegS: round((last.steeringDeg - first.steeringDeg) / spanS, 0),
  };
}

const etaAfter = (item, h) => (item?.etaSec == null ? null : round(item.etaSec - h, 1));

// Stopping deceleration from the horizon on, if the car holds its current speed until then.
function stopDecelAfter(mps, h, nextTurn, frontEvent) {
  const distances = [nextTurn, frontEvent].filter(Boolean).map((item) => item.distanceM);
  if (mps === 0 || distances.length === 0) return null;
  const left = Math.min(...distances) - mps * h;
  return left > 0 ? round((mps * mps) / (2 * left), 2) : null;
}

// Straight-line arithmetic per horizon. Nothing here is a judgement about what the driver will do.
function deriveProjections(horizonsSec, last, motion, nextTurn, frontEvent) {
  return horizonsSec.map((horizonSec) => ({
    horizonSec,
    speedKph: round(Math.max(0, last.speedKph + motion.accelMps2 * 3.6 * horizonSec), 1),
    wheel: describeWheel(last.steeringDeg + motion.steeringRateDegS * horizonSec),
    nextTurnEtaAfterSec: etaAfter(nextTurn, horizonSec),
    frontEventEtaAfterSec: etaAfter(frontEvent, horizonSec),
    stopDecelAfterMps2: stopDecelAfter(last.speedKph / 3.6, horizonSec, nextTurn, frontEvent),
  }));
}

// Copies contract fields one by one, so anything else in the body never reaches Jev.
// Derived values come from the same contract fields, so the app's request does not change.
export function buildState({ signals, nextTurn, frontEvent, horizonsSec }) {
  const last = signals[signals.length - 1];
  const motion = deriveMotion(signals);
  const turn = pickRouteItem(nextTurn, last.speedKph);
  const front = pickRouteItem(frontEvent, last.speedKph);
  return {
    notes: STATE_NOTES,
    signals: signals.map(({ ageMs, speedKph, steeringDeg, brake, turnSignal }) => ({
      ageMs,
      speedKph,
      steeringDeg,
      brake,
      turnSignal,
    })),
    wheelNow: describeWheel(last.steeringDeg),
    motion,
    projections: deriveProjections(horizonsSec, last, motion, turn, front),
    nextTurn: turn,
    frontEvent: front,
  };
}

// One request covers every horizon. Keys use the index because horizons contain dots.
export function buildJevRequest(input) {
  const { horizonsSec } = input;
  const questions = {};

  horizonsSec.forEach((h, i) => {
    questions[`lateral_${i}`] = lateralQuestion(h, i);
    questions[`longitudinal_${i}`] = longitudinalQuestion(h, i);
  });

  return { model: JEV_MODEL, state: buildState(input), questions };
}

const isProbability = (v) => isFiniteNumber(v) && v >= 0 && v <= 1;

function mapChoice(jev, key, classes) {
  const answer = jev.answers[key];
  if (!isObject(answer) || !classes.includes(answer.choice)) {
    throw new UpstreamError('upstream_bad_response', `invalid answer for ${key}`);
  }

  const probability = answer.probabilities?.[answer.choice];
  // The docs show confidence under providerMetadata for the AI SDK. The raw HTTP location
  // is not verified, so the answer object is accepted as well.
  const confidence = answer.confidence ?? jev.providerMetadata?.typesafe?.confidence?.[key];

  if (!isProbability(probability) || !isProbability(confidence)) {
    throw new UpstreamError('upstream_bad_response', `missing probability or confidence for ${key}`);
  }
  return { value: answer.choice, probability, confidence };
}

// Largest probability of a moving class on this axis, read from the direction answer.
function motionProbability(jev, key, neutral) {
  const { [neutral]: _, ...moving } = jev.answers[key].probabilities;
  return Math.max(...Object.values(moving).filter(isProbability));
}

// Maps a Jev response to the contract's predictions. Throws rather than filling gaps.
export function mapJevResponse(jev, horizonsSec) {
  if (!isObject(jev) || !isObject(jev.answers)) {
    throw new UpstreamError('upstream_bad_response', 'response has no answers');
  }

  return horizonsSec.map((horizonSec, i) => ({
    horizonSec,
    lateral: mapChoice(jev, `lateral_${i}`, LATERAL_CLASSES),
    longitudinal: mapChoice(jev, `longitudinal_${i}`, LONGITUDINAL_CLASSES),
    hint: Math.max(
      motionProbability(jev, `lateral_${i}`, LATERAL_NEUTRAL),
      motionProbability(jev, `longitudinal_${i}`, LONGITUDINAL_NEUTRAL),
    ),
  }));
}

// Sends one request to the gateway. The timeout also covers reading the response body.
export async function callJev(requestBody, apiKey) {
  try {
    const response = await fetch(GATEWAY_URL, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${apiKey}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify(requestBody),
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });

    if (!response.ok) {
      const detail = (await response.text()).slice(0, 200);
      throw new UpstreamError('upstream_status', `gateway returned ${response.status}: ${detail}`);
    }
    return await response.json();
  } catch (error) {
    if (error instanceof UpstreamError) throw error;
    if (error.name === 'TimeoutError') {
      throw new UpstreamError('upstream_timeout', `no response within ${UPSTREAM_TIMEOUT_MS} ms`);
    }
    if (error instanceof SyntaxError) {
      throw new UpstreamError('upstream_bad_response', 'response is not JSON');
    }
    throw new UpstreamError('upstream_unreachable', error.message);
  }
}
