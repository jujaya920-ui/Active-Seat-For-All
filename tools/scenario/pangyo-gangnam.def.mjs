// Placeholder scenario for the planner to confirm. Turn order and labels come from the real
// NaviHelper route; distances are compressed well below the real Pangyo to Gangnam route so
// that the drive fits a 90 to 150 s demo.
//
// segments: played in order. Each one commands a speed target for durS seconds, with accel
//   (m/s²) and optional jerk (m/s³) limits. A target need not be reached before durS ends.
// corners: tApex is seconds from scenario start; steering is signed from the turn type.
//   rampS is each raised-cosine side, holdS the plateau, signalLeadS the turn signal lead
//   before the first steering motion.
// frontEvent on a segment: placed offsetM past the point where that segment reaches its speed,
//   or at the segment's start when it speeds up. Corner speed is reached before steering starts,
//   so distance over current speed is a true time to the turn during the last seconds.

export default {
  id: 'pangyo-gangnam',
  title: '강남역',
  mapImage: 'maps/pangyo-gangnam.webp',
  routeLengthM: 1586,
  // Degrees clockwise from map north, for the schematic route only.
  startHeadingDeg: -130,

  segments: [
    { name: 'stopped at origin', durS: 2, kph: 0 },
    { name: 'pull away', durS: 11, kph: 50, accel: 1.6 },
    { name: 'slow for corner 1', durS: 13, kph: 25, accel: 1.5 },
    { name: 'exit corner 1', durS: 9, kph: 60, accel: 1.8 },
    { name: 'slow for corner 2', durS: 13, kph: 28, accel: 1.6 },
    { name: 'ease onto the ramp', durS: 4, kph: 60, accel: 0.5 },
    {
      name: 'hard acceleration',
      durS: 8,
      kph: 90,
      accel: 3.2,
      jerk: 3,
      frontEvent: { type: 'HIGHWAY_MERGE', description: '고속 구간 합류', offsetM: 0 },
    },
    { name: 'cruise', durS: 20, kph: 90 },
    { name: 'lift off', durS: 3, kph: 60, accel: 0.5 },
    {
      name: 'hard brake',
      durS: 5,
      kph: 15,
      accel: 5.6,
      jerk: 6,
      frontEvent: { type: 'CONGESTION', description: '정체 구간', offsetM: 8 },
    },
    { name: 'crawl in congestion', durS: 8, kph: 15 },
    {
      name: 'traffic clears',
      durS: 7.5,
      kph: 42,
      accel: 1.6,
      frontEvent: { type: 'CONGESTION_END', description: '정체 해소', offsetM: 0 },
    },
    { name: 'slow for corner 3', durS: 13.5, kph: 24, accel: 1.5 },
    { name: 'exit corner 3', durS: 7, kph: 40, accel: 1.4 },
    {
      name: 'arrive',
      durS: 10,
      kph: 0,
      accel: 1.5,
      frontEvent: { type: 'DESTINATION', description: '강남역', offsetM: -3 },
    },
    { name: 'stopped at destination', durS: 2, kph: 0 },
  ],

  corners: [
    { tApex: 24.5, type: 'TURN_RIGHT', description: '대왕판교IC 방면', peakDeg: 165, rampS: 2, holdS: 1, signalLeadS: 4 },
    { tApex: 46.5, type: 'TURN_RIGHT', description: '양재 방면', peakDeg: 150, rampS: 2, holdS: 1, signalLeadS: 4 },
    { tApex: 114, type: 'TURN_LEFT', description: '강남역 방면', peakDeg: 170, rampS: 2, holdS: 1, signalLeadS: 4 },
  ],
};
