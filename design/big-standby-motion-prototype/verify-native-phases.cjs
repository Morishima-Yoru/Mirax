'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const hostPath = path.join(__dirname, '..', 'big-standby-prototype.html');
const host = fs.readFileSync(hostPath, 'utf8');

// 1. Static Contract & Mapping checks
assert.ok(host.includes('const nativePhases ='), 'nativePhases mapping must exist');
const mappingMatch = host.match(/const nativePhases = Object\.freeze\((\{[\s\S]*?\})\);/);
assert.ok(mappingMatch, 'nativePhases definition must be extractable');
const nativePhases = vm.runInNewContext('(' + mappingMatch[1] + ')');

const expectedMappings = {
    FROZEN: 'frozen',
    READY: 'off',
    ARMING: 'arming',
    ADVERTISING: 'waiting',
    CONNECTING: 'connecting',
    CONNECTED: 'projecting',
    WIFI_PAUSED: 'wifi-paused'
};
assert.deepEqual({ ...nativePhases }, expectedMappings, 'Exact 7 native ScreenPhase mappings required');

// 2. VM Execution of Phase Helper & URL Parser Functions
const extractFn = name => {
    const match = host.match(new RegExp(String.raw`    function ${name}\b[\s\S]*?\r?\n    \}`));
    assert.ok(match, `Function ${name} must exist in host`);
    return match[0];
};

const sandbox = {
    location: { href: 'http://127.0.0.1:8765/big-standby-prototype.html' },
    URL,
    nativePhases,
    Object
};
vm.createContext(sandbox);
vm.runInContext(extractFn('readPhase'), sandbox);
vm.runInContext(extractFn('nativePhaseFor'), sandbox);

// Whitelisted native queries & lower-case aliases
assert.equal(sandbox.readPhase('ARMING'), 'arming');
assert.equal(sandbox.readPhase('arming'), 'arming');
assert.equal(sandbox.readPhase('WIFI_PAUSED'), 'wifi-paused');
assert.equal(sandbox.readPhase('wifi-paused'), 'wifi-paused');
assert.equal(sandbox.readPhase('FROZEN'), 'frozen');
assert.equal(sandbox.readPhase('frozen'), 'frozen');
assert.equal(sandbox.readPhase('READY'), 'off');
assert.equal(sandbox.readPhase('off'), 'off');
assert.equal(sandbox.readPhase('ADVERTISING'), 'waiting');
assert.equal(sandbox.readPhase('CONNECTING'), 'connecting');
assert.equal(sandbox.readPhase('CONNECTED'), 'projecting');

// Unknown inputs safely fall back to 'off'
assert.equal(sandbox.readPhase('unknown_value'), 'off');
assert.equal(sandbox.readPhase('<script>alert(1)</script>'), 'off');
assert.equal(sandbox.readPhase(''), 'off');
assert.equal(sandbox.readPhase(null), 'off');

// Reverse lookup
for (const [nativeEnum, previewPhase] of Object.entries(expectedMappings)) {
    assert.equal(sandbox.nativePhaseFor(previewPhase), nativeEnum);
}
assert.equal(sandbox.nativePhaseFor('failed'), null, 'failed is a legacy episode, not a native ScreenPhase');

// 3. Phase Definitions & Content Safety
const phasesMatch = host.match(/const phases = (\{[\s\S]*?\r?\n    \});/);
assert.ok(phasesMatch, 'phases dictionary must exist');
const phases = vm.runInNewContext('(' + phasesMatch[1] + ')');

// Check all 8 phases (7 native previews + 1 legacy failed)
const allPreviewPhases = ['off', 'arming', 'waiting', 'connecting', 'projecting', 'wifi-paused', 'frozen', 'failed'];
for (const p of allPreviewPhases) {
    assert.ok(phases[p], `Phase config missing for ${p}`);
    assert.ok(phases[p].title, `Phase title missing for ${p}`);
    assert.ok(phases[p].eyebrow, `Phase eyebrow missing for ${p}`);
    assert.ok(phases[p].cStatus, `C status abbreviation missing for ${p}`);
}

