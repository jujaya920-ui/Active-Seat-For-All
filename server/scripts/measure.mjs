// Runs fixed corner/brake/accel inputs N times and prints round trip and prediction stability.
//
// Through the deployed function (needs DEMO_TOKEN):
//   node scripts/measure.mjs --url https://<host>/api/predict --runs 20
// Directly against the gateway (needs AI_GATEWAY_API_KEY):
//   node scripts/measure.mjs --direct --runs 20

import { parseArgs } from 'node:util';
import { setTimeout as sleep } from 'node:timers/promises';

import { buildJevRequest, callJev, mapJevResponse, validateInput } from '../lib/jev.js';

const HORIZONS_SEC = [1.6, 1.8, 2.0, 2.2, 2.4];

// Builds five samples, oldest first, 200 ms apart.
const samples = (rows) =>
  rows.map(([speedKph, steeringDeg, brake, turnSignal], i) => ({
    ageMs: (rows.length - 1 - i) * 200,
    speedKph,
    steeringDeg,
    brake,
    turnSignal,
  }));

const INPUTS = {
  corner: {
    signals: samples([
      [42, 0, false, 'left'],
      [41, 0, false, 'left'],
      [40, 1, true, 'left'],
      [38, 2, true, 'left'],
      [36, 4, true, 'left'],
    ]),
    nextTurn: { type: 'TURN_LEFT', distanceM: 30 },
    frontEvent: null,
    horizonsSec: HORIZONS_SEC,
  },
  brake: {
    signals: samples([
      [60, 0, false, 'off'],
      [60, 0, false, 'off'],
      [59, 0, false, 'off'],
      [58, 0, true, 'off'],
      [55, 0, true, 'off'],
    ]),
    nextTurn: null,
    frontEvent: { type: 'CONGESTION', distanceM: 60 },
    horizonsSec: HORIZONS_SEC,
  },
  accel: {
    signals: samples([
      [20, 0, false, 'off'],
      [23, 0, false, 'off'],
      [26, 0, false, 'off'],
      [30, 0, false, 'off'],
      [34, 0, false, 'off'],
    ]),
    nextTurn: null,
    frontEvent: null,
    horizonsSec: HORIZONS_SEC,
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
  return async (input) => {
    const jev = await callJev(buildJevRequest(input), apiKey);
    return mapJevResponse(jev, input.horizonsSec);
  };
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
    return body.predictions;
  };
}

const format = (v) => v.toFixed(2);
const rangeOf = (values) => `${format(Math.min(...values))}~${format(Math.max(...values))}`;

function timing(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mean = sorted.reduce((sum, v) => sum + v, 0) / sorted.length;
  const median = sorted[Math.floor((sorted.length - 1) / 2)];
  return `min ${sorted[0]} / median ${median} / mean ${Math.round(mean)} / max ${sorted.at(-1)} ms`;
}

// Counts how often each value won, then shows the spread of the most common value.
function stability(answers) {
  const counts = Map.groupBy(answers, (a) => a.value);
  const [value, wins] = [...counts].sort((a, b) => b[1].length - a[1].length)[0];
  const others = [...counts].filter(([v]) => v !== value).map(([v, l]) => `${v} ${l.length}`);
  const suffix = others.length ? ` (also ${others.join(', ')})` : '';
  return (
    `${value} ${wins.length}/${answers.length}${suffix}, ` +
    `p ${rangeOf(wins.map((a) => a.probability))}, conf ${rangeOf(wins.map((a) => a.confidence))}`
  );
}

function report(name, roundTrips, results, failures) {
  console.log(`\n== ${name}: ${results.length} ok, ${failures.length} failed`);
  for (const message of new Set(failures)) console.log(`  failure: ${message}`);
  if (results.length === 0) return;

  console.log(`  round trip: ${timing(roundTrips)}`);
  HORIZONS_SEC.forEach((horizon, i) => {
    const atHorizon = results.map((predictions) => predictions[i]);
    console.log(`  ${horizon}s lateral:      ${stability(atHorizon.map((p) => p.lateral))}`);
    console.log(`  ${horizon}s longitudinal: ${stability(atHorizon.map((p) => p.longitudinal))}`);
  });
  console.log(`  hint: ${rangeOf(results.map((predictions) => predictions[0].hint))}`);
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
        const predictions = await predict(input);
        roundTrips.push(Math.round(performance.now() - startedAt));
        results.push(predictions);
      } catch (error) {
        failures.push(error.message);
      }
    }

    report(name, roundTrips, results, failures);
  }
}

await main();
