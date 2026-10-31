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

// Seconds before a road feature at which drivers start each motion. Only the question wording
// uses these, to tie a motion to etaSec. Jev still gives the answer.
const TURN_IN_ETA_S = 2.3;
const SLOW_FOR_TURN_ETA_S = 7;
const ACCEL_FOR_ROAD_ETA_S = 1;
const BRAKE_FOR_CONGESTION_ETA_S = 3;
const BRAKE_FOR_DESTINATION_ETA_S = 5;

const round = (value, digits) => Number(value.toFixed(digits));

function lateralQuestion(h) {
  const turnEta = round(h + TURN_IN_ETA_S, 1);
  const side = (name) =>
    `the wheel is past 15 degrees ${name} at that moment: \`nextTurn\` turns ${name} and its \`etaSec\` is at most ${turnEta}, or the wheel is already ${name} and not returning to centre`;
  return {
    type: 'choice',
    instructions: `Where is the steering wheel exactly ${h} seconds from now? Use \`nextTurn.etaSec\`, \`motion.steeringRateDegS\` and the newest \`steeringDeg\`.`,
    criteria: {
      left: side('left'),
      right: side('right'),
      [LATERAL_NEUTRAL]: `the wheel is within 15 degrees of centre at that moment: \`nextTurn\` is more than ${turnEta} seconds away even if the turn signal is on, or the wheel is returning to centre after a turn, including when it is passing through zero`,
    },
  };
}

function longitudinalQuestion(h) {
  return {
    type: 'choice',
    instructions: `What is the car doing to its speed exactly ${h} seconds from now? Use \`frontEvent.etaSec\`, \`nextTurn.etaSec\`, \`motion.accelMps2\` and \`brake\`.`,
    criteria: {
      accelerate: `speed is rising by more than 0.6 m/s² at that moment: \`frontEvent\` is HIGHWAY_MERGE or CONGESTION_END, where drivers accelerate on reaching it, with \`etaSec\` at most ${round(h + ACCEL_FOR_ROAD_ETA_S, 1)}, or \`motion.accelMps2\` is already above 0.6, or the wheel is returning to centre after a turn, since drivers accelerate out of a turn`,
      brake: `speed is falling by more than 0.6 m/s² at that moment: \`frontEvent\` is CONGESTION with \`etaSec\` at most ${round(h + BRAKE_FOR_CONGESTION_ETA_S, 1)} while speed is above 35 km/h, or DESTINATION with \`etaSec\` at most ${round(h + BRAKE_FOR_DESTINATION_ETA_S, 1)}, or \`nextTurn.etaSec\` is at most ${round(h + SLOW_FOR_TURN_ETA_S, 1)} while speed is above 35 km/h, or \`motion.accelMps2\` is already below -0.6`,
      [LONGITUDINAL_NEUTRAL]: 'speed changes by less than 0.6 m/s² at that moment: none of the cases above applies, for example cruising, crawling, or holding turn speed while the wheel moves away from centre',
    },
  };
}

const STATE_NOTES =
  'signals are oldest first. ageMs is how long ago the sample was taken. steeringDeg is positive to the left. ' +
  'motion is computed from signals: accelMps2 is the speed change per second, steeringRateDegS the steering change per second. ' +
  'etaSec is distanceM divided by the current speed, null when stopped. nextTurn and frontEvent are null when absent.';

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

// Copies contract fields one by one, so anything else in the body never reaches Jev.
// Derived values come from the same contract fields, so the app's request does not change.
export function buildState({ signals, nextTurn, frontEvent }) {
  const { speedKph } = signals[signals.length - 1];
  return {
    notes: STATE_NOTES,
    signals: signals.map(({ ageMs, speedKph, steeringDeg, brake, turnSignal }) => ({
      ageMs,
      speedKph,
      steeringDeg,
      brake,
      turnSignal,
    })),
    motion: deriveMotion(signals),
    nextTurn: pickRouteItem(nextTurn, speedKph),
    frontEvent: pickRouteItem(frontEvent, speedKph),
  };
}

// One request covers every horizon. Keys use the index because horizons contain dots.
export function buildJevRequest(input) {
  const { horizonsSec } = input;
  const questions = {};

  horizonsSec.forEach((h, i) => {
    questions[`lateral_${i}`] = lateralQuestion(h);
    questions[`longitudinal_${i}`] = longitudinalQuestion(h);
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
