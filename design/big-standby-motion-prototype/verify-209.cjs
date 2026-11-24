'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const filename = '209-cathode-extrusion.js';
const source = fs.readFileSync(path.join(__dirname, filename), 'utf8');
const html = fs.readFileSync(path.join(__dirname, '..', 'big-standby-prototype.html'), 'utf8');
const id = 'study-209-cathode-extrusion';
const deny = () => { throw new Error('Study attempted a host-owned side effect'); };

function load(text = source) {
    const registry = new Map();
    const window = new Proxy({ miraxDStudies: registry }, {
        get(target, key) { return key in target ? target[key] : deny(); },
        set: deny
    });
    const math = Object.create(Math);
    math.random = deny;
    const sandbox = vm.createContext({
        window, Math: math, Date: deny, performance: { now: deny },
        document: new Proxy({}, { get: deny }), fetch: deny,
        setTimeout: deny, setInterval: deny, requestAnimationFrame: deny,
        addEventListener: deny, localStorage: new Proxy({}, { get: deny })
    });
    vm.runInContext(text, sandbox, { filename, timeout: 1000 });
    assert.equal(registry.size, 1, 'Standalone loading must register exactly one study');
    return { study: registry.get(id), sandbox };
}

const { study } = load();
assert.equal(study.id, id);
assert.equal(study.title, '209 描邊引伸框 · 17＋118');
assert.equal(study.category, '合成提案');
assert.deepEqual(Array.from(study.sourceIds), ['study-17-cathode', 'study-118-section-extrusion']);
assert.ok(study.description.includes('不縮放') && study.description.includes('原Ｘ'));

function freeze(value) {
    if (!value || typeof value !== 'object' || Object.isFrozen(value)) return value;
    Object.values(value).forEach(freeze);
    return Object.freeze(value);
}

function extract(start, end) {
    const from = html.indexOf(start);
    const to = html.indexOf(end, from);
    assert.ok(from >= 0 && to > from, `Missing host geometry: ${start}`);
    return html.slice(from, to);
}

// Execute the actual host vertices, projection and clipping, not an approximate X fixture.
const hostGeometry = extract('const dArmVertices =', 'const contourCurrents =')
    + extract('function clipDStudyEdge(', 'function drawDMotion(');
assert.ok(hostGeometry.includes('function createDStudyFrame('));

function fixture(width, height, time = 0) {
    const scale = Math.max(width / 1600, height / 1000);
    const matrix = { a: scale, d: scale, e: (width - 1600 * scale) / 2, f: (height - 1000 * scale) / 2 };
    const bandTop = height * (height < 450 ? 0.43 : 0.41);
    const bandBottom = height * (height < 450 ? 0.62 : 0.6);
    const mark = /class="x-core"[^>]*x="([^"]+)" y="([^"]+)" width="([^"]+)"/.exec(html);
    assert.ok(mark, 'Missing original X registration');
    const val = (value) => ({ baseVal: { value } });
    const sandbox = vm.createContext({
        canvasWidth: width, canvasHeight: height, dHeaderLower: 68 * scale + matrix.f + 1,
        xPlane: { getScreenCTM: () => matrix },
        stage: { getBoundingClientRect: () => ({ left: 0, top: 0 }) },
        xCore: { x: val(Number(mark[1])), y: val(Number(mark[2])), width: val(Number(mark[3])) },
        centerInformation: { getBoundingClientRect: () => ({ top: bandTop, bottom: bandBottom }) },
        $: () => ({ getBoundingClientRect: () => ({ width: 180, height: 40, top: height - 52 }) }),
        dRails: [0, 1].map(index => {
            const gap = Math.max(16, Math.min(32, 39 * scale));
            const start = height * 0.88 + index * gap;
            const reach = Math.min(430 * scale, width * 0.4, height * 0.65) + index * gap;
            return [{ x: -32, y: start + 32 }, { x: reach, y: start - reach }];
        }),
        DOMPoint: class {
            constructor(x, y) { this.x = x; this.y = y; }
            matrixTransform(m) { return { x: this.x * m.a + m.e, y: this.y * m.d + m.f }; }
        }
    });
    vm.runInContext(hostGeometry, sandbox, { timeout: 1000 });
    return freeze(sandbox.createDStudyFrame(time));
}

function clip(a, b, width, height) {
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    let from = 0;
    let to = 1;
    for (const [p, q] of [[-dx, a.x], [dx, width - a.x], [-dy, a.y], [dy, height - a.y]]) {
        if (p === 0 && q < 0) return null;
        if (p < 0) from = Math.max(from, q / p);
        if (p > 0) to = Math.min(to, q / p);
        if (from > to) return null;
    }
    return [{ x: a.x + dx * from, y: a.y + dy * from },
        { x: a.x + dx * to, y: a.y + dy * to }];
}