// Connection invitation safety: only waiting and failed can invite connection
for (const p of allPreviewPhases) {
    if (['waiting', 'failed'].includes(p)) {
        assert.equal(phases[p].invitesConnection, true, `${p} must invite connection`);
    } else {
        assert.ok(!phases[p].invitesConnection, `${p} must NOT invite connection`);
    }
}

// Specific C status codes
assert.equal(phases.off.cStatus, 'DISCONN');
assert.equal(phases.arming.cStatus, 'ARMING');
assert.equal(phases.arming.title, '啟動中');
assert.equal(phases.arming.eyebrow, 'STARTING');
assert.ok(phases.arming.title.length <= 4, 'Shortened arming title length constraint');
assert.ok(!phases.arming.hint.includes('Win + K'), 'ARMING must not invite Win + K');
assert.equal(phases.arming.detail, '接收啟動中');
assert.equal(phases.arming.hint, 'Starting · Not ready');
assert.equal(phases.connecting.title, '正在連接');
assert.equal(phases.connecting.detail, '模擬發送端已選取此裝置。');
assert.equal(phases.connecting.hint, 'Connecting · Please wait');
assert.equal(phases.waiting.cStatus, 'STANDBY');
assert.equal(phases['wifi-paused'].cStatus, 'WAIT_WIFI');
assert.equal(phases.frozen.cStatus, 'UNAVAILABLE');
assert.equal(phases['wifi-paused'].title, 'Wi-Fi 已關閉');
assert.equal(phases['wifi-paused'].detail, 'Wi-Fi 已關閉 · 請開啟 Wi-Fi 以廣播');
assert.equal(phases['wifi-paused'].hint, 'Wi-Fi is off');
assert.equal(phases.frozen.title, '接收不可用');
assert.equal(phases.frozen.detail, '接收不可用');
assert.equal(phases.frozen.hint, 'Receiver unavailable');
assert.ok(!phases.frozen.hint.includes('Check settings'));
assert.ok(!host.includes('Broadcast intent retained'));
assert.ok(host.includes('id="receiver-details"'));
assert.ok(host.includes('.v-C[data-phase="frozen"] .c-status-light { background: #ff5959; }'));
assert.ok(host.includes('event.miraxReceiverDetails = true'));
assert.ok(host.includes("addEventListener('click', onBroadcastControlClick)"));

assert.equal(phases.failed.cStatus, 'ERROR');

// 4. Behavioral & State Transition Logic
const simEnv = {
    state: {
        broadcast: false,
        phase: 'off',
        home: 'big',
        variant: 'C',
        settingsOpen: false,
        media: { status: 'none' },
        motion: { paused: false }
    },
    phases,
    nativePhases,
    nativePhaseFor: sandbox.nativePhaseFor,
    lastAnnounce: null,
    announce(msg) { this.lastAnnounce = msg; },
    act(action, fn, msg) { fn(); if (msg) simEnv.announce(msg); },
    cFailureStartedAt: null,
    cGlitch: null,
    particleTime: 1000
};
vm.createContext(simEnv);
vm.runInContext(extractFn('toggleBroadcast'), simEnv);
vm.runInContext(extractFn('confirmServiceReady'), simEnv);
vm.runInContext(extractFn('previewNativePhase'), simEnv);
vm.runInContext(extractFn('setPhase'), simEnv);

// Test ON -> ARMING -> ack -> WAITING flow
assert.equal(simEnv.state.phase, 'off');
assert.equal(simEnv.state.broadcast, false);

simEnv.toggleBroadcast();
assert.equal(simEnv.state.broadcast, true, 'Broadcasting must be on');
assert.equal(simEnv.state.phase, 'arming', 'ON enters ARMING first, never waiting directly');

// Cannot jump to connecting, projecting, or failed while arming
simEnv.setPhase('connecting');
assert.equal(simEnv.state.phase, 'arming', 'setPhase(connecting) must be blocked during ARMING');
simEnv.setPhase('projecting');
assert.equal(simEnv.state.phase, 'arming', 'setPhase(projecting) must be blocked during ARMING');
simEnv.setPhase('failed');
assert.equal(simEnv.state.phase, 'arming', 'setPhase(failed) must be blocked during ARMING');

simEnv.confirmServiceReady();
assert.equal(simEnv.state.phase, 'waiting', 'Service ready confirmation transitions to waiting');

