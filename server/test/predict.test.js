import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it, mock } from 'node:test';

import handler from '../api/predict.js';
import { SAMPLE_COUNT } from '../lib/describe.js';
import { GATEWAY_URL, JEV_MODEL } from '../lib/jev.js';

const TOKEN = 'test-token';
const KEY = 'test-key';
const DIRS = ['forward', 'backward', 'left', 'right'];

const signal = (ageMs, extra = {}) => ({
  ageMs,
  speedKph: 42,
  steeringDeg: 3,
  brake: false,
  turnSignal: 'left',
  ...extra,
});

const ages = () => Array.from({ length: SAMPLE_COUNT }, (_, i) => (SAMPLE_COUNT - 1 - i) * 200);

const validInput = () => ({
  signals: ages().map((age) => signal(age)),
  nextTurn: { type: 'TURN_LEFT', distanceM: 80 },
  frontEvent: { type: 'CONGESTION', distanceM: 200 },
});

const levelProbabilities = (weak, moderate, strong, veryStrong) => ({ weak, moderate, strong, 'very strong': veryStrong });

// Documented shape of POST /v1/evaluate: one choice answer per question.
const jevResponse = () => ({
  model: JEV_MODEL,
  answers: {
    forward_on: { type: 'choice', choice: 'yes', probabilities: { yes: 0.88, no: 0.12 } },
    forward_level: { type: 'choice', choice: 'moderate', probabilities: levelProbabilities(0.2, 0.55, 0.22, 0.03) },
    backward_on: { type: 'choice', choice: 'no', probabilities: { yes: 0.04, no: 0.96 } },
    backward_level: { type: 'choice', choice: 'weak', probabilities: levelProbabilities(0.7, 0.25, 0.05, 0) },
    left_on: { type: 'choice', choice: 'no', probabilities: { yes: 0.06, no: 0.94 } },
    left_level: { type: 'choice', choice: 'weak', probabilities: levelProbabilities(0.8, 0.15, 0.05, 0) },
    right_on: { type: 'choice', choice: 'yes', probabilities: { yes: 0.62, no: 0.38 } },
    right_level: { type: 'choice', choice: 'moderate', probabilities: levelProbabilities(0.3, 0.5, 0.18, 0.02) },
  },
  usage: { inputTokens: 1500, outputTokens: 120 },
});

const post = (body, headers = { 'x-demo-token': TOKEN }) =>
  handler.fetch(
    new Request('http://localhost/api/predict', {
      method: 'POST',
      headers,
      body: typeof body === 'string' ? body : JSON.stringify(body),
    }),
  );

const mockGateway = (impl) => mock.method(globalThis, 'fetch', impl);
const replyWith = (body, status = 200) => async () => Response.json(body, { status });
const sentBody = (fake) => JSON.parse(fake.mock.calls[0].arguments[1].body);

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
  const withFirst = (extra) => ({ ...validInput(), signals: [signal(ages()[0], extra), ...validInput().signals.slice(1)] });
  const cases = {
    'malformed JSON': '{',
    'non-object body': [],
    'too few signals': { ...validInput(), signals: validInput().signals.slice(1) },
    'too many signals': { ...validInput(), signals: [signal(99999), ...validInput().signals] },
    'newest first signals': { ...validInput(), signals: validInput().signals.reverse() },
    'non-numeric speed': withFirst({ speedKph: '42' }),
    'non-boolean brake': withFirst({ brake: 1 }),
    'absent nextTurn': { ...validInput(), nextTurn: undefined },
    'absent frontEvent': { ...validInput(), frontEvent: undefined },
    'malformed nextTurn': { ...validInput(), nextTurn: { type: 'TURN_LEFT' } },
    'malformed frontEvent': { ...validInput(), frontEvent: 'CONGESTION' },
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

  it('ignores a leftover horizonsSec field', async () => {
    mockGateway(replyWith(jevResponse()));
    const response = await post({ ...validInput(), horizonsSec: [1.6, 2.0] });
    assert.equal(response.status, 200);
  });
});

describe('jev request', () => {
  it('makes one authenticated call with an on and a level question per direction', async () => {
    const fake = mockGateway(replyWith(jevResponse()));
    await post(validInput());

    assert.equal(fake.mock.callCount(), 1);
    const [url, init] = fake.mock.calls[0].arguments;
    const sent = JSON.parse(init.body);
    assert.equal(url, GATEWAY_URL);
    assert.equal(init.headers.authorization, `Bearer ${KEY}`);
    assert.equal(sent.model, JEV_MODEL);
    assert.deepEqual(Object.keys(sent.questions).sort(), DIRS.flatMap((d) => [`${d}_level`, `${d}_on`]).sort());
    for (const dir of DIRS) {
      assert.deepEqual(Object.keys(sent.questions[`${dir}_on`].criteria), ['yes', 'no']);
      assert.deepEqual(Object.keys(sent.questions[`${dir}_level`].criteria), ['weak', 'moderate', 'strong', 'very strong']);
      assert.match(sent.questions[`${dir}_on`].instructions, /Exactly 2 seconds/);
    }
  });

  it('sends only plain language: scale, rules, three windows, now and nav', async () => {
    const fake = mockGateway(replyWith(jevResponse()));
    await post({ ...validInput(), extra: 'secret' });
    const { state } = sentBody(fake);
    assert.deepEqual(Object.keys(state).sort(), ['nav', 'now', 'rules', 'scale', 'windows']);
    assert.equal(state.windows.length, 3);
    assert.doesNotMatch(JSON.stringify(state), /secret|steeringDeg|degrees/);
  });

  it('keeps numbers out of the criteria', async () => {
    const fake = mockGateway(replyWith(jevResponse()));
    await post(validInput());
    for (const q of Object.values(sentBody(fake).questions)) {
      assert.doesNotMatch(JSON.stringify(q.criteria), /[0-9]/);
    }
  });
});

describe('response mapping', () => {
  it('returns pOn and the weighted level for each body direction', async () => {
    mockGateway(replyWith(jevResponse()));
    const response = await post(validInput());
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.horizonSec, 2);
    assert.deepEqual(body.push.forward, { pOn: 0.88, level: 2, levelMean: 2.08 });
    assert.deepEqual(body.push.right, { pOn: 0.62, level: 2, levelMean: 1.92 });
    assert.equal(body.push.backward.pOn, 0.04);
    assert.equal(typeof body.serverMs, 'number');
  });

  it('normalizes probabilities that do not sum to one', async () => {
    const jev = jevResponse();
    jev.answers.left_on.probabilities = { yes: 0.3, no: 0.3 };
    jev.answers.left_level.probabilities = levelProbabilities(0, 0, 0, 0.5);
    mockGateway(replyWith(jev));
    const body = await (await post(validInput())).json();
    assert.deepEqual(body.push.left, { pOn: 0.5, level: 4, levelMean: 4 });
  });

  const broken = {
    'no answers': (jev) => delete jev.answers,
    'missing on question': (jev) => delete jev.answers.right_on,
    'missing level question': (jev) => delete jev.answers.backward_level,
    'missing class probability': (jev) => delete jev.answers.forward_level.probabilities.strong,
    'out of range probability': (jev) => (jev.answers.left_on.probabilities.yes = 1.2),
    'all zero level': (jev) => (jev.answers.left_level.probabilities = levelProbabilities(0, 0, 0, 0)),
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