class CanvasRecorder {
    constructor(frame, capture = false) {
        this.frame = frame;
        this.capture = capture;
        this.state = { lineWidth: 2, lineCap: 'round', lineJoin: 'bevel', strokeStyle: 'initial', alpha: 1 };
        this.initial = { ...this.state };
        this.stack = [];
        this.paths = [];
        this.calls = [];
        this.sections = [];
        this.connections = 0;
        this.strokes = 0;
        this.maxAlpha = 0;
        this.moments = [0, 0, 0];
        this.grid = new Float64Array(16 * 12);
    }

    save() { this.stack.push({ ...this.state }); }
    restore() {
        assert.ok(this.stack.length, 'Unbalanced restore');
        this.state = this.stack.pop();
    }
    set lineWidth(value) {
        assert.ok(Number.isFinite(value) && value > 0 && value <= 1.05, 'Invalid/excessive stroke width');
        this.state.lineWidth = value;
    }
    get lineWidth() { return this.state.lineWidth; }
    set lineCap(value) { this.state.lineCap = value; }
    set lineJoin(value) { this.state.lineJoin = value; }
    set strokeStyle(value) {
        const match = /^rgba\(226,232,230,([^)]+)\)$/.exec(value);
        assert.ok(match, `Invalid stroke style: ${value}`);
        const alpha = Number(match[1]);
        assert.ok(Number.isFinite(alpha) && alpha >= 0 && alpha <= 0.43 + 1e-12, 'Invalid/excessive alpha');
        this.state.strokeStyle = value;
        this.state.alpha = alpha;
        this.maxAlpha = Math.max(this.maxAlpha, alpha);
    }
    beginPath() { this.paths = []; }
    moveTo(x, y) {
        assert.ok(Number.isFinite(x) && Number.isFinite(y), 'Non-finite moveTo');
        this.paths.push({ points: [{ x, y }], closed: false });
    }
    lineTo(x, y) {
        assert.ok(Number.isFinite(x) && Number.isFinite(y), 'Non-finite lineTo');
        assert.ok(this.paths.length, 'lineTo without moveTo');
        this.paths.at(-1).points.push({ x, y });
    }
    closePath() { this.paths.at(-1).closed = true; }
    stroke() {
        if (this.failStroke) throw new Error('Injected canvas failure');
        this.strokes += 1;
        if (this.paths.some(part => part.closed)) {
            assert.ok(this.paths.every(part => part.closed), 'Partial sectional outline');
            this.sections.push({ paths: this.paths, alpha: this.state.alpha });
        }
        if (this.state.lineWidth === 0.7) {
            this.connections += this.paths.length;
            assert.ok(this.state.alpha <= 0.055, 'Connectors overpower the real X');
        }
        if (this.capture) this.calls.push({ ...this.state, paths: this.paths });
        for (const part of this.paths) {
            for (let index = 1; index < part.points.length; index += 1) {
                this.measure(part.points[index - 1], part.points[index]);
            }
            if (part.closed) this.measure(part.points.at(-1), part.points[0]);
        }
    }
    measure(a, b) {
        const visible = clip(a, b, this.frame.width, this.frame.height);
        if (!visible) return;
        const [start, end] = visible;
        const ink = Math.hypot(end.x - start.x, end.y - start.y) * this.state.lineWidth * this.state.alpha;
        const x = (start.x + end.x) / (2 * this.frame.width);
        const y = (start.y + end.y) / (2 * this.frame.height);
        this.moments[0] += ink;
        this.moments[1] += ink * x;
        this.moments[2] += ink * y;
        if (!this.capture) return;
        const gx = Math.max(0, Math.min(15, x * 15));
        const gy = Math.max(0, Math.min(11, y * 11));
        const left = Math.min(14, Math.floor(gx));
        const top = Math.min(10, Math.floor(gy));
        const fx = gx - left;
        const fy = gy - top;
        this.grid[top * 16 + left] += ink * (1 - fx) * (1 - fy);
        this.grid[top * 16 + left + 1] += ink * fx * (1 - fy);
        this.grid[(top + 1) * 16 + left] += ink * (1 - fx) * fy;
        this.grid[(top + 1) * 16 + left + 1] += ink * fx * fy;
    }
}

function near(a, b, tolerance = 1e-8) { assert.ok(Math.abs(a - b) <= tolerance, `${a} != ${b}`); }

