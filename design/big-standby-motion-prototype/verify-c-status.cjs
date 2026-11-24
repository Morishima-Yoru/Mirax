'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const host = fs.readFileSync(path.join(__dirname, '..', 'big-standby-prototype.html'), 'utf8');
for (const [, code] of host.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(code);

assert.ok(!/state\.motion\.(?:reduced|aPreviewOverride|cPreviewOverride)\b|function reducedMotionActive\b|prefers-reduced-motion/.test(host),
    'The host must not retain system-reduction gates or preview overrides');

const animations = [];
let prefersReduced = false;
const environment = {
    cFailureStartedAt: null,
    cGlitch: null,
    particleTime: 1000,
    window: { innerWidth: 1440, matchMedia: () => ({ matches: prefersReduced }) },
    state: { phase: 'failed', broadcast: true, motion: { paused: false, hidden: false } },
    oledShiftActive: () => true,
    document: { body: { animate(frames, timing) {
        const animation = {
            frames, timing, cancelled: false, paused: false,
            cancel() { this.cancelled = true; },
            pause() { this.paused = true; }
        };
        animations.push(animation);
        return animation;
    } } }
};
vm.createContext(environment);
for (const name of ['startCFailure', 'cParticleColor']) {
    const source = host.match(new RegExp(String.raw`    function ${name}\([^)]*\) \{[\s\S]*?\r?\n    \}`));
    assert.ok(source, `The host defines ${name}`);
    vm.runInContext(source[0], environment);
}

const color = environment.cParticleColor;
assert.equal(color(0), '231,231,224', 'Idle particles retain their original color');
environment.startCFailure();
assert.equal(environment.cFailureStartedAt, null, 'Do not start red during the glitch');
assert.equal(animations[0].timing.duration, 250, 'The whole-body glitch lasts 250ms');
assert.equal(animations[0].timing.easing, undefined, 'A global one-step easing would hide intermediate glitch frames');
assert.ok(animations[0].frames.every(frame => frame.easing === 'steps(1, end)'));
assert.ok(animations[0].frames.some(frame => frame.transform?.includes('translate')));
assert.ok(animations[0].frames.some(frame => frame.transform?.includes('12px')), 'The failure impact has stronger screen movement');
assert.ok(animations[0].frames.some(frame => frame.offset === .2), 'The impact is timed unevenly');
assert.ok(animations[0].frames.every(frame => !frame.clipPath), 'Never cut the display into television dropout bands');
assert.ok(animations[0].frames.some(frame => frame.filter === 'url(#c-error-dispersion)'), 'Disperse the full composited page, not just its text');
assert.ok(host.includes('<filter id="c-error-dispersion"'));
assert.ok(host.includes('<feOffset') && host.includes('<feGaussianBlur'), 'Chromatic ghosting includes optical defocus');
assert.equal(color(1000), '231,231,224', 'The glitch precedes the red warning');
assert.equal(color(5000), '231,231,224', 'A slow frame must not make the warning overlap the glitch');
animations[0].onfinish({ currentTarget: animations[0] });
assert.equal(environment.cFailureStartedAt, 1000);
assert.equal(color(1000), '231,231,224', 'Warning starts white instead of jumping to red');
assert.equal(color(1250), '243,148,144', 'White blends to red through an intermediate color');
assert.equal(color(1500), '255,64,64', 'The red attack takes half a second');
assert.equal(color(6500), '255,64,64', 'Keep full red for five seconds after the attack');
assert.equal(color(7500), '243,148,144', 'Recovery blends through intermediate colors');
assert.equal(color(8500), '231,231,224');
assert.equal(color(100000), '231,231,224', 'Recovery never repeats');
let previousGreen = 64;
for (let time = 6500; time <= 8500; time += 20) {
    const [red, green, blue] = color(time).split(',').map(Number);
    assert.ok(red >= 231 && red <= 255 && blue >= 64 && blue <= 224);
    assert.ok(green >= previousGreen && green <= 231, 'Recovery is monotone');
    previousGreen = green;
}

environment.startCFailure();
assert.ok(animations[0].cancelled, 'A retrigger cancels the old glitch');
animations[0].onfinish({ currentTarget: animations[0] });
assert.equal(environment.cFailureStartedAt, null, 'A stale finish event cannot restart the warning');
prefersReduced = true;
environment.startCFailure();
assert.deepEqual(animations.at(-1).frames, animations[0].frames,
    'Both system preferences must retain the identical full-page failure displacement');
assert.deepEqual(animations.at(-1).timing, animations[0].timing,
    'Both system preferences must retain the identical 250ms failure timing');
environment.oledShiftActive = () => false;
environment.startCFailure();
assert.ok(animations.at(-1).paused, 'An explicitly paused preview does not play the glitch');
environment.cFailureStartedAt = null;
assert.equal(color(5000), '231,231,224', 'Resetting the failure clears its warning tint');
console.log('C failure timing, recovery, retrigger, pause, system-preference independence and JavaScript syntax passed');
