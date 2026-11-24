'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'big-standby-c-gyroscope.js'), 'utf8');
const statuses = ['idle', 'insecure', 'unsupported', 'requesting', 'waiting', 'active',
    'suspended', 'denied', 'unavailable', 'error'];
const observedStatuses = new Set();
const degrees = Math.PI / 180;
const neutral = { alpha: 0, beta: 0, gamma: 0 };

class MockEventTarget {
    constructor() {
        this.listeners = new Map();
    }

    addEventListener(type, listener) {
        if (!this.listeners.has(type)) this.listeners.set(type, new Set());
        this.listeners.get(type).add(listener);
    }

    removeEventListener(type, listener) {
        this.listeners.get(type)?.delete(listener);
    }

    dispatch(type, event = {}) {
        for (const listener of [...(this.listeners.get(type) || [])]) listener(event);
    }

    count(type) {
        return this.listeners.get(type)?.size || 0;
    }

    countAll() {
        return [...this.listeners.values()].reduce((sum, listeners) => sum + listeners.size, 0);
    }
}

class MockClock {
    constructor() {
        this.now = 0;
        this.nextId = 0;
        this.tasks = new Map();
    }

    setTimeout(callback, delay) {
        const id = ++this.nextId;
        this.tasks.set(id, { callback, due: this.now + delay });
        return id;
    }

    clearTimeout(id) {
        this.tasks.delete(id);
    }

    advance(duration) {
        const end = this.now + duration;
        while (true) {
            const next = [...this.tasks].filter(([, task]) => task.due <= end)
                .sort((a, b) => a[1].due - b[1].due)[0];
            if (!next) break;
            const [id, task] = next;
            this.tasks.delete(id);
            this.now = task.due;
            task.callback();
        }
        this.now = end;
    }
}

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((accept, fail) => { resolve = accept; reject = fail; });
    return { promise, resolve, reject };
}

function createHarness(options = {}) {
    const clock = new MockClock();
    const window = new MockEventTarget();
    const document = new MockEventTarget();
    const orientation = options.modern === false ? null : new MockEventTarget();
    if (orientation) {
        orientation.angle = Object.hasOwn(options, 'angle') ? options.angle : 0;
        if (options.modernEvents === false) {
            orientation.addEventListener = undefined;
            orientation.removeEventListener = undefined;
        }
    }
    Object.assign(window, {
        isSecureContext: options.secure !== false,
        screen: orientation ? { orientation } : {},
        orientation: options.legacyAngle,
        performance: { now: () => clock.now },
        setTimeout: (callback, delay) => clock.setTimeout(callback, delay),
        clearTimeout: id => clock.clearTimeout(id)
    });
    document.hidden = false;
    document.hasFocus = () => true;
    let gesture = false;
    const permissionCalls = [];
    const warnings = [];
    const PermissionEvent = class DeviceOrientationEvent {};
    if (Object.hasOwn(options, 'permission')) {
        PermissionEvent.requestPermission = function (...args) {
            permissionCalls.push({ gesture, args, receiver: this });
            return options.permission(...args);
        };
    }
    if (options.supported !== false) window.DeviceOrientationEvent = PermissionEvent;
    const previousKeys = Object.keys(window);
    vm.runInNewContext(source, { window, document, console: { warn: (...args) => warnings.push(args) } });
    assert.deepEqual(Object.keys(window).filter(key => !previousKeys.includes(key)), ['MiraxCGyroscope']);
    const inputs = [];
    const resets = [];
    const changes = [];
    let output = { x: 0, y: 0 };
    let gyro;
    gyro = new window.MiraxCGyroscope({
        onInput(...args) {
            assert.equal(args.length, 2);
            const [x, y] = args;
            assert.ok(Number.isFinite(x) && Number.isFinite(y));
            assert.ok(Math.abs(x) <= 1 && Math.abs(y) <= 1);
            output = { x, y };
            inputs.push(output);
        },
        onReset(...args) {
            assert.equal(args.length, 0);
            output = { x: 0, y: 0 };
            resets.push(inputs.length);
        },
        onStatus(...args) {
            assert.equal(args.length, 0);
            assert.ok(gyro, 'The constructor must not call onStatus before assignment');
            assert.ok(statuses.includes(gyro.status), `Unexpected status: ${gyro.status}`);
            changes.push(gyro.status);
            observedStatuses.add(gyro.status);
        }
    });
    observedStatuses.add(gyro.status);
    return {
        gyro, window, document, orientation, clock, inputs, resets, changes, warnings, permissionCalls,
        get output() { return output; },
        sample(event, interval = 100) {
            clock.advance(interval);
            window.dispatch('deviceorientation', event);
        },
        clickEnable() {
            gesture = true;
            try { return gyro.enable(); }
            finally { gesture = false; }
        }
    };
}