function verifySections(recorder, frame) {
    assert.ok(recorder.sections.length <= 2, 'More than two depth layers');
    assert.ok(recorder.connections <= 10, 'Excessive prism connectors');
    const polygons = [...(frame.arms || []), frame.diamond || []].filter(polygon => polygon.length >= 3);
    for (const section of recorder.sections) {
        assert.equal(section.paths.length, polygons.length);
        const dx = section.paths[0].points[0].x - polygons[0][0].x;
        const dy = section.paths[0].points[0].y - polygons[0][0].y;
        assert.ok(Math.hypot(dx, dy) <= Math.min(frame.width, frame.height) * 0.11 + 1e-8);
        section.paths.forEach((part, index) => {
            assert.equal(part.points.length, polygons[index].length, 'Original polygon was cut apart');
            part.points.forEach((point, vertex) => {
                near(point.x - polygons[index][vertex].x, dx);
                near(point.y - polygons[index][vertex].y, dy);
            });
        });
    }
}

function render(frame, capture = false, renderer = study) {
    freeze(frame);
    const before = JSON.stringify(frame);
    const recorder = new CanvasRecorder(frame, capture);
    renderer.draw(recorder, frame);
    assert.equal(recorder.stack.length, 0, 'Unbalanced save/restore');
    assert.deepEqual(recorder.state, recorder.initial, 'Canvas state leaked');
    assert.equal(JSON.stringify(frame), before, 'Read-only frame was modified');
    return recorder;
}

function distance(a, b) {
    const scale = Math.max(1e-12, a.reduce((sum, value) => sum + Math.abs(value), 0),
        b.reduce((sum, value) => sum + Math.abs(value), 0));
    return a.reduce((sum, value, index) => sum + Math.abs(value - b[index]), 0) / scale;
}

// Narrow VM-only injection exposes pure math without shipping a production test API.
const marker = '    window.miraxDStudies.set(';
assert.equal(source.split(marker).length, 2);
const { sandbox: mathSandbox } = load(source.replace(marker,
    '    globalThis.__pure = { PERIOD, smooth, envelope, phaseAt };\n' + marker));
const { PERIOD, smooth, envelope, phaseAt } = mathSandbox.__pure;
const epsilon = 1e-6;
for (const fn of [smooth, envelope, phase => envelope(phase) * smooth(phase)]) {
    near(fn(0), 0);
    near(fn(1), fn === smooth ? 1 : 0);
    assert.ok(Math.abs((fn(epsilon) - fn(0)) / epsilon) < 1e-4, 'Nonzero start derivative');
    assert.ok(Math.abs((fn(1) - fn(1 - epsilon)) / epsilon) < 1e-4, 'Nonzero end derivative');
}
for (let index = -100; index <= 100; index += 1) {
    const time = index * PERIOD / 73;
    near(envelope(phaseAt(time, 0)) + envelope(phaseAt(time, 1)), 1);
}