// Turning broadcast OFF returns to 'off'
simEnv.toggleBroadcast();
assert.equal(simEnv.state.broadcast, false);
assert.equal(simEnv.state.phase, 'off');

// Test WIFI_PAUSED behavior & intent preservation
simEnv.previewNativePhase('WIFI_PAUSED');
assert.equal(simEnv.state.phase, 'wifi-paused');
assert.equal(simEnv.state.broadcast, true, 'WIFI_PAUSED retains broadcast intent');
simEnv.setPhase('connecting');
assert.equal(simEnv.state.phase, 'wifi-paused', 'setPhase(connecting) must be blocked during WIFI_PAUSED');

simEnv.toggleBroadcast();
assert.equal(simEnv.state.broadcast, false, 'User can stop broadcast from WIFI_PAUSED');
assert.equal(simEnv.state.phase, 'off');

// Test FROZEN behavior & intent preservation
// 1. Cold/READY -> FROZEN: broadcast intent stays false
simEnv.state.phase = 'off';
simEnv.state.broadcast = false;
simEnv.previewNativePhase('FROZEN');
assert.equal(simEnv.state.phase, 'frozen');
assert.equal(simEnv.state.broadcast, false, 'READY -> FROZEN retains broadcast intent false');

// Toggling broadcast while FROZEN is blocked
simEnv.toggleBroadcast();
assert.equal(simEnv.state.phase, 'frozen', 'Broadcast toggle cannot proceed while FROZEN');
assert.equal(simEnv.state.broadcast, false);
simEnv.setPhase('connecting');
assert.equal(simEnv.state.phase, 'frozen', 'setPhase(connecting) must be blocked during FROZEN');

// 2. Active broadcast -> FROZEN: broadcast intent stays true
simEnv.previewNativePhase('ADVERTISING');
assert.equal(simEnv.state.broadcast, true);
simEnv.previewNativePhase('FROZEN');
assert.equal(simEnv.state.phase, 'frozen');
assert.equal(simEnv.state.broadcast, true, 'ADVERTISING -> FROZEN preserves broadcast intent true');

// 3. WIFI_PAUSED -> FROZEN: broadcast intent stays true
simEnv.previewNativePhase('WIFI_PAUSED');
assert.equal(simEnv.state.broadcast, true);
simEnv.previewNativePhase('FROZEN');
assert.equal(simEnv.state.phase, 'frozen');
assert.equal(simEnv.state.broadcast, true, 'WIFI_PAUSED -> FROZEN preserves broadcast intent true');

// Stale 7.5s failure episode clearing
simEnv.cFailureStartedAt = 500;
simEnv.state.phase = 'failed';
simEnv.state.broadcast = true;

// Previewing new state clears failure episode
simEnv.previewNativePhase('ARMING');
assert.equal(simEnv.state.phase, 'arming');
// In animateStandby:
const canTriggerOldRecovery = simEnv.state.phase === 'failed' && simEnv.state.broadcast && simEnv.cFailureStartedAt !== null;
assert.equal(canTriggerOldRecovery, false, 'New native phases cannot be reset by stale 7.5s failure episodes');

simEnv.openReceiverDetails = (trigger) => { simEnv.detailsTrigger = trigger; };
vm.runInContext(extractFn('onBroadcastControlClick'), simEnv);
simEnv.state.phase = 'frozen';
simEnv.state.broadcast = true;
simEnv.detailsTrigger = null;
simEnv.onBroadcastControlClick({ currentTarget: 'info' });
assert.equal(simEnv.detailsTrigger, 'info');
assert.equal(simEnv.state.phase, 'frozen');
assert.equal(simEnv.state.broadcast, true, 'FROZEN info click must not change intent');
simEnv.detailsTrigger = null;
simEnv.state.phase = 'off';
simEnv.state.broadcast = false;
simEnv.onBroadcastControlClick({ currentTarget: 'info' });
assert.equal(simEnv.detailsTrigger, null);
assert.equal(simEnv.state.phase, 'arming');
assert.equal(simEnv.state.broadcast, true);


