const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// Exercise the exact script shipped in the APK, rather than a second implementation.
const kotlin = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/kcliang/autostream/YouTubeJs.kt'), 'utf8');
const source = kotlin.match(/private val DOUBLE_TAP_SEEK = """([\s\S]*?)"""\.trimIndent\(\)/)?.[1];
assert.ok(source, 'seek script exists');

function fixture(options = {}) {
  const state = { time: options.time ?? 50, duration: options.duration ?? 100, paused: options.paused ?? false, events: [] };
  const rect = options.rect ?? { left: 100, top: 100, width: 800, height: 400 };
  const video = {
    get currentTime() { return state.time; },
    set currentTime(value) { if (options.throwOnSeek) throw Error('not seekable'); state.time = value; },
    get duration() { return state.duration; },
    get paused() { return state.paused; },
    play() { throw Error('seeking must not call play'); },
    pause() { throw Error('seeking must not call pause'); },
    getBoundingClientRect: () => rect,
  };
  const player = { querySelector: () => options.noVideo ? null : video, classList: { contains: () => !!options.ad } };
  const document = {
    getElementById: () => player,
    querySelector: () => options.noVideo ? null : video,
    elementFromPoint: () => options.noHit ? null : { closest: () => options.control ? {} : null },
  };
  const window = { innerWidth: 1000, innerHeight: 800, __asState: event => state.events.push(event) };
  const seek = vm.runInNewContext(`(${source})`, { document, window });
  return { state, seek, left: () => seek(.2, .375, .21, .38, false), right: () => seek(.8, .375, .79, .38, false) };
}

let count = 0;
function test(name, run) { run(); count++; }

test('left seeks back ten seconds while playing', () => {
  const f = fixture(); assert.equal(f.left(), true); assert.equal(f.state.time, 40); assert.equal(f.state.paused, false); assert.deepEqual(f.state.events, ['seek']);
});
test('right seeks forward ten seconds', () => {
  const f = fixture(); assert.equal(f.right(), true); assert.equal(f.state.time, 60);
});
test('paused video remains paused', () => {
  const f = fixture({ paused: true }); assert.equal(f.right(), true); assert.equal(f.state.paused, true);
});
test('rewind clamps at zero', () => {
  const f = fixture({ time: 3 }); f.left(); assert.equal(f.state.time, 0);
});
test('forward clamps at duration', () => {
  const f = fixture({ time: 97 }); f.right(); assert.equal(f.state.time, 100);
});
test('successive double taps accumulate', () => {
  const f = fixture(); f.right(); f.right(); assert.equal(f.state.time, 70);
});
test('native controls are not intercepted', () => {
  const f = fixture({ control: true }); assert.equal(f.right(), false); assert.equal(f.state.time, 50);
});
test('video center retains native click behavior', () => {
  const f = fixture(); assert.equal(f.seek(.5, .375, .51, .38, false), false);
});
test('top and bottom controls are outside seek zones', () => {
  const f = fixture(); assert.equal(f.seek(.2, .15, .21, .15, false), false); assert.equal(f.seek(.2, .59, .21, .59, false), false);
});
test('opposite sides do not form a seek', () => {
  const f = fixture(); assert.equal(f.seek(.2, .375, .8, .375, false), false);
});
test('ad playback is left alone', () => {
  const f = fixture({ ad: true }); assert.equal(f.left(), false); assert.equal(f.state.time, 50);
});
test('live streams without finite duration are left alone', () => {
  const f = fixture({ duration: Infinity }); assert.equal(f.right(), false);
});
test('missing video is not handled', () => assert.equal(fixture({ noVideo: true }).left(), false));
test('invalid coordinates are rejected', () => {
  const f = fixture(); assert.equal(f.seek(NaN, .3, .2, .3, false), false); assert.equal(f.seek(-.1, .3, .2, .3, false), false);
});
test('live geometry is checked after scrolling', () => {
  const f = fixture({ rect: { left: 100, top: 500, width: 800, height: 200 } }); assert.equal(f.right(), false);
});
test('native HTML5 fullscreen uses whole viewport even when DOM geometry is stale', () => {
  const f = fixture({ noHit: true, rect: { left: 0, top: 0, width: 0, height: 0 } }); assert.equal(f.seek(.8, .5, .81, .5, true), true); assert.equal(f.state.time, 60);
});
test('DOM buttons are protected in fullscreen too', () => {
  const f = fixture({ control: true }); assert.equal(f.seek(.8, .5, .81, .5, true), false);
});
test('seek errors fall back without reporting success', () => {
  const f = fixture({ throwOnSeek: true }); assert.equal(f.left(), false); assert.equal(f.state.time, 50); assert.deepEqual(f.state.events, []);
});

console.log(`PASS: ${count} double-tap seek script cases`);