const sizes = [[360, 800], [844, 390], [1440, 900], [1920, 1080], [3440, 1440]];
let frames = 0;
let maxBoundaryJump = 0;
const results = [];
for (const [width, height] of sizes) {
    const base = fixture(width, height);
    assert.equal(base.arms.length, 4);
    assert.equal(base.diamond.length, 4);
    assert.ok(base.visibleEdges.length > 0 && base.rails.length > 0);
    assert.ok(base.arms.flat().some(point => point.y < 0 || point.x > width), 'Fixture must include cropping');
    const initialGeometry = JSON.stringify([base.arms, base.diamond, base.rails, base.visibleEdges]);
    let previous = null;
    let minInk = Infinity;
    let maxInk = 0;
    let maxLayers = 0;
    for (let index = 0; index <= PERIOD * 4 * 30; index += 1) {
        const recorder = render({ ...base, time: index / 30 });
        verifySections(recorder, base);
        frames += 1;
        maxLayers = Math.max(maxLayers, recorder.sections.length);
        minInk = Math.min(minInk, recorder.moments[0]);
        maxInk = Math.max(maxInk, recorder.moments[0]);
        if (index % 15 === 0) {
            if (previous) assert.ok(distance(previous, recorder.moments) > 1e-5, 'Animation stalled for half a second');
            previous = recorder.moments;
        }
    }
    assert.equal(maxLayers, 2);
    assert.ok(minInk > 0 && maxInk / (width * height) < 0.01, 'Dark gap or excessive whole-screen ink');
    assert.equal(JSON.stringify([base.arms, base.diamond, base.rails, base.visibleEdges]), initialGeometry);

    const sample = render({ ...base, time: 2.3 }, true);
    const repeated = render({ ...base, time: 2.3 }, true);
    assert.deepEqual(sample.calls, repeated.calls, 'Same-time rendering must be deterministic');
    assert.deepEqual(sample.calls, render({ ...base, time: 2.3 }, true, load().study).calls, 'Hidden cross-frame state');
    const originalEdges = [...base.visibleEdges, ...base.rails.map(([a, b]) => ({ a, b }))];
    const onSegment = (point, edge) => {
        const dx = edge.b.x - edge.a.x;
        const dy = edge.b.y - edge.a.y;
        const px = point.x - edge.a.x;
        const py = point.y - edge.a.y;
        const dot = px * dx + py * dy;
        return Math.abs(px * dy - py * dx) < 1e-6 && dot >= -1e-6 && dot <= dx * dx + dy * dy + 1e-6;
    };
    for (const call of sample.calls.filter(call => call.lineWidth === 1.05)) {
        assert.ok(originalEdges.some(edge => call.paths[0].points.every(point => onSegment(point, edge))),
            'Cathode front left the fixed original X/rail contour');
    }

    // Spatially weighted alpha/geometry, not equality of raw drawing-call hashes.
    for (let cycle = 0; cycle <= 8; cycle += 1) {
        const boundary = cycle * PERIOD / 2;
        let previousJump = Infinity;
        for (const delta of [0.01, 0.001, 0.0001]) {
            const left = render({ ...base, time: boundary - delta }, true);
            const right = render({ ...base, time: boundary + delta }, true);
            const jump = distance(Array.from(left.grid), Array.from(right.grid));
            assert.ok(jump < delta * 2, `Visible reset at ${width}x${height} t=${boundary}: ${jump}`);
            assert.ok(jump < previousJump * 0.2, 'Boundary jump did not converge to zero');
            previousJump = jump;
            if (delta === 0.0001) maxBoundaryJump = Math.max(maxBoundaryJump, jump);
        }
    }
    results.push({ viewport: `${width}x${height}`, seconds: PERIOD * 4, fps: 30, maxLayers,
        minInk: Number(minInk.toFixed(3)), maxInk: Number(maxInk.toFixed(3)) });
}

const real = fixture(360, 800, 1.25);
for (const override of [{ visibleEdges: [] }, { rails: [] }, { visibleEdges: [], rails: [] },
    { arms: [], diamond: [] }, { field: undefined }]) {
    const recorder = render({ ...real, ...override }, true);
    assert.ok(recorder.strokes > 0, 'Optional geometry removed all animation');
}
for (const override of [{ width: 0 }, { height: 0 }, { width: -1 }, { height: -1 },
    { width: NaN }, { height: Infinity }, { time: NaN }, { time: Infinity }]) {
    assert.equal(render({ ...real, ...override }).strokes, 0, 'Invalid dimensions/time must be a no-op');
}
assert.equal(render({ width: 1, height: 1, time: 0 }).strokes, 0, 'Empty minimal fixture must be safe');
const point = { x: 12, y: 12 };
assert.equal(render({ width: 360, height: 800, time: 1,
    arms: [[point, point, point]], diamond: [point], rails: [[point, point]],
    visibleEdges: [{ a: point, b: point, arm: 0 }, { a: { x: NaN, y: 0 }, b: point, arm: 1 }]
}).strokes, 0, 'Degenerate/non-finite geometry must be ignored');
for (const time of [-100, -9.6, -0.001, 0, 9.6, 48, 1e6]) {
    const frame = { ...real, time };
    assert.deepEqual(render(frame, true).calls, render(frame, true).calls, 'Pause/seek determinism');
}
for (const [width, height] of [[1, 1], [320, 1], [1, 800], [4096, 240]]) render(fixture(width, height, 1.25));
const failed = new CanvasRecorder(real);
failed.failStroke = true;
assert.throws(() => study.draw(failed, real), /Injected canvas failure/);
assert.equal(failed.stack.length, 0, 'Canvas failure leaked a saved state');
assert.deepEqual(failed.state, failed.initial);

console.log(JSON.stringify({ ok: true, standalone: true, frames, results, maxBoundaryJump,
    checks: ['real host geometry', 'read-only frame', 'finite canvas', 'balanced stack', 'determinism',
        'C1 envelopes/head/depth', 'two-layer overlap', 'unchanged X vertices', 'empty/degenerate geometry'],
    limits: ['No browser, screenshot, GPU, device or live CSS-layout verification',
        'Host geometry is real; information-band/dock bounds are synthetic fixtures',
        'Ink signatures approximate spatial continuity, not pixel compositing'] }, null, 2));