// A-only else branch. C and D return before this expression.
const aHintLine = host.split(/\r?\n/).find(line => line.includes("replaceMainCopy($('connection-hint'), state.phase"));
const aHintExpr = aHintLine.match(/replaceMainCopy\(\$\('connection-hint'\), (.*)\);/)[1];
let motionChecks = 0;
const motionEqual = (actual, expected, message) => { assert.equal(actual, expected, message); motionChecks += 1; };
motionEqual(vm.runInNewContext(aHintExpr, { state: { phase: 'connecting' }, phase: phases.connecting }), '', 'A connecting hint blank');
motionEqual(vm.runInNewContext(aHintExpr, { state: { phase: 'off' }, phase: phases.off }), '', 'A off hint blank');
motionEqual(vm.runInNewContext(aHintExpr, { state: { variant: 'A', phase: 'arming' }, phase: phases.arming }), '', 'A arming hint blank');
motionEqual(vm.runInNewContext(aHintExpr, { state: { variant: 'C', phase: 'arming' }, phase: phases.arming }), '接收啟動中', 'Non-A arming hint retains detail');
motionEqual(vm.runInNewContext(aHintExpr, { state: { phase: 'wifi-paused' }, phase: phases['wifi-paused'] }), phases['wifi-paused'].detail, 'A Wi-Fi hint unchanged');
assert.ok(host.includes('.v-A[data-phase="arming"] .connection-hint:not(.a-copy-exiting)'), 'A arming connection hint must be display none');

vm.runInContext(extractFn('broadcastDockFace'), simEnv);
const faces = [
    ['wifi-paused', 'C', true, '停止廣播', 'warning', 'Wi-Fi 已關閉，停止廣播'],
    ['wifi-paused', 'D', true, '停止廣播', 'warning', 'Wi-Fi 已關閉，停止廣播'],
    ['wifi-paused', 'A', true, '停止廣播', 'stop', '停止廣播'],
    ['failed', 'A', true, '停止廣播', 'warning', '連接失敗，停止廣播'],
    ['failed', 'C', true, '停止廣播', 'warning', '連接失敗，停止廣播'],
    ['failed', 'D', true, '停止廣播', 'stop', '停止廣播'],
    ['frozen', 'C', false, '查看接收不可用詳情', 'warning', '查看接收不可用詳情'],
    ['frozen', 'D', true, '查看接收不可用詳情', 'warning', '查看接收不可用詳情'],
    ['waiting', 'C', true, '停止廣播', 'stop', '停止廣播'],
    ['off', 'A', false, '開始廣播', 'play', '開始廣播']
];
for (const [phase, variant, broadcast, passed, symbol, label] of faces) {
    simEnv.state.phase = phase;
    simEnv.state.variant = variant;
    simEnv.state.broadcast = broadcast;
    const face = simEnv.broadcastDockFace(passed);
    motionEqual(face.symbol, symbol, phase + ' ' + variant + ' symbol');
    motionEqual(face.label, label, phase + ' ' + variant + ' label');
}

const targets = [];
simEnv.context = { clearRect() {}, fillStyle: '' };
simEnv.canvasWidth = 20;
simEnv.canvasHeight = 20;
simEnv.cParticlePadding = 0;
simEnv.particleTime = 2500;
simEnv.particles = [];
simEnv.cParticleColor = () => '0,0,0';
simEnv.cParticleAssembly = { setBroadcast(active, time) { targets.push({ active, time, phase: simEnv.state.phase }); } };
vm.runInContext(extractFn('drawParticles'), simEnv);
for (const [phase, broadcast, active] of [
    ['waiting', true, true],
    ['wifi-paused', true, false],
    ['waiting', true, true],
    ['arming', true, true],
    ['frozen', true, true],
    ['frozen', false, false],
    ['off', false, false],
    ['failed', true, true]
]) {
    targets.length = 0;
    simEnv.state.phase = phase;
    simEnv.state.broadcast = broadcast;
    simEnv.state.variant = 'C';
    simEnv.drawParticles();
    motionEqual(targets.length, 1, phase + ' draw');
    motionEqual(targets[0].active, active, phase + ' target');
    motionEqual(targets[0].time, 2.5, phase + ' clock');
}
console.log('motion checks', motionChecks);