function state(harness, status, enabled, requesting, listening) {
    const gyro = harness.gyro;
    assert.deepEqual({ status: gyro.status, enabled: gyro.enabled,
        requesting: gyro.requesting, listening: gyro.listening },
    { status, enabled, requesting, listening });
}

function clean(harness) {
    assert.equal(harness.window.countAll(), 0, 'Window listeners must be removed');
    assert.equal(harness.orientation?.countAll() || 0, 0, 'Screen listeners must be removed');
    assert.equal(harness.document.countAll(), 0, 'The host owns document lifecycle gates');
    assert.equal(harness.clock.tasks.size, 0, 'No sample or idle timeout may survive cleanup');
}

function close(actual, expected, message = 'Relative angular velocity output') {
    assert.ok(Math.abs(actual.x - expected.x) < 1e-7 && Math.abs(actual.y - expected.y) < 1e-7,
        `${message}: ${JSON.stringify(actual)} != ${JSON.stringify(expected)}`);
}

// The oracle uses full independent axis matrices, not component-angle subtraction.
function multiply(left, right) {
    return left.map(row => right[0].map((_, column) =>
        row.reduce((sum, value, index) => sum + value * right[index][column], 0)));
}

function rotation(axis, angle) {
    const c = Math.cos(angle * degrees);
    const s = Math.sin(angle * degrees);
    if (axis === 'x') return [[1, 0, 0], [0, c, -s], [0, s, c]];
    if (axis === 'y') return [[c, 0, s], [0, 1, 0], [-s, 0, c]];
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]];
}

function absoluteMatrix({ alpha, beta, gamma }) {
    return multiply(multiply(rotation('z', alpha), rotation('x', beta)), rotation('y', gamma));
}

function matrixSample(matrix) {
    let beta = Math.atan2(matrix[2][1], Math.hypot(matrix[0][1], matrix[1][1]));
    let alpha;
    let gamma;
    if (Math.abs(Math.cos(beta)) > 1e-7) {
        alpha = Math.atan2(-matrix[0][1], matrix[1][1]);
        gamma = Math.atan2(-matrix[2][0], matrix[2][2]);
    } else {
        alpha = Math.atan2(matrix[1][0], matrix[0][0]);
        gamma = 0;
    }
    alpha /= degrees;
    beta /= degrees;
    gamma /= degrees;
    if (Math.abs(gamma) > 90) {
        alpha += 180;
        beta = beta >= 0 ? 180 - beta : -180 - beta;
        gamma += gamma > 0 ? -180 : 180;
    }
    const sample = { alpha: (alpha % 360 + 360) % 360, beta, gamma };
    const roundTrip = absoluteMatrix(sample);
    for (let row = 0; row < 3; row += 1) {
        for (let column = 0; column < 3; column += 1) {
            assert.ok(Math.abs(roundTrip[row][column] - matrix[row][column]) < 1e-7,
                'Generated Euler sample must reproduce the independently composed rotation');
        }
    }
    return sample;
}

function localOutput(matrix, interval = 100) {
    const scale = angle => Math.max(-1, Math.min(1, angle / degrees / (interval / 1000) / 90));
    return { x: scale(Math.atan2(matrix[0][2], matrix[2][2])),
        y: scale(-Math.atan2(matrix[1][2], matrix[2][2])) };
}

