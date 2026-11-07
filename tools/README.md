# tools

PC-side demo tools. Node 24, no dependencies. Run everything from the repo root.

## Console proxy

Serves the Virtual Console page with its speed integration paused unless a pedal is held, so scenario playback through the API does not conflict with the page.

```sh
adb forward tcp:18088 tcp:8088
node tools/console-proxy.js
```

Open http://127.0.0.1:18089. A red banner means a page internal (`physicsTick`, `pedals`, `pedalControl`, `refresh`) is gone, usually after a console update. The page is then left unmodified, so run the scenario without it.

## Server tests

`npm test --prefix server`. Do not use `node --test server/test/`: Node 24 takes a directory argument as a module path and fails with `MODULE_NOT_FOUND`.

## Scenarios

| Command | Purpose |
| --- | --- |
| `node tools/scenario/from-route.mjs [id...]` | Generate `app/src/main/assets/scenarios/<id>.json` from `tools/scenario/routes/` with one driver model. Writes nothing for a route that fails validation. `build.mjs` runs the same thing |
| `node tools/scenario/validate.mjs [file...]` | Check format and constraints, and print event times |
| `node tools/scenario/labels.mjs [file] [--horizon 2] [--json]` | Print each body push span (forward, backward, left, right) with its peak level, from the server's own preprocessing |
| `node tools/scenario/eval-predict.mjs --scenario <id> [--window] [--stride 1]` | Replay a scenario through live Jev with the app's timing (applied at call + 1 s, dropped past 1 s round trip) and print per-direction recall and precision against the push at t + 2 s, on-time events and false hint time. Needs `AI_GATEWAY_API_KEY`. A short route costs about $0.008 per pass (measured 2026-11-02). `--save file` keeps the calls and `--replay file` re-scores them free with other `--on`, `--off`, `--hold` values |
| `node --test tools/test/` | Run the tests |

Edit the generator, not the JSON. All routes share one set of constants at the top of `from-route.mjs`: no per-route parameter, and nothing tuned against prediction results. Curvature, turns and length come from the route. Stops at turns and traffic slowdowns come from a PRNG seeded by the scenario id, so real light states and real traffic are not represented. The driver's steering (a per-corner gain error, a wandering disturbance and the small corrections that follow) uses its own PRNG seeded by the id; corrections never move the wheel across the 15 degree label threshold, so corners and labels stay the road's.

Speed cameras (`cameras`, limit 50 km/h urban, 100 km/h motorway) and caution points (`cautionPoints`, speed bumps and school zones on urban roads) follow `CAMERAS` and `CAUTION`, with their own PRNG seeds on long routes. A camera passed at the limit tells a prediction nothing, so the driver speeds 15 km/h over the limit before half of them and brakes at 3 m/s² to 5 km/h under it at the camera; the other half are passed at or below the limit.

A route of 1500 m or less follows `SHORT` instead: no lights or PRNG traffic, a 50 km/h cap, a hard launch at the origin, one announced hard brake at 60% of its longest straight, one camera always approached speeding near the end of the second longest straight, and a speed bump in the middle of the third longest straight when the car is above 35 km/h before it. These values are the same for every route and were not tuned against prediction results. A scenario of 150 s or less plays whole, so its `demoWindow` is the full timeline. The app lists only scenarios with a `demoWindow`.

Route data in `tools/scenario/routes/` are responses of the public OSRM demo server, derived from © OpenStreetMap contributors (ODbL).
