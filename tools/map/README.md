# Map backgrounds

`render.mjs` renders the `app/src/main/assets/maps/<id>.webp` of every route in its `ROUTES` at 2000x2880
from OpenFreeMap vector tiles, with MapLibre GL JS in headless Chrome. Each image covers exactly
`fitRoute(coordinates).bounds` from `tools/scenario/projection.mjs` in Web Mercator, so a route projected
with `toPx` lands on the roads of the 1000x1440 image space. The route itself is not drawn. The script
aborts if the camera misses the bounds by more than 0.01 px.

## Run

Installed Chrome and a Korean system font (Malgun Gothic or Noto Sans KR) are required. Keep the
dependencies outside the repo.

```sh
npm install --prefix <deps> --ignore-scripts maplibre-gl@5.24.0 playwright-core@1.64.1
node tools/map/render.mjs --routes <dir with osrm_*.json> --deps <deps>
```

Options: `--only <name>`, `--out <dir>`, `--cache <dir>` (default `<deps>/tile-cache`).

## Rules

- OpenFreeMap terms: at most 4 parallel requests, the unique User-Agent `ActiveSeat-demo-map-render`, under
  3000 tiles and 50 MB. The script enforces all of these per run and caches tiles on disk.
- The third column of `ROUTES` adds tile zoom levels for detail. Each level quadruples the tiles fetched.
  A full run with an empty cache needs a few hundred tiles.
- Hangul glyphs come from the local font (`localIdeographFontFamily`); the Latin ones come from OpenFreeMap.
- The attribution in the lower right corner is required. Do not remove it or crop it in the app.