test('constructor API, default host gate, no callback before assignment', async () => {
    const h = createHarness();
    state(h, 'idle', false, false, false);
    assert.equal(h.changes.length, 0);
    assert.equal(h.resets.length, 0);
    for (const name of ['enable', 'disable', 'setActive', 'resetMotion', 'destroy']) {
        assert.equal(typeof h.gyro[name], 'function');
    }
    clean(h);
    await h.clickEnable();
    state(h, 'suspended', true, false, false);
    clean(h);
    h.gyro.setActive(true);
    state(h, 'waiting', true, false, true);
    h.sample({ alpha: 123, beta: 62, gamma: -31 });
    state(h, 'active', true, false, true);
    assert.deepEqual(h.output, { x: 0, y: 0 });
    const changes = h.changes.length;
    await h.clickEnable();
    assert.equal(h.changes.length, changes, 'Enabling an enabled controller is idempotent');
    h.gyro.destroy();
    clean(h);
});

test('motion reverses immediately while still on the same side of the starting pose', async () => {
    const h = createHarness();
    h.gyro.setActive(true);
    await h.clickEnable();
    h.sample({ ...neutral, beta: 45 });
    h.sample({ ...neutral, beta: 45, gamma: 8 });
    assert.ok(h.output.x > 0, 'Moving right must produce positive input');
    h.sample({ ...neutral, beta: 45, gamma: 4 });
    assert.ok(h.output.x < 0, 'Moving left must reverse input before crossing the starting pose');
    h.gyro.destroy();
    clean(h);
});

test('no requestPermission or DeviceMotionEvent is needed; four directions and 90 degrees/sec saturation', async () => {
    const h = createHarness();
    h.gyro.setActive(true);
    const enabling = h.clickEnable();
    state(h, 'waiting', true, false, true);
    await enabling;
    assert.equal(h.permissionCalls.length, 0);
    for (const [sample, expected] of [
        [{ ...neutral, gamma: 4.5 }, { x: .5, y: 0 }],
        [{ ...neutral, gamma: -4.5 }, { x: -.5, y: 0 }],
        [{ ...neutral, beta: 4.5 }, { x: 0, y: .5 }],
        [{ ...neutral, beta: -4.5 }, { x: 0, y: -.5 }],
        [{ ...neutral, gamma: 9 }, { x: 1, y: 0 }],
        [{ ...neutral, gamma: -9 }, { x: -1, y: 0 }],
        [{ ...neutral, beta: 9 }, { x: 0, y: 1 }],
        [{ ...neutral, beta: -9 }, { x: 0, y: -1 }]
    ]) {
        h.sample(neutral);
        h.sample(sample);
        close(h.output, expected);
    }
    h.gyro.destroy();
    clean(h);
});

test('independent matrix oracle covers successive flat, compound, inverted and upright beta=90 motion', async () => {
    const startingPoses = [neutral, { alpha: 75, beta: 23, gamma: -31 },
        { alpha: 350, beta: 89, gamma: 12 }, { alpha: 0, beta: 90, gamma: 0 },
        { alpha: 137, beta: 90, gamma: 29 }, { alpha: 215, beta: -90, gamma: 10 },
        { alpha: 24, beta: 180, gamma: -19 }];
    const localRotations = [rotation('x', 0), rotation('x', 1.5), rotation('x', -1.5),
        rotation('y', 1.5), rotation('y', -1.5), rotation('z', 90),
        rotation('y', 1.5), rotation('y', 45), rotation('x', -45),
        multiply(rotation('x', 3), rotation('y', -5)),
        multiply(rotation('y', 5), rotation('x', -3))];
    for (const startingPose of startingPoses) {
        const h = createHarness();
        h.gyro.setActive(true);
        await h.clickEnable();
        h.sample(startingPose);
        assert.deepEqual(h.output, { x: 0, y: 0 });
        let previous = absoluteMatrix(startingPose);
        for (const relative of localRotations) {
            const current = multiply(previous, relative);
            const before = h.inputs.length;
            h.sample(matrixSample(current));
            assert.equal(h.inputs.length, before + 1, 'Generated relative pose must be accepted');
            close(h.output, localOutput(relative), `Starting pose ${JSON.stringify(startingPose)}`);
            previous = current;
        }
        h.gyro.destroy();
        clean(h);
    }
});

