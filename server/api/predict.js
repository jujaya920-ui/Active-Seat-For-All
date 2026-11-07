// POST /api/predict: relays one prediction request to Jev through Vercel AI Gateway.

import { buildJevRequest, callJev, mapJevResponse, UpstreamError, validateInput } from '../lib/jev.js';

const json = (status, body) => Response.json(body, { status });

async function handle(request, startedAt) {
  if (request.method !== 'POST') {
    return json(405, { error: 'method_not_allowed' });
  }

  const expectedToken = process.env.DEMO_TOKEN;
  if (!expectedToken || request.headers.get('x-demo-token') !== expectedToken) {
    return json(401, { error: 'unauthorized' });
  }

  let input;
  try {
    input = await request.json();
  } catch {
    return json(400, { error: 'invalid_input', message: 'body is not valid JSON' });
  }

  const inputError = validateInput(input);
  if (inputError) {
    return json(400, { error: 'invalid_input', message: inputError });
  }

  const apiKey = process.env.AI_GATEWAY_API_KEY;
  if (!apiKey) {
    console.error('AI_GATEWAY_API_KEY is not set');
    return json(500, { error: 'server_misconfigured' });
  }

  try {
    const jev = await callJev(buildJevRequest(input), apiKey);
    const prediction = mapJevResponse(jev);
    return json(200, { ...prediction, serverMs: Math.round(performance.now() - startedAt) });
  } catch (error) {
    if (!(error instanceof UpstreamError)) throw error;
    // Details stay in the server log. The client only needs the failure class.
    console.error(`${error.code}: ${error.message}`);
    return json(502, { error: error.code });
  }
}

export default {
  fetch(request) {
    return handle(request, performance.now());
  },
};
