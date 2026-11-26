'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createHash } = require('node:crypto');

const html = fs.readFileSync(path.join(__dirname, '..', 'big-standby-prototype.html'), 'utf8');
const files = fs.readdirSync(__dirname).filter(name => /^\d{2,3}-.+\.js$/.test(name)).sort();
const studies = new Map();
assert.ok(files.length > 0, 'No active study files');
const scripts = Array.from(html.matchAll(new RegExp('src="big-standby-motion-prototype/([0-9]{2,3}-.+[.]js)"', 'g')), match => match[1]).sort();
assert.deepEqual(scripts, files, 'Host scripts must match the active catalogue exactly');
for (const [index, file] of files.entries()) {

    assert.ok(html.includes(`src="big-standby-motion-prototype/${file}"`), `Missing script: ${file}`);
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, file), 'utf8'), {
        window: { miraxDStudies: studies }, console
    }, { filename: file, timeout: 1000 });
    assert.ok(studies.has(`study-${file.slice(0, -3)}`));
    assert.equal(studies.size, index + 1, `${file}: expected exactly one registration`);
}
assert.equal(studies.size, files.length);
const orderStatement = html.match(/const dMotions = .+;/)?.[0];
assert.ok(orderStatement, 'Missing host ordering statement');
const ordered = vm.runInNewContext(orderStatement + '; dMotions', { dStudies: studies });
const numericOrder = Array.from(ordered).filter(id => id.startsWith('study-')).map(id => Number(id.split('-')[1]));
assert.deepEqual(numericOrder, [...numericOrder].sort((a, b) => a - b), 'Candidate IDs must sort numerically across 99/100');

function freeze(value) {
    if (typeof value !== 'object' || value === null) return value;
    Object.values(value).forEach(freeze);
    return Object.freeze(value);
}

function fixture(width, height, time) {
    const point = (x, y) => ({ x: x * width, y: y * height });
    const arms = [
        [[.53, -.1], [.59, -.1], [.78, .25], [.73, .34], [.53, .04]],
        [[.95, -.1], [1.04, -.1], [1.04, .04], [.86, .34], [.8, .25]],
        [[.53, .72], [.73, .5], [.78, .58], [.59, .9], [.53, .9]],
        [[.86, .5], [1.04, .72], [1.04, .9], [.95, .9], [.8, .58]]
    ].map(arm => arm.map(([x, y]) => point(x, y)));
    const visibleEdges = arms.flatMap((arm, index) => arm.flatMap((a, i) => {
        const b = arm[(i + 1) % arm.length];
        if (a.y < 0 || b.y < 0 || a.x > width || b.x > width) return [];
        return [{ a, b, length: Math.hypot(b.x - a.x, b.y - a.y), arm: index }];
    }));
    return freeze({ width, height, time, arms, visibleEdges,
        field: { top: { x: 14, y: 14, width: width - 28, height: height * .33 - 28 },
            bottom: { x: 14, y: height * .62, width: width - 28, height: height * .22 } },
        rails: [[point(-.03, .91), point(.3, .58)], [point(0, .94), point(.33, .61)]],
        diamond: [[.78, .38], [.84, .44], [.78, .5], [.72, .44]].map(([x, y]) => point(x, y))
    });
}

function trace(study, frame) {
    const calls = [];
    let depth = 0;
    const finite = values => values.forEach(value => {
        if (typeof value === 'number') assert.ok(Number.isFinite(value), `${study.id}: non-finite drawing value`);
        else if (Array.isArray(value)) finite(value);
    });
    const methods = new Set(('beginPath closePath moveTo lineTo arc arcTo ellipse rect roundRect '
        + 'bezierCurveTo quadraticCurveTo stroke fill clip fillRect strokeRect clearRect fillText '
        + 'setLineDash translate rotate scale transform setTransform resetTransform save restore').split(' '));
    const context = new Proxy({}, {
        set(target, key, value) { finite([value]); target[key] = value; calls.push(['set', key, value]); return true; },
        get(target, key) {
            if (key in target) return target[key];
            if (key === 'createLinearGradient' || key === 'createRadialGradient') return (...args) => {
                finite(args); calls.push([key, ...args]);
                return { addColorStop(...stop) { finite(stop); calls.push(['colorStop', ...stop]); } };
            };
            assert.ok(methods.has(key), `${study.id}: unsupported context member ${String(key)}`);
            return (...args) => {
                finite(args);
                if (key === 'save') depth++;
                if (key === 'restore') { depth--; assert.ok(depth >= 0, `${study.id}: unbalanced restore`); }
                calls.push([key, ...args]);
            };
        }
    });
    study.draw(context, frame);
    assert.equal(depth, 0, `${study.id}: leaked save`);
    assert.ok(calls.length > 0, `${study.id}: empty draw`);
    return createHash('sha256').update(JSON.stringify(calls)).digest('hex');
}

