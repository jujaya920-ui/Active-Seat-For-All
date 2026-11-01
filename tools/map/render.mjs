// Renders the bundled route maps from OpenFreeMap vector tiles with MapLibre GL JS in headless Chrome.
// See README.md. Each map is one render covering fitRoute(...)'s whole map, so a route projected with
// toPx lands on the roads and labels never break at a tile seam.
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { fitRoute, MAP } from '../scenario/projection.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const ROUTES = [
  ['gangnam-pangyo', 'osrm_gangnam_pangyo.json'],
  ['pangyo-dongpangyo', 'osrm_pangyo_dongpangyo.json'],
  ['seohyeon-yanghyeon', 'osrm_seohyeon_yanghyeon.json'],
  ['gangnam-teheran', 'osrm_gangnam_teheran.json'],
  ['seocho-jungang', 'osrm_seocho_jungang.json'],
  ['seoulstation-cheongpa', 'osrm_seoulstation_cheongpa.json'],
];
const ORIGIN = 'https://tiles.openfreemap.org';
const USER_AGENT = 'ActiveSeat-demo-map-render';
const MAX_PARALLEL = 4; // OpenFreeMap terms, together with the two caps below.
const MAX_REQUESTS = 3000;
const MAX_BYTES = 50 * 1024 * 1024;
const TILE_PX = 1024; // Same as MAP_TILE_PX in the app.
const OVERVIEW_PX = 2048;
const OVERVIEW_EXTENT = 1.6; // Same as MAP_OVERVIEW_EXTENT in the app.
const MAX_CANVAS_PX = 8192; // The largest canvas the software renderer is trusted with.
const TILE_QUALITY = 0.8;
const OVERVIEW_QUALITY = 0.85;

const { values: args } = parseArgs({
  options: {
    routes: { type: 'string' }, // directory holding the raw OSRM JSON files
    deps: { type: 'string' }, // directory whose node_modules has maplibre-gl and playwright-core
    cache: { type: 'string' }, // tile cache directory; defaults to <deps>/tile-cache
    out: { type: 'string', default: resolve(here, '../../app/src/main/assets/maps') },
    only: { type: 'string' }, // one route name
  },
});
if (!args.routes || !args.deps) {
  console.error('usage: node tools/map/render.mjs --routes <dir> --deps <dir> [--cache <dir>] [--out <dir>] [--only <name>]');
  process.exit(2);
}
const depsRequire = createRequire(join(resolve(args.deps), 'package.json'));
const { chromium } = depsRequire('playwright-core');
const maplibreDir = dirname(depsRequire.resolve('maplibre-gl/package.json'));
const cacheDir = resolve(args.cache ?? join(args.deps, 'tile-cache'));

const stats = { requests: 0, tiles: 0, bytes: 0, cached: 0 };
let active = 0;
const waiting = [];
async function limited(task) {
  if (active >= MAX_PARALLEL) await new Promise((go) => waiting.push(go));
  else active += 1;
  try {
    return await task();
  } finally {
    const next = waiting.shift();
    if (next) next();
    else active -= 1;
  }
}

// Every network request of the render passes here: one limiter, one User-Agent, one budget.
async function download(path) {
  if (stats.requests >= MAX_REQUESTS || stats.bytes >= MAX_BYTES) throw new Error('download budget exceeded');
  stats.requests += 1;
  const res = await fetch(ORIGIN + path, { headers: { 'User-Agent': USER_AGENT }, signal: AbortSignal.timeout(60_000) });
  if (res.status === 204) return new Uint8Array();
  if (!res.ok) throw new Error(`${res.status} on ${path}`);
  const body = new Uint8Array(await res.arrayBuffer());
  stats.bytes += body.length;
  if (path.endsWith('.pbf') && path.startsWith('/planet')) stats.tiles += 1;
  return body;
}

async function cached(path) {
  const file = join(cacheDir, path.replace(/[^\w.-]+/g, '_'));
  try {
    const body = await readFile(file);
    stats.cached += 1;
    return body;
  } catch {
    const body = await limited(() => download(path));
    await mkdir(cacheDir, { recursive: true });
    await writeFile(file, body);
    return body;
  }
}

const label = ['coalesce', ['get', 'name:ko'], ['get', 'name']];
const COLORS = {
  land: '#f1f4f9', urban: '#ebeef4', park: '#d5ecd2', water: '#b5dcf3', building: '#dfe4ec',
  rail: '#c3cad6', road: '#ffffff', roadCasing: '#ccd4e0', motorway: '#ffd98f', motorwayCasing: '#e2a94c',
  text: '#2a3342', textDim: '#5d6878', textWater: '#3f7fa8', halo: '#ffffff',
};

