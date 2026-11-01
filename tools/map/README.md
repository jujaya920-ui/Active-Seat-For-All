# Map backgrounds

`render.mjs` renders the map of every route in its `ROUTES` from OpenFreeMap vector tiles, with MapLibre
GL JS in headless Chrome, at the fixed scale of `tools/scenario/projection.mjs` (Web Mercator zoom 15). The
map covers the route plus a 900 px margin, so a route projected with `toPx` lands on the roads and the app
can keep the car at the centre of its panel. The route itself is not drawn.

- `app/src/main/assets/maps/<id>/<col>_<row>.webp`: 1024 px tiles of the full-scale map, cut from one
  render so labels do not break at seams. Only tiles within the margin of the route are kept.
- `app/src/main/assets/maps/<id>.webp`: a 2048 px square around the map, 1.6 times its longer side, so the
  zoomed-out route preview has map all the way to the panel edges.

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
- A full run with an empty cache needs a few hundred tiles.
- Hangul glyphs come from the local font (`localIdeographFontFamily`); the Latin ones come from OpenFreeMap.
- The app draws the attribution at the bottom of the panel on every screen. It is required, so do not remove it.
