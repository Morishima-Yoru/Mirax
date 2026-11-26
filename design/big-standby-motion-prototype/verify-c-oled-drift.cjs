'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const environment = { window: {} };
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', 'big-standby-c-oled-drift.js'), 'utf8'), environment);
const Drift = environment.window.MiraxCOledDrift;
let checks = 0;
let peakSpeed = 0;
for (const initialSeed of [7, 123, 74321, 918273]) {
    let seed = initialSeed;
    const random = () => {
        seed = seed * 16807 % 2147483647;
        return (seed - 1) / 2147483646;
    };
    const drift = new Drift(random);
    let previous = { x: 0, y: 0 };
    const directions = new Set();
    for (let tick = 0; tick < 108000; tick += 1) {
        const point = drift.advance(.1);
        const dx = point.x - previous.x;
        const dy = point.y - previous.y;
        const speed = Math.hypot(dx, dy) * 10;
        peakSpeed = Math.max(peakSpeed, speed);
        assert.ok(Math.abs(point.x) <= 3 && Math.abs(point.y) <= 3, 'Drift must stay within the safe three-pixel envelope');
        assert.ok(speed <= .026, `No visible burst at a random target boundary: ${speed}px/s`);
        if (dx > .00001) directions.add('right');
        if (dx < -.00001) directions.add('left');
        if (dy > .00001) directions.add('down');
        if (dy < -.00001) directions.add('up');
        previous = { ...point };
        checks += 2;
    }
    assert.equal(directions.size, 4, 'Random drift must eventually move in all four directions');
    const before = { ...previous };
    for (const elapsed of [0, -1, NaN, Infinity]) {
        const point = drift.advance(elapsed);
        assert.equal(point.x, before.x, 'Invalid or zero elapsed time cannot reset the drift');
        assert.equal(point.y, before.y);
    }
    const resumed = drift.advance(3600);
    assert.ok(Math.hypot(resumed.x - before.x, resumed.y - before.y) <= .026 * .25,
        'A suspended tab cannot catch up with a visible jump');
}
const first = new Drift(() => .2);
const second = new Drift(() => .8);
for (let tick = 0; tick < 1200; tick += 1) { first.advance(.1); second.advance(.1); }
assert.notDeepEqual({ x: first.x, y: first.y }, { x: second.x, y: second.y }, 'Different random inputs must not trace the same fixed path');
console.log(`C OLED random drift: ${checks} continuity/bounds checks passed; peak ${peakSpeed.toFixed(5)} CSS px/s`);
