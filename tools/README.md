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
| `node tools/scenario/build.mjs` | Generate `app/src/main/assets/scenarios/pangyo-gangnam.json` from `tools/scenario/pangyo-gangnam.def.mjs`. Writes nothing if validation fails |
| `node tools/scenario/validate.mjs [file...]` | Check format and constraints, and print event times |
| `node tools/scenario/labels.mjs [file] [--horizon 2] [--json]` | Print expected left/right/straight and accelerate/brake/steady at t + horizon |
| `node tools/scenario/eval-predict.mjs` | Replay a scenario through live Jev and print accuracy plus each maneuver's hint time. Needs `AI_GATEWAY_API_KEY`. Costs about $0.01 per pass. `--save file` keeps the calls and `--replay file` re-scores them free with other `--on`, `--off`, `--hold`, `--rtt` values |
| `node --test tools/test/` | Run the tests |

Edit the `.def.mjs` file, not the JSON. Turn and front event positions are computed from the integrated speed, so changing a segment moves them.