{
    // C Particle Modes & Presets Contract
    assert.ok(host.includes('id="c-particle-mode"'), 'C particle mode selector in gyro settings must exist');
    assert.ok(host.includes('id="c-particle-picker"'), 'C particle picker selector in review tools must exist');
    assert.ok(host.includes('const cParticleModes = ['), 'cParticleModes allowlist must exist');

    const pSandbox = {
        location: { href: 'http://127.0.0.1:8765/big-standby-prototype.html?variant=C' },
        URL,
        cParticleModes: ['auto', 'cloud', 'x-mark', 'arming-vortex', 'connecting-tunnel', 'wifi-ripple', 'frozen-lattice']
    };
    vm.createContext(pSandbox);
    vm.runInContext(extractFn('readCParticleMode'), pSandbox);

    // Default when no query is present
    assert.equal(pSandbox.readCParticleMode(), 'auto', 'Default particle mode must be auto');

    for (const mode of pSandbox.cParticleModes) {
        pSandbox.location.href = `http://127.0.0.1:8765/big-standby-prototype.html?particle=${mode}`;
        assert.equal(pSandbox.readCParticleMode(), mode, `?particle=${mode} must be recognized`);

        pSandbox.location.href = `http://127.0.0.1:8765/big-standby-prototype.html?cParticle=${mode}`;
        assert.equal(pSandbox.readCParticleMode(), mode, `?cParticle=${mode} must be recognized`);
    }

    // Safety fallback for unknown modes
    pSandbox.location.href = 'http://127.0.0.1:8765/big-standby-prototype.html?particle=invalid_mode';
    assert.equal(pSandbox.readCParticleMode(), 'auto', 'Unknown particle mode must safely fall back to auto');

    // drawParticles must route every particle through the state-aware projection.
    assert.ok(host.includes("const projected = cParticleSampleProject(state.cParticleMode || 'auto', index, screenX, screenY, frame);"), 'drawParticles must route auto through cParticleSampleProject');

    // Unit test cParticleSampleProject across all modes
    const particleFn = extractFn('cParticleSampleProject');
    const projSandbox = {
        particles: Array.from({ length: 100 }, (_, i) => ({
            angle: (i / 100) * Math.PI * 2,
            tube: (i / 100) * Math.PI,
            radius: 0.25 + (i % 5) * 0.05
        })),
        cParticleAssembly: {
            project: (index, freeX, freeY, frame) => ({ x: freeX + (frame ? frame.time : 0), y: freeY + (frame ? frame.time : 0) })
        },
        getCParticleSampleAssembly: () => ({
            project: (index, freeX, freeY, frame) => ({ x: freeX + (frame ? frame.time * 2 : 0), y: freeY + (frame ? frame.time * 2 : 0) })
        }),
        state: { phase: 'off' },
        Math
    };
    vm.createContext(projSandbox);
    vm.runInContext(particleFn, projSandbox);

    const frameA = { width: 800, height: 600, time: 2.0 };
    const frameB = { width: 800, height: 600, time: 2.0 };
    const frameLater = { width: 800, height: 600, time: 3.5 };

    const coordinatesByMode = {};
    for (const mode of pSandbox.cParticleModes) {
        // Test non-ambient index (e.g. index 3, since index % 5 === 0 stays ambient)
        const posA = projSandbox.cParticleSampleProject(mode, 3, 400, 300, frameA);
        const posB = projSandbox.cParticleSampleProject(mode, 3, 400, 300, frameB);
        const posLater = projSandbox.cParticleSampleProject(mode, 3, 400, 300, frameLater);

        assert.ok(Number.isFinite(posA.x) && Number.isFinite(posA.y), `${mode}: coordinates must be finite numbers`);
        // Determinism: same frame.time yields identical coordinates (frozen stasis contract)
        assert.equal(posA.x, posB.x, `${mode}: same timestamp must yield identical x`);
        assert.equal(posA.y, posB.y, `${mode}: same timestamp must yield identical y`);

        if (mode !== 'cloud') {
            // Advancing time should advance position
            assert.ok(posA.x !== posLater.x || posA.y !== posLater.y, `${mode}: advancing time must advance coordinates`);
        }
        coordinatesByMode[mode] = `${posA.x.toFixed(1)},${posA.y.toFixed(1)}`;
    }

    // auto delegates to proposal 06 (Locked Stasis Lattice) only while the receiver is unavailable
    projSandbox.state.phase = 'frozen';
    const autoFrozen = projSandbox.cParticleSampleProject('auto', 3, 400, 300, frameA);
    const explicitFrozen = projSandbox.cParticleSampleProject('frozen-lattice', 3, 400, 300, frameA);
    assert.equal(autoFrozen.x, explicitFrozen.x, 'auto in FROZEN must match the frozen-lattice x');
    assert.equal(autoFrozen.y, explicitFrozen.y, 'auto in FROZEN must match the frozen-lattice y');
    assert.ok(autoFrozen.x !== 400 || autoFrozen.y !== 300, 'auto in FROZEN must leave the free-flight position');

    projSandbox.state.phase = 'waiting';
    const autoAssembled = projSandbox.cParticleSampleProject('auto', 3, 400, 300, frameA);
    assert.equal(autoAssembled.x, 400 + frameA.time, 'auto outside FROZEN must use the assembly x');
    assert.equal(autoAssembled.y, 300 + frameA.time, 'auto outside FROZEN must use the assembly y');

    // Modes produce distinct formations
    const uniquePositions = new Set(Object.values(coordinatesByMode));
    assert.ok(uniquePositions.size >= 5, 'Distinct particle sample modes must produce distinct formations');
    console.log('c particle sample checks: 7 modes verified with deterministic stasis; auto applies proposal 06 on FROZEN');
}


