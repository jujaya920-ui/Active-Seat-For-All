import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it, mock } from 'node:test';

import handler from '../api/predict.js';
import { GATEWAY_URL, JEV_MODEL } from '../lib/jev.js';

const TOKEN = 'test-token';
const KEY = 'test-key';

const signal = (ageMs, extra = {}) => ({
  ageMs,
  speedKph: 42,
  steeringDeg: 3,
  brake: false,
  turnSignal: 'left',
  ...extra,
});

const validInput = () => ({
  signals: [800, 600, 400, 200, 0].map((age) => signal(age)),
  nextTurn: { type: 'TURN_LEFT', distanceM: 80 },
  frontEvent: { type: 'CONGESTION', distanceM: 200 },
  horizonsSec: [1.6, 2.0],
});

// Documented shape of POST /v1/evaluate, with confidence where the AI SDK guide puts it.
const jevResponse = () => ({
  model: JEV_MODEL,
  answers: {
    lateral_0: { type: 'choice', choice: 'left', probabilities: { left: 0.8, right: 0.1, straight: 0.1 } },
    longitudinal_0: { type: 'choice', choice: 'brake', probabilities: { accelerate: 0.1, brake: 0.7, steady: 0.2 } },
    lateral_1: { type: 'choice', choice: 'straight', probabilities: { left: 0.3, right: 0.1, straight: 0.6 } },
    longitudinal_1: { type: 'choice', choice: 'steady', probabilities: { accelerate: 0.1, brake: 0.4, steady: 0.5 } },
  },
  usage: { inputTokens: 400, outputTokens: 60 },
  providerMetadata: {
    typesafe: { confidence: { lateral_0: 0.7, longitudinal_0: 0.6, lateral_1: 0.5, longitudinal_1: 0.4 } },
  },
});

const post = (body, headers = { 'x-demo-token': TOKEN }) =>
  handler.fetch(
    new Request('http://localhost/api/predict', {
      method: 'POST',
      headers,
      body: typeof body === 'string' ? body : JSON.stringify(body),
    }),
  );

const mockGateway = (impl) => {
  const fake = mock.method(globalThis, 'fetch', impl);
  return fake;
};
const replyWith = (body, status = 200) => async () => Response.json(body, { status });

beforeEach(() => {
  process.env.DEMO_TOKEN = TOKEN;
  process.env.AI_GATEWAY_API_KEY = KEY;
  mock.method(console, 'error', () => {});
});

afterEach(() => {
  mock.restoreAll();
  delete process.env.DEMO_TOKEN;
  delete process.env.AI_GATEWAY_API_KEY;
});

describe('auth', () => {
  it('rejects a missing token without calling the gateway', async () => {
    const fake = mockGateway(replyWith(jevResponse()));
    const response = await post(validInput(), {});
    assert.equal(response.status, 401);
    assert.equal(fake.mock.callCount(), 0);
  });

  it('rejects a wrong token', async () => {
    mockGateway(replyWith(jevResponse()));
    const response = await post(validInput(), { 'x-demo-token': 'wrong' });
    assert.equal(response.status, 401);
  });

  it('fails closed when DEMO_TOKEN is not configured', async () => {
    delete process.env.DEMO_TOKEN;
    mockGateway(replyWith(jevResponse()));
    const response = await post(validInput(), { 'x-demo-token': '' });
    assert.equal(response.status, 401);
  });

  it('rejects methods other than POST', async () => {
    const response = await handler.fetch(new Request('http://localhost/api/predict'));
    assert.equal(response.status, 405);
  });
});

describe('validation', () => {
  const cases = {
    'malformed JSON': '{',
    'non-object body': [],
    'too few signals': { ...validInput(), signals: validInput().signals.slice(1) },
    'too many signals': { ...validInput(), signals: [signal(1000), ...validInput().signals] },
    'newest first signals': { ...validInput(), signals: validInput().signals.reverse() },
    'non-numeric speed': {
      ...validInput(),
      signals: [signal(800, { speedKph: '42' }), ...validInput().signals.slice(1)],
    },
    'non-boolean brake': {
      ...validInput(),
      signals: [signal(800, { brake: 1 }), ...validInput().signals.slice(1)],
    },
    'absent nextTurn': { ...validInput(), nextTurn: undefined },
    'absent frontEvent': { ...validInput(), frontEvent: undefined },
    'malformed nextTurn': { ...validInput(), nextTurn: { type: 'TURN_LEFT' } },
    'malformed frontEvent': { ...validInput(), frontEvent: 'CONGESTION' },
    'empty horizons': { ...validInput(), horizonsSec: [] },
    'seven horizons': { ...validInput(), horizonsSec: [1, 2, 3, 4, 5, 6, 7] },
    'non-positive horizon': { ...validInput(), horizonsSec: [0] },
  };

  for (const [name, body] of Object.entries(cases)) {
    it(`returns 400 for ${name}`, async () => {
      const fake = mockGateway(replyWith(jevResponse()));
      const response = await post(body);
      assert.equal(response.status, 400);
      assert.equal((await response.json()).error, 'invalid_input');
      assert.equal(fake.mock.callCount(), 0);
    });
  }

  it('accepts null nextTurn and frontEvent', async () => {
    mockGateway(replyWith(jevResponse()));
    const response = await post({ ...validInput(), nextTurn: null, frontEvent: null });
    assert.equal(response.status, 200);
  });
});

