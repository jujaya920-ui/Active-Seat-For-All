// Web Mercator projection of a route into the app's map pixels. The scenario builder and the map
// renderer share this, so routePx lands exactly on the rendered map.
// Every route uses one scale, like a real navigation map. The margin keeps the map under a panel
// centred on the car at any point of the route, in any heading.
export const MAP = { zoom: 15, margin: 900 };

const WORLD_PX = 512 * 2 ** MAP.zoom;

const mercator = ([lon, lat]) => {
  const rad = (lat * Math.PI) / 180;
  return [(lon + 180) / 360, (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2];
};

const unmercator = ([x, y]) => [
  x * 360 - 180,
  (Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180) / Math.PI,
];

// coordinates: [lon, lat] pairs. Returns the projection, the map size and the map's centre.
export function fitRoute(coordinates) {
  const points = coordinates.map(mercator);
  const xs = points.map((p) => p[0] * WORLD_PX);
  const ys = points.map((p) => p[1] * WORLD_PX);
  const originX = Math.floor(Math.min(...xs)) - MAP.margin;
  const originY = Math.floor(Math.min(...ys)) - MAP.margin;
  const width = Math.ceil(Math.max(...xs)) + MAP.margin - originX;
  const height = Math.ceil(Math.max(...ys)) + MAP.margin - originY;
  const toPx = (lonLat) => {
    const [x, y] = mercator(lonLat);
    return [Math.round(x * WORLD_PX - originX), Math.round(y * WORLD_PX - originY)];
  };
  const center = unmercator([(originX + width / 2) / WORLD_PX, (originY + height / 2) / WORLD_PX]);
  return { toPx, width, height, center };
}
