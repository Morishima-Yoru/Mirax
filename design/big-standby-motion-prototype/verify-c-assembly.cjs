'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const environment = { window: {} };
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', 'big-standby-c-particle-assembly.js'), 'utf8'), environment);
const Assembly = environment.window.MiraxCParticleAssembly;
const count = 1800;
const frame = (width, height, time) => ({ width, height, time });
const normalizedExponentialOut = value => (1 - Math.exp(-4 * value)) / (1 - Math.exp(-4));
let checks = 0;

function check(value, message) {
    assert.ok(value, message);
    checks += 1;
}

const host = fs.readFileSync(path.join(__dirname, '..', 'big-standby-prototype.html'), 'utf8');
const population = host.match(/const particles = Array[.]from[(][{] length: ([0-9]+) [}]/);
check(Number(population?.[1]) === count, 'The host particle population matches the tested density');

const cloud = new Assembly(count);
for (const time of [0, 4, 100]) {
    for (let index = 0; index < count; index += 1) {
        const result = cloud.project(index, index * .37, index * .21, frame(800, 528, time));
        check(result.x === index * .37 && result.y === index * .21, 'OFF must preserve exact free-flight positions');
    }
}

cloud.setBroadcast(true, 100);
let previous = 0;
for (let sample = 0; sample <= 1200; sample += 1) {
    const progress = cloud.progress(100 + sample / 100);
    check(progress >= previous && progress >= 0 && progress <= 1, 'Assembly progress must be monotone and bounded');
    previous = progress;
}
check(cloud.progress(100) === 0 && cloud.progress(106) === 1, 'Gathering completes over six elapsed seconds');
check(cloud.progress(105.9) < 1, 'Assembly must still travel continuously rather than complete early');
const paused = cloud.project(17, 72, 154, frame(800, 528, 105));
const repeated = cloud.project(17, 72, 154, frame(800, 528, 105));
check(paused.x === repeated.x && paused.y === repeated.y, 'A paused shared clock freezes assembly');
cloud.setBroadcast(false, 105);
const reversed = cloud.project(17, 72, 154, frame(800, 528, 105));
check(Math.hypot(paused.x - reversed.x, paused.y - reversed.y) < .000001, 'Mid-flight reversal must not jump');
cloud.setBroadcast(true, 105.5);
const resumed = cloud.project(17, 72, 154, frame(800, 528, 105.5));
cloud.setBroadcast(false, 105.5);
const toggled = cloud.project(17, 72, 154, frame(800, 528, 105.5));
check(Math.hypot(resumed.x - toggled.x, resumed.y - toggled.y) < .000001, 'Repeated toggles stay continuous');
cloud.setBroadcast(true, 120);
check(cloud.progress(132) === 1, 'Assembly completes after an interrupted transition');
cloud.setBroadcast(false, 132);
check(cloud.progress(132) === 1 && cloud.progress(140) === 0, 'Full dispersal takes eight elapsed seconds');
const dispersed = cloud.project(5, 12, 34, frame(800, 528, 140));
check(dispersed.x === 12 && dispersed.y === 34, 'Dispersal returns exactly to free flight');

const prompt = new Assembly(count);
prompt.setBroadcast(true, 0);
const fullTarget = new Assembly(count);
fullTarget.target = 1;
let onsetDistance = 0;
for (const time of [1 / 120, 1 / 60, .05, .1, .25]) {
    check(Math.abs(prompt.progress(time) - normalizedExponentialOut(time / 6)) < 1e-12,
        'Gathering must follow exponential ease-out without a second ease-in');
    for (let index = 1; index < 100; index += 1) {
        const point = prompt.project(index, 72, 154, frame(800, 528, time));
        if (index % 5 === 0) {
            check(point.x === 72 && point.y === 154, 'Immediate pickup preserves reserved free-flight particles');
            continue;
        }
        const destination = fullTarget.project(index, 72, 154, frame(800, 528, time));
        const dx = destination.x - 72;
        const dy = destination.y - 154;
        const pull = ((point.x - 72) * dx + (point.y - 154) * dy) / (dx * dx + dy * dy);
        const fraction = normalizedExponentialOut(time / 6);
        const expected = fraction + prompt.targets[index].lag * fraction * (1 - fraction);
        check(Math.abs(pull - expected) < 1e-10,
            'Each participating particle must pull toward X immediately, without a random sensing offset');
        if (index === 17 && time === .1) onsetDistance = Math.hypot(point.x - 72, point.y - 154);
    }
}
check(prompt.duration === 6 && prompt.progress(5.9) < 1 && prompt.progress(6) === 1,
    'Exponential ease-out must reach its exact endpoint at six seconds');
check(prompt.progress(0) === 0 && Math.abs(prompt.progress(3) - normalizedExponentialOut(.5)) < 1e-12,
    'Exponential ease-out must start at zero and be ahead of linear progress halfway through');
let previousProgress = 0;
let previousIncrement = Infinity;
for (let step = 0; step <= 600; step += 1) {
    const time = step / 100;
    const value = prompt.progress(time);
    check(value >= previousProgress && value >= 0 && value <= 1, 'Exponential progress must remain bounded and monotonic');
    check(Math.abs(value - normalizedExponentialOut(time / 6)) < 1e-12, 'Progress must match the independent exponential oracle');
    if (step > 0) {
        const increment = value - previousProgress;
        check(increment <= previousIncrement, 'Equal-time motion must keep slowing down without a late acceleration');
        previousIncrement = increment;
    }
    previousProgress = value;
}
const earlyStep = prompt.progress(.2) - prompt.progress(.1);
const middleStep = prompt.progress(3.1) - prompt.progress(3);
const lateStep = prompt.progress(5.9) - prompt.progress(5.8);
check(earlyStep > middleStep && middleStep > lateStep, 'Exponential ease-out must start fast then keep decelerating');
check(prompt.progress(.1) > .06, 'The directed pickup must be immediately visible');
check(prompt.progress(-1) === 0 && prompt.progress(7) === 1, 'Exponential ease-out must clamp safely at both finite endpoints');
const firstResponse = prompt.project(17, 72, 154, frame(800, 528, .1));
prompt.setBroadcast(false, .1);
const earlyReverse = prompt.project(17, 72, 154, frame(800, 528, .1));
check(Math.hypot(firstResponse.x - earlyReverse.x, firstResponse.y - earlyReverse.y) < 1e-6,
    'Reversing during immediate pickup must not jump');
console.log('First 100ms directed pickup: ' + onsetDistance.toFixed(3) + 'px');

const formed = new Assembly(count);
formed.setBroadcast(true, 0);
for (const [width, height] of [[0, 0], [1, 1], [320, 568], [390, 700], [390, 780],
    [390, 844], [568, 320], [667, 375], [800, 528], [1440, 1000], [2560, 720]]) {
    let bottom = -Infinity;
    const quadrants = [0, 0, 0, 0];
    for (let index = 0; index < count; index += 1) {
        const point = formed.project(index, width * .5, height * .5, frame(width, height, 16));
        check(Number.isFinite(point.x) && Number.isFinite(point.y), 'Projection must remain finite');
        if (index % 5 === 0) {
            check(point.x === width * .5 && point.y === height * .5, 'A fifth of particles retain exact free-flight positions when assembled');
            continue;
        }
        bottom = Math.max(bottom, point.y);
        if (width >= 320 && height >= 320) {
            check(point.x >= 0 && point.x <= width && point.y >= 0 && point.y <= height, 'The complete mark stays on screen');
            quadrants[(point.y > formed.cy ? 2 : 0) + (point.x > formed.cx ? 1 : 0)] += 1;
        }
    }
    if (width <= 600 && height > width && height >= 568) {
        check(bottom < height - 126, 'Portrait mark must not hide behind bottom navigation');
    }
    check(formed.cx === width * .5 && formed.cy === height * .5, 'The mark is anchored at the exact viewport centre');
    if (width >= 320 && height >= 320) check(quadrants.every(value => value >= 150), 'All four X arms remain populated');
}

const twin = new Assembly(count);
twin.setBroadcast(true, 0);
for (let index = 0; index < count; index += 1) {
    const a = formed.project(index, 123, 456, frame(800, 528, 16));
    const b = twin.project(index, 123, 456, frame(800, 528, 16));
    check(a.x === b.x && a.y === b.y, 'Fresh instances project deterministically');
}
const beforeResize = formed.project(41, 123, 456, frame(390, 844, 16));
const afterResize = formed.project(41, 123, 456, frame(390.000001, 844, 16));
check(Math.hypot(beforeResize.x - afterResize.x, beforeResize.y - afterResize.y) < .001, 'Responsive placement has no breakpoint jump');

let minimumStep = Infinity;
for (const time of [16, 24, 45, 120]) {
    let roaming = 0;
    for (let index = 0; index < count; index += 1) {
        const a = formed.project(index, 101, 203, frame(800, 528, time));
        const b = formed.project(index, 104, 207, frame(800, 528, time + .25));
        const step = Math.hypot(b.x - a.x, b.y - a.y);
        if (index % 5 === 0) {
            check(a.x === 101 && a.y === 203 && b.x === 104 && b.y === 207, 'Ambient particles keep following their incoming free-flight motion');
            roaming += 1;
        } else {
            minimumStep = Math.min(minimumStep, step);
            check(step > .1, 'Assembled particles keep moving instead of reaching zero flow');
            const frozen = formed.project(index, 104, 207, frame(800, 528, time + .25));
            check(frozen.x === b.x && frozen.y === b.y, 'Contour flow also freezes on the shared clock');
        }
    }
    check(roaming === count / 5, 'Exactly twenty percent remain in the ambient cloud');
}
for (const index of [1, 2, 3, 4, 6, 7, 8, 9, 19]) {
    const target = formed.targets[index];
    for (const edge of target.contour.edges) {
        let time = (edge.start / target.contour.perimeter - target.start) / target.speed;
        while (time < 16) time += 1 / Math.abs(target.speed);
        const a = formed.project(index, 0, 0, frame(800, 528, time - .000001));
        const b = formed.project(index, 0, 0, frame(800, 528, time + .000001));
        check(Math.hypot(b.x - a.x, b.y - a.y) < .01, 'Flow passes polygon corners without position jumps');
    }
}
const diamonds = formed.targets.flatMap((target, index) => target.contour.edges.length === 4 ? [index] : []);
check(diamonds.length === count / 10, 'A dedicated tenth of particles forms the central diamond');
for (const index of diamonds) {
    check(index % 5 !== 0, 'Diamond particles cannot be reserved for free flight');
    const point = formed.project(index, 0, 0, frame(800, 528, 16));
    check(Math.abs(point.x - formed.cx) < formed.size * .12 + 4 && Math.abs(point.y - formed.cy) < formed.size * .12 + 4, 'Diamond particles stay inside the central clearance');
}
console.log('Persistent flow: minimum quarter-second movement ' + minimumStep.toFixed(3) + 'px');
console.log('C particle assembly: ' + checks + ' checks passed');