const roadLine = (id, classes, color, width, extra = {}) => ({
  id,
  type: 'line',
  source: 'omt',
  'source-layer': 'transportation',
  filter: ['all', ['in', ['get', 'class'], ['literal', classes]], ...(extra.filter ?? [])],
  layout: { 'line-cap': 'round', 'line-join': 'round', ...extra.layout },
  paint: { 'line-color': color, 'line-width': width, ...extra.paint },
});
const notTunnel = ['!=', ['get', 'brunnel'], 'tunnel'];
const isTunnel = ['==', ['get', 'brunnel'], 'tunnel'];
const placeLabel = (id, classes, size, color, font, padding) => ({
  id,
  type: 'symbol',
  source: 'omt',
  'source-layer': 'place',
  filter: ['in', ['get', 'class'], ['literal', classes]],
  layout: {
    'text-field': label,
    'text-font': [font],
    'text-size': size,
    'text-max-width': 8,
    'text-padding': padding,
  },
  paint: { 'text-color': color, 'text-halo-color': COLORS.halo, 'text-halo-width': 1.6 },
});

function buildStyle() {
  const zoomed = (...stops) => ['interpolate', ['exponential', 1.5], ['zoom'], ...stops];
  const textZoomed = (...stops) => ['interpolate', ['linear'], ['zoom'], ...stops];
  const widths = {
    minor: zoomed(10, 0.3, 12, 0.7, 14, 2, 15, 3.5),
    tertiary: zoomed(10, 0.5, 12, 1, 14, 2.6, 15, 5),
    secondary: zoomed(10, 0.7, 12, 1.4, 14, 3.4, 15, 6.5),
    primary: zoomed(10, 1, 12, 2, 14, 4.4, 15, 9),
    motorway: zoomed(10, 1.8, 12, 3, 14, 6, 15, 11),
    casing: zoomed(10, 0.8, 12, 1.6, 14, 4, 15, 7.5),
    primaryCasing: zoomed(10, 1.6, 12, 3, 14, 6, 15, 11),
    motorwayCasing: zoomed(10, 3, 12, 4.6, 14, 8, 15, 14),
  };
  return {
    version: 8,
    glyphs: 'ofm://fonts/{fontstack}/{range}.pbf',
    sources: { omt: { type: 'vector', tiles: ['ofm://tile/{z}/{x}/{y}'], minzoom: 0, maxzoom: 14 } },
    layers: [
      { id: 'land', type: 'background', paint: { 'background-color': COLORS.land } },
      {
        id: 'urban', type: 'fill', source: 'omt', 'source-layer': 'landuse',
        filter: ['in', ['get', 'class'], ['literal', ['residential', 'suburb', 'neighbourhood', 'commercial', 'industrial', 'retail']]],
        paint: { 'fill-color': COLORS.urban },
      },
      {
        id: 'green', type: 'fill', source: 'omt', 'source-layer': 'landcover',
        filter: ['in', ['get', 'class'], ['literal', ['wood', 'grass', 'farmland']]],
        paint: { 'fill-color': COLORS.park, 'fill-opacity': 0.8 },
      },
      { id: 'park', type: 'fill', source: 'omt', 'source-layer': 'park', paint: { 'fill-color': COLORS.park } },
      { id: 'water', type: 'fill', source: 'omt', 'source-layer': 'water', paint: { 'fill-color': COLORS.water } },
      {
        id: 'waterway', type: 'line', source: 'omt', 'source-layer': 'waterway',
        paint: { 'line-color': COLORS.water, 'line-width': zoomed(10, 0.6, 14, 2) },
      },
      {
        id: 'runway', type: 'line', source: 'omt', 'source-layer': 'aeroway',
        filter: ['in', ['get', 'class'], ['literal', ['runway', 'taxiway']]],
        paint: { 'line-color': COLORS.building, 'line-width': zoomed(10, 1, 14, 8) },
      },
      { id: 'building', type: 'fill', source: 'omt', 'source-layer': 'building', minzoom: 13, paint: { 'fill-color': COLORS.building } },
      roadLine('tunnel-minor', ['secondary', 'tertiary', 'minor'], COLORS.roadCasing, widths.tertiary, {
        filter: [isTunnel], layout: { 'line-cap': 'butt' }, paint: { 'line-dasharray': [2, 1], 'line-opacity': 0.8 },
      }),
      roadLine('tunnel-major', ['motorway', 'trunk', 'primary'], COLORS.roadCasing, widths.primary, { filter: [isTunnel] }),
      roadLine('rail', ['rail', 'transit'], COLORS.rail, zoomed(10, 0.5, 14, 1.2), {
        filter: [notTunnel], paint: { 'line-dasharray': [3, 2] },
      }),
      roadLine('casing', ['minor', 'service', 'tertiary', 'secondary'], COLORS.roadCasing, widths.casing, { filter: [notTunnel] }),
      roadLine('primary-casing', ['primary'], COLORS.roadCasing, widths.primaryCasing, { filter: [notTunnel] }),
      roadLine('motorway-casing', ['motorway', 'trunk'], COLORS.motorwayCasing, widths.motorwayCasing, { filter: [notTunnel] }),
      roadLine('minor', ['minor', 'service'], COLORS.road, widths.minor, { filter: [notTunnel] }),
      roadLine('tertiary', ['tertiary'], COLORS.road, widths.tertiary, { filter: [notTunnel] }),
      roadLine('secondary', ['secondary'], COLORS.road, widths.secondary, { filter: [notTunnel] }),
      roadLine('primary', ['primary'], COLORS.road, widths.primary, { filter: [notTunnel] }),
      roadLine('motorway', ['motorway', 'trunk'], COLORS.motorway, widths.motorway, { filter: [notTunnel] }),
      {
        id: 'water-name', type: 'symbol', source: 'omt', 'source-layer': 'water_name',
        filter: ['all', ['has', 'name:ko'], ['!=', ['get', 'class'], 'lake']], // ponds and fountains crowd out road names
        layout: { 'text-field': label, 'text-font': ['Noto Sans Italic'], 'text-size': 15 },
        paint: { 'text-color': COLORS.textWater, 'text-halo-color': COLORS.halo, 'text-halo-width': 1.4 },
      },
      placeLabel('place-minor', ['neighbourhood', 'quarter', 'village', 'hamlet'], textZoomed(10, 13, 15, 16), COLORS.textDim, 'Noto Sans Regular', 60),
      placeLabel('place-mid', ['suburb', 'town'], textZoomed(10, 15, 15, 20), COLORS.text, 'Noto Sans Regular', 28),
      {
        id: 'road-name', type: 'symbol', source: 'omt', 'source-layer': 'transportation_name',
        filter: ['in', ['get', 'class'], ['literal', ['motorway', 'trunk', 'primary', 'secondary']]],
        layout: {
          'symbol-placement': 'line', 'text-field': label, 'text-font': ['Noto Sans Regular'],
          'text-size': 15, 'symbol-spacing': 600, 'text-padding': 16,
        },
        paint: { 'text-color': COLORS.textDim, 'text-halo-color': COLORS.halo, 'text-halo-width': 1.6 },
      },
      placeLabel('place-major', ['city'], textZoomed(10, 21, 15, 26), COLORS.text, 'Noto Sans Bold', 6),
      {
        id: 'airport', type: 'symbol', source: 'omt', 'source-layer': 'aerodrome_label',
        layout: { 'text-field': label, 'text-font': ['Noto Sans Bold'], 'text-size': 17, 'text-max-width': 8 },
        paint: { 'text-color': COLORS.text, 'text-halo-color': COLORS.halo, 'text-halo-width': 1.6 },
      },
    ],
  };
}

