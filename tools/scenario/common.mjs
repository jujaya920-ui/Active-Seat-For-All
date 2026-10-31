// Shared limits and timeline analysis. Everything here works from the scenario JSON alone,
// so the validator and labels never depend on how a scenario was authored.

export const STEP_S = 0.2;

export const LIMITS = {
  minDurationS: 90,
  maxDurationS: 150,
  maxSteeringDeg: 450,
  maxSteeringRateDegS: 200,
  maxSpeedKph: 130,
  maxAccelMps2: 3.5,
  // Anything harder than normalBrakeMps2 counts as a hard brake and needs a front event.
  normalBrakeMps2: -3.5,
  maxBrakeMps2: -6.0,
  maxJerkMps3: 8,
  maxLateralMps2: 4,
  hardAccelMps2: 2.5,
  routeLengthTolerance: 0.01,
  // Demo horizon 2 s, plus the 0.8 s signal window and part of the round trip.
  minSignalLeadS: 3,
  precursorWindowS: 2,
  precursorAccelMps2: 0.2,
  cornerSlowdownWindowS: 6,
  turnPositionToleranceM: 5,
  polylineTolerance: 0.01,
};

// Label thresholds. The lift-off and gentle pre-acceleration phases sit below
// longitudinalMps2 on purpose, so the label flips only when the real maneuver starts.
export const LABEL = {
  horizonS: 2,
  lateralDeg: 15,
  longitudinalMps2: 0.6,
};

// Bicycle-model parameters for lateral acceleration and heading estimates.
export const VEHICLE = { wheelbaseM: 2.9, steeringRatio: 14 };

export const kphToMps = (kph) => kph / 3.6;

// Forward difference; the last sample has no successor and reports 0.
export function accelerations(timeline) {
  return timeline.map((row, i) => {
    const next = timeline[i + 1];
    return next ? (kphToMps(next.speedKph) - kphToMps(row.speedKph)) / (next.t - row.t) : 0;
  });
}

// Cumulative trapezoidal distance. With zero speed at both ends this equals a left or
// right Riemann sum, so an app that integrates sampled speed gets the same total.
export function distances(timeline) {
  const out = [0];
  for (let i = 1; i < timeline.length; i++) {
    const dt = timeline[i].t - timeline[i - 1].t;
    const v = (kphToMps(timeline[i].speedKph) + kphToMps(timeline[i - 1].speedKph)) / 2;
    out.push(out[i - 1] + v * dt);
  }
  return out;
}

export function curvature(steeringDeg) {
  const roadWheelRad = ((steeringDeg / VEHICLE.steeringRatio) * Math.PI) / 180;
  return Math.tan(roadWheelRad) / VEHICLE.wheelbaseM;
}

export function lateralAccel(row) {
  return kphToMps(row.speedKph) ** 2 * Math.abs(curvature(row.steeringDeg));
}

// Contiguous runs of samples satisfying the predicate, as inclusive index pairs.
function runs(values, predicate) {
  const out = [];
  let start = -1;
  values.forEach((value, i) => {
    if (predicate(value) && start < 0) start = i;
    if (!predicate(value) && start >= 0) {
      out.push([start, i - 1]);
      start = -1;
    }
  });
  if (start >= 0) out.push([start, values.length - 1]);
  return out;
}

function detectCorners(timeline) {
  const steering = timeline.map((row) => row.steeringDeg);
  return runs(steering, (deg) => Math.abs(deg) >= LABEL.lateralDeg).map(([startI, endI]) => {
    let moveI = startI;
    while (moveI > 0 && Math.abs(steering[moveI - 1]) >= 1) moveI--;
    const peak = Math.max(...steering.slice(startI, endI + 1).map(Math.abs));
    const plateau = [];
    for (let i = startI; i <= endI; i++) if (Math.abs(steering[i]) === peak) plateau.push(i);
    return {
      direction: steering[startI] > 0 ? 'left' : 'right',
      moveI,
      startI,
      apexI: plateau[Math.floor(plateau.length / 2)],
      endI,
      peakDeg: peak,
    };
  });
}

// A maneuver spans the onset threshold outward to where the longitudinal label flips.
function detectLongitudinal(accel, isHard, isActive) {
  return runs(accel, isHard).map(([hardI, lastHardI]) => {
    let startI = hardI;
    while (startI > 0 && isActive(accel[startI - 1])) startI--;
    let endI = lastHardI;
    while (endI < accel.length - 1 && isActive(accel[endI + 1])) endI++;
    return { startI, hardI, endI };
  });
}

export function detectManeuvers(timeline) {
  const accel = accelerations(timeline);
  const active = LABEL.longitudinalMps2;
  return {
    corners: detectCorners(timeline),
    hardAccels: detectLongitudinal(accel, (a) => a >= LIMITS.hardAccelMps2, (a) => a >= active),
    hardBrakes: detectLongitudinal(accel, (a) => a <= LIMITS.normalBrakeMps2, (a) => a <= -active),
  };
}

export const signalRuns = (timeline) =>
  ['BlinkLeft', 'BlinkRight'].flatMap((mode) =>
    runs(timeline.map((row) => row.turnSignal), (value) => value === mode).map(([startI, endI]) => ({
      mode,
      startI,
      endI,
    })),
  );

export function polylineLength(points) {
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    total += Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]);
  }
  return total;
}