for (const interval of [16, 33, 100]) {
    for (const [rate, expected] of [[30, 1 / 3], [90, 1]]) {
        test(`${rate} degrees/sec = ${expected} at ${interval}ms`, async () => {
            const h = createHarness();
            h.gyro.setActive(true);
            await h.clickEnable();
            for (const component of ['gamma', 'beta']) {
                for (const direction of [-1, 1]) {
                    h.sample(neutral);
                    h.sample({ ...neutral, [component]: direction * rate * interval / 1000 }, interval);
                    const value = direction * expected;
                    close(h.output, component === 'gamma' ? { x: value, y: 0 } : { x: 0, y: value },
                        `${interval}ms ${component} ${direction * rate} degrees/sec`);
                }
            }
            h.gyro.destroy();
            clean(h);
        });
    }
}

test('16/33/100ms sampling gives the same normalized angular velocity, including 90 degrees/sec = 1', async () => {
    for (const interval of [16, 33, 100]) {
        for (const startingPose of [neutral, { alpha: 137, beta: 90, gamma: 29 }]) {
            for (const axis of ['x', 'y']) {
                const h = createHarness();
                h.gyro.setActive(true);
                await h.clickEnable();
                let current = absoluteMatrix(startingPose);
                h.sample(startingPose);
                for (const rate of [15, 15, -15, 0, 30, -30, 60, 90, -90, 180, -180]) {
                    current = multiply(current, rotation(axis, rate * interval / 1000));
                    h.sample({ ...matrixSample(current), timeStamp: Infinity }, interval);
                    const value = Math.max(-1, Math.min(1, rate / 90));
                    close(h.output, axis === 'y' ? { x: value, y: 0 } : { x: 0, y: value },
                        `${interval}ms ${axis}-axis ${rate} degrees/sec`);
                }
                h.gyro.destroy();
                clean(h);
            }
        }
    }
});

test('identical compound and upright poses produce zero on every screen orientation', async () => {
    for (const angle of [0, 90, 180, 270]) {
        for (const pose of [{ alpha: 75, beta: 23, gamma: -31 }, { alpha: 137, beta: 90, gamma: 29 }]) {
            const h = createHarness({ angle });
            h.gyro.setActive(true);
            await h.clickEnable();
            h.sample(pose);
            for (let count = 0; count < 3; count += 1) {
                const before = h.inputs.length;
                h.sample(pose);
                assert.equal(h.inputs.length, before + 1);
                close(h.output, { x: 0, y: 0 });
            }
            h.gyro.destroy();
            clean(h);
        }
    }
});

test('zero, negative and nonfinite times cannot emit spikes, replace history or renew idle', async () => {
    for (const invalidTime of [100, 99, NaN, Infinity, -Infinity]) {
        const h = createHarness();
        let time = NaN;
        h.window.performance.now = () => time;
        h.gyro.setActive(true);
        await h.clickEnable();
        h.sample(neutral, 0);
        state(h, 'waiting', true, false, true);
        assert.equal(h.inputs.length, 0);
        time = 100;
        h.sample(neutral, 0);
        const inputs = h.inputs.length;
        const timers = [...h.clock.tasks.keys()];
        time = invalidTime;
        h.sample({ ...neutral, gamma: 45, timeStamp: 999999 }, 0);
        assert.equal(h.inputs.length, inputs, 'Bad dt must not emit an input');
        assert.deepEqual([...h.clock.tasks.keys()], timers, 'Bad dt must not renew idle');
        time = 200;
        h.sample({ ...neutral, gamma: 1.5, timeStamp: -999999 }, 0);
        close(h.output, { x: 1 / 6, y: 0 }, 'Bad dt must not replace the previous pose or time');
        h.gyro.destroy();
        clean(h);
    }
});

test('gaps over 250ms replace the entire previous pose with zero; 250ms remains valid', async () => {
    const h = createHarness();
    h.gyro.setActive(true);
    await h.clickEnable();
    h.sample(neutral);
    h.sample({ ...neutral, gamma: 1.5 });
    const resets = h.resets.length;
    const changes = h.changes.length;
    let current = absoluteMatrix({ alpha: 340, beta: 90, gamma: 8 });
    h.sample(matrixSample(current), 251);
    assert.deepEqual(h.output, { x: 0, y: 0 });
    state(h, 'active', true, false, true);
    assert.equal(h.resets.length, resets);
    assert.equal(h.changes.length, changes);
    current = multiply(current, rotation('y', 1.5));
    h.sample(matrixSample(current));
    close(h.output, { x: 1 / 6, y: 0 });
    current = multiply(current, rotation('x', 3));
    h.sample(matrixSample(current), 250);
    close(h.output, { x: 0, y: 2 / 15 });
    h.gyro.destroy();
    clean(h);
});

