// Renders the bundled route map backgrounds from OpenFreeMap vector tiles with MapLibre GL JS in
// headless Chrome. See README.md. The image covers exactly fitRoute(...).bounds, so a route projected
// with toPx lands on the roads of the 1000x1000 image space.
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { fitRoute, MAP } from '../scenario/projection.mjs';

const here = dirname(fileURLToPath(import.meta.url));
// Third column: extra tile zoom levels. Tiles at the map's own zoom drop the service and minor roads
// the route starts and ends on; each level quadruples the tiles fetched. Vector sources only accept a
// tileSize of 512, so a level is gained by doubling the map size and zoom and halving pixelRatio.
const ROUTES = [
  ['gangnam-pangyo', 'osrm_gangnam_pangyo.json', 2],
  ['pangyo-dongpangyo', 'osrm_pangyo_dongpangyo.json', 0],
  ['seohyeon-yanghyeon', 'osrm_seohyeon_yanghyeon.json', 0],
  ['gangnam-teheran', 'osrm_gangnam_teheran.json', 0],
  ['seocho-jungang', 'osrm_seocho_jungang.json', 0],
  ['seoulstation-cheongpa', 'osrm_seoulstation_cheongpa.json', 0],
];
const ORIGIN = 'https://tiles.openfreemap.org';
const USER_AGENT = 'ActiveSeat-demo-map-render';
const MAX_PARALLEL = 4; // OpenFreeMap terms, together with the two caps below.
const MAX_REQUESTS = 3000;
const MAX_BYTES = 50 * 1024 * 1024;
const MAX_FILE_BYTES = 1.5 * 1024 * 1024;
const OUTPUT_SCALE = 2; // 2000x2000 output for the 1000x1000 image space.
const ATTRIBUTION = '© OpenStreetMap contributors · OpenFreeMap © OpenMapTiles';

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
  land: '#131a28', urban: '#182133', park: '#15261f', water: '#0c3350', building: '#1f2a3d',
  rail: '#3d4a64', minor: '#35425a', tertiary: '#47566f', secondary: '#5d6e8c', primary: '#7d90b0',
  motorway: '#c2d3ee', motorwayCasing: '#0a0f18', text: '#e3eaf6', textDim: '#a9b6cc', textWater: '#7fb4d6',
  halo: '#0b101a',
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
const placeLabel = (k, id, classes, size, color, font, padding) => ({
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
    'text-padding': padding * k,
  },
  paint: { 'text-color': color, 'text-halo-color': COLORS.halo, 'text-halo-width': 1.6 * k },
});