// The tiles a panel centred on the route can show: those within the margin of it.
function tilesNearRoute(route, width, height) {
  const keep = [];
  for (let row = 0; row * TILE_PX < height; row++) {
    for (let col = 0; col * TILE_PX < width; col++) {
      const near = route.some(([x, y]) => {
        const dx = Math.max(col * TILE_PX - x, 0, x - (col + 1) * TILE_PX);
        const dy = Math.max(row * TILE_PX - y, 0, y - (row + 1) * TILE_PX);
        return Math.hypot(dx, dy) <= MAP.margin;
      });
      if (near) keep.push({ col, row });
    }
  }
  return keep;
}

// Runs in the page. Hands back each image through window.saveImage as base64 WebP.
async function renderInPage({ style, center, zoom, tiles, tilePx, quality, maxCanvas }) {
  maplibregl.addProtocol('ofm', async ({ url }) => {
    const encoded = await window.ofmFetch(url.slice('ofm://'.length));
    const raw = atob(encoded);
    const data = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i += 1) data[i] = raw.charCodeAt(i);
    return { data: data.buffer };
  });
  const map = new maplibregl.Map({
    container: document.getElementById('map'),
    style,
    center,
    zoom,
    pixelRatio: 1,
    maxCanvasSize: [maxCanvas, maxCanvas],
    interactive: false,
    attributionControl: false,
    fadeDuration: 0,
    renderWorldCopies: false,
    localIdeographFontFamily: '"Malgun Gothic", "Noto Sans KR", sans-serif',
    canvasContextAttributes: { preserveDrawingBuffer: true, antialias: true },
  });
  await new Promise((done, fail) => {
    map.on('error', (e) => fail(e.error ?? new Error('map error')));
    map.once('idle', done);
  });
  const source = map.getCanvas();

  const save = async (name, x, y, width, height) => {
    const out = document.createElement('canvas');
    out.width = width;
    out.height = height;
    out.getContext('2d').drawImage(source, x, y, width, height, 0, 0, width, height);
    const blob = await new Promise((done) => out.toBlob(done, 'image/webp', quality));
    if (blob.type !== 'image/webp') throw new Error(`encoder produced ${blob.type}`);
    const bytes = new Uint8Array(await blob.arrayBuffer());
    let raw = '';
    for (let i = 0; i < bytes.length; i += 0x8000) raw += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    await window.saveImage(name, btoa(raw));
  };
  if (tiles) {
    for (const { col, row } of tiles) {
      const [x, y] = [col * tilePx, row * tilePx];
      await save(`${col}_${row}`, x, y, Math.min(tilePx, source.width - x), Math.min(tilePx, source.height - y));
    }
  } else {
    await save('overview', 0, 0, source.width, source.height);
  }
  return [source.width, source.height];
}