{
    const celestialChecks = [];
    const celestialEqual = (actual, expected, message) => {
        assert.equal(actual, expected, message);
        celestialChecks.push(message);
    };
    const props = {};
    let resetAdds = 0;
    const celestial = {
        attrs: {},
        classList: {
            add(name) { if (name === 'a-layout-reset') resetAdds += 1; },
            remove() {}
        },
        style: {
            getPropertyValue(name) { return props[name] || ''; },
            setProperty(name, value) { props[name] = value; }
        },
        getAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null; },
        setAttribute(name, value) { this.attrs[name] = value; },
        getBoundingClientRect() { return { top: 0, height: 0 }; }
    };
    const sky = {
        viewBox: { baseVal: { x: 0, y: 0, width: 1600, height: 1000 } },
        querySelector(selector) {
            if (selector === '.a-sun' || selector === '.a-moon') {
                return {
                    tagName: 'circle',
                    getAttribute(name) { return { cx: '1110', cy: '204', r: '30' }[name]; },
                    transform: { baseVal: { consolidate() { return null; } } },
                    ownerSVGElement: { createSVGMatrix() { return {}; } }
                };
            }
            if (selector === '.a-celestial') return celestial;
            return null;
        }
    };
    const boxes = {
        standby: { top: 0, height: 375 },
        brand: { top: 44, bottom: 80, height: 36 },
        primary: { top: 200, bottom: 280, height: 80 }
    };
    const sandbox = {
        getComputedStyle() { return { stroke: 'none', strokeWidth: '0' }; },
        document: {
            querySelector(selector) {
                if (selector === '#standby-view .brand') return { getBoundingClientRect: () => boxes.brand };
                if (selector === '#standby-view .center') return { getBoundingClientRect: () => boxes.primary };
                return null;
            }
        },
        stage: { getBoundingClientRect: () => ({ top: 0, left: 0, width: sandbox.canvasWidth, height: sandbox.canvasHeight }) },
        standbyView: { getBoundingClientRect: () => boxes.standby },
        state: { variant: 'A' },
        canvasWidth: 667,
        canvasHeight: 375,
        $(id) {
            if (id !== 'landscape') throw new Error(id);
            return { querySelector(selector) { return selector === 'svg' ? sky : null; } };
        }
    };
    vm.createContext(sandbox);
    vm.runInContext('class DOMPoint { constructor(x, y) { this.x = x; this.y = y; } matrixTransform() { return new DOMPoint(this.x, this.y); } }', sandbox);
    vm.runInContext(extractFn('celestialNodeRadius'), sandbox);
    vm.runInContext(extractFn('layoutCelestial'), sandbox);
    const place = (width, height, burn, leak, hidden, clamp) => {
        sandbox.canvasWidth = width;
        sandbox.canvasHeight = height;
        boxes.standby = { top: burn, height: hidden ? 0 : height };
        const child = hidden ? 0 : burn;
        boxes.brand = { top: child + leak + 44, bottom: child + leak + 80, height: clamp ? 36 : 0 };
        boxes.primary = { top: child + leak + 200, bottom: child + leak + 280, height: clamp ? 80 : 0 };
    };
    const read = () => celestial.getAttribute('transform');
    for (const [width, height] of [[667, 375], [2560, 720], [390, 844]]) {
        place(width, height, 0, 0, false, false);
        sandbox.layoutCelestial();
        const natural = read();
        place(width, height, 0, 0, false, true);
        sandbox.layoutCelestial();
        const clamped = read();
        celestialEqual(clamped === natural, false, width + ' clamp changes the rest position');
        const resets = resetAdds;
        place(width, height, 12.4, 0, false, true);
        sandbox.layoutCelestial();
        celestialEqual(read(), clamped, width + ' shared burn leaves the transform');
        celestialEqual(resetAdds, resets, width + ' shared burn does not reset the rise');
        place(width, height, 12.4, 1e-6, false, true);
        sandbox.layoutCelestial();
        celestialEqual(read(), clamped, width + ' subpixel noise stays inside 0.01px');
        celestialEqual(resetAdds, resets, width + ' subpixel noise does not reset');
        place(width, height, 0, 12, false, true);
        sandbox.layoutCelestial();
        celestialEqual(read() === clamped, false, width + ' a stage-top leak still moves the transform');
        celestialEqual(resetAdds, resets + 1, width + ' a real layout change still resets once');
    }
    place(667, 375, 0, 0, false, true);
    sandbox.layoutCelestial();
    const visible = read();
    const hiddenResets = resetAdds;
    place(667, 375, 18, 0, true, true);
    sandbox.layoutCelestial();
    celestialEqual(read(), visible, 'hidden standby ignores its own top');
    celestialEqual(resetAdds, hiddenResets, 'hidden standby does not reset');
    assert.ok(host.includes('const originTop = viewBoxRect.height > 0 ? viewBoxRect.top : stageBox.top;'));
    assert.ok(!host.includes('screenY - stageBox.top'));
    console.log('celestial checks', celestialChecks.length);
}