test('full alpha handles upright wrap in both directions and flat yaw stays neutral', async () => {
    for (const [alpha, nextAlpha, expected] of [[359, 1, 2 / 9], [1, 359, -2 / 9]]) {
        const h = createHarness();
        h.gyro.setActive(true);
        await h.clickEnable();
        h.sample({ alpha, beta: 90, gamma: 0 });
        h.sample({ alpha: nextAlpha, beta: 90, gamma: 0 });
        close(h.output, { x: expected, y: 0 });
        h.gyro.destroy();
    }
    const h = createHarness();
    h.gyro.setActive(true);
    await h.clickEnable();
    h.sample({ ...neutral, alpha: 359 });
    h.sample({ ...neutral, alpha: 1 });
    close(h.output, { x: 0, y: 0 });
    h.gyro.destroy();
});

test('invalid, null and out-of-range components never establish history or update output', async () => {
    const h = createHarness();
    h.gyro.setActive(true);
    await h.clickEnable();
    const invalid = [];
    for (const component of ['alpha', 'beta', 'gamma']) {
        for (const value of [null, undefined, NaN, Infinity, -Infinity, '0', '', false]) {
            invalid.push({ ...neutral, [component]: value });
        }
    }
    invalid.push({ ...neutral, alpha: -1 }, { ...neutral, alpha: 360 },
        { ...neutral, beta: -180.01 }, { ...neutral, beta: 180.01 },
        { ...neutral, gamma: -90.01 }, { ...neutral, gamma: 90.01 });
    for (const sample of invalid) h.sample(sample, 0);
    state(h, 'waiting', true, false, true);
    assert.equal(h.inputs.length, 0);
    assert.equal(h.clock.tasks.size, 1);
    h.sample(neutral);
    state(h, 'active', true, false, true);
    assert.deepEqual(h.output, { x: 0, y: 0 });
    for (const sample of invalid) h.sample(sample, 0);
    assert.equal(h.inputs.length, 1);
    h.gyro.destroy();
    clean(h);
});

test('finite domain endpoints include beta=180 and gamma=+/-90', async () => {
    for (const sample of [{ ...neutral, alpha: 359.999 }, { ...neutral, beta: 180 },
        { ...neutral, beta: -180 }, { ...neutral, gamma: 90 }, { ...neutral, gamma: -90 }]) {
        const h = createHarness();
        h.gyro.setActive(true);
        await h.clickEnable();
        h.sample(sample);
        state(h, 'active', true, false, true);
        assert.deepEqual(h.output, { x: 0, y: 0 });
        h.gyro.destroy();
        clean(h);
    }
});

test('screen 0/90/180/270/-90 mapping, legacy fallback and modern-angle precedence', async () => {
    for (const [options, expected] of [
        [{ angle: 0, legacyAngle: 90 }, { x: 1 / 6, y: 0 }],
        [{ angle: 90, legacyAngle: 180 }, { x: 0, y: -1 / 6 }],
        [{ angle: 180 }, { x: -1 / 6, y: 0 }],
        [{ angle: 270 }, { x: 0, y: 1 / 6 }],
        [{ angle: -90 }, { x: 0, y: 1 / 6 }],
        [{ modern: false, legacyAngle: -90 }, { x: 0, y: 1 / 6 }],
        [{ angle: undefined, legacyAngle: 90 }, { x: 0, y: -1 / 6 }],
        [{ angle: null, legacyAngle: 180 }, { x: -1 / 6, y: 0 }],
        [{ angle: NaN, legacyAngle: 270 }, { x: 0, y: 1 / 6 }],
        [{ modern: false }, { x: 1 / 6, y: 0 }]
    ]) {
        const h = createHarness(options);
        h.gyro.setActive(true);
        await h.clickEnable();
        h.sample(neutral);
        h.sample({ ...neutral, gamma: 1.5 });
        close(h.output, expected);
        h.gyro.resetMotion();
        h.sample(neutral);
        h.sample({ ...neutral, beta: 1.5 });
        close(h.output, { x: -expected.y, y: expected.x });
        h.gyro.destroy();
        clean(h);
    }
});