async function main() {
  const out = resolve(args.out);
  await mkdir(out, { recursive: true });
  const tileJson = JSON.parse(new TextDecoder().decode(await limited(() => download('/planet'))));
  const template = tileJson.tiles[0];
  if (!template.startsWith(ORIGIN) || !template.endsWith('/{z}/{x}/{y}.pbf')) throw new Error(`unexpected tile URL ${template}`);
  const tilePrefix = template.slice(ORIGIN.length, -'/{z}/{x}/{y}.pbf'.length);

  const browser = await chromium.launch({
    channel: 'chrome',
    headless: true,
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  });
  try {
    for (const [name, file] of ROUTES) {
      if (args.only && args.only !== name) continue;
      const { coordinates } = JSON.parse(await readFile(join(args.routes, file), 'utf8')).routes[0].geometry;
      const { toPx, width, height, center } = fitRoute(coordinates);
      if (Math.max(width, height) > MAX_CANVAS_PX) throw new Error(`${name}: ${width}x${height} exceeds the canvas limit`);
      const tiles = tilesNearRoute(coordinates.map(toPx), width, height);

      const tileDir = join(out, name);
      await rm(tileDir, { recursive: true, force: true });
      await mkdir(tileDir, { recursive: true });
      let totalBytes = 0;

      const shrink = (Math.max(width, height) * OVERVIEW_EXTENT) / OVERVIEW_PX;
      const passes = [
        { size: { width, height }, zoom: MAP.zoom, tiles, quality: TILE_QUALITY },
        { size: { width: OVERVIEW_PX, height: OVERVIEW_PX }, zoom: MAP.zoom - Math.log2(shrink), tiles: null, quality: OVERVIEW_QUALITY },
      ];
      for (const pass of passes) {
        const page = await browser.newPage({ viewport: pass.size, deviceScaleFactor: 1 });
        page.on('pageerror', (e) => console.error('page error:', e.message));
        await page.exposeFunction('ofmFetch', async (path) => {
          const full = path.startsWith('tile/') ? `${tilePrefix}/${path.slice('tile/'.length)}.pbf` : `/${path}`;
          return Buffer.from(await cached(full)).toString('base64');
        });
        await page.exposeFunction('saveImage', async (image, base64) => {
          const body = Buffer.from(base64, 'base64');
          totalBytes += body.length;
          await writeFile(image === 'overview' ? join(out, `${name}.webp`) : join(tileDir, `${image}.webp`), body);
        });
        await page.setContent(`<!doctype html><style>html,body{margin:0}#map{width:${pass.size.width}px;height:${pass.size.height}px}</style><div id="map"></div>`);
        await page.addStyleTag({ path: join(maplibreDir, 'dist/maplibre-gl.css') });
        await page.addScriptTag({ path: join(maplibreDir, 'dist/maplibre-gl.js') });
        const [canvasW, canvasH] = await page.evaluate(renderInPage, {
          style: buildStyle(), center, zoom: pass.zoom, tiles: pass.tiles, tilePx: TILE_PX, quality: pass.quality, maxCanvas: MAX_CANVAS_PX,
        });
        await page.close();
        if (canvasW !== pass.size.width || canvasH !== pass.size.height) throw new Error(`${name}: canvas ${canvasW}x${canvasH}`);
      }
      console.log(`${name}: ${width}x${height}, ${tiles.length} tiles, ${(totalBytes / 1048576).toFixed(1)} MB`);
    }
  } finally {
    await browser.close();
  }
  console.log(`network: ${stats.tiles} tiles, ${stats.requests} requests, ${(stats.bytes / 1048576).toFixed(1)} MB; ${stats.cached} served from cache`);
}

await main();
