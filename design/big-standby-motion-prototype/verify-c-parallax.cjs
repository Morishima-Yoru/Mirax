'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const environment = { window: {} };
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', 'big-standby-c-parallax.js'), 'utf8'), environment);
const Parallax = environment.window.MiraxCParallax;
assert.deepEqual(Object.keys(environment.window), ['MiraxCParallax']);

function createCanvas(width = 1920, height = 1080) {
    return {
        id: 'particle-canvas', tagName: 'CANVAS', clientWidth: width, clientHeight: height,
        style: new Proxy({ transform: '', pointerEvents: 'auto', transformOrigin: '', zIndex: '-5' }, {
            set(style, key, value) {
                assert.ok(['transform', 'pointerEvents', 'transformOrigin'].includes(key), `Unexpected style write: ${key}`);
                style[key] = value;
                return true;
            }
        })
    };
}

function createInformation(width = 1920, height = 1080) {
    const values = new Map();
    return { id: 'standby-view', clientWidth: width, clientHeight: height, style: {
        setProperty(name, value) { assert.equal(name, '--c-parallax-transform'); values.set(name, value); },
        removeProperty(name) { assert.equal(name, '--c-parallax-transform'); values.delete(name); },
        getPropertyValue(name) { return values.get(name) || ''; }
    } };
}

function readTransform(canvas) {
    const match = canvas.style.transform.match(/^translate3d\(([^,]+)px, ([^,]+)px, 0\)$/);
    assert.ok(match, `Only uniform translation is allowed: ${canvas.style.transform}`);
    const [dx, dy] = match.slice(1).map(Number);
    assert.ok([dx, dy].every(Number.isFinite), 'Translation must remain finite');
    return { dx, dy };
}

let maximumDisplacement = 0;
function verifyTranslation(canvas) {
    const { dx, dy } = readTransform(canvas);
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    const limit = Math.min(width, height) * .0189;
    assert.ok(Math.abs(dx) <= limit && Math.abs(dy) <= limit, 'Translation must stay within 1.89 percent of the short edge');
    maximumDisplacement = Math.max(maximumDisplacement, Math.abs(dx / width), Math.abs(dy / height));
    assert.equal(canvas.style.zIndex, '-5', 'Parallax must not change stacking');
}

