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
| `node tools/scenario/labels.mjs [file] [--horizon 2] [--json]` | Print expected left/right/straight and accelerate/brake/steady at t + horizon |
| `node tools/scenario/eval-predict.mjs --scenario <id> [--window] [--stride 1]` | Replay a scenario through live Jev and print accuracy plus each maneuver's hint time. Needs `AI_GATEWAY_API_KEY`. `--window` scores only `demoWindow`, about $0.02 per pass (unverified, scaled from 136 s at $0.01). The whole route is one call per stride for its full real length, so use it deliberately. `--save file` keeps the calls and `--replay file` re-scores them free with other `--on`, `--off`, `--hold`, `--rtt` values |
| `node --test tools/test/` | Run the tests |

Edit the generator, not the JSON. All routes share one set of constants at the top of `from-route.mjs`: no per-route parameter, and nothing tuned against prediction results. Curvature, turns and length come from the route. Stops at turns and traffic slowdowns come from a PRNG seeded by the scenario id, so real light states and real traffic are not represented. The driver's steering (a per-corner gain error, a wandering disturbance and the small corrections that follow) uses its own PRNG seeded by the id; corrections never move the wheel across the 15 degree label threshold, so corners and labels stay the road's.

A route of 1500 m or less follows `SHORT` instead: no lights or PRNG traffic, a 50 km/h cap, a hard launch at the origin, and one announced hard brake at 60% of its longest straight. These values are the same for every route and were not tuned against prediction results. A scenario of 150 s or less plays whole, so its `demoWindow` is the full timeline. The app lists only scenarios with a `demoWindow`.

Route data in `tools/scenario/routes/` are responses of the public OSRM demo server, derived from © OpenStreetMap contributors (ODbL).
