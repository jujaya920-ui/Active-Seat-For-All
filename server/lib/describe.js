// Turns raw vehicle samples and navigation into the plain-language state Jev reads.
// Every number Jev would otherwise have to interpret is resolved here, so Jev only judges.
// All thresholds are provisional and must stay general: never fit them to a scenario.

export const STEP_S = 0.2;
export const WINDOW_SAMPLES = 10; // 2 s
export const WINDOWS = 3;
export const SMOOTH = 3; // 0.6 s trailing average
// Three windows of labels, each label needing SMOOTH earlier samples.
export const SAMPLE_COUNT = WINDOWS * WINDOW_SAMPLES + SMOOTH;

export const PARAMS = {
  steeringRatio: 14,
  wheelbaseM: 2.9,
  wheelDeadbandDeg: 3,
  rateDeadbandDegS: 10,
  pushBoundsMps2: [0.6, 1.5, 2.5, 4],
  pushHystMps2: 0.15,
  wheelBoundsDeg: [5, 30, 90, 180],
  wheelHystDeg: 2,
  minRunSamples: 2, // 0.4 s
  maxSegments: 3,
  stoppedMps: 0.5,
};

export const PUSH_LEVELS = ['none', 'weak', 'moderate', 'strong', 'very strong'];
const WHEEL_LEVELS = ['centre', 'slightly', 'moderately', 'sharply', 'very sharply'];
export const DIRECTIONS = ['forward', 'backward', 'left', 'right'];

export const SCALE =
  "Pushes describe what a passenger's body feels, not what the car does: braking pushes the body forward, " +
  'speeding up pushes it backward, turning left pushes it right and turning right pushes it left. ' +
  'Push strength has five levels. none: barely felt. weak: like an ordinary start or stop. ' +
  'moderate: the body clearly leans. strong: a standing passenger would need to hold on. ' +
  'very strong: like an emergency stop or a sharp turn taken fast. ' +
  'The steering wheel has five positions. centre. slightly: small corrections within a lane. ' +
  'moderately: a gentle bend. sharply: turning at a junction. very sharply: a U-turn or parking. ' +
  'Each window covers 2 seconds, oldest first, and the last one ends now.';

export const RULES = [
  'A driver approaching a turn point at high speed slows down before the turn, more for a tighter turn (a junction turn more than a fork or an interchange).',
  'A driver approaching congestion slows down before it starts.',
  'After a turn the driver releases the wheel and speeds up again.',
  'A car pulling away from a stop speeds up.',
  'Far from any turn point or event ahead, a driver mostly holds speed.',
  'Drivers avoid abrupt inputs. Strong pushes come from reacting late.',
  'The wheel is turned only once the car actually reaches the turn point. Until then it stays centred, even with the turn signal on or the turn announced.',
  'During a turn the wheel stays turned, and it is released once the turn is done.',
  'Roads also bend without any guidance. A wheel already turned follows that bend.',
  'Forks and interchange ramps need less wheel than junction turns.',
  'The turn signal shows the side of a turn or a lane change. A lane change is a short weak push to one side followed by one to the other.',
  'The same wheel position pushes much harder at higher speed.',
];

export const TURN_NAMES = {
  TURN_UNKNOWN: 'unnamed manoeuvre',
  TURN_STRAIGHT: 'straight-on',
  TURN_RIGHT: 'right turn',
  TURN_LEFT: 'left turn',
  TURN_KEEP_R: 'keep-right fork',
  TURN_KEEP_L: 'keep-left fork',
  TURN_SHARP_R: 'sharp right turn',
  TURN_SHARP_L: 'sharp left turn',
  TURN_UTURN: 'U-turn',
  TURN_ENTER_IC_R: 'interchange entry on the right',
  TURN_ENTER_IC_L: 'interchange entry on the left',
  TURN_ENTER_IC_C: 'interchange entry straight ahead',
  TURN_EXIT_IC_R: 'interchange exit on the right',
  TURN_EXIT_IC_L: 'interchange exit on the left',
  TURN_EXIT_IC_C: 'interchange exit straight ahead',
  TURN_ARRIVE: 'destination',
  CONGESTION: 'congestion start',
};

const deadband = (x, db) => Math.sign(x) * Math.max(0, Math.abs(x) - db);
const fixed = (x) => x.toFixed(1);

// Moving up a level needs bound + h, moving down needs bound - h.
export function levelWithHyst(value, prev, bounds, h) {
  const x = Math.abs(value);
  let level = prev ?? bounds.filter((b) => x >= b).length;
  while (level < bounds.length && x >= bounds[level] + h) level++;
  while (level > 0 && x < bounds[level - 1] - h) level--;
  return level;
}