for (const invalid of [null, {}, { id: 'brand', tagName: 'CANVAS' }, { id: 'particle-canvas', tagName: 'DIV' }]) {
    assert.throws(() => new Parallax(invalid), /C parallax requires #particle-canvas/);
}

assert.throws(() => new Parallax(createCanvas(), null), /C parallax requires #standby-view/);
const canvas = createCanvas();
const motion = new Parallax(canvas, createInformation());
assert.equal(canvas.style.pointerEvents, 'none');
assert.equal(canvas.style.transformOrigin, '50% 50%');
motion.setPointer(1, 1);
assert.equal(canvas.style.transform, '', 'Pointer input must only update state');
motion.update(.1);
const first = readTransform(canvas);
assert.ok(first.dx > 0 && first.dy > 0, 'First frame must ease toward down/right');
assert.ok(Math.abs(motion.x - (1 - Math.exp(-.4))) < 1e-12,
    'The particle response must retain a four-per-second smoothing coefficient');
assert.ok(Math.abs(motion.informationX - (1 - Math.exp(-.2))) < 1e-12 &&
    Math.abs(motion.informationY - (1 - Math.exp(-.2))) < 1e-12,
    'Corner titles must use independent two-per-second motion, not scaled particle state');
const firstInformation = readTransform({ style: { transform: motion.information.style.getPropertyValue('--c-parallax-transform') } });
assert.ok(Math.abs(firstInformation.dx - 1080 * .01 * (1 - Math.exp(-.2))) < 1e-12,
    'Corner titles must combine their own response with a 1-percent displacement budget');
canvas.style.transform = 'rotate(45deg)';
motion.reset();
assert.equal(canvas.style.transform, '', 'Reset must synchronously clear any existing transform');
assert.equal(motion.information.style.getPropertyValue('--c-parallax-transform'), '', 'Reset must also clear information translation');
assert.equal(motion.informationX, 0, 'Reset must clear the title horizontal history');
assert.equal(motion.informationY, 0, 'Reset must clear the title vertical history');
motion.update(.1);
assert.equal(canvas.style.transform, '', 'Reset must clear both current and target input');

for (const invalid of [undefined, null, NaN, Infinity, -Infinity, '1', {}, []]) {
    motion.reset();
    motion.setPointer(invalid, invalid);
    motion.update(.1);
    assert.equal(canvas.style.transform, '', 'Non-finite or non-numeric pointer input must be neutral');
}
for (const invalid of [undefined, null, NaN, Infinity, -Infinity, '0.1', -1, 0]) {
    motion.reset();
    motion.setPointer(1, 1);
    motion.update(invalid);
    assert.equal(canvas.style.transform, '', 'Invalid, negative or zero time must not advance motion');
}
motion.reset();
motion.setPointer(1e100, 1e100);
motion.update(1e100);
const clamped = readTransform(canvas);
assert.deepEqual(clamped, first, 'Out-of-range pointer input and elapsed time must be clamped');

const paddedCanvas = createCanvas(1964, 1124);
const padded = new Parallax(paddedCanvas, createInformation());
padded.setPointer(1, 1);
padded.update(.1);
assert.deepEqual(readTransform(paddedCanvas), first, 'A drawing buffer must not increase viewport-relative motion');

const splitCanvas = createCanvas();
const split = new Parallax(splitCanvas, createInformation());
split.setPointer(1, 1);
for (let step = 0; step < 10; step += 1) split.update(.01);
const splitFrame = readTransform(splitCanvas);
for (const key of ['dx', 'dy']) {
    assert.ok(Math.abs(splitFrame[key] - first[key]) < 1e-12, 'Exponential smoothing must be frame-rate independent');
}
const splitInformation = readTransform({ style: { transform: split.information.style.getPropertyValue('--c-parallax-transform') } });
for (const key of ['dx', 'dy']) {
    assert.ok(Math.abs(splitInformation[key] - firstInformation[key]) < 1e-12,
        'Independent title motion must also be frame-rate independent');
}
const layered = new Parallax(createCanvas(), createInformation());
layered.setPointer(1, -1);
layered.update(.1);
const earlyRatio = layered.informationX / layered.x;
layered.update(.1);
assert.ok(layered.informationX < layered.x && layered.informationY > layered.y &&
    layered.informationX / layered.x > earlyRatio, 'The layer speed ratio must evolve, not stay a fixed amplitude multiplier');
const titleBeforeIdle = layered.informationX;
const particleBeforeIdle = layered.x;
layered.setPointer(0, 0);
layered.update(.1);
assert.ok(Math.abs(layered.x / particleBeforeIdle - Math.exp(-.4)) < 1e-12 &&
    Math.abs(layered.informationX / titleBeforeIdle - Math.exp(-.2)) < 1e-12,
    'Both layers must settle independently after the sensor sends zero input');
layered.setPointer(-1, 1);
layered.update(.1);
assert.ok(layered.x < 0 && layered.informationX > 0,
    'Particles must reverse sooner than the slower title layer');
for (let step = 0; step < 30; step += 1) layered.update(.1);
assert.ok(layered.informationX < 0 && layered.informationY > 0, 'Both layers must eventually follow the reversed direction');
layered.reset();
layered.informationX = .25;
layered.update(0);
assert.ok(layered.information.style.getPropertyValue('--c-parallax-transform'),
    'A neutral particle layer must not erase the remaining title motion');
layered.reset();


for (const [width, height] of [[320, 640], [640, 320], [1080, 1920], [1920, 1080], [800, 800], [1, 4096], [4096, 1]]) {
    const plane = createCanvas(width, height);
    const parallax = new Parallax(plane, createInformation(width, height));
    for (const x of [-1, -.5, 0, .5, 1]) {
        for (const y of [-1, -.5, 0, .5, 1]) {
            parallax.reset();
            parallax.setPointer(x, y);
            for (let step = 0; step < 60; step += 1) {
                parallax.update(.1);
                if (x !== 0 || y !== 0) {
                    verifyTranslation(plane);
                    const info = parallax.information.style.getPropertyValue('--c-parallax-transform');
                    const frame = readTransform(plane);
                    const foreground = readTransform({ style: { transform: info } });
                    const limit = Math.min(width, height) * .01;
                    assert.ok(Math.abs(foreground.dx) <= limit && Math.abs(foreground.dy) <= limit,
                        'Corner titles must remain within 1 percent of the viewport short edge');
                    assert.ok(Math.abs(foreground.dx) <= Math.abs(frame.dx) * .01 / .0189 + 1e-12 &&
                        Math.abs(foreground.dy) <= Math.abs(frame.dy) * .01 / .0189 + 1e-12,
                        'From rest, the slower title layer must not overtake the particle layer');
                }
            }
            if (x === 0 && y === 0) assert.equal(plane.style.transform, '');
            else {
                const frame = readTransform(plane);
                assert.equal(Math.sign(frame.dy) || 0, Math.sign(y) || 0, 'Vertical translation must follow input direction');
                assert.equal(Math.sign(frame.dx) || 0, Math.sign(x) || 0, 'Horizontal translation must follow input direction');
            }
        }
    }
}

for (const size of [0, -1, NaN, Infinity]) {
    for (const axis of ['clientWidth', 'clientHeight']) {
        motion.information[axis] = size;
        motion.setPointer(1, 1);
        motion.update(.1);
        assert.equal(canvas.style.transform, '', 'Hidden or invalid geometry must reset motion');
        motion.information.clientWidth = 1920;
        motion.information.clientHeight = 1080;
        motion.update(.1);
        assert.equal(canvas.style.transform, '', 'Geometry recovery must not restore stale input');
    }
}

assert.ok(maximumDisplacement > .0188 && maximumDisplacement <= .0189, 'Pure translation must retain gentle nonzero motion');
console.log(`C parallax verified: four directions, uniform translation, no zoom, reset, independent particle/title velocities; maximum sampled axis displacement ${(maximumDisplacement * 100).toFixed(3)}% (<=1.89%).`);
