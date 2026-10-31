// Web Mercator fit of a route into the app's map image space. The scenario builder and the map
// renderer share this, so routePx lands exactly on the rendered map.
// Portrait to fill the app's map panel. bottomMargin keeps the route above the guidance card.
export const MAP = { width: 1000, height: 1440, margin: 80, bottomMargin: 480 };

const mercator = ([lon, lat]) => {
  const rad = (lat * Math.PI) / 180;
  return [(lon + 180) / 360, (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2];
};

const unmercator = ([x, y]) => [
  x * 360 - 180,
  (Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180) / Math.PI,
];

// coordinates: [lon, lat] pairs. Returns the projection and the geographic bounds of the image.
export function fitRoute(coordinates) {
  const points = coordinates.map(mercator);
  const xs = points.map((p) => p[0]);
  const ys = points.map((p) => p[1]);
  const [minX, maxX, minY, maxY] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const scale = Math.min(
    (MAP.width - 2 * MAP.margin) / (maxX - minX),
    (MAP.height - MAP.margin - MAP.bottomMargin) / (maxY - minY),
  );
  const offsetX = (MAP.width - scale * (maxX - minX)) / 2 - scale * minX;
  const offsetY = MAP.margin + (MAP.height - MAP.margin - MAP.bottomMargin - scale * (maxY - minY)) / 2 - scale * minY;
  const toPx = (lonLat) => {
    const [x, y] = mercator(lonLat);
    return [Math.round(offsetX + scale * x), Math.round(offsetY + scale * y)];
  };
  const fromPx = ([px, py]) => unmercator([(px - offsetX) / scale, (py - offsetY) / scale]);
  return {
    toPx,
    bounds: { southWest: fromPx([0, MAP.height]), northEast: fromPx([MAP.width, 0]) },
  };
}