let draws = 0;
for (const [id, study] of studies) {
    assert.equal(id, study.id);
    const numeric = Number(id.split('-')[1]);
    if ((numeric >= 21 && numeric <= 50) || (numeric >= 61 && numeric <= 65)) assert.equal(study.scope, 'field', `${id}: field scope required`);
    assert.ok(study.title && study.description && study.category && typeof study.draw === 'function');
    if (numeric >= 119 && numeric <= 208) assert.equal(study.xMotion, undefined, `${id}: main X stays fixed`);
    if (numeric >= 66 && numeric <= 92) {
        assert.equal(typeof study.sourceId, 'string', `${id}: source lineage required`);
        assert.match(study.sourceId, /^study-[0-9]{2,3}-[a-z0-9]+(-[a-z0-9]+)*$/);
        assert.notEqual(study.sourceId, id);
        const fieldSources = new Set(['study-34-depth', 'study-36-quasicrystal', 'study-45-growth-front']);
        assert.equal(study.scope, fieldSources.has(study.sourceId) ? 'field' : undefined, `${id}: source scope mismatch`);
        assert.equal(study.xMotion, undefined, `${id}: fourth-batch X stays fixed`);
    }
    if (numeric >= 93 && numeric <= 112) {
        assert.ok(studies.has(study.sourceId), id + ': approved source must stay loaded');
        assert.notEqual(study.sourceId, id);
        const field = numeric >= 108 && numeric <= 111;
        assert.equal(study.scope, field ? 'field' : undefined, id + ': fifth-batch scope mismatch');
        assert.equal(study.xMotion, undefined, id + ': fifth-batch X stays fixed');
    }
    if (numeric >= 113 && numeric <= 118) {
        assert.ok(studies.has(study.sourceId), id + ': source must stay loaded');
        assert.notEqual(study.sourceId, id);
        assert.equal(study.scope, numeric === 115 || numeric === 116 ? 'field' : undefined, id + ': comparison scope mismatch');
        assert.equal(study.xMotion, undefined, id + ': main X stays fixed');
    }
    if (study.xMotion !== undefined) {
        assert.equal(typeof study.xMotion, 'function', `${id}: xMotion must be a function`);
        for (const time of [0, 2.4, 7.7]) {
            const motion = study.xMotion(time, fixture(1440, 1000, time));
            assert.ok(motion && typeof motion === 'object', `${id}: xMotion must return an object`);
            for (const key of ['dx', 'dy', 'rotate', 'scale']) {
                if (motion[key] === undefined) continue;
                assert.ok(Number.isFinite(motion[key]), `${id}: non-finite xMotion ${key}`);
            }
        }
    }
    for (const [width, height] of [[1440, 1000], [2560, 720], [390, 844], [844, 390], [667, 375]]) {
        const hashes = new Set();
        for (const time of [0, .5, 1.06, 1.39, 2.6, 3.12, 3.85, 4.4, 5.9, 7.3, 9.1, 13.1, 19.9]) {
            const frame = fixture(width, height, time);
            const hash = trace(study, frame);
            assert.equal(trace(study, frame), hash, `${id}: non-deterministic`);
            hashes.add(hash);
            draws += 2;
        }
        const minimumStates = numeric === 127 ? 3 : 4;
        assert.ok(hashes.size >= minimumStates, `${id}: no evolving drawing commands`);
    }
    console.log(`PASS ${id}`);
}
if (studies.has('study-191-growth-branching')) {
    const source = fs.readFileSync(path.join(__dirname, '191-growth-branching.js'), 'utf8');
    const marker = "    window.miraxDStudies.set('study-191-growth-branching', {";
    assert.ok(source.includes(marker), '191: growth probe marker missing');
    const probe = { window: { miraxDStudies: new Map() }, console };
    vm.runInNewContext(source.replace(marker, '    globalThis.growthPathState = pathState;' + String.fromCharCode(10) + marker), probe);
    for (const count of [3, 4, 6, 8, 12]) for (const kind of ['grow', 'retreat']) {
        let previous = probe.growthPathState(count, 0, kind);
        for (let tick = 1; tick <= count * 3 + 1; tick++) {
            const current = probe.growthPathState(count, tick, kind);
            assert.equal(current.size, previous.size, '191: whole-path occupancy changed at wrap');
            let changes = 0;
            for (let index = 0; index < count; index++) if (previous.get(index) !== current.get(index)) changes++;
            assert.ok(changes <= 3, '191: whole-path reset at tick ' + tick);
            previous = current;
        }
    }
    console.log('PASS study-191-growth-branching cycle continuity');
}
for (const id of ['study-199-glitch-frame-log', 'study-203-glitch-contour-log']) {
    if (!studies.has(id)) continue;
    const base = fixture(1280, 800, 1);
    const frame = freeze({ ...base, visibleEdges: [...base.visibleEdges,
        { a: { x: 2, y: 2 }, b: { x: 2.1, y: 2 }, length: 0.1, arm: 0 }] });
    const texts = [];
    const context = new Proxy({}, {
        set(target, key, value) { target[key] = value; return true; },
        get(target, key) {
            if (key in target) return target[key];
            return key === 'fillText' ? text => texts.push(String(text)) : () => {};
        }
    });
    studies.get(id).draw(context, frame);
    assert.ok(texts.some(text => text.includes(String(frame.visibleEdges.length) + ' 段')),
        id + ': displayed edge count must include every visible frame edge');
    console.log('PASS ' + id + ' actual visible-edge count');
}
console.log(`${studies.size} registrations, ${draws} deterministic contract draws passed; visual quality requires browser review.`);