describe('jev request', () => {
  it('makes one authenticated call with all questions', async () => {
    const fake = mockGateway(replyWith(jevResponse()));
    await post(validInput());

    assert.equal(fake.mock.callCount(), 1);
    const [url, init] = fake.mock.calls[0].arguments;
    const sent = JSON.parse(init.body);

    assert.equal(url, GATEWAY_URL);
    assert.equal(init.headers.authorization, `Bearer ${KEY}`);
    assert.equal(sent.model, JEV_MODEL);
    assert.deepEqual(Object.keys(sent.questions).sort(), [
      'lateral_0',
      'lateral_1',
      'longitudinal_0',
      'longitudinal_1',
    ]);
    assert.equal(sent.questions.lateral_1.type, 'choice');
    assert.match(sent.questions.lateral_1.instructions, /2 seconds/);
  });

  it('forwards contract fields only', async () => {
    const fake = mockGateway(replyWith(jevResponse()));
    const input = validInput();
    input.scenarioId = 'pangyo-gangnam';
    input.signals[0].futureBrakeAt = 12.4;
    input.nextTurn.description = 'secret';
    await post(input);

    const { state } = JSON.parse(fake.mock.calls[0].arguments[1].body);
    assert.deepEqual(Object.keys(state).sort(), ['frontEvent', 'motion', 'nextTurn', 'notes', 'projections', 'signals', 'wheelNow']);
    assert.deepEqual(Object.keys(state.signals[0]).sort(), [
      'ageMs',
      'brake',
      'speedKph',
      'steeringDeg',
      'turnSignal',
    ]);
    assert.deepEqual(Object.keys(state.nextTurn).sort(), ['distanceM', 'etaSec', 'type']);
  });

  it('derives eta and motion from the signals', async () => {
    const fake = mockGateway(replyWith(jevResponse()));
    const input = validInput();
    input.signals = [800, 600, 400, 200, 0].map((age, i) => signal(age, { speedKph: 36 - i * 1.8, steeringDeg: i * -10 }));
    await post(input);

    const { state } = JSON.parse(fake.mock.calls[0].arguments[1].body);
    assert.deepEqual(state.motion, { accelMps2: -2.5, steeringRateDegS: -50 });
    // The newest speed, 28.8 km/h, is 8 m/s.
    assert.deepEqual(state.nextTurn, { type: 'TURN_LEFT', distanceM: 80, etaSec: 10 });
    assert.deepEqual(state.frontEvent, { type: 'CONGESTION', distanceM: 200, etaSec: 25 });
  });

  it('projects each horizon by arithmetic only', async () => {
    const fake = mockGateway(replyWith(jevResponse()));
    const input = validInput();
    input.signals = [800, 600, 400, 200, 0].map((age, i) => signal(age, { speedKph: 36 - i * 1.8, steeringDeg: i * -10 }));
    input.frontEvent = null;
    input.horizonsSec = [2, 20];
    await post(input);

    const { state } = JSON.parse(fake.mock.calls[0].arguments[1].body);
    assert.equal(state.wheelNow, '40 degrees right');
    assert.deepEqual(state.projections[0], {
      horizonSec: 2,
      speedKph: 10.8, // 28.8 km/h falling 2.5 m/s² for 2 s.
      wheel: '140 degrees right', // -40 degrees turning at -50 per second.
      nextTurnEtaAfterSec: 8,
      frontEventEtaAfterSec: null,
      stopDecelAfterMps2: 0.5, // 8 m/s over the 64 m left after 2 s.
    });
    assert.equal(state.projections[1].speedKph, 0);
    assert.equal(state.projections[1].stopDecelAfterMps2, null);
  });

  it('calls a centred wheel centred', async () => {
    const fake = mockGateway(replyWith(jevResponse()));
    const input = validInput();
    input.signals = [800, 600, 400, 200, 0].map((age) => signal(age, { steeringDeg: 0 }));
    await post(input);
    assert.equal(JSON.parse(fake.mock.calls[0].arguments[1].body).state.wheelNow, 'centred');
  });

  it('sends no eta when stopped', async () => {
    const fake = mockGateway(replyWith(jevResponse()));
    const input = validInput();
    input.signals = [800, 600, 400, 200, 0].map((age) => signal(age, { speedKph: 0 }));
    await post(input);

    const { state } = JSON.parse(fake.mock.calls[0].arguments[1].body);
    assert.equal(state.nextTurn.etaSec, null);
  });

  it('states each horizon in the question and keeps timing rules out of the criteria', async () => {
    const fake = mockGateway(replyWith(jevResponse()));
    await post(validInput());

    const { questions } = JSON.parse(fake.mock.calls[0].arguments[1].body);
    assert.match(questions.lateral_0.instructions, /exactly 1\.6 seconds/);
    // The only numbers allowed are the horizon, the state index and the label definitions.
    const numbers = JSON.stringify(questions).match(/\d+(\.\d+)?/g);
    assert.deepEqual([...new Set(numbers)].sort(), ['0', '0.6', '1', '1.6', '15', '2']);
    assert.deepEqual(Object.keys(questions.lateral_0.criteria), ['left', 'right', 'straight']);
    assert.deepEqual(Object.keys(questions.longitudinal_0.criteria), ['accelerate', 'brake', 'steady']);
  });
});

