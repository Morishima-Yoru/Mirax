'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const uiPath = path.join(__dirname, 'verify-v36-ui.js');
const source = fs.readFileSync(uiPath, 'utf8');
const factory = new vm.Script('(' + source + ')', { filename: uiPath });
const start = source.indexOf('// Pure geometry begins.');
const end = source.indexOf('// Pure geometry ends.');
assert.ok(start >= 0 && end > start, 'Exercise the exact browser ink-geometry helpers');
const geometry = vm.runInNewContext(source.slice(start, end) + '\n({ polygonArea, clipPolygon, visibleAxisLength, v35BandLayout, completeKeyIds, enteringKeyIds, marqueeAxis })', {}, { timeout: 1000 });
const box = (left, top, right, bottom) => [{ x: left, y: top }, { x: right, y: top }, { x: right, y: bottom }, { x: left, y: bottom }];
const near = (actual, expected, tolerance = 1e-8) => assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} != ${expected}`);
let checks = 0;
for (const boundary of [box(0, 0, 10, 10), box(0, 0, 10, 10).reverse()]) {
    near(geometry.polygonArea(geometry.clipPolygon(box(2, 3, 7, 8), boundary)), 25);
    near(geometry.polygonArea(geometry.clipPolygon(box(-2, 3, 7, 8), boundary)), 35);
    near(geometry.polygonArea(geometry.clipPolygon(box(12, 3, 17, 8), boundary)), 0);
    near(geometry.polygonArea(geometry.clipPolygon(box(10, 3, 17, 8), boundary)), 0);
    near(geometry.polygonArea(geometry.clipPolygon(box(-10, -10, 20, 20), boundary)), 100);
    checks += 5;
}
const diamond = [{ x: 5, y: 0 }, { x: 10, y: 5 }, { x: 5, y: 10 }, { x: 0, y: 5 }];
near(geometry.polygonArea(geometry.clipPolygon(box(0, 0, 1, 1), diamond)), 0);
near(geometry.polygonArea(geometry.clipPolygon(diamond, box(0, 0, 10, 5))), 25);
near(geometry.polygonArea(geometry.clipPolygon([], diamond)), 0);
checks += 3;
let improved = 0;
const sizes = [[320, 568], [390, 844], [480, 375], [568, 320], [667, 375], [896, 592], [1440, 1000], [2560, 720]];
for (const [width, height] of sizes) {
    assert.ok(source.includes(`[${width}, ${height}]`), `Required viewport ${width}x${height}`);
    const old = geometry.v35BandLayout(width, height).lower;
    const shifted = { x: old.x - 12, y: old.y - 12 };
    const windowWidth = Math.min(1600, Math.max(width, height) * 1.6);
    const before = geometry.visibleAxisLength(old, windowWidth, width, height);
    const after = geometry.visibleAxisLength(shifted, windowWidth, width, height);
    assert.ok(after >= before - 1e-8, 'The v35 baseline comparison cannot invent lost length');
    if (after > before + 8) improved++;
    checks += 2;
}
assert.ok(improved > 0, 'The constrained-viewport length check must detect a real north-west benefit');
for (const [width, height, originX, originY, size] of [[320, 568, .59, .38, Math.min(320 * .84, 568 * .48)],
    [568, 320, .75, .47, Math.min(568 * .44, 320 * .74)], [1440, 1000, .72, .48, Math.min(1440 * .54, 1000 * .78)]]) {
    const layout = geometry.v35BandLayout(width, height);
    near((layout.lower.x + layout.upper.x) / 2, width * originX);
    near((layout.lower.y + layout.upper.y) / 2, height * originY);
    near(layout.lower.x - layout.upper.x, size * .59 * Math.SQRT2);
    checks += 3;
}
near(geometry.visibleAxisLength({ x: 5, y: 5 }, 100, 10, 10), Math.sqrt(200));
near(geometry.visibleAxisLength({ x: 5, y: 5 }, 4, 10, 10), 4);
near(geometry.visibleAxisLength({ x: -50, y: -50 }, 4, 10, 10), 0);
checks += 4;
assert.ok(!/stage\.dataset\.(?:phase|broadcast)\s*=(?!=)|classList\.(?:add|remove|toggle)\(/.test(source), 'UI verification must use real actions, never fake status classes/datasets');
assert.ok(source.includes('7499 - start') && source.includes('__v36Advance(1)'), 'Recovery verifies 7499/7500 active-ms boundaries');
assert.ok(source.includes('full shortcut passage') && source.includes('keyId === keyId'), 'Complete shortcut glyphs must belong to the same repeated word');
checks += 3;
const glyph = (character, keyId = 0, visibleFraction = 1) => ({ character, keyId, visibleFraction });
const word = 'Win+K'.split('').map(character => glyph(character));
assert.deepEqual([...geometry.completeKeyIds(word)], [0]);
assert.deepEqual([...geometry.completeKeyIds([glyph('W'), glyph('i'), glyph('n', 1), glyph('+', 1), glyph('K', 1)])], [], 'Different repeated words cannot fabricate one whole shortcut');
assert.deepEqual([...geometry.completeKeyIds(word.map(item => item.character === 'i' ? { ...item, visibleFraction: .99 } : item))], [], 'Partially clipped ink is not a fully visible shortcut');
assert.deepEqual([...geometry.completeKeyIds(word.map(item => ({ ...item, keyId: -1 })))], [], 'Decorative letters are not shortcut glyphs');
assert.deepEqual([...geometry.enteringKeyIds([glyph('+'), glyph('K')])], [0]);
assert.deepEqual([...geometry.enteringKeyIds(word)], [], 'A word already fully present is not entry evidence');
assert.deepEqual([...geometry.enteringKeyIds([glyph('W'), glyph('i')])], [], 'An exiting word cannot stand in for an entering word');
assert.deepEqual([...geometry.enteringKeyIds([glyph('K'), glyph('W', 1)])], [0]);
assert.deepEqual([...geometry.completeKeyIds([...word, ...word.map(item => ({ ...item, keyId: 1 }))])], [0, 1]);
checks += 9;
const fullWidthPortrait = geometry.v35BandLayout(390, 844).lower;
const northWestPortrait = { x: fullWidthPortrait.x - 16 * Math.SQRT1_2, y: fullWidthPortrait.y - 16 * Math.SQRT1_2 };
near(geometry.visibleAxisLength(fullWidthPortrait, 844 * 1.6, 390, 844),
    geometry.visibleAxisLength(northWestPortrait, 844 * 1.6, 390, 844));
checks++;
assert.doesNotMatch(source, /\banimation\.(?:pause|play)\(/, 'Seeking must retain the real host/CSS pause gate, not override native play state');
checks++;

for (const [width, height] of [[1920, 1080], [2560, 720]]) {
    const axis = geometry.marqueeAxis(width, height);
    assert.ok(axis.start > axis.oldStart + 8 && axis.width > 0, width + 'x' + height + ' marquee entry leaves the old centered start');
    checks += 2;
}


async function verifyCleanup() {
    const modes = ['route', 'newPage', 'goto'];
    for (const mode of modes) {
        let contextClosed = false;
        let callerTouched = false;
        const expected = `injected ${mode} failure`;
        const fail = step => { if (step === mode) throw Error(expected); };
        const ownedPage = { on() {}, async goto() { fail('goto'); } };
        const context = { setDefaultTimeout() {}, setDefaultNavigationTimeout() {}, async route() { fail('route'); },
            async newPage() { fail('newPage'); return ownedPage; }, async close() { contextClosed = true; } };
        const browser = { async newContext() { return context; } };
        const sourcePage = { context: () => ({ browser: () => browser }), async goto() { callerTouched = true; }, async close() { callerTouched = true; } };
        const runner = factory.runInNewContext({}, { timeout: 1000 });
        const result = await runner(sourcePage);
        assert.equal(contextClosed, true, 'Owned context closes on setup failure');
        assert.equal(callerTouched, false, 'The supplied browser page remains untouched');
        assert.equal(result.ok, false);
        assert.equal(result.failures[0].message, expected);
    }
    return { injectedFailures: modes, ownedContextClosed: true, callerUntouched: true };
}

verifyCleanup().then(cleanup => {
    console.log(JSON.stringify({ ok: true, checks, geometry: 'actual UI helper block', viewportCases: sizes.length,
        cleanup, browserProof: 'PENDING parent run; syntax/unit checks do not prove visible UI' }, null, 2));
}).catch(error => {
    console.error(error);
    process.exitCode = 1;
});