// Sizes are written for the 1000x1000 image at its own zoom; k rescales them to the enlarged map.
function buildStyle(extraTileZoom) {
  const k = 2 ** extraTileZoom;
  const stops = (list) => list.flatMap((value, i) => (i % 2 ? value * k : value + extraTileZoom));
  const zoomed = (...list) => ['interpolate', ['exponential', 1.5], ['zoom'], ...stops(list)];
  const textZoomed = (...list) => ['interpolate', ['linear'], ['zoom'], ...stops(list)];
  const widths = {
    minor: zoomed(10, 0.3, 12, 0.7, 14, 2),
    tertiary: zoomed(10, 0.5, 12, 1, 14, 2.6),
    secondary: zoomed(10, 0.7, 12, 1.4, 14, 3.4),
    primary: zoomed(10, 1, 12, 2, 14, 4.4),
    motorway: zoomed(10, 1.8, 12, 3, 14, 6),
    casing: zoomed(10, 3, 12, 4.6, 14, 8.4),
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
        paint: { 'line-color': COLORS.minor, 'line-width': zoomed(10, 1, 14, 8) },
      },
      { id: 'building', type: 'fill', source: 'omt', 'source-layer': 'building', minzoom: 13 + extraTileZoom, paint: { 'fill-color': COLORS.building } },
      roadLine('tunnel-minor', ['secondary', 'tertiary', 'minor'], COLORS.secondary, widths.tertiary, {
        filter: [isTunnel], layout: { 'line-cap': 'butt' }, paint: { 'line-dasharray': [2, 1], 'line-opacity': 0.8 },
      }),
      roadLine('tunnel-major', ['motorway', 'trunk', 'primary'], COLORS.primary, widths.primary, { filter: [isTunnel] }),
      roadLine('rail', ['rail', 'transit'], COLORS.rail, zoomed(10, 0.5, 14, 1.2), {
        filter: [notTunnel], paint: { 'line-dasharray': [3, 2] },
      }),
      roadLine('minor', ['minor', 'service'], COLORS.minor, widths.minor, { filter: [notTunnel] }),
      roadLine('tertiary', ['tertiary'], COLORS.tertiary, widths.tertiary, { filter: [notTunnel] }),
      roadLine('secondary', ['secondary'], COLORS.secondary, widths.secondary, { filter: [notTunnel] }),
      roadLine('primary', ['primary'], COLORS.primary, widths.primary, { filter: [notTunnel] }),
      roadLine('motorway-casing', ['motorway', 'trunk'], COLORS.motorwayCasing, widths.casing, { filter: [notTunnel] }),
      roadLine('motorway', ['motorway', 'trunk'], COLORS.motorway, widths.motorway, { filter: [notTunnel] }),
      {
        id: 'water-name', type: 'symbol', source: 'omt', 'source-layer': 'water_name',
        filter: ['all', ['has', 'name:ko'], ['!=', ['get', 'class'], 'lake']], // ponds and fountains crowd out road names
        layout: { 'text-field': label, 'text-font': ['Noto Sans Italic'], 'text-size': 15 * k },
        paint: { 'text-color': COLORS.textWater, 'text-halo-color': COLORS.halo, 'text-halo-width': 1.4 * k },
      },
      placeLabel(k, 'place-minor', ['neighbourhood', 'quarter', 'village', 'hamlet'], textZoomed(10, 13, 14, 15), COLORS.textDim, 'Noto Sans Regular', 60),
      placeLabel(k, 'place-mid', ['suburb', 'town'], textZoomed(10, 15, 14, 19), COLORS.text, 'Noto Sans Regular', 28),
      {
        id: 'road-name', type: 'symbol', source: 'omt', 'source-layer': 'transportation_name',
        filter: ['in', ['get', 'class'], ['literal', ['motorway', 'trunk', 'primary']]],
        layout: {
          'symbol-placement': 'line', 'text-field': label, 'text-font': ['Noto Sans Regular'],
          'text-size': 14 * k, 'symbol-spacing': 600 * k, 'text-padding': 16 * k, 'text-offset': [0, 1],
        },
        paint: { 'text-color': COLORS.textDim, 'text-halo-color': COLORS.halo, 'text-halo-width': 1.6 * k },
      },
      placeLabel(k, 'place-major', ['city'], textZoomed(10, 21, 14, 26), '#ffffff', 'Noto Sans Bold', 6),
      {
        id: 'airport', type: 'symbol', source: 'omt', 'source-layer': 'aerodrome_label',
        layout: { 'text-field': label, 'text-font': ['Noto Sans Bold'], 'text-size': 17 * k, 'text-max-width': 8 },
        paint: { 'text-color': COLORS.text, 'text-halo-color': COLORS.halo, 'text-halo-width': 1.6 * k },
      },
    ],
  };
}