// 5. Custom broadcast name parsing and HTML escaping
{
    const nameOf = href => {
        const context = { location: { href }, URL, Object };
        vm.createContext(context);
        vm.runInContext(extractFn('readDeviceName'), context);
        vm.runInContext(extractFn('escapeHtml'), context);
        return context;
    };
    const base = 'http://127.0.0.1:8765/big-standby-prototype.html';
    assert.equal(nameOf(base).readDeviceName(), 'MIRAX · 客廳', 'Default receiver name must be the fixed prototype name');
    assert.equal(nameOf(base + '?name=MIRAX%20%C2%B7%20%E6%9B%B8%E6%88%BF').readDeviceName(), 'MIRAX · 書房', 'name param must decode to a custom receiver name');
    assert.equal(nameOf(base + '?deviceName=Living%20Room').readDeviceName(), 'Living Room', 'deviceName alias must be accepted');
    assert.equal(nameOf(base + '?name=%20%20%20').readDeviceName(), 'MIRAX · 客廳', 'Whitespace-only name must fall back to the default');
    assert.equal(nameOf(base + '?name=' + 'x'.repeat(60)).readDeviceName().length, 40, 'Custom name must clamp to 40 characters');
    assert.equal(nameOf(base + '?name=%3Cb%3ETest%3C%2Fb%3E').readDeviceName(), '<b>Test</b>', 'Name keeps raw markup; escaping happens at render time');
    assert.equal(
        nameOf(base).escapeHtml('<b>Test</b> & ' + String.fromCharCode(34) + 'q' + String.fromCharCode(34) + ' ' + String.fromCharCode(39) + '&amp;'),
        '&lt;b&gt;Test&lt;/b&gt; &amp; &quot;q&quot; &#39;&amp;amp;',
        'escapeHtml must escape &, <, >, quote, and apostrophe'
    );
    console.log('custom device name checks', 7);
}