test('permission is called synchronously in click gesture, with receiver and no arguments', async () => {
    const permission = deferred();
    const h = createHarness({ permission: () => permission.promise });
    h.gyro.setActive(true);
    const enabling = h.clickEnable();
    state(h, 'requesting', true, true, false);
    assert.equal(h.permissionCalls.length, 1);
    assert.equal(h.permissionCalls[0].gesture, true);
    assert.deepEqual(h.permissionCalls[0].args, []);
    assert.equal(h.permissionCalls[0].receiver, h.window.DeviceOrientationEvent);
    assert.equal(h.window.count('deviceorientation'), 0);
    assert.equal(h.clock.tasks.size, 0);
    h.sample(neutral);
    assert.equal(h.inputs.length, 0);
    await h.clickEnable();
    assert.equal(h.permissionCalls.length, 1, 'Repeated clicks must not duplicate the dialog');
    permission.resolve('granted');
    await enabling;
    state(h, 'waiting', true, false, true);
    h.sample(neutral);
    state(h, 'active', true, false, true);
    h.gyro.destroy();
    clean(h);
});

for (const [name, permission, expected] of [
    ['denied', () => Promise.resolve('denied'), 'denied'],
    ['rejected', () => Promise.reject(new Error('Permission rejected')), 'error'],
    ['synchronous throw', () => { throw new Error('Permission threw'); }, 'error']
]) {
    test(`permission ${name} stops safely, logs failure and permits retry`, async () => {
        let attempts = 0;
        const h = createHarness({ permission: () => ++attempts === 1 ? permission() : Promise.resolve('granted') });
        h.gyro.setActive(true);
        await h.clickEnable();
        state(h, expected, false, false, false);
        assert.equal(h.warnings.length, 1);
        clean(h);
        await h.clickEnable();
        assert.equal(h.permissionCalls.length, 2);
        state(h, 'waiting', true, false, true);
        h.gyro.destroy();
        clean(h);
    });
}

test('disable invalidates pending grant; stale result cannot alter a newer retry', async () => {
    const first = deferred();
    const second = deferred();
    let attempts = 0;
    const h = createHarness({ permission: () => ++attempts === 1 ? first.promise : second.promise });
    h.gyro.setActive(true);
    const stale = h.clickEnable();
    h.gyro.disable();
    state(h, 'idle', false, false, false);
    clean(h);
    const retry = h.clickEnable();
    first.resolve('granted');
    await stale;
    state(h, 'requesting', true, true, false);
    assert.equal(h.window.count('deviceorientation'), 0);
    second.resolve('granted');
    await retry;
    state(h, 'waiting', true, false, true);
    assert.equal(h.permissionCalls.length, 2);
    h.gyro.disable();
    await h.clickEnable();
    state(h, 'waiting', true, false, true);
    assert.equal(h.permissionCalls.length, 2, 'A remembered grant must not re-prompt');
    h.gyro.destroy();
    clean(h);
});

test('a late grant after stop cannot revive intent or listeners', async () => {
    const permission = deferred();
    const h = createHarness({ permission: () => permission.promise });
    h.gyro.setActive(true);
    const enabling = h.clickEnable();
    h.gyro.disable();
    const changes = h.changes.length;
    permission.resolve('granted');
    await enabling;
    state(h, 'idle', false, false, false);
    assert.equal(h.changes.length, changes);
    clean(h);
});

test('blur gate during permission preserves intent; resume never requests permission again', async () => {
    const permission = deferred();
    const h = createHarness({ permission: () => permission.promise });
    h.gyro.setActive(true);
    const enabling = h.clickEnable();
    h.document.hidden = true;
    h.window.dispatch('blur');
    h.gyro.setActive(false);
    state(h, 'requesting', true, true, false);
    assert.equal(h.resets.length, 1);
    permission.resolve('granted');
    await enabling;
    state(h, 'suspended', true, false, false);
    clean(h);
    h.clock.advance(10000);
    state(h, 'suspended', true, false, false);
    h.document.hidden = false;
    h.gyro.setActive(true);
    state(h, 'waiting', true, false, true);
    assert.equal(h.permissionCalls.length, 1);
    h.sample(neutral);
    h.sample({ ...neutral, beta: 1.5 });
    close(h.output, { x: 0, y: 1 / 6 });
    h.gyro.setActive(false);
    state(h, 'suspended', true, false, false);
    assert.deepEqual(h.output, { x: 0, y: 0 });
    clean(h);
    h.gyro.setActive(true);
    h.sample({ alpha: 50, beta: 90, gamma: 10 });
    assert.deepEqual(h.output, { x: 0, y: 0 }, 'Resume must start fresh delta history');
    state(h, 'active', true, false, true);
    assert.equal(h.permissionCalls.length, 1);
    h.gyro.destroy();
    clean(h);
});