describe('response mapping', () => {
  it('maps answers to the contract in horizon order', async () => {
    mockGateway(replyWith(jevResponse()));
    const response = await post(validInput());
    assert.equal(response.status, 200);

    const body = await response.json();
    assert.deepEqual(Object.keys(body).sort(), ['predictions', 'serverMs']);
    assert.equal(typeof body.serverMs, 'number');
    assert.deepEqual(body.predictions, [
      {
        horizonSec: 1.6,
        lateral: { value: 'left', probability: 0.8, confidence: 0.7 },
        longitudinal: { value: 'brake', probability: 0.7, confidence: 0.6 },
        hint: 0.8,
      },
      {
        horizonSec: 2,
        lateral: { value: 'straight', probability: 0.6, confidence: 0.5 },
        longitudinal: { value: 'steady', probability: 0.5, confidence: 0.4 },
        hint: 0.4,
      },
    ]);
  });

  it('accepts confidence on the answer object', async () => {
    const jev = jevResponse();
    delete jev.providerMetadata;
    for (const key of ['lateral_0', 'longitudinal_0', 'lateral_1', 'longitudinal_1']) {
      jev.answers[key].confidence = 0.55;
    }
    mockGateway(replyWith(jev));
    const response = await post(validInput());
    assert.equal(response.status, 200);
    assert.equal((await response.json()).predictions[1].lateral.confidence, 0.55);
  });

  const broken = {
    'no answers': (jev) => delete jev.answers,
    'missing question': (jev) => delete jev.answers.longitudinal_1,
    'unknown choice': (jev) => (jev.answers.lateral_0.choice = 'up'),
    'missing probabilities': (jev) => delete jev.answers.lateral_0.probabilities,
    'missing confidence': (jev) => delete jev.providerMetadata,
  };

  for (const [name, breakIt] of Object.entries(broken)) {
    it(`returns 502 rather than guessing for ${name}`, async () => {
      const jev = jevResponse();
      breakIt(jev);
      mockGateway(replyWith(jev));
      const response = await post(validInput());
      assert.equal(response.status, 502);
      assert.deepEqual(await response.json(), { error: 'upstream_bad_response' });
    });
  }
});

describe('upstream failure', () => {
  it('returns 502 on a gateway error status without leaking its body', async () => {
    mockGateway(replyWith({ message: 'internal detail' }, 403));
    const response = await post(validInput());
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { error: 'upstream_status' });
  });

  it('returns 502 on a network error', async () => {
    mockGateway(async () => {
      throw new TypeError('fetch failed');
    });
    const response = await post(validInput());
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { error: 'upstream_unreachable' });
  });

  it('returns 502 on a non-JSON body', async () => {
    mockGateway(async () => new Response('<html>', { status: 200 }));
    const response = await post(validInput());
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { error: 'upstream_bad_response' });
  });

  it('returns 500 when the gateway key is not configured', async () => {
    delete process.env.AI_GATEWAY_API_KEY;
    const fake = mockGateway(replyWith(jevResponse()));
    const response = await post(validInput());
    assert.equal(response.status, 500);
    assert.equal(fake.mock.callCount(), 0);
  });

  it('times out after about 1500 ms and returns 502', async () => {
    // Behaves like real fetch: never answers, rejects when the signal aborts.
    mockGateway(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          init.signal.addEventListener('abort', () => reject(init.signal.reason));
        }),
    );

    const startedAt = performance.now();
    const response = await post(validInput());
    const elapsed = performance.now() - startedAt;

    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { error: 'upstream_timeout' });
    assert.ok(elapsed >= 1400 && elapsed < 2500, `elapsed ${elapsed} ms`);
  });
});
