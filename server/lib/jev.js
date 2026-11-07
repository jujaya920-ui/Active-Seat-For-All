// Input validation, Jev request building and response mapping for /api/predict.

import { DIRECTIONS, SAMPLE_COUNT, describeState } from './describe.js';

export const GATEWAY_URL = 'https://ai-gateway.vercel.sh/v1/evaluate';
export const JEV_MODEL = 'typesafe-ai/jev';
export const UPSTREAM_TIMEOUT_MS = 1500;

const MAX_LABEL_LENGTH = 64;
export const HORIZON_SEC = 2;

const LEVEL_CLASSES = ['weak', 'moderate', 'strong', 'very strong'];

const LEVEL_CRITERIA = {
  weak: 'like an ordinary start or stop',
  moderate: 'the body clearly leans',
  strong: 'a standing passenger would need to hold on',
  'very strong': 'like an emergency stop or a sharp turn taken fast',
};

// Jev only judges: every input is already plain language in state, read against state.scale and state.rules.
function onQuestion(dir) {
  return {
    type: 'choice',
    instructions: `Exactly ${HORIZON_SEC} seconds from now, will the passenger's body be pushed ${dir}? Judge it from \`windows\`, \`now\` and \`nav\`, reading them with \`scale\` and \`rules\`.`,
    criteria: {
      yes: `at that moment the body is pushed ${dir}, weak or stronger`,
      no: `at that moment there is no push ${dir}, or the push goes another way`,
    },
  };
}

function levelQuestion(dir) {
  return {
    type: 'choice',
    instructions: `If the passenger's body is pushed ${dir} exactly ${HORIZON_SEC} seconds from now, how strong is that push? Judge it from \`windows\`, \`now\` and \`nav\`, reading them with \`scale\` and \`rules\`.`,
    criteria: Object.fromEntries(LEVEL_CLASSES.map((c) => [c, LEVEL_CRITERIA[c]])),
  };
}

export class UpstreamError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

const round = (value, digits) => Number(value.toFixed(digits));
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

  const { signals, nextTurn, frontEvent } = body;

  if (!Array.isArray(signals) || signals.length !== SAMPLE_COUNT) {
    return `signals must be an array of ${SAMPLE_COUNT}`;
  }
  for (let i = 0; i < signals.length; i++) {
    const error = signalError(signals[i], i);
    if (error) return error;
    if (i > 0 && signals[i].ageMs >= signals[i - 1].ageMs) {
      return 'signals must be oldest first (ageMs decreasing)';
    }
  }

  return routeItemError(nextTurn, 'nextTurn') ?? routeItemError(frontEvent, 'frontEvent');
}

const routeItem = (item) => (item === null ? null : { type: item.type, distanceM: item.distanceM });

// Only contract fields reach describeState, so anything else in the body never reaches Jev.
export function buildState({ signals, nextTurn, frontEvent }) {
  const rows = signals.map(({ speedKph, steeringDeg, brake, turnSignal }) => ({ speedKph, steeringDeg, brake, turnSignal }));
  return describeState(rows, routeItem(nextTurn), routeItem(frontEvent));
}

export function buildJevRequest(input) {
  const questions = {};
  for (const dir of DIRECTIONS) {
    questions[`${dir}_on`] = onQuestion(dir);
    questions[`${dir}_level`] = levelQuestion(dir);
  }
  return { model: JEV_MODEL, state: buildState(input), questions };
}

const isProbability = (v) => isFiniteNumber(v) && v >= 0 && v <= 1;

function probabilitiesOf(jev, key, classes) {
  const probabilities = jev.answers[key]?.probabilities;
  if (!isObject(probabilities) || !classes.every((c) => isProbability(probabilities[c]))) {
    throw new UpstreamError('upstream_bad_response', `missing probabilities for ${key}`);
  }
  return probabilities;
}

// Strength if pushed: the probability-weighted mean of levels 1..4, rounded to the nearest level.
function mapDirection(jev, dir) {
  const on = probabilitiesOf(jev, `${dir}_on`, ['yes', 'no']);
  const level = probabilitiesOf(jev, `${dir}_level`, LEVEL_CLASSES);
  const total = LEVEL_CLASSES.reduce((sum, c) => sum + level[c], 0);
  if (on.yes + on.no === 0 || total === 0) {
    throw new UpstreamError('upstream_bad_response', `empty probabilities for ${dir}`);
  }
  const levelMean = LEVEL_CLASSES.reduce((sum, c, i) => sum + level[c] * (i + 1), 0) / total;
  return {
    pOn: round(on.yes / (on.yes + on.no), 3),
    level: Math.min(4, Math.max(1, Math.round(levelMean))),
    levelMean: round(levelMean, 2),
  };
}

// Maps a Jev response to the contract's prediction. Throws rather than filling gaps.
export function mapJevResponse(jev) {
  if (!isObject(jev) || !isObject(jev.answers)) {
    throw new UpstreamError('upstream_bad_response', 'response has no answers');
  }
  return {
    horizonSec: HORIZON_SEC,
    push: Object.fromEntries(DIRECTIONS.map((dir) => [dir, mapDirection(jev, dir)])),
  };
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