test('insecure and unsupported checks precede any permission request', async () => {
    for (const [options, expected] of [[{ secure: false, supported: false }, 'insecure'],
        [{ secure: false }, 'insecure'], [{ supported: false }, 'unsupported']]) {
        const h = createHarness({ ...options, permission: () => { throw new Error('Must not request'); } });
        h.gyro.setActive(true);
        await h.clickEnable();
        state(h, expected, false, false, false);
        assert.equal(h.permissionCalls.length, 0);
        assert.equal(h.warnings.length, 0);
        clean(h);
        h.gyro.destroy();
    }
});

test('150ms idle sends only a zero target, renews on valid samples and never marks hardware unavailable', async () => {
    const h = createHarness();
    h.gyro.setActive(true);
    await h.clickEnable();
    h.sample(neutral);
    h.sample({ ...neutral, gamma: 1.5 });
    close(h.output, { x: 1 / 6, y: 0 });
    h.clock.advance(100);
    h.sample({ ...neutral, gamma: 3 }, 0);
    close(h.output, { x: 1 / 6, y: 0 });
    const resets = h.resets.length;
    const changes = h.changes.length;
    const inputs = h.inputs.length;
    h.clock.advance(50);
    assert.equal(h.inputs.length, inputs, 'The replaced idle timer must not fire');
    h.clock.advance(99);
    h.sample({ alpha: null, beta: 0, gamma: 0 }, 0);
    close(h.output, { x: 1 / 6, y: 0 });
    h.clock.advance(1);
    assert.deepEqual(h.output, { x: 0, y: 0 });
    assert.equal(h.inputs.length, inputs + 1);
    assert.equal(h.resets.length, resets, 'Idle must let the host smooth, never snap via onReset');
    assert.equal(h.changes.length, changes);
    state(h, 'active', true, false, true);
    assert.equal(h.clock.tasks.size, 0);
    h.clock.advance(60000);
    assert.equal(h.inputs.length, inputs + 1, 'Idle zero is emitted once, not periodically');
    state(h, 'active', true, false, true);
    h.gyro.destroy();
    clean(h);
});

test('three-second first-valid-sample timeout cleans up; quiet hardware stays active', async () => {
    const h = createHarness();
    h.gyro.setActive(true);
    await h.clickEnable();
    const removedListener = [...h.window.listeners.get('deviceorientation')][0];
    h.clock.advance(2999);
    h.sample({ alpha: null, beta: null, gamma: null }, 0);
    state(h, 'waiting', true, false, true);
    h.clock.advance(1);
    state(h, 'unavailable', false, false, false);
    assert.deepEqual(h.output, { x: 0, y: 0 });
    clean(h);
    removedListener(neutral);
    assert.equal(h.inputs.length, 0, 'A queued event after timeout must be ignored');
    h.gyro.setActive(true);
    state(h, 'unavailable', false, false, false);
    await h.clickEnable();
    h.sample(neutral);
    state(h, 'active', true, false, true);
    assert.equal(h.clock.tasks.size, 1);
    h.clock.advance(60000);
    state(h, 'active', true, false, true);
    h.gyro.destroy();
    clean(h);
});