const mercatorX = (lon) => (lon + 180) / 360;
const mercatorY = (lat) => {
  const rad = (lat * Math.PI) / 180;
  return (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2;
};
const unmercatorLat = (y) => (Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180) / Math.PI;

// Camera that puts bounds.southWest at the lower left and bounds.northEast at the upper right.
function cameraFor({ southWest, northEast }, mapWidth) {
  const zoom = Math.log2(mapWidth / (512 * (mercatorX(northEast[0]) - mercatorX(southWest[0]))));
  const y = (mercatorY(southWest[1]) + mercatorY(northEast[1])) / 2;
  return { center: [(southWest[0] + northEast[0]) / 2, unmercatorLat(y)], zoom };
}

// Runs in the page. Returns the WebP bytes as base64, lowering quality until the file fits.
async function renderInPage({ style, camera, bounds, map: size, pixelRatio, attribution, maxBytes }) {
  maplibregl.addProtocol('ofm', async ({ url }) => {
    const encoded = await window.ofmFetch(url.slice('ofm://'.length));
    const raw = atob(encoded);
    const data = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i += 1) data[i] = raw.charCodeAt(i);
    return { data: data.buffer };
  });
  const container = document.getElementById('map');
  const map = new maplibregl.Map({
    container,
    style,
    ...camera,
    pixelRatio,
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

  const sw = map.project(bounds.southWest);
  const ne = map.project(bounds.northEast);
  const errorPx = Math.max(Math.abs(sw.x), Math.abs(sw.y - size.height), Math.abs(ne.x - size.width), Math.abs(ne.y));

  const source = map.getCanvas();
  const out = document.createElement('canvas');
  out.width = source.width;
  out.height = source.height;
  const ctx = out.getContext('2d');
  ctx.drawImage(source, 0, 0);

  ctx.font = '600 30px "Malgun Gothic", "Noto Sans KR", sans-serif';
  const textWidth = ctx.measureText(attribution).width;
  const [boxW, boxH] = [textWidth + 40, 52];
  const [boxX, boxY] = [out.width - boxW - 16, out.height - boxH - 16];
  ctx.fillStyle = 'rgba(8, 12, 20, 0.85)';
  ctx.beginPath();
  ctx.roundRect(boxX, boxY, boxW, boxH, 10);
  ctx.fill();
  ctx.fillStyle = '#eef3fb';
  ctx.textBaseline = 'middle';
  ctx.fillText(attribution, boxX + 20, boxY + boxH / 2 + 1);

  for (let quality = 0.85; ; quality -= 0.1) {
    const blob = await new Promise((done) => out.toBlob(done, 'image/webp', quality));
    if (blob.size <= maxBytes || quality < 0.3) {
      const bytes = new Uint8Array(await blob.arrayBuffer());
      let raw = '';
      for (let i = 0; i < bytes.length; i += 0x8000) raw += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      return { webp: btoa(raw), type: blob.type, quality, errorPx, width: out.width, height: out.height, zoom: map.getZoom() };
    }
  }
}

async function main() {
  await mkdir(resolve(args.out), { recursive: true });
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
    for (const [name, file, extraTileZoom] of ROUTES) {
      if (args.only && args.only !== name) continue;
      const route = JSON.parse(await readFile(join(args.routes, file), 'utf8'));
      const { bounds } = fitRoute(route.routes[0].geometry.coordinates);

      const k = 2 ** extraTileZoom;
      const size = { width: MAP.width * k, height: MAP.height * k };
      const page = await browser.newPage({ viewport: size, deviceScaleFactor: 1 });
      page.on('pageerror', (e) => console.error('page error:', e.message));
      await page.exposeFunction('ofmFetch', async (path) => {
        const full = path.startsWith('tile/') ? `${tilePrefix}/${path.slice('tile/'.length)}.pbf` : `/${path}`;
        return Buffer.from(await cached(full)).toString('base64');
      });
      await page.setContent(`<!doctype html><style>html,body{margin:0}#map{width:${size.width}px;height:${size.height}px}</style><div id="map"></div>`);
      await page.addStyleTag({ path: join(maplibreDir, 'dist/maplibre-gl.css') });
      await page.addScriptTag({ path: join(maplibreDir, 'dist/maplibre-gl.js') });

      const result = await page.evaluate(renderInPage, {
        style: buildStyle(extraTileZoom),
        camera: cameraFor(bounds, size.width),
        bounds,
        map: size,
        pixelRatio: OUTPUT_SCALE / k,
        attribution: ATTRIBUTION,
        maxBytes: MAX_FILE_BYTES,
      });
      await page.close();

      if (result.type !== 'image/webp') throw new Error(`${name}: encoder produced ${result.type}`);
      if (result.width !== MAP.width * OUTPUT_SCALE || result.height !== MAP.height * OUTPUT_SCALE) throw new Error(`${name}: size ${result.width}x${result.height}`);
      if (result.errorPx > 0.01) throw new Error(`${name}: bounds off by ${result.errorPx}px`);
      const body = Buffer.from(result.webp, 'base64');
      if (body.length > MAX_FILE_BYTES) throw new Error(`${name}: ${body.length} bytes at the lowest quality`);
      await writeFile(join(args.out, `${name}.webp`), body);
      console.log(`${name}: ${result.width}x${result.height} zoom ${result.zoom.toFixed(3)} quality ${result.quality.toFixed(2)} ${body.length} bytes, bounds error ${result.errorPx.toExponential(1)}px`);
    }
  } finally {
    await browser.close();
  }
  console.log(`network: ${stats.tiles} tiles, ${stats.requests} requests, ${(stats.bytes / 1048576).toFixed(1)} MB; ${stats.cached} served from cache`);
}

await main();