// rows: { speedKph, steeringDeg, brake, turnSignal }, 0.2 s apart, oldest first.
// Output starts at rows[SMOOTH]. Steering is positive to the left.
export function physics(rows) {
  const out = [];
  for (let k = SMOOTH; k < rows.length; k++) {
    const v = rows[k].speedKph / 3.6;
    const v0 = rows[k - SMOOTH].speedKph / 3.6;
    let wheel = 0;
    for (let j = k - SMOOTH + 1; j <= k; j++) wheel += rows[j].steeringDeg / SMOOTH;
    const wheelRate = (rows[k].steeringDeg - rows[k - SMOOTH].steeringDeg) / (SMOOTH * STEP_S);
    const roadRad = (deadband(wheel, PARAMS.wheelDeadbandDeg) / PARAMS.steeringRatio) * (Math.PI / 180);
    out.push({
      v,
      brake: rows[k].brake,
      turnSignal: rows[k].turnSignal,
      longAcc: (v - v0) / (SMOOTH * STEP_S), // positive when speeding up
      latAcc: (v * v * Math.tan(roadRad)) / PARAMS.wheelbaseM, // positive when turning left
      wheel,
      wheelRate: deadband(wheelRate, PARAMS.rateDeadbandDegS),
    });
  }
  return out;
}

// Body push per sample: { long: {dir, level}, lat: {dir, level} }, level 0 meaning none.
export function pushes(phys) {
  let longLevel = null;
  let latLevel = null;
  const { pushBoundsMps2: bounds, pushHystMps2: h } = PARAMS;
  return phys.map((s) => {
    longLevel = levelWithHyst(s.longAcc, longLevel, bounds, h);
    latLevel = levelWithHyst(s.latAcc, latLevel, bounds, h);
    return {
      long: { dir: s.longAcc < 0 ? 'forward' : 'backward', level: longLevel },
      lat: { dir: s.latAcc > 0 ? 'right' : 'left', level: latLevel },
    };
  });
}

// Level of each body direction per sample, for ground truth: { forward, backward, left, right }.
export function directionLevels(phys) {
  return pushes(phys).map(({ long, lat }) => {
    const levels = { forward: 0, backward: 0, left: 0, right: 0 };
    if (long.level) levels[long.dir] = long.level;
    if (lat.level) levels[lat.dir] = lat.level;
    return levels;
  });
}

function wheelPositions(phys) {
  let level = null;
  return phys.map((s) => {
    level = levelWithHyst(s.wheel, level, PARAMS.wheelBoundsDeg, PARAMS.wheelHystDeg);
    return level === 0 ? { side: null, level: 0 } : { side: s.wheel > 0 ? 'left' : 'right', level };
  });
}

const pushKey = (p) => (p.level === 0 ? 'none' : `${p.dir}:${p.level}`);
const pushRank = (key) => {
  if (key === 'none') return 0;
  const [dir, level] = key.split(':');
  return (dir === 'forward' || dir === 'right' ? 1 : -1) * Number(level);
};
const wheelKey = (w) => (w.level === 0 ? 'centre' : `${w.side}:${w.level}`);
const wheelRank = (key) => (key === 'centre' ? 0 : (key.startsWith('left') ? 1 : -1) * Number(key.split(':')[1]));
const brakeRank = (key) => (key === 'on' ? 1 : 0);

// Runs of equal keys; runs shorter than minRunSamples join the closer neighbour (the earlier one
// on a tie), then the shortest runs join until at most maxSegments remain.
export function segments(keys, rank) {
  let segs = [];
  for (const key of keys) {
    if (segs.length && segs.at(-1).key === key) segs.at(-1).n++;
    else segs.push({ key, n: 1 });
  }
  const absorb = (i) => {
    const left = segs[i - 1];
    const right = segs[i + 1];
    const gap = (other) => Math.abs(rank(other.key) - rank(segs[i].key));
    const into = !left ? right : !right ? left : gap(left) <= gap(right) ? left : right;
    into.n += segs[i].n;
    segs.splice(i, 1);
    segs = segs.reduce((acc, s) => {
      if (acc.length && acc.at(-1).key === s.key) acc.at(-1).n += s.n;
      else acc.push(s);
      return acc;
    }, []);
  };
  const shortest = () => segs.reduce((m, s, i) => (s.n < segs[m].n ? i : m), 0);
  while (segs.length > 1 && segs[shortest()].n < PARAMS.minRunSamples) absorb(shortest());
  while (segs.length > PARAMS.maxSegments) absorb(shortest());
  return segs;
}

