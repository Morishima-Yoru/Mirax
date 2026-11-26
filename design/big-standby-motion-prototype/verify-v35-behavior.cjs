'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const streamPath = path.join(__dirname, '..', 'big-standby-e-scene.js');
const uiPath = path.join(__dirname, 'verify-v35-ui.js');
const streamSource = fs.readFileSync(streamPath, 'utf8');
const uiSource = fs.readFileSync(uiPath, 'utf8');
const hostSource = fs.readFileSync(path.join(__dirname, '..', 'big-standby-prototype.html'), 'utf8');
const uiFactory = new vm.Script('(' + uiSource + ')', { filename: uiPath });
const names = ['lower-bottom', 'lower-top', 'upper-bottom', 'upper-top'];
const clone = value => JSON.parse(JSON.stringify(value));
const near = (actual, expected, tolerance = 1e-9) => assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} != ${expected}`);

function createStreams(initialSeed) {
    let seed = initialSeed;
    let draws = 0;
    const math = Object.create(Math);
    math.random = () => {
        draws++;
        seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
        return seed / 2 ** 32;
    };
    const channels = [0, 1].map(() => ({ style: {
        values: new Map(), setProperty(name, value) { this.values.set(name, value); }
    } }));
    const attributes = new Map();
    const root = {
        setAttribute(name, value) { attributes.set(name, value); },
        querySelectorAll(selector) {
            assert.equal(selector, '.e-channel');
            return channels;
        }
    };
    const sandbox = { window: {}, Math: math, document: { getElementById: id => id === 'e-scene' ? root : null } };
    vm.runInNewContext(streamSource, sandbox, { filename: streamPath, timeout: 1000 });
    assert.ok(sandbox.window.MiraxDStreams, 'D controller must be exported');
    return { controller: sandbox.window.MiraxDStreams, channels, root, attributes, draws: () => draws };
}

function verifyStreams() {
    let observedShots = 0;
    let easeSamples = 0;
    for (const seed of [0xD035, 1, 0xFFFFFFFF, 0xA55A]) {
        const { controller, channels, draws } = createStreams(seed);
        const snapshot = () => clone(controller.snapshot());
        assert.deepEqual(snapshot().streams.map(stream => stream.name).sort(), names);
        assert.equal(snapshot().active, false);
        assert.equal(snapshot().elapsedSeconds, 0);
        assert.equal(draws(), 8, 'Schedule gap/duration independently for all four edges');
        const before = snapshot();
        for (const delta of [0, -1, Number.NaN, Number.POSITIVE_INFINITY, 3600]) controller.update(delta);
        assert.deepEqual(snapshot(), before, 'Inactive updates cannot consume time, entropy, or shots');
        assert.equal(draws(), 8);
        controller.setActive(true);
        controller.update(3600);
        assert.equal(snapshot().elapsedSeconds, 0, 'Discard the first resumed wall-clock delta');
        const emissions = new Map(names.map(name => [name, new Map()]));
        const gaps = new Map(names.map(name => [name, new Set()]));
        const velocities = new Map();
        for (let frame = 0; frame < 8000; frame++) {
            controller.update(.01);
            const value = snapshot();
            assert.ok(value.history.length <= 32, 'Bound the public history at 32 actual emissions');
            for (const stream of value.streams) {
                assert.ok(stream.gapSeconds >= .12 && stream.gapSeconds <= .9, 'Gap is 0.12-0.90 seconds');
                assert.ok(stream.durationSeconds >= .95 && stream.durationSeconds <= 1.60, 'Shot is 0.95-1.60 seconds, not the old 4.8 seconds');
                assert.ok(['waiting', 'shot'].includes(stream.phase));
                const channel = channels[stream.name.startsWith('upper') ? 0 : 1];
                const edge = stream.name.endsWith('top') ? 'top' : 'bottom';
                near(Number(channel.style.values.get(`--e-${edge}-position`)), stream.position);
                near(Number(channel.style.values.get(`--e-${edge}-alpha`)), stream.alpha);
                if (stream.phase !== 'shot') continue;
                assert.ok(stream.progress >= 0 && stream.progress <= 1);
                near(stream.position, stream.progress ** 2);
                near(stream.progress, stream.elapsedSeconds / stream.durationSeconds);
                assert.ok(stream.alpha >= 0 && stream.alpha <= 1);
                emissions.get(stream.name).set(stream.shotIndex, stream);
                gaps.get(stream.name).add(stream.gapSeconds);
                const key = `${stream.name}/${stream.shotIndex}`;
                const previous = velocities.get(key);
                if (previous && stream.progress > previous.progress) {
                    const slope = (stream.position - previous.position) / (stream.progress - previous.progress);
                    near(slope, stream.progress + previous.progress, 1e-8);
                    assert.ok(slope >= previous.slope - 1e-8, 'Cathode accelerates throughout the shot, never ease-out');
                    velocities.set(key, { progress: stream.progress, position: stream.position, slope });
                    easeSamples++;
                } else velocities.set(key, { progress: stream.progress, position: stream.position, slope: 0 });
            }
        }
        for (const name of names) {
            assert.ok(emissions.get(name).size >= 30, name + ': exercise multiple real controller emissions');
            assert.ok(gaps.get(name).size >= 30, name + ': randomize on every emission, not once per edge');
            const ordered = [...emissions.get(name).values()].sort((a, b) => a.shotIndex - b.shotIndex);
            for (let index = 1; index < ordered.length; index++) {
                const current = ordered[index];
                const previous = ordered[index - 1];
                const effectiveGap = current.startedAtSeconds - previous.startedAtSeconds - previous.durationSeconds;
                assert.ok(effectiveGap >= current.gapSeconds - .011 && effectiveGap <= current.gapSeconds + .011,
                    name + ': reported random gap must be the actual interval between emitted shots');
            }
            observedShots += ordered.length;
        }
        assert.equal(snapshot().history.length, 32);
        assert.ok(draws() > 200, 'Random sampling continues after startup');
        controller.setActive(false);
        const paused = snapshot();
        const entropy = draws();
        for (const delta of [.01, 1, 60, 86400]) controller.update(delta);
        controller.setActive(false);
        assert.deepEqual(snapshot(), paused, 'Pause freezes positions, pending gaps, elapsed time and history');
        assert.equal(draws(), entropy, 'Paused wall time cannot consume random gaps');
        controller.setActive(true);
        const resumed = snapshot();
        controller.update(86400);
        assert.deepEqual(snapshot(), resumed, 'Resume cannot replay a day of hidden wall time or burst shots');
        controller.setActive(true);
        controller.update(.016);
        near(snapshot().elapsedSeconds - resumed.elapsedSeconds, .016, 1e-8);
        const beforeLargeDelta = snapshot();
        controller.update(1000);
        near(snapshot().elapsedSeconds - beforeLargeDelta.elapsedSeconds, .1, 1e-8);
        for (const stream of snapshot().streams) {
            const previous = beforeLargeDelta.streams.find(item => item.name === stream.name);
            assert.ok(stream.shotIndex - previous.shotIndex <= 1, 'Large frames cannot burst multiple emissions');
        }
        const untouched = snapshot();
        for (const delta of [0, -1, Number.NaN, Number.POSITIVE_INFINITY]) controller.update(delta);
        assert.deepEqual(snapshot(), untouched, 'Invalid delta values cannot corrupt the active controller');
    }
    return { seeds: 4, observedShots, quadraticPaintSamples: easeSamples };
}

function productionFunctions(names) {
    return names.map(name => {
        const match = hostSource.match(new RegExp('    function ' + name + '\\([^]*?\\n    }'));
        assert.ok(match, 'Missing verified production function: ' + name);
        return match[0];
    }).join('\n');
}

function verifyProductionSharedClock() {
    const functions = productionFunctions(['oledShiftActive', 'animateStandby', 'startCFailure']);
    let frameId = 0;
    let streamSeconds = 0;
    const body = { animate(frames, timing) {
        assert.equal(timing.duration, 250);
        assert.ok(frames.every(frame => !frame.clipPath), 'C glitch remains composited, not cropped bands');
        return { playState: 'running', onfinish: null,
            cancel() { this.playState = 'idle'; }, pause() { this.playState = 'paused'; } };
    } };
    const sandbox = {
        state: { variant: 'A', home: 'big', phase: 'failed', broadcast: true, motion: { paused: false, hidden: false } },
        particleTime: 0, cFailureStartedAt: null, previousFrameTime: null, animationFrame: null, cGlitch: null,
        window: { innerWidth: 390, MiraxDStreams: { update(delta) { streamSeconds += delta; } } },
        document: { body }, updateCHudOrbit() {}, cParallaxActive: () => false,
        cParallax: { reset() {} }, applyAmbientTransforms() {}, shouldAnimateParticles: () => false, drawParticles() {},
        requestAnimationFrame: () => ++frameId,
        setPhase(phase) { sandbox.state.phase = phase; if (phase !== 'failed') sandbox.cFailureStartedAt = null; }
    };
    vm.runInNewContext(functions, sandbox, { filename: 'v35-live-production-clock', timeout: 1000 });
    for (const variant of ['A', 'C', 'D']) {
        sandbox.state.variant = variant; sandbox.state.phase = 'failed';
        sandbox.particleTime = 0; sandbox.cFailureStartedAt = 0; sandbox.previousFrameTime = 0;
        let time = 0;
        let remaining = 7499;
        while (remaining > 0) {
            const step = Math.min(50, remaining); time += step; remaining -= step;
            sandbox.animateStandby(time);
        }
        assert.equal(sandbox.particleTime, 7499);
        assert.equal(sandbox.cFailureStartedAt, 0);
        assert.equal(sandbox.state.phase, 'failed');
        sandbox.animateStandby(time + 1);
        assert.equal(sandbox.state.phase, 'waiting', variant + ': actual production RAF recovers at 7500 shared active ms');
        assert.equal(sandbox.cFailureStartedAt, null);
    }
    assert.ok(streamSeconds > 22.49 && streamSeconds < 22.51, 'The same actual RAF delta also feeds DStreams in seconds');
    for (const naturalOffset of [16.7, 80]) {
        sandbox.state.variant = 'C'; sandbox.state.phase = 'failed';
        sandbox.cFailureStartedAt = 0; sandbox.particleTime = naturalOffset; sandbox.previousFrameTime = 0;
        let time = 0; let remaining = 7499 - naturalOffset;
        while (remaining > 0) {
            const step = Math.min(50, remaining); time += step; remaining -= step;
            sandbox.animateStandby(time);
        }
        near(sandbox.particleTime, 7499, 1e-8);
        assert.equal(sandbox.state.phase, 'failed', 'An observed C offset must not weaken the 7499ms pre-boundary guard');
        sandbox.animateStandby(time + 1);
        assert.equal(sandbox.state.phase, 'waiting', 'Use only the remaining anchored duration, not a fresh 7500ms after observing C');
    }
    sandbox.state.phase = 'failed'; sandbox.cFailureStartedAt = 0; sandbox.particleTime = 0; sandbox.previousFrameTime = 0;
    sandbox.animateStandby(5000);
    assert.equal(sandbox.particleTime, 80, 'Actual production frame cap is 80ms');
    for (const mode of ['paused', 'hidden', 'simple', 'projected']) {
        sandbox.state.home = 'big'; sandbox.state.phase = 'failed';
        sandbox.state.motion.paused = mode === 'paused'; sandbox.state.motion.hidden = mode === 'hidden';
        if (mode === 'simple') sandbox.state.home = 'simple';
        if (mode === 'projected') sandbox.state.phase = 'projecting';
        const before = sandbox.particleTime;
        sandbox.animateStandby(50000);
        assert.equal(sandbox.particleTime, before, mode + ': actual production active clock freezes');
        assert.equal(sandbox.previousFrameTime, null);
    }
    sandbox.state.home = 'big'; sandbox.state.phase = 'failed';
    sandbox.state.motion.paused = false; sandbox.state.motion.hidden = false;
    sandbox.startCFailure(); const previous = sandbox.cGlitch;
    assert.equal(sandbox.cFailureStartedAt, null, 'C recovery has no anchor before its real glitch completion');
    sandbox.startCFailure(); const current = sandbox.cGlitch;
    previous.onfinish({ currentTarget: previous });
    assert.equal(sandbox.cFailureStartedAt, null, 'Old glitch identity cannot anchor a new episode');
    sandbox.particleTime = 1000; current.onfinish({ currentTarget: current });
    assert.equal(sandbox.cFailureStartedAt, 1000);
    sandbox.particleTime = 2000; current.onfinish({ currentTarget: current });
    assert.equal(sandbox.cFailureStartedAt, 1000, 'Repeated finish cannot retimestamp an existing anchor');
    sandbox.cGlitch = null; sandbox.cFailureStartedAt = null;
    current.onfinish({ currentTarget: current });
    assert.equal(sandbox.cFailureStartedAt, null, 'Cancelled glitch completion cannot revive a cleared episode');
    return { source: 'live production functions', bindings: ['particleTime', 'cFailureStartedAt', 'animateStandby'],
        variants: 3, observedCOffsetsMs: [16.7, 80], frozenModes: 4, frameCapMs: 80, guardedFinishCallbacks: 3 };
}

function verifyProductionCopyFader() {
    const functions = productionFunctions(['canAnimateMainCopy', 'fadeMainCopyIn', 'replaceMainCopy', 'finishHiddenMainCopyChanges']);
    const animations = [];
    const sandbox = { state: { variant: 'A', home: 'big', phase: 'off', motion: { hidden: false } },
        mainCopyChanges: new Map(), uiMotionAllowed: () => !sandbox.state.motion.hidden,
        getComputedStyle: () => ({ opacity: '1' }) };
    vm.runInNewContext(functions, sandbox, { filename: 'v35-live-production-copy-fader', timeout: 1000 });
    const classes = new Set();
    const node = { textContent: 'off', innerHTML: 'off', hidden: false,
        classList: {
            add(...names) { for (const name of names) classes.add(name); },
            remove(...names) { for (const name of names) classes.delete(name); },
            contains: name => classes.has(name),
            toggle(name, force = !classes.has(name)) { if (force) classes.add(name); else classes.delete(name); return force; }
        }, getClientRects: () => [{}],
        animate(frames, timing) {
            const animation = { frames: clone(frames), timing: clone(timing), onfinish: null, canceled: false,
                cancel() { this.canceled = true; } };
            animations.push(animation);
            return animation;
        } };
    sandbox.replaceMainCopy(node, 'waiting');
    assert.equal(node.textContent, 'off', 'Actual fader keeps old copy during fade-out');
    assert.equal(animations[0].timing.duration, 150);
    assert.deepEqual(animations[0].frames.map(frame => Number(frame.opacity)), [1, 0]);
    for (let index = 0; index < 12; index++) sandbox.replaceMainCopy(node, 'waiting');
    assert.equal(animations.length, 1, 'Unchanged pending strings cannot restart the fader');
    animations[0].onfinish();
    assert.equal(node.textContent, 'waiting');
    assert.equal(animations[1].timing.duration, 180);
    assert.deepEqual(animations[1].frames.map(frame => Number(frame.opacity)), [0, 1]);
    animations[1].onfinish();
    assert.equal(sandbox.mainCopyChanges.size, 0);
    sandbox.replaceMainCopy(node, ''); const exit = animations.at(-1);
    assert.equal(node.textContent, 'waiting', 'Blank/OFF target must retain existing text until fade-out finishes');
    assert.equal(exit.timing.duration, 150);
    const exitCount = animations.length;
    for (let index = 0; index < 12; index++) sandbox.replaceMainCopy(node, '');
    assert.equal(animations.length, exitCount, 'Repeated pending OFF render cannot hide/clear/restart its exit');
    exit.onfinish(); assert.equal(node.textContent, '');
    sandbox.mainCopyChanges.get(node)?.animation.onfinish();
    node.textContent = 'waiting'; sandbox.replaceMainCopy(node, ''); const off = animations.at(-1);
    sandbox.replaceMainCopy(node, 'waiting'); off.onfinish();
    assert.equal(node.textContent, 'waiting', 'Quick OFF/ON must ignore the old empty-string completion');
    sandbox.mainCopyChanges.get(node)?.animation.onfinish();
    sandbox.mainCopyChanges.get(node)?.animation.onfinish();
    sandbox.replaceMainCopy(node, 'connecting'); const obsolete = animations.at(-1);
    sandbox.replaceMainCopy(node, 'failed'); const newest = animations.at(-1);
    assert.ok(obsolete.canceled);
    obsolete.onfinish(); assert.equal(node.textContent, 'waiting', 'Old finish must not commit stale text');
    newest.onfinish(); assert.equal(node.textContent, 'failed');
    animations.at(-1).onfinish();
    for (const mode of ['theme', 'hidden', 'simple', 'projected']) {
        sandbox.state.variant = 'A'; sandbox.state.home = 'big'; sandbox.state.phase = 'waiting'; sandbox.state.motion.hidden = false;
        sandbox.replaceMainCopy(node, 'latest-' + mode); const pending = animations.at(-1);
        if (mode === 'theme') sandbox.state.variant = 'D';
        if (mode === 'hidden') sandbox.state.motion.hidden = true;
        if (mode === 'simple') sandbox.state.home = 'simple';
        if (mode === 'projected') sandbox.state.phase = 'projecting';
        sandbox.finishHiddenMainCopyChanges();
        assert.equal(node.textContent, 'latest-' + mode);
        assert.ok(pending.canceled);
        assert.equal(sandbox.mainCopyChanges.size, 0);
        pending.onfinish();
        assert.equal(node.textContent, 'latest-' + mode, mode + ': canceled callback cannot overwrite committed latest copy');
    }
    return { source: 'live production functions; deterministic animation stub', fadeOutMs: 150, fadeInMs: 180,
        repeatedRenderNoReplay: true, blankTargetExitDeferred: true, quickOffOnLatest: true, newestWins: true, canceledModes: 4 };
}

function verifyFooterInkFixture() {
    const match = uiSource.match(/function footerInkBounds\(bounds, metrics\) \{[\s\S]*?\n                        }/);
    assert.ok(match, 'The actual UI footer ink helper must remain unit-testable');
    const boundsFromInk = vm.runInNewContext('(' + match[0] + ')');
    const fontBox = { left: 192.359, right: 280, top: 402.8125, bottom: 426.8125, height: 24 };
    const metrics = { fontBoundingBoxAscent: 20, fontBoundingBoxDescent: 4, actualBoundingBoxAscent: 15, actualBoundingBoxDescent: 0 };
    const plate = { left: 168, right: 296, top: 404.8125, bottom: 444 };
    const ink = clone(boundsFromInk(fontBox, metrics));
    assert.ok(fontBox.top < plate.top, 'The parent-observed font leading exceeds its line box');
    assert.deepEqual(ink, { left: 192.359, right: 280, top: 407.8125, bottom: 422.8125 });
    assert.ok(ink.top >= plate.top && ink.bottom <= plate.bottom, 'Real capital-letter ink fits despite the unpainted Range font leading');
    const clipped = boundsFromInk(fontBox, { ...metrics, actualBoundingBoxAscent: 24 });
    assert.ok(clipped.top < plate.top, 'Actual ascender clipping must still fail the vertical ink guard');
    const horizontal = boundsFromInk({ ...fontBox, left: 160 }, metrics);
    assert.ok(horizontal.left < plate.left, 'The actual ink helper must preserve horizontal overflow evidence');
    return { rangeLeadingNotInk: true, actualVerticalClippingDetected: true, horizontalOverflowPreserved: true };
}

async function verifyUiLifecycle() {
    let callerClosed = false;
    let ownedClosed = 0;
    const callerContext = { close() { callerClosed = true; } };
    const contexts = new Set([callerContext]);
    const browser = {
        contexts: () => [...contexts],
        async newContext() {
            const context = {
                setDefaultTimeout() {}, setDefaultNavigationTimeout() {}, async route() {},
                async newPage() {
                    return { on() {}, async goto() { throw Error('injected navigation failure'); } };
                },
                async close() { ownedClosed++; contexts.delete(context); }
            };
            contexts.add(context);
            return context;
        }
    };
    const verifyUi = uiFactory.runInNewContext({ Date, Set, Map, URL, Error, console });
    await assert.rejects(() => verifyUi({ context: () => ({ browser: () => browser }) }), error => {
        assert.match(error.message, /injected navigation failure/);
        assert.match(error.message, /"ownedContexts": 1/);
        assert.match(error.message, /"closedContexts": 1/);
        return true;
    });
    assert.equal(ownedClosed, 1, 'Actual UI verification code must close its context even when initial navigation throws');
    assert.equal(callerClosed, false, 'Actual UI verification code must not close caller contexts');
    assert.deepEqual([...contexts], [callerContext]);
    await assert.rejects(() => verifyUi({ context: () => ({ browser: () => null }) }), /no shared-page fallback/);
    return { injectedFailureCleanup: true, callerPreserved: true, disconnectedBrowserRejected: true };
}

(async () => {
    const streams = verifyStreams();
    const productionClock = verifyProductionSharedClock();
    const productionFader = verifyProductionCopyFader();
    const footerFixture = verifyFooterInkFixture();
    const lifecycle = await verifyUiLifecycle();
    console.log(JSON.stringify({ syntax: 'pass', streams, productionClock, productionFader, footerFixture, lifecycle }, null, 2));
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