test('resetMotion only while listening, clears output/history and renews timeout', async () => {
    const h = createHarness();
    h.gyro.resetMotion();
    assert.equal(h.resets.length, 0);
    state(h, 'idle', false, false, false);
    h.gyro.setActive(true);
    await h.clickEnable();
    h.sample(neutral);
    h.sample({ ...neutral, gamma: 1.5 });
    close(h.output, { x: 1 / 6, y: 0 });
    h.gyro.resetMotion();
    state(h, 'waiting', true, false, true);
    assert.deepEqual(h.output, { x: 0, y: 0 });
    h.clock.advance(2999);
    h.gyro.resetMotion();
    h.clock.advance(1);
    state(h, 'waiting', true, false, true);
    h.sample({ alpha: 89, beta: 90, gamma: 0 });
    assert.deepEqual(h.output, { x: 0, y: 0 });
    state(h, 'active', true, false, true);
    h.gyro.resetMotion();
    h.clock.advance(3000);
    state(h, 'unavailable', false, false, false);
    clean(h);
    const changes = h.changes.length;
    h.gyro.resetMotion();
    assert.equal(h.changes.length, changes);
    h.gyro.destroy();
});

test('modern and legacy screen changes reset history and remove the matching listener', async () => {
    for (const options of [{}, { modern: false, legacyAngle: 0 }, { modernEvents: false }]) {
        const h = createHarness(options);
        h.gyro.setActive(true);
        await h.clickEnable();
        h.sample(neutral);
        h.sample({ ...neutral, gamma: 4 });
        const modern = h.orientation && options.modernEvents !== false;
        if (modern) {
            assert.equal(h.orientation.count('change'), 1);
            assert.equal(h.window.count('orientationchange'), 0);
            h.orientation.angle = 90;
            h.orientation.dispatch('change');
        } else {
            assert.equal(h.window.count('orientationchange'), 1);
            h.window.orientation = -90;
            h.window.dispatch('orientationchange');
        }
        state(h, 'waiting', true, false, true);
        assert.deepEqual(h.output, { x: 0, y: 0 });
        assert.equal(h.clock.tasks.size, 1);
        h.sample({ alpha: 40, beta: 67, gamma: -13 });
        assert.deepEqual(h.output, { x: 0, y: 0 });
        h.gyro.disable();
        state(h, 'idle', false, false, false);
        clean(h);
        const changes = h.changes.length;
        h.orientation?.dispatch('change');
        h.window.dispatch('orientationchange');
        assert.equal(h.changes.length, changes);
        h.gyro.destroy();
    }
});

test('suspend/stop detach waiting timers; disabled or queued samples are ignored', async () => {
    const h = createHarness();
    h.gyro.setActive(true);
    await h.clickEnable();
    const queuedSample = [...h.window.listeners.get('deviceorientation')][0];
    h.gyro.setActive(false);
    state(h, 'suspended', true, false, false);
    clean(h);
    h.clock.advance(3000);
    queuedSample(neutral);
    assert.equal(h.inputs.length, 0);
    h.gyro.setActive(true);
    const resets = h.resets.length;
    h.gyro.setActive(true);
    assert.equal(h.window.count('deviceorientation'), 1);
    assert.equal(h.resets.length, resets, 'An unchanged active gate must not reset history');
    h.gyro.disable();
    state(h, 'idle', false, false, false);
    clean(h);
    queuedSample(neutral);
    h.sample(neutral);
    assert.equal(h.inputs.length, 0);
    h.gyro.setActive(true);
    clean(h);
    h.gyro.destroy();
});

test('destroy removes listening resources and permanently invalidates pending permission', async () => {
    for (const pending of [false, true]) {
        const permission = deferred();
        const h = createHarness(pending ? { permission: () => permission.promise } : {});
        h.gyro.setActive(true);
        const enabling = h.clickEnable();
        if (!pending) h.sample(neutral);
        const inputs = h.inputs.length;
        h.gyro.destroy();
        state(h, 'idle', false, false, false);
        clean(h);
        const changes = h.changes.length;
        const resets = h.resets.length;
        permission.resolve('granted');
        await enabling;
        await h.clickEnable();
        h.gyro.setActive(true);
        h.gyro.resetMotion();
        h.gyro.disable();
        h.gyro.destroy();
        h.sample(neutral);
        h.clock.advance(3000);
        assert.equal(h.inputs.length, inputs);
        assert.equal(h.changes.length, changes);
        assert.equal(h.resets.length, resets);
        clean(h);
    }
});

test('all and only the contract statuses are exercised', () => {
    assert.deepEqual([...observedStatuses].sort(), [...statuses].sort());
});