const pushPhrase = (key) => {
  if (key === 'none') return 'no push';
  const [dir, level] = key.split(':');
  return `a ${PUSH_LEVELS[Number(level)]} push ${dir}`;
};

const wheelPlace = (key) => {
  const [side, level] = key.split(':');
  return `${WHEEL_LEVELS[Number(level)]} ${side}`;
};

// Each wheel segment is told against the one before it, so a drop toward centre reads as a release.
function wheelPhrases(segs) {
  return segs.map((s, i) => {
    const prev = segs[i - 1]?.key;
    if (s.key === 'centre') return prev ? 'returned to centre' : 'at centre';
    if (!prev) return `turned ${wheelPlace(s.key)}`;
    const [side, level] = s.key.split(':');
    const [prevSide, prevLevel] = prev.split(':');
    if (prevSide === side && Number(prevLevel) > Number(level)) return `released to ${wheelPlace(s.key)}`;
    return `turned to ${wheelPlace(s.key)}`;
  });
}

const BRAKE_PHRASE = { on: 'on the brake', off: 'off the brake' };

function tell(segs, phrases) {
  if (segs.length === 1) return `${phrases[0]} for the whole 2 s`;
  return segs.map((s, i) => `${fixed(s.n * STEP_S)} s ${phrases[i]}`).join(', then ');
}

const signalPhrase = (signal) => (signal === 'left' || signal === 'right' ? `${signal} turn signal on` : null);

function windowText(phys, push, wheel, from, to) {
  const long = segments(push.slice(from, to).map((p) => pushKey(p.long)), pushRank);
  const lat = segments(push.slice(from, to).map((p) => pushKey(p.lat)), pushRank);
  const wh = segments(wheel.slice(from, to).map(wheelKey), wheelRank);
  const brake = segments(phys.slice(from, to).map((s) => (s.brake ? 'on' : 'off')), brakeRank);
  const signal = signalPhrase(phys[to - 1].turnSignal);
  return [
    `Front-back: ${tell(long, long.map((s) => pushPhrase(s.key)))}.`,
    `Sideways: ${tell(lat, lat.map((s) => pushPhrase(s.key)))}.`,
    `Wheel: ${tell(wh, wheelPhrases(wh))}.`,
    `Pedal: ${tell(brake, brake.map((s) => BRAKE_PHRASE[s.key]))}.`,
    signal ? `${signal[0].toUpperCase()}${signal.slice(1)}.` : null,
    `Speed ${fixed(phys[from].v)} to ${fixed(phys[to - 1].v)} m/s.`,
  ]
    .filter(Boolean)
    .join(' ');
}

function nowText(last, position) {
  let wheel = position.level === 0 ? 'wheel at centre' : `wheel turned ${wheelPlace(wheelKey(position))}`;
  if (last.wheelRate !== 0) {
    const toward = last.wheelRate > 0 ? 'left' : 'right';
    if (position.level === 0) wheel += `, starting to turn ${toward}`;
    else wheel += toward === position.side ? `, turning further ${toward}` : ', returning toward centre';
  }
  return [`${fixed(last.v)} m/s`, wheel, last.brake ? 'on the brake' : null, signalPhrase(last.turnSignal)]
    .filter(Boolean)
    .join(', ');
}

function navText(item, v, what) {
  if (!item) return `No ${what} reported.`;
  const name = TURN_NAMES[item.type] ?? item.type;
  if (v < PARAMS.stoppedMps) return `The car is stopped; the ${name} point is ${item.distanceM} m ahead.`;
  return `At the current speed the ${name} point is ${fixed(item.distanceM / v)} s ahead (${item.distanceM} m).`;
}

const WINDOW_NAMES = ['6 to 4 s ago', '4 to 2 s ago', '2 s ago to now'];

// rows: SAMPLE_COUNT samples, oldest first. nextTurn and frontEvent are { type, distanceM } or null.
export function describeState(rows, nextTurn, frontEvent) {
  const phys = physics(rows);
  const push = pushes(phys);
  const wheel = wheelPositions(phys);
  const windows = WINDOW_NAMES.map((when, w) => {
    const from = w * WINDOW_SAMPLES;
    return { when, text: windowText(phys, push, wheel, from, from + WINDOW_SAMPLES) };
  });
  const last = phys.at(-1);
  return {
    scale: SCALE,
    rules: RULES,
    windows,
    now: nowText(last, wheel.at(-1)),
    nav: {
      nextGuidance: navText(nextTurn, last.v, 'turn guidance'),
      eventAhead: navText(frontEvent, last.v, 'event ahead'),
    },
  };
}