// 6. Broadcast name persistence through a mock localStorage
{
    const DEFAULT_NAME = 'MIRAX · 客廳';
    const KEY = 'mirax_broadcast_name';
    const createStorage = (initial) => {
        const store = new Map(Object.entries(initial ?? {}));
        return {
            getItem: (key) => (store.has(key) ? store.get(key) : null),
            setItem: (key, value) => { store.set(key, String(value)); },
            removeItem: (key) => { store.delete(key); },
            has: (key) => store.has(key),
            read: (key) => (store.has(key) ? store.get(key) : null)
        };
    };
    const warnings = [];
    const loadName = (href, storage) => {
        const quietConsole = { warn: (...args) => warnings.push(args.at(0)), log: () => {} };
        const context = { location: { href }, URL, Object, localStorage: storage, console: quietConsole };
        vm.createContext(context);
        vm.runInContext(host.match(/const STORAGE_BROADCAST_NAME = .*?;/)[0], context);
        vm.runInContext(extractFn('readDeviceName'), context);
        vm.runInContext(extractFn('saveDeviceName'), context);
        return context;
    };
    const base = 'http://127.0.0.1:8765/big-standby-prototype.html';

    assert.equal(loadName(base, createStorage()).readDeviceName(), DEFAULT_NAME, 'Empty storage falls back to the default name');
    assert.equal(loadName(base, createStorage({ [KEY]: '臥室電視機' })).readDeviceName(), '臥室電視機', 'Stored name must be restored on reload');
    assert.equal(
        loadName(base + '?name=' + encodeURIComponent('書房'), createStorage({ [KEY]: '臥室電視機' })).readDeviceName(),
        '書房',
        'URL parameter must win over the stored name'
    );
    assert.equal(loadName(base, createStorage({ [KEY]: '   ' })).readDeviceName(), DEFAULT_NAME, 'Whitespace-only stored value must be ignored');
    assert.equal(loadName(base, createStorage({ [KEY]: 'k'.repeat(60) })).readDeviceName().length, 40, 'Stored name must clamp to 40 characters');

    const broken = { getItem: () => { throw new Error('denied'); }, setItem: () => { throw new Error('denied'); }, removeItem: () => { throw new Error('denied'); } };
    assert.equal(loadName(base, broken).readDeviceName(), DEFAULT_NAME, 'Denied storage must not break the reader');

    const writes = createStorage();
    const writer = loadName(base, writes);
    writer.saveDeviceName('臥室電視機');
    assert.equal(writes.read(KEY), '臥室電視機', 'saveDeviceName must persist a custom name');
    assert.equal(loadName(base, writes).readDeviceName(), '臥室電視機', 'Persisted name must survive a simulated reload');

    writer.saveDeviceName('  ' + DEFAULT_NAME + '  ');
    assert.equal(writes.has(KEY), false, 'Saving the default name must clear storage');
    writer.saveDeviceName('');
    assert.equal(writes.has(KEY), false, 'Saving an empty name must keep storage clear');

    writer.saveDeviceName('k'.repeat(60));
    assert.equal(writes.read(KEY).length, 40, 'saveDeviceName must clamp to 40 characters');

    writer.saveDeviceName(DEFAULT_NAME);
    assert.equal(loadName(base, writes).readDeviceName(), DEFAULT_NAME, 'Cleared storage falls back to the default name');

    let deniedWrites = 0;
    const throwing = { getItem: () => null, setItem: () => { deniedWrites += 1; throw new Error('denied'); }, removeItem: () => { throw new Error('denied'); } };
    const deniedWriter = loadName(base, throwing);
    deniedWriter.saveDeviceName('客廳二');
    deniedWriter.saveDeviceName('');
    assert.equal(deniedWrites, 1, 'saveDeviceName must swallow storage write failures');
    assert.equal(warnings.filter(message => message === 'Broadcast name storage unavailable').length, 3, 'Storage failures must be logged, not silent');

    console.log('broadcast name persistence checks', 14);
}

console.log('PASS verify-native-phases.cjs: All 7 ScreenPhase contracts, guards, mappings, and edge cases verified.');
