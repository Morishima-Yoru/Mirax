(() => {
    'use strict';

    const assert = require('node:assert/strict');
    const fs = require('node:fs');
    const path = require('node:path');
    const vm = require('node:vm');
    const crypto = require('node:crypto');
    const sha256 = value => crypto.createHash('sha256').update(value).digest('hex');
    const studyHashes = {
        '17-cathode.js': 'f1893c12bd8f94b1cac66435d11098db36f1f1748cb406931c42ba4b3f3cb526',
        '118-section-extrusion.js': '43ed31dc1debaaf58cf46cd7fe7b53e6a1d38d64d975d87a081c3f2d7ba30767',
        '209-cathode-extrusion.js': '44678823d73064b47ba4a9eda50d9a13ba5e1390092fdbb7054b9e997624930d'
    };
    for (const [file, expected] of Object.entries(studyHashes)) {
        assert.equal(sha256(fs.readFileSync(path.join(__dirname, 'big-standby-motion-prototype', file))), expected, file + ': original source bytes');
    }
    const host = fs.readFileSync(path.join(__dirname, 'big-standby-prototype.html'), 'utf8');
    const symbol = host.match(/<symbol id="mirax-mark"[\s\S]*?<\/symbol>/)?.[0] ?? '';
    const paths = [...symbol.matchAll(/<path\b[^>]*\sd="([^"]+)"/g)].map(match => match[1]);
    assert.equal(paths.length, 5, 'Four original leaves plus the central diamond');
    const vertices = JSON.parse(vm.runInNewContext(host.slice(host.indexOf('    const dArmVertices ='), host.indexOf('    const contourCurrents =')) + '\nJSON.stringify([...dArmVertices, dDiamondVertices])'));
    const shapeHash = sha256(JSON.stringify({ paths, vertices }));
    assert.equal(shapeHash, '7d46f43abb4ab833c16a32bdbb837a3691a3bf29c19b94788f0e5b1e916103b6', 'Exact original SVG paths and production projected vertices');
    const ui = fs.readFileSync(path.join(__dirname, 'big-standby-motion-prototype/verify-v36-ui.js'), 'utf8');
    const geometryStart = ui.indexOf('// Face sampling geometry begins.');
    const geometryEnd = ui.indexOf('// Face sampling geometry ends.');
    assert.ok(geometryStart >= 0 && geometryEnd > geometryStart);
    const insetContains = vm.runInNewContext(ui.slice(geometryStart, geometryEnd) + '\ninsetContains');
    const square = [{ x: 0, y: 0 }, { x: 20, y: 0 }, { x: 20, y: 20 }, { x: 0, y: 20 }];
    for (const polygon of [square, square.toReversed()]) {
        assert.equal(insetContains({ x: 10, y: 10 }, polygon, 4), true);
        assert.equal(insetContains({ x: 4, y: 10 }, polygon, 4), true);
        assert.equal(insetContains({ x: 3.9, y: 10 }, polygon, 4), false, 'Reject edge ink before sampling opaque faces');
        assert.equal(insetContains({ x: 21, y: 10 }, polygon, 0), false);
        assert.equal(insetContains({ x: 23.9, y: 10 }, polygon, -4), true, 'Exclude a four-pixel outer edge margin from wave evidence');
        assert.equal(insetContains({ x: 24.1, y: 10 }, polygon, -4), false);
    }
    const header = host.match(/<path d="M0 68H1600"[^>]+\/>/)?.[0] ?? '';
    assert.match(header, /stroke="#fff" stroke-width="2" vector-effect="non-scaling-stroke"/);
    assert.ok(host.indexOf(header) < host.indexOf('<g class="ambient x-plane">'), 'Original X group must paint after the unchanged-position header');
    assert.doesNotMatch(host, /M1390 0H1444V65H1390Z/, 'Remove the extraneous corner path, not any original X contour');
    assert.match(host, /#hardline-scene > svg:not\(\.d-motion-overlay\) \{ position: relative; z-index: 4;/);
    assert.match(host, /#hardline-scene > svg\.d-motion-overlay \{ position: fixed; z-index: 5;/);
    assert.match(host, /\.stage\.v-D \.device \{ position: absolute; top: auto; right: auto; left: var\(--edge-left\); bottom: var\(--edge-bottom\);/);
    assert.match(host, /max-width: calc\(50vw - var\(--edge-left\) - 54px\); box-sizing: border-box;/);
    assert.match(host, /padding: 0 0 0 10px; border: 0; border-left: 2px solid #eee;/);
    assert.match(host, /@media \(max-width: 440px\), \(orientation: portrait\) and \(max-width: 600px\) \{/);
    assert.match(host, /max-width: calc\(100vw - var\(--edge-left\) - var\(--edge-right\) - 24px\);/);
    assert.match(host, /\.stage\.v-D \.device-name \{ font-size: 14px; max-width: 100%; overflow: hidden; text-overflow: ellipsis; \}/);
    assert.doesNotMatch(host, /d-receiver-shift|right: calc\(var\(--edge-right\) \+ 72px\)|max-width: 460px/);
    assert.match(host, /\.stage\.v-D #standby-view \.brand \{ border-left: 2px solid #eee; padding: 0 12px 0 10px; \}/, 'D brand rule must beat the ID-bearing shared brand rule without important');
    assert.match(host, /\.stage\.v-D \.micro-label \{ font-size: 8px; margin-bottom: 3px; \}/);
    assert.match(host, /--brand-version-gap: 7px/, 'Do not shrink the unrelated brand version gap');
    const projectedFaces = vertices.map(polygon => polygon.map(([x, y]) => ({ x: 430 + x * 1370 / 108, y: -335 + y * 1370 / 108 })));
    const outsideStub = Array.from({ length: 27 }, (_, index) => ({ x: 1310, y: 6 + index * 2 }))
        .filter(point => !projectedFaces.some(polygon => insetContains(point, polygon, -8)));
    assert.ok(outsideStub.length >= 8, 'The mapped old left stub has genuine outside-face samples at 1440x1000, not X outline pixels');
    const exactBlock = (begin, end) => { const start = ui.indexOf(begin), stop = ui.indexOf(end); assert.ok(start >= 0 && stop > start); return ui.slice(start, stop); };
    const rwd = fs.readFileSync(path.join(__dirname, 'big-standby-motion-prototype/verify-rwd.js'), 'utf8');
    const brandStart = rwd.indexOf('// Brand section geometry begins.'), brandEnd = rwd.indexOf('// Brand section geometry ends.');
    assert.ok(brandStart >= 0 && brandEnd > brandStart);
    const brandSectionBounds = vm.runInNewContext(rwd.slice(brandStart, brandEnd) + '\nbrandSectionBounds');
    const brandReference = [[0, 0, 110, 33], [0, 1.5, 30, 30], [38, 0, 72, 33], [38, 25, 72, 8]], untouchedReference = JSON.stringify(brandReference);
    assert.deepEqual(JSON.parse(JSON.stringify(brandSectionBounds(brandReference))), [[0, 0, 134, 33], [12, 1.5, 30, 30], [50, 0, 72, 33], [50, 25, 72, 8]]);
    assert.match(host, /scale\(1\.4074074074\)/);
    assert.match(host, /r="30"/);
    assert.match(host, /y="13" width="86" height="70" rx="35"/);
    assert.doesNotMatch(host, /translate\(120 0\)/);
    assert.match(host, /id="d-corner-diamond"/);
    assert.match(host, /visibleHeight \* \.045/);
    assert.match(host, /\.stage\.v-D ~ #broadcast-dock \.broadcast-frame-d path \{ fill: #262a28; fill-opacity: 1; \}/);
    const presetBlock = host.slice(host.indexOf('const cLogPresets'), host.indexOf('let cHudTimer'));
    const cPresets = JSON.parse(vm.runInNewContext(presetBlock + '\nJSON.stringify(cLogPresets)'));
    const originalMeasurements = [
        [], ['> OPTICS: 421 THz'], ['> PHASE: <0.08%'], [],
        ['> DRIFT: -12ps/s', '> BUS: 0.42ms'], ['> APERTURE: 98.2%', '> REFRESH: 60fps'],
        ['> RF: -138dBm'], ['> COLLISIONS: 0%']
    ];
    const assertCLogPool = presets => {
        assert.equal(presets.length, 8, 'Retain eight C presets');
        for (const [index, preset] of presets.entries()) {
            assert.equal(preset.lines.length, 3, 'Retain three C body rows');
            for (const line of preset.lines) {
                assert.match(line, /^> [A-Z]/, 'C rows begin with machine-status keys');
                assert.doesNotMatch(line.replace(/THz|ps\/s|ms|fps|dBm/g, ''), /[a-z]|[.!?]$/, 'C rows are log fragments, not conversational sentences');
                assert.ok(line.length <= 20 && line === line.trim() && !/ {2,}/.test(line), 'Keep compact unpadded rows for the narrow index style');
                if (/\d/.test(line)) assert.ok(originalMeasurements[index].includes(line), 'Only original display-only measurement values and units');
            }
            for (const measurement of originalMeasurements[index]) {
                assert.ok(preset.lines.slice(0, 2).includes(measurement), 'Restore original display-only measurement in the first two rows: ' + measurement);
            }
        }
        const lengths = presets.map(preset => preset.lines.map(line => line.length));
        const orders = new Set(lengths.map(rows => rows.map(length => rows.filter(other => other < length).length).join('-')));
        const longestRows = [...new Set(lengths.map(rows => rows.indexOf(Math.max(...rows)) + 1))].sort();
        const allShortFrames = lengths.filter(rows => Math.max(...rows) <= 16).length;
        const mixedFrames = lengths.filter(rows => Math.max(...rows) > 16 && Math.max(...rows) - Math.min(...rows) >= 5).length;
        assert.ok(allShortFrames >= 2 && mixedFrames >= 2, 'Keep genuinely all-short frames alongside mixed-length frames');
        assert.ok(orders.size >= 4, 'Reject a uniform ascending or mechanically rotated length recipe');
        assert.deepEqual(longestRows, [1, 2, 3], 'The longest row must occur in different positions');
        return { presets: presets.length, bodyLines: presets.flatMap(preset => preset.lines).length,
            originalMeasurementRows: originalMeasurements.flat().length, allShortFrames, mixedFrames,
            lengthPatterns: orders.size, longestRows, maximumLineLength: Math.max(...lengths.flat()) };
    };
    const cLogSummary = assertCLogPool(cPresets);
    const rejectedConversationPool = [
        ['> Hold.', '> Lattice stays locked.', '> Polar rest keeps the open field quiet.'],
        ['> Sync.', '> Optics stay idle.', '> Spectral bus waits without a carrier.'],
        ['> Lock.', '> Curve stays even.', '> Gravity shell holds a steady phase.'],
        ['> Live.', '> Vector stays cold.', '> Relay cluster keeps the link balanced.'],
        ['> Drift.', '> Rail stays linked.', '> Chrono sketch shows no clock sample.'],
        ['> Idle.', '> Aperture stays open.', '> Kinetic coupler rests on a quiet bus.'],
        ['> Clear.', '> Carrier stays absent.', '> Sub radio listens on an empty mesh.'],
        ['> Safe.', '> Field stays matched.', '> Shell reports a quiet field, no collision.']
    ].map(lines => ({ lines }));
    assert.throws(() => assertCLogPool(rejectedConversationPool), { code: 'ERR_ASSERTION' }, 'Reject the supplied all-ascending conversational pool');
    assert.match(host, /top: \{ x: 0, y: dHeaderLower, width: canvasWidth, height: Math\.max\(0, upper - dHeaderLower\) \}/);
    assert.match(host, /bottom: \{ x: 0, y: lower, width: canvasWidth, height: Math\.max\(0, canvasHeight - lower\) \}/);
    assert.match(host, /studyContext\.rect\(0, frame\.field\.top\.y, frame\.width, Math\.max\(0, frame\.height - frame\.field\.top\.y\)\)/);
    assert.doesNotMatch(host, /const fieldBottom = Math\.min\(canvasHeight - 12/);
    const handleSource = host.slice(host.indexOf('function dStatusHandleTop('), host.indexOf('function applyAmbientTransforms()'));
    const dStatusHandleTop = vm.runInNewContext(handleSource + '\ndStatusHandleTop');
    assert.equal(dStatusHandleTop(0, 800, 2.4, true), 800 * .47 + 2.4);
    assert.ok(Math.abs(dStatusHandleTop(0, 800, 2.4, true) - 378.4) < 1e-9);
    assert.ok(Math.abs(400 - dStatusHandleTop(0, 800, 2.4, true) - 21.6) < 1e-9, 'The old 50% handle reproduces the known 800px error');
    assert.equal(dStatusHandleTop(0, 800, 2.4, false), null);
    assert.equal(dStatusHandleTop(Number.NaN, 800, 0, true), null);
    const oldFieldEnd = (height, dockTop) => Math.min(height - 12, dockTop - 12);
    assert.equal(oldFieldEnd(800, 704), 692, 'Parent 800px field stopped at 692');
    assert.ok(oldFieldEnd(800, 704) < 800);
    assert.equal(Math.max(0, 800 - 447.605316) > 244, true);
    assert.ok(ui.includes('const nativeFrame = createDStudyFrame;') && ui.includes('createDStudyFrame = nativeFrame;'));
    assert.equal(JSON.stringify(brandReference), untouchedReference, 'D allowances cannot mutate the shared A/C reference');
    assert.ok(rwd.includes("if (variant === 'D') {") && rwd.includes('const expected = brandSectionBounds(reference.bounds);'));
    const rangeGap = vm.runInNewContext(exactBlock('// Receiver gap geometry begins.', '// Receiver gap geometry ends.') + '\nreceiverRangeGap');
    const currentGap = rangeGap({ bottom: 14.40625 }, { top: 21.203125 }), oldGap = rangeGap({ bottom: 10.40625 }, { top: 21.203125 });
    assert.equal(oldGap - currentGap, 4, 'Measure the actual Range-gap difference, not the margin alone');
    assert.equal(rangeGap({ bottom: 24.65625 }, { top: 31.453125 }), currentGap, 'OLED translation cannot change the measured gap');
    assert.equal(rangeGap({ bottom: 0 }, { top: 0 }), 0, 'Empty ranges cannot fabricate a four-pixel shrink');
    assert.ok(ui.includes("label.style.setProperty('margin-bottom', '7px')") && ui.includes('spacing.old.gap - spacing.current.gap - 4'));
    const lineInkProfile = vm.runInNewContext(exactBlock('// Rule profile begins.', '// Rule profile ends.') + '\nlineInkProfile');
    const raster = (red, green = red, blue = red) => ({ width: 16, height: 16, pixels: Uint8ClampedArray.from({ length: 16 * 16 * 4 }, (_, index) => [red, green, blue, 255][index % 4]) });
    const glyphCounter = vm.runInNewContext(exactBlock('// Glyph paint mask begins.', '// Glyph paint mask ends.') + '\nglyphPaintPixels');
    const calibration = ui.match(/inkCutoff: (Math.ceil\(Math.min\(\.\.\.item.rgb\) \* \.6\))/)?.[1];
    assert.ok(calibration, 'Only receiver glyphs derive their AA threshold from computed CSS RGB');
    const inkCutoff = vm.runInNewContext(calibration, { item: { rgb: [203, 208, 203] } });
    assert.equal(inkCutoff, 122);
    const receiverGlyph = { character: 'RECEIVER', quad: square };
    assert.equal(glyphCounter(raster(178), raster(9), [receiverGlyph])[0].changedPixels, 0, 'Default white Win + K >=200 must still reject gray AA ink');
    assert.equal(glyphCounter(raster(178), raster(9), [{ ...receiverGlyph, inkCutoff }])[0].changedPixels, 256, 'Receiver-calibrated actual AA ink counts inside bounded quads');
    assert.equal(glyphCounter(raster(9), raster(9), [{ ...receiverGlyph, inkCutoff }])[0].changedPixels, 0, 'Absent receiver text cannot earn ink coverage');
    assert.equal(glyphCounter(raster(178), raster(163), [{ ...receiverGlyph, inkCutoff }])[0].changedPixels, 0, 'Preserve channel delta >=16');
    assert.equal(glyphCounter(raster(178), raster(162), [{ ...receiverGlyph, inkCutoff }])[0].changedPixels, 256);
    assert.equal(glyphCounter(raster(199), raster(9), [receiverGlyph])[0].changedPixels, 0);
    assert.equal(glyphCounter(raster(200), raster(9), [receiverGlyph])[0].changedPixels, 256, 'Default white threshold remains exactly 200');
    const thick = raster(9), thin = raster(9);
    for (let x = 0; x < 16; x++) for (const y of [7, 8]) for (const channel of [0, 1, 2]) {
        thick.pixels[(y * 16 + x) * 4 + channel] = 255;
        thin.pixels[(y * 16 + x) * 4 + channel] = 128;
    }
    const point = { x: 8.5, y: 8 };
    assert.equal(lineInkProfile(thick, point).energy, 2, 'Integrate actual two-pixel white coverage, not CSS width alone');
    assert.ok(lineInkProfile(thick, point).energy > lineInkProfile(thin, point).energy + .4, 'Fractional antialiasing cannot fabricate equal thickness');
    assert.equal(lineInkProfile(raster(9), point).peak, 9);
    assert.equal(lineInkProfile(raster(255, 0, 255), point).energy, 0, 'A colored wave/probe is not white header ink');
    assert.ok(Number.isFinite(lineInkProfile(thick, { x: 0, y: 0 }).energy), 'Clip raster edges');
    const vertical = raster(9);
    for (let y = 0; y < 16; y++) for (const x of [7, 8]) for (const channel of [0, 1, 2]) vertical.pixels[(y * 16 + x) * 4 + channel] = 255;
    assert.equal(lineInkProfile(vertical, { x: 8, y: 8.5 }, { x: 1, y: 0 }).energy, 2);
    const sectionBar = { ...vertical, pixels: vertical.pixels.map((value, index) => index % 4 !== 3 && value === 255 ? 238 : value) };
    const barGlyph = { character: 'left-rule', quad: [{ x: 7, y: 0 }, { x: 9, y: 0 }, { x: 9, y: 16 }, { x: 7, y: 16 }] };
    assert.equal(glyphCounter(sectionBar, raster(9), [barGlyph])[0].changedPixels, 32, 'Actual #eee two-pixel section rule earns bounded white PNG coverage');
    assert.equal(glyphCounter(raster(9), raster(9), [barGlyph])[0].changedPixels, 0, 'Transparent-border control cannot earn left-rule coverage');
    const matchingRules = [barGlyph, { ...barGlyph, character: 'brand-left-rule' }];
    assert.deepEqual([...glyphCounter(sectionBar, raster(9), matchingRules)].map(rule => rule.changedPixels), [32, 32]);
    assert.deepEqual([...glyphCounter(raster(9), raster(9), matchingRules)].map(rule => rule.changedPixels), [0, 0], 'Both receiver/brand borders need real PNG evidence');
    assert.ok(ui.includes('backing.leftRule.length === (width === 896 ? 2 : 1)') && ui.includes('backing.leftRule.every(rule => rule.changedPixels >= 8)'));
    assert.equal(lineInkProfile(raster(9), { x: 8, y: 8.5 }, { x: 1, y: 0 }).peak, 9, 'A removed stub leaves the quiet baseline dark');
    const backingMask = exactBlock('// Receiver backing mask begins.', '// Receiver backing mask ends.');
    const normal = raster(9), blocked = raster(9), exposed = raster(255, 0, 255), rect = { left: 0, top: 0, right: 16, bottom: 16 };
    const backing = probe => vm.runInNewContext(backingMask + '\n({qualified, maxDelta})', { normal, blocked: probe, exposed, rect });
    assert.equal(backing(blocked).qualified, 144, 'Require genuine exposed magenta behind the receiver, excluding its two-pixel edge');
    assert.equal(backing(blocked).maxDelta, 0);
    blocked.pixels[(8 * 16 + 8) * 4] = 255;
    assert.equal(backing(blocked).maxDelta, 246, 'A leaking backing must fail the exact max-delta sampler');
    exposed.pixels.fill(9);
    assert.equal(backing(blocked).qualified, 0);
    assert.ok(ui.includes('backing.qualified >= 40') && ui.includes('backing.maxDelta <= 3'), 'Zero qualification must never pass');
    assert.ok(ui.includes('fixed.every(glyph => glyph.changedPixels < 2)') && ui.includes('textAbove.every(glyph => glyph.changedPixels >= 2)'), 'Whole-X intent requires a hidden word plus a stack-only visible negative control');
    const source = fs.readFileSync(path.join(__dirname, 'big-standby-e-scene.js'), 'utf8');
    const css = fs.readFileSync(path.join(__dirname, 'big-standby-e.css'), 'utf8');
    const channels = Array.from({ length: 2 }, () => ({ style: {
        values: new Map(), setProperty(name, value) { this.values.set(name, value); }
    } }));
    const attributes = {};
    const root = {
        setAttribute(name, value) { attributes[name] = value; },
        querySelectorAll(selector) { assert.equal(selector, '.e-channel'); return channels; }
    };
    let seed = 0xD035;
    const math = Object.create(Math);
    math.random = () => ((seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0) / 2 ** 32);
    const sandbox = { window: {}, document: { getElementById: () => root }, Math: math };
    vm.runInNewContext(source, sandbox);
    const controller = sandbox.window.MiraxDStreams;
    const snapshot = () => JSON.parse(JSON.stringify(controller.snapshot()));
    const [upper, lower] = root.innerHTML.split('<div class="e-channel e-diagonal e-channel--lower"');
    assert.equal(attributes['aria-hidden'], 'true');
    assert.equal((upper.match(/class="e-stream-row(?: e-stream-row--status)?"/g) || []).length, 3);
    assert.equal((lower.match(/class="e-stream-row"/g) || []).length, 2);
    assert.equal((upper.match(/class="e-stream-track"/g) || []).length, 10);
    assert.equal((lower.match(/class="e-stream-track"/g) || []).length, 2);
    assert.equal((upper.match(/e-stream-row--status/g) || []).length, 1);
    assert.ok(upper.lastIndexOf('class="e-stream-row"') < upper.indexOf('e-stream-row--status'));
    assert.doesNotMatch(lower, /e-stream-row--status|e-status-message/);
    assert.ok(upper.indexOf('display-only register sketch') < upper.indexOf('0xD030:'));
    assert.doesNotMatch(upper, /no input sampled|decor seq/);
    assert.ok(lower.indexOf('no input sampled') < lower.indexOf('decor seq'));
    const expectedStatus = {
        off: 'Press the broadcast button to start receiving',
        arming: 'Starting · Not ready',
        'wifi-paused': 'Wi-Fi is off · Please turn on Wi-Fi to broadcast',
        frozen: 'Receiver unavailable · Check settings',
        waiting: 'Windows: press Win + K · Choose MIRAX · 客廳',
        connecting: 'Connecting · Please wait',
        failed: 'Connection failed · Press Win + K to retry',
        projecting: 'Connected · Receiving projection'
    };
    const statusMessages = [...upper.matchAll(/<div class="e-status-message" data-status="([^"]+)">([\s\S]*?)<\/div><\/div>/g)];
    assert.deepEqual(statusMessages.map(match => match[1]), Object.keys(expectedStatus));
    for (const [, phase, markup] of statusMessages) {
        assert.equal(markup.replace(/<[^>]+>/g, ''), expectedStatus[phase].repeat(16));
        const stateSelector = `.stage[data-phase="${phase}"]`;
        assert.ok(css.includes(`${stateSelector} #e-scene .e-status-message[data-status="${phase}"]`));
        if (['off', 'arming', 'wifi-paused', 'frozen'].includes(phase)) assert.doesNotMatch(markup, /Win \+ K|e-key-hint/);
        if (phase === 'waiting' || phase === 'failed') assert.match(markup, /class="e-key-hint">Win \+ K<\/span>/);
    }
    const copies = [...root.innerHTML.matchAll(/<div class="e-stream-track">([\s\S]*?)<\/div>/g)].map(track => {
        const segments = [...track[1].matchAll(/<span class="e-copy">([\s\S]*?)<\/span><\/span>(?=<span class="e-stream-segment">|$)/g)];
        assert.equal(segments.length, 2);
        assert.equal(segments[0][1], segments[1][1]);
        return segments[0][1];
    });
    assert.equal(new Set(copies).size, 12);
    assert.match(css, /e-command-flow 240s linear infinite/);
    assert.match(css, /animation-duration: 360s/);
    assert.ok(css.includes('from { transform: translate3d(-50%, 0, 0); }'));
    assert.ok(css.includes('to { transform: translate3d(0, 0, 0); }'));
    assert.doesNotMatch(css + source, /prefers-reduced-motion|ease-in-out|requestAnimationFrame|setTimeout|setInterval/);
    assert.ok(css.includes('opacity: var(--e-shot-alpha, 0)'));
    assert.ok(css.includes('var(--e-shot-position, 0)'));
    assert.doesNotMatch(css + source, /e-border-scan|e-hint-copy|e-stream-row--hint|9\.5px/);
    assert.doesNotMatch(css, /font[^;{}]*\d(?:vw|vh|vmin|vmax)/);

    // The grid stays window-sized while both equal marquee segments retain their full width.
    const streamWindowRule = css.match(/#e-scene \.e-stream-window \{([^}]+)\}/)?.[1] ?? '';
    assert.match(streamWindowRule, /grid-template-columns:\s*minmax\(0,\s*1fr\)/);
    assert.match(streamWindowRule, /overflow:\s*hidden/);
    assert.match(css, /#e-scene \.e-stream-track \{[^}]*width:\s*max-content/);
    const statusRule = css.match(/#e-scene \.e-stream-row--status \{([^}]+)\}/)?.[1] ?? '';
    assert.match(statusRule, /font-size:\s*calc\(12px \+ 2pt\)/);
    assert.match(statusRule, /font-weight:\s*700/);
    assert.match(statusRule, /line-height:\s*24px/);
    assert.match(statusRule, /height:\s*24px/);
    assert.match(statusRule, /color:\s*#f2f2f2/);
    assert.ok(24 + 16 + 3 + 2 <= 52, 'Both rows, gap and borders fit the enlarged lower band');
    assert.ok(css.includes('--e-channel-offset: calc(var(--e-channel-size) * .59 - 16px)'));
    assert.ok(css.includes('--e-channel-height: 52px'));
    assert.ok(css.includes('opacity: 0; visibility: hidden; transition: opacity 180ms ease'));
    assert.ok(css.includes('--e-axis-start:'));
    assert.ok(css.includes('.stage[data-broadcast="true"][data-phase="failed"] #e-scene .e-stream-row--status { color: #ff6b6b; }'));
    assert.ok(css.includes('.stage[data-phase="frozen"] #e-scene .e-stream-row--status { color: #ff6b6b; }'));
    for (const selector of ['.stage[data-home="simple"]', '.stage[data-phase="projecting"]', '.stage.page-hidden', '.stage.has-media']) {
        assert.ok(css.includes(`${selector} #e-scene .e-stream-row--status`));
    }
    const periods = { off: 148, arming: 68, 'wifi-paused': 144, frozen: 112, waiting: 144, connecting: 84, failed: 140, projecting: 108 };
    for (const [phase, seconds] of Object.entries(periods)) {
        assert.ok(css.includes(`.e-status-message[data-status="${phase}"] { --e-status-duration: ${seconds}s; }`));
        assert.ok(seconds / 8 < 20, 'Each phrase returns within twenty seconds');
    }
    assert.ok(css.includes('#e-scene .e-channel--upper .e-stream-row--status .e-stream-track { animation-duration: var(--e-status-duration); }'));

    const shots = new Map();
    const previous = new Map();
    const completed = [];
    let accelerationChecks = 0;
    controller.setActive(true);
    for (let frame = 0; frame < 2400; frame += 1) {
        controller.update(1 / 60);
        const state = snapshot();
        assert.equal(state.streams.length, 4);
        assert.ok(state.history.length <= 32);
        for (const shot of state.history) shots.set(`${shot.name}:${shot.shotIndex}`, shot);
        for (const stream of state.streams) {
            for (const key of ['gapSeconds', 'durationSeconds', 'elapsedSeconds', 'progress', 'position', 'alpha']) {
                assert.ok(Number.isFinite(stream[key]), `${stream.name}.${key}`);
            }
            assert.ok(stream.alpha >= 0 && stream.alpha <= 1);
            assert.ok(stream.position >= 0 && stream.position <= 1);
            assert.ok(Math.abs(stream.position - stream.progress ** 2) < 1e-12);
            const style = channels[stream.name.startsWith('upper') ? 0 : 1].style;
            assert.equal(Number(style.values.get(`--e-${stream.edge}-position`)), stream.position);
            assert.equal(Number(style.values.get(`--e-${stream.edge}-alpha`)), stream.alpha);
            const prev = previous.get(stream.name);
            if (stream.phase === 'waiting' && prev?.phase === 'shot') {
                const observed = state.elapsedSeconds - prev.startedAtSeconds;
                assert.ok(observed >= prev.durationSeconds && observed < prev.durationSeconds + 1 / 60 + 1e-10);
                completed.push(observed);
            }
            const continuous = stream.phase === 'shot' && prev?.phase === 'shot' && stream.shotIndex === prev.shotIndex;
            const movement = continuous ? stream.position - prev.position : null;
            if (continuous && prev.movement !== null) {
                assert.ok(movement > prev.movement - 1e-12, 'Travel must keep accelerating');
                accelerationChecks += 1;
            }
            previous.set(stream.name, { ...stream, movement });
        }
    }
    assert.ok(accelerationChecks > 1000);
    for (const stream of snapshot().streams) {
        const emissions = [...shots.values()].filter(shot => shot.name === stream.name);
        assert.ok(emissions.length >= 15);
        assert.ok(new Set(emissions.map(shot => shot.gapSeconds)).size === emissions.length);
        assert.ok(new Set(emissions.map(shot => shot.durationSeconds)).size === emissions.length);
        for (const shot of emissions) {
            assert.ok(shot.gapSeconds >= .12 && shot.gapSeconds < .90);
            assert.ok(shot.durationSeconds >= .95 && shot.durationSeconds < 1.60);
        }
        const intervals = emissions.slice(1).map((shot, index) => shot.startedAtSeconds - emissions[index].startedAtSeconds);
        assert.ok(new Set(intervals.map(interval => interval.toFixed(4))).size > intervals.length / 2);
    }
    controller.setActive(false);
    const paused = snapshot();
    for (let frame = 0; frame < 100; frame += 1) controller.update(3600);
    assert.deepEqual(snapshot(), paused);
    controller.setActive(true);
    const resumed = snapshot();
    controller.update(3600);
    assert.deepEqual(snapshot(), resumed, 'Discard the first resumed wall-clock delta');
    for (const delta of [NaN, Infinity, -Infinity, -1, 0]) controller.update(delta);
    assert.deepEqual(snapshot(), resumed);
    controller.update(3600);
    assert.ok(Math.abs(snapshot().elapsedSeconds - resumed.elapsedSeconds - .1) < 1e-12);

    const emissions = [...shots.values()];
    const durations = emissions.map(shot => shot.durationSeconds);
    const gaps = emissions.map(shot => shot.gapSeconds);
    const range = values => [Math.min(...values), Math.max(...values)].map(value => +value.toFixed(3));
    console.log(JSON.stringify({
        shots: emissions.length, accelerationChecks,
        cLog: { ...cLogSummary, rejectedConversationPools: 1 },
        durationSeconds: range(durations), observedDurationSeconds: range(completed),
        gapSeconds: range(gaps),
        fasterThan4_8Seconds: range(completed.map(duration => 4.8 / duration)),
        pause: 'frozen', resume: 'no catch-up', historyLimit: 32
    }, null, 2));
})();
