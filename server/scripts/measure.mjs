// Runs fixed corner/brake/accel inputs N times and prints round trip and prediction stability.
//
// Through the deployed function (needs DEMO_TOKEN):
//   node scripts/measure.mjs --url https://<host>/api/predict --runs 20
// Directly against the gateway (needs AI_GATEWAY_API_KEY):
//   node scripts/measure.mjs --direct --runs 20

import { parseArgs } from 'node:util';
import { setTimeout as sleep } from 'node:timers/promises';

import { DIRECTIONS, SAMPLE_COUNT } from '../lib/describe.js';
import { buildJevRequest, callJev, mapJevResponse, validateInput } from '../lib/jev.js';

// SAMPLE_COUNT samples, oldest first, 200 ms apart, from per-sample functions of the index.
const samples = ({ kph, wheel = () => 0, brake = () => false, signal = 'off' }) =>
  Array.from({ length: SAMPLE_COUNT }, (_, i) => ({
    ageMs: (SAMPLE_COUNT - 1 - i) * 200,
    speedKph: kph(i),
    steeringDeg: wheel(i),
    brake: brake(i),
    turnSignal: signal,
  }));

const INPUTS = {
  corner: {
    signals: samples({ kph: (i) => (i < 20 ? 45 : 45 - (i - 20)), wheel: (i) => (i < 28 ? 0 : (i - 28) * 3), brake: (i) => i >= 20, signal: 'left' }),
    nextTurn: { type: 'TURN_LEFT', distanceM: 15 },
    frontEvent: null,
  },
  brake: {
    signals: samples({ kph: (i) => (i < 27 ? 60 : 60 - (i - 27) * 1.5), brake: (i) => i >= 27 }),
    nextTurn: null,
    frontEvent: { type: 'CONGESTION', distanceM: 60 },
  },
  accel: {
    signals: samples({ kph: (i) => (i < 25 ? 0 : (i - 25) * 2.5) }),
    nextTurn: null,
    frontEvent: null,
  },
};

function usage(message) {
  console.error(message);
  console.error('usage: measure.mjs (--url <predict url> | --direct) [--runs N] [--pause-ms N]');
  console.error('env: DEMO_TOKEN for --url, AI_GATEWAY_API_KEY for --direct');
  process.exit(2);
}

function parseOptions() {
  let values;
  try {
    ({ values } = parseArgs({
      options: {
        url: { type: 'string' },
        direct: { type: 'boolean', default: false },
        runs: { type: 'string', default: '10' },
        'pause-ms': { type: 'string', default: '1000' },
      },
    }));
  } catch (error) {
    usage(error.message);
  }

  const runs = Number(values.runs);
  const pauseMs = Number(values['pause-ms']);
  if (!Number.isInteger(runs) || runs < 1) usage('--runs must be a positive integer');
  if (!Number.isFinite(pauseMs) || pauseMs < 0) usage('--pause-ms must be >= 0');
  if (Boolean(values.url) === values.direct) usage('pass exactly one of --url or --direct');

  return { url: values.url, direct: values.direct, runs, pauseMs };
}

function directCaller() {
  const apiKey = process.env.AI_GATEWAY_API_KEY;
  if (!apiKey) usage('AI_GATEWAY_API_KEY is not set');
  return async (input) => mapJevResponse(await callJev(buildJevRequest(input), apiKey)).push;
}

function urlCaller(url) {
  const token = process.env.DEMO_TOKEN;
  if (!token) usage('DEMO_TOKEN is not set');
  return async (input) => {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-demo-token': token },
      body: JSON.stringify(input),
    });
    const body = await response.json();
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${body.error}`);
    return body.push;
  };
}

const range = (values) => `${Math.min(...values).toFixed(2)}~${Math.max(...values).toFixed(2)}`;

function timing(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mean = sorted.reduce((sum, v) => sum + v, 0) / sorted.length;
  const median = sorted[Math.floor((sorted.length - 1) / 2)];
  return `min ${sorted[0]} / median ${median} / mean ${Math.round(mean)} / max ${sorted.at(-1)} ms`;
}

function report(name, roundTrips, results, failures) {
  console.log(`\n== ${name}: ${results.length} ok, ${failures.length} failed`);
  for (const message of new Set(failures)) console.log(`  failure: ${message}`);
  if (results.length === 0) return;

  console.log(`  round trip: ${timing(roundTrips)}`);
  for (const dir of DIRECTIONS) {
    const levels = results.map((push) => push[dir].level);
    console.log(`  ${dir.padEnd(8)} pOn ${range(results.map((push) => push[dir].pOn))}, level ${[...new Set(levels)].join('/')}`);
  }
}

async function main() {
  const { url, direct, runs, pauseMs } = parseOptions();
  const predict = direct ? directCaller() : urlCaller(url);

  console.log(`target: ${direct ? 'gateway (direct)' : url}, runs per input: ${runs}`);

  for (const [name, input] of Object.entries(INPUTS)) {
    const inputError = validateInput(input);
    if (inputError) throw new Error(`${name}: ${inputError}`);

    const roundTrips = [];
    const results = [];
    const failures = [];

    for (let run = 0; run < runs; run++) {
      if (run > 0) await sleep(pauseMs);
      const startedAt = performance.now();
      try {
        results.push(await predict(input));
        roundTrips.push(Math.round(performance.now() - startedAt));
      } catch (error) {
        failures.push(error.message);
      }
    }

    report(name, roundTrips, results, failures);
  }
}

await main();
