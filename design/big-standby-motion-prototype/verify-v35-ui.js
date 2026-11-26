// Run with Playwright browser_run_code_unsafe; the supplied page is never navigated or closed.
async (sourcePage) => {
    const browser = sourcePage.context().browser();
    if (!browser) throw Error('V35 requires a live browser; no shared-page fallback is permitted');
    const base = 'http://127.0.0.1:8765';
    const startedAt = Date.now();
    const initialContexts = new Set(browser.contexts());
    const ownedContexts = new Set();
    const failures = [];
    const pageErrors = [];
    const blockedWrites = [];
    const results = [];
    const sizes = [[320, 568], [390, 844], [480, 375], [896, 592], [1440, 1000], [2560, 720]];
    const styles = ['rail', 'frame', 'cap', 'index', 'footnote', 'bracket', 'telemetry', 'stamp', 'terminal', 'fold'];
    const baselineSha256 = '949fad98b0c273b402ee71d5540f5dc7f0996a039dedfb23942449000fb0ef5f';
    // Geometry copied from tidy-squid-v34-baseline.html, identified by baselineSha256.
    const baselineArt = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="84" height="84" style="position:fixed;left:-1000px;top:0;overflow:visible">' +
        '<g class="broadcast-frame-a"><rect x="7" y="25" width="86" height="46" rx="23"/></g>' +
        '<g class="broadcast-frame-c"><polygon points="12,18 88,18 50,83.81793"/><path d="M10 28 38 76.49742"/></g>' +
        '<g class="broadcast-frame-d"><path d="M34 18H86V58L62 82H10V42Z"/></g>' +
        '<g class="broadcast-symbol-play"><g class="broadcast-glyph-default"><path d="m50 35 11 20H39Z"/></g>' +
        '<g class="broadcast-glyph-a"><path d="M44 36Q42 35 42 38V58Q42 61 44 60L61 50Q64 48 61 46Z"/></g></g>' +
        '<g class="broadcast-symbol-stop"><g class="broadcast-glyph-default"><path d="m40 37 20 20m0-20L40 57"/></g>' +
        '<g class="broadcast-glyph-a"><rect x="39" y="37" width="22" height="22" rx="4"/></g></g>' +
        '<g class="broadcast-symbol-warning"><path d="M50 33v18"/><circle cx="50" cy="59" r="1.6"/></g></svg>';
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const near = (a, b, tolerance = .6) => Math.abs(a - b) <= tolerance;
    const run = async (name, test) => {
        check(Date.now() - startedAt < 58000, 'V35 exceeded its 58-second work budget');
        try { results.push({ name, result: await test() }); }
        catch (error) { failures.push({ name, message: String(error.message || error) }); }
    };
    const createProbe = async options => {
        const context = await browser.newContext({ serviceWorkers: 'block', ...options });
        ownedContexts.add(context);
        context.setDefaultTimeout(1800);
        context.setDefaultNavigationTimeout(6000);
        await context.route('**/*', async route => {
            const request = route.request();
            const method = request.method();
            if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
                blockedWrites.push({ method, url: request.url() });
                await route.fulfill({ status: 405, contentType: 'application/json', body: '{"error":"read-only verification"}' });
            } else if (new URL(request.url()).pathname === '/api/d-reviews') {
                await route.fulfill({ status: 200, contentType: 'application/json', body: '{"schemaVersion":1,"votes":{},"notes":{}}' });
            } else await route.continue();
        });
        const probe = await context.newPage();
        probe.on('pageerror', error => pageErrors.push(error.message));
        probe.on('console', message => {
            if (message.type() === 'error' || message.type() === 'assert') pageErrors.push(message.text());
        });
        await probe.goto(base + '/big-standby-prototype.html?variant=A&v=41');
        await probe.evaluate(() => { cGyroscope.disable(); });
        return { context, probe };
    };
    const frames = probe => probe.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const finishButton = probe => probe.evaluate(() => {
        for (const animation of document.querySelector('#broadcast-dock').getAnimations({ subtree: true })) {
            if (Number.isFinite(animation.effect.getComputedTiming().endTime)) animation.finish();
        }
    });
    const reviewHash = async ({ context, probe }) => {
        const response = await context.request.get(base + '/big-standby-motion-prototype/review-votes.json');
        check(response.ok(), 'Read-only review fixture must be served for SHA-256 verification');
        const text = await response.text();
        return probe.evaluate(async value => {
            const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
            return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('');
        }, text);
    };
    let primary;
    let reviewBefore;
    let reviewAfter;
    try {
        primary = await createProbe({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1, reducedMotion: 'no-preference' });
        reviewBefore = await reviewHash(primary);
        await run('initial off, no persistent writes', async () => {
            const value = await primary.probe.evaluate(() => ({ phase: state.phase, broadcast: state.broadcast,
                checked: document.querySelector('#broadcast-toggle').getAttribute('aria-checked'),
                tools: state.prototypeTools, loaded: dReviews.loaded }));
            check(value.phase === 'off' && !value.broadcast && value.checked === 'false', 'Fresh contexts must initially be off');
            check(!value.tools, 'Prototype tools must remain opt-in');
            return value;
        });

        const densityReference = new Map();
        for (const density of [1, 3]) {
            const setup = density === 1 ? primary : await createProbe({ viewport: { width: 390, height: 844 },
                deviceScaleFactor: density, isMobile: true, hasTouch: true, reducedMotion: 'reduce' });
            const { probe, context } = setup;
            await run(`geometry, reference fonts, backgrounds @${density}x`, async () => {
                let layouts = 0;
                for (const [width, height] of sizes) {
                    await probe.setViewportSize({ width, height });
                    for (const variant of ['A', 'C', 'D']) {
                        await probe.evaluate(value => { selectVariant(value); state.motion.paused = true; render(); resizeCanvas(); }, variant);
                        await frames(probe);
                        await finishButton(probe);
                        const sample = await probe.evaluate(({ baselineArt, variant }) => {
                            const reference = new DOMParser().parseFromString(baselineArt, 'image/svg+xml').documentElement;
                            const symbol = document.querySelector('#broadcast-toggle').dataset.symbol;
                            for (const name of ['play', 'stop', 'warning']) {
                                const node = reference.querySelector('.broadcast-symbol-' + name);
                                node.style.transform = `translateY(${variant === 'C' ? -7 : 0}px) scale(${name === symbol ? 1 : .72})`;
                                node.style.transformOrigin = '50px 48px';
                            }
                            // Isolate old geometry from the new host's global frame/icon CSS.
                            const referenceHost = document.createElement('div');
                            referenceHost.attachShadow({ mode: 'closed' }).append(reference);
                            document.body.append(referenceHost);
                            try {
                                const rect = node => { const value = node.getBoundingClientRect(); return {
                                    x: value.x, y: value.y, width: value.width, height: value.height,
                                    left: value.left, top: value.top, right: value.right, bottom: value.bottom }; };
                                const art = document.querySelector('.broadcast-art');
                                const ownArt = rect(art);
                                const refArt = rect(reference);
                                const shape = node => [...node.querySelectorAll('path,rect,polygon,circle')].map(child =>
                                    [child.tagName.toLowerCase(), ...['d', 'points', 'x', 'y', 'width', 'height', 'rx', 'cx', 'cy', 'r'].map(name => child.getAttribute(name))]);
                                const geometry = selector => {
                                    const current = document.querySelector('#broadcast-dock ' + selector);
                                    const old = reference.querySelector(selector);
                                    const actual = rect(current);
                                    const baseline = rect(old);
                                    return { shapes: JSON.stringify(shape(current)) === JSON.stringify(shape(old)), actual, baseline,
                                        dx: actual.x - ownArt.x - (baseline.x - refArt.x),
                                        dy: actual.y - ownArt.y - (baseline.y - refArt.y) };
                                };
                                const root = getComputedStyle(document.documentElement);
                                const fonts = ['.brand-name', '.brand small', '.device-name', '#status-title', '#status-eyebrow',
                                    '#connection-hint', '.c-standby-word', '.c-standby-time', '.c-hud-body'].map(selector => {
                                    const value = getComputedStyle(document.querySelector(selector));
                                    return [selector, value.fontSize, value.fontFamily, value.lineHeight];
                                });
                                const button = rect(document.querySelector('#broadcast-toggle'));
                                const background = getComputedStyle(document.querySelector('#stage')).backgroundColor;
                                const center = rect(document.querySelector('#standby-view .center'));
                                const standbyBounds = rect(document.querySelector('#standby-view'));
                                const centerAnchorY = center.y + center.height / 2 - standbyBounds.top;
                                const layoutHeight = document.querySelector('#stage').clientHeight;
                                const scale = Math.max(innerWidth / 1600, innerHeight / 1000);
                                const visibleHeight = innerHeight / scale;
                                const visibleTop = (1000 - visibleHeight) / 2;
                                const oldY = Math.max(visibleTop + 48, Math.min(visibleTop + visibleHeight * .25, 250));
                                const celestial = document.querySelector('.a-celestial').transform.baseVal.consolidate().matrix;
                                const framePaint = getComputedStyle(document.querySelector('#broadcast-dock .broadcast-frame-' + variant.toLowerCase()).firstElementChild);
                                return { art: ownArt, button, background, fonts, center, centerAnchorY, layoutHeight, standbyBounds,
                                    moon: rect(document.querySelector('.a-moon')),
                                    pill: ['x', 'y', 'width', 'height', 'rx'].map(name => document.querySelector('.broadcast-frame-a rect').getAttribute(name)).join(','),
                                    celestial: { x: celestial.e, y: celestial.f + 204, oldY },
                                    frameFill: framePaint.fill, frameFillOpacity: Number(framePaint.fillOpacity),
                                    frame: geometry('.broadcast-frame-' + variant.toLowerCase()),
                                    glyphs: ['.broadcast-symbol-play .broadcast-glyph-a', '.broadcast-symbol-play .broadcast-glyph-default',
                                        '.broadcast-symbol-stop .broadcast-glyph-a', '.broadcast-symbol-stop .broadcast-glyph-default', '.broadcast-symbol-warning'].map(geometry),
                                    viewport: document.querySelector('meta[name=viewport]').content,
                                    locked: document.documentElement.dataset.sizeLock,
                                    rootFont: root.fontSize, adjust: root.textSizeAdjust || root.webkitTextSizeAdjust,
                                    scale: visualViewport.scale,
                                    fields: [...document.querySelectorAll('#settings input:not([type=checkbox],[type=radio]), #settings select, #settings textarea')]
                                        .map(node => Number.parseFloat(getComputedStyle(node).fontSize)) };
                            } finally { referenceHost.remove(); }
                        }, { baselineArt, variant });
                        const label = `${variant} ${width}x${height} @${density}x`;
                        check(near(sample.art.width, 84) && near(sample.art.height, 84), label + ': SVG CSS size stays 84px');
                        const buttonSize = variant === 'D' ? 84 : 96;
                        check(near(sample.button.width, buttonSize) && near(sample.button.height, buttonSize), label + ': A/C hit target grows to 96px; D stays 84px');
                        check(sample.pill === '7,13,86,70,35', label + ': A pill is taller at the same width');
                        check((variant === 'A' ? !sample.frame.shapes : sample.frame.shapes) && sample.glyphs.every(value => value.shapes), label + ': C/D frame geometry and every glyph stay on the original paths');
                        const ratios = [sample.frame.actual.width / sample.frame.baseline.width, sample.frame.actual.height / sample.frame.baseline.height];
                        if (variant === 'A') check(ratios[0] >= 1.10 && ratios[0] <= 1.15 && ratios[1] >= 1.66 && ratios[1] <= 1.75, label + ': A pill grows in height only');
                        else check(ratios.every(value => variant === 'D' ? near(value, 1, .008) : value >= 1.10 && value <= 1.15), label + ': enlarge only the unchanged C frame by 10-15%');
                        if (variant === 'D') check(sample.frameFill.replace(/\s+/g, '') === 'rgb(38,42,40)' && sample.frameFillOpacity === 1, label + ': D frame fill is opaque #262a28');
                        else check(sample.frameFill.replace(/\s+/g, '') === (variant === 'A' ? 'rgb(25,59,48)' : 'rgb(38,42,40)') && sample.frameFillOpacity >= .85, label + ': A/C frames retain their solid theme fill');
                        for (const glyph of sample.glyphs) {
                            check(near(glyph.actual.width, glyph.baseline.width, .12) && near(glyph.actual.height, glyph.baseline.height, .12), label + ': icon must not inherit frame growth');
                            check(near(glyph.dx, variant === 'D' ? -2 : 0, .7) && near(glyph.dy, variant === 'D' ? 2 : 0, .7), label + ': D icon moves left/down about 2 CSS px only');
                        }
                        const rgb = sample.background.match(/[\d.]+/g)?.map(Number) || [];
                        check(rgb.length >= 3 && rgb.slice(0, 3).some(value => value > 0) && (rgb.length < 4 || rgb[3] > 0), label + ': visible stage background must not be pure black/transparent');
                        check(sample.rootFont === '15px' && sample.adjust === '100%' && sample.fields.every(value => value >= 16), label + ': retain reference fonts and 16px form controls');
                        check(sample.locked === 'true' && /maximum-scale=1/.test(sample.viewport) && /user-scalable=no/.test(sample.viewport) && near(sample.scale, 1, .01), label + ': presentation zoom remains locked');
                        const key = `${variant}/${width}/${height}`;
                        const fingerprint = JSON.stringify(sample.fonts);
                        if (density === 1) densityReference.set(key, fingerprint);
                        else check(densityReference.get(key) === fingerprint, label + ': density must not change CSS text sizes');
                        if (variant === 'A') {
                            if (width > height) {
                                check(sample.moon.left >= -1 && sample.moon.right <= width + 1 && sample.moon.top >= -1 && sample.moon.bottom <= height + 1, label + ': enlarged night moon stays fully inside');
                                const oldCenter = sample.layoutHeight * (height <= 820 ? .51 : .49);
                                check(sample.centerAnchorY < oldCenter - 2, label + ': landscape status must move up: ' + JSON.stringify({ actual: sample.centerAnchorY, oldCenter }));
                            } else {
                                check(sample.celestial.y > sample.celestial.oldY + 8, label + ': portrait sun/moon sit lower than the previous anchor');
                                // v34 short portrait is 47%, normal portrait 49%; retained OLED translation is not the CSS anchor.
                                const oldCenter = sample.layoutHeight * (width <= 600 && height <= 820 ? .47 : .49);
                                check(near(sample.centerAnchorY, oldCenter, 1.5), label + ': portrait status anchor stays fixed: ' + JSON.stringify({ actual: sample.centerAnchorY, oldCenter, center: sample.center, standbyBounds: sample.standbyBounds }));
                            }
                        }
                        layouts++;
                    }
                }
                return { layouts };
            });
            await run(`zoom lock and accessible escape @${density}x`, async () => {
                await probe.setViewportSize({ width: 390, height: 844 });
                await probe.evaluate(() => { selectVariant('A'); state.motion.paused = false; render(); });
                const blocked = await probe.evaluate(() => [
                    document.dispatchEvent(new KeyboardEvent('keydown', { key: '+', ctrlKey: true, bubbles: true, cancelable: true })),
                    document.dispatchEvent(new WheelEvent('wheel', { deltaY: -100, ctrlKey: true, bubbles: true, cancelable: true }))]);
                check(blocked.every(value => !value), 'Locked presentation consumes keyboard/wheel zoom');
                let pinch;
                if (density === 3) {
                    const cdp = await context.newCDPSession(probe);
                    pinch = async () => {
                        await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 145, y: 350, id: 1 }, { x: 245, y: 350, id: 2 }] });
                        for (let step = 1; step <= 6; step++) {
                            await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 145 - step * 7, y: 350, id: 1 }, { x: 245 + step * 7, y: 350, id: 2 }] });
                            await frames(probe);
                        }
                        await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
                        await frames(probe);
                        return probe.evaluate(() => visualViewport.scale);
                    };
                    check(near(await pinch(), 1, .01), 'Locked native pinch must not scale');
                }
                await probe.locator('#settings-handle').click();
                await probe.locator('#preview-size-lock').uncheck();
                await probe.locator('#close-settings').click();
                await probe.waitForFunction(() => !document.querySelector('#settings').open);
                check(await probe.evaluate(() => document.documentElement.dataset.sizeLock === 'false'), 'The zoom escape must operate');
                if (pinch) check(await pinch() > 1.1, 'Unlocked native pinch must actually scale');
                await probe.evaluate(() => { state.sizeLocked = true; render(); });
                if (density === 3) {
                    const cdp = await context.newCDPSession(probe);
                    await cdp.send('Emulation.setPageScaleFactor', { pageScaleFactor: 1 });
                }
                return { nativePinch: Boolean(pinch) };
            });
        }

        const { probe } = primary;
        await probe.bringToFront();
        await run('C footer follows measured action half-width/top plus its 16px gap', async () => {
            await probe.evaluate(() => { selectVariant('C'); state.home = 'big'; state.motion.paused = true; render(); });
            const evidence = [];
            try {
                for (const [width, height] of sizes) {
                    await probe.setViewportSize({ width, height });
                    const result = await probe.evaluate(() => {
                        const failures = [];
                        const counts = { aligned: 0, stacked: 0 };
                        let inkChecks = 0;
                        let fontBoxOverhangs = 0;
                        const canvas = document.createElement('canvas'); const context = canvas.getContext('2d');
                        const inside = (rect, bounds) => rect.left >= bounds.left - 1 && rect.right <= bounds.right + 1 && rect.top >= bounds.top - 1 && rect.bottom <= bounds.bottom + 1;
                        const insideHorizontal = (rect, bounds) => rect.left >= bounds.left - 1 && rect.right <= bounds.right + 1;
                        function footerInkBounds(bounds, metrics) {
                            const baseline = bounds.top + (bounds.height - metrics.fontBoundingBoxAscent - metrics.fontBoundingBoxDescent) / 2 + metrics.fontBoundingBoxAscent;
                            return { left: bounds.left, right: bounds.right, top: baseline - metrics.actualBoundingBoxAscent, bottom: baseline + metrics.actualBoundingBoxDescent };
                        }
                        for (const phase of ['off', 'waiting', 'connecting', 'failed']) {
                            if (phase === 'off') { if (state.broadcast) toggleBroadcast(); }
                            else {
                                if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); }
                                if (phase === 'connecting' || phase === 'failed') setPhase(phase);
                            }
                            render(); layoutCCornerInformation();
                            const action = document.querySelector('#broadcast-toggle').getBoundingClientRect();
                            const stageBounds = stage.getBoundingClientRect();
                            const viewBounds = document.querySelector('#standby-view').getBoundingClientRect();
                            const shift = { x: viewBounds.left - stageBounds.left, y: viewBounds.top - stageBounds.top };
                            const gap = Number.parseFloat(getComputedStyle(stage).getPropertyValue('--c-action-gap'));
                            const mode = stage.dataset.cFooterLayout;
                            const blocks = [document.querySelector('#connection-hint'), document.querySelector('.c-standby-plate')];
                            const rects = blocks.map(node => node.getBoundingClientRect());
                            const label = `${innerWidth}x${innerHeight}/${phase}/${mode}`;
                            if (gap !== 16 || !['aligned', 'stacked'].includes(mode)) failures.push(label + ': missing action gap/layout');
                            else counts[mode]++;
                            for (let index = 0; index < blocks.length; index++) {
                                const block = blocks[index]; const rect = rects[index];
                                if (!inside(rect, { left: 0, top: 0, right: innerWidth, bottom: innerHeight })) failures.push(label + ': footer outside viewport');
                                // OLED drift remains independent; compare layout anchors before that retained translation.
                                if (mode === 'aligned') {
                                    if (Math.abs(rect.top + rect.height / 2 - shift.y - action.top - action.height / 2) > 1) failures.push(label + ': side block is not action-centered');
                                    if (index === 0 && rect.right - shift.x > action.left - gap + 1) failures.push(label + ': left slot violates measured half-action width/gap');
                                    if (index === 1 && rect.left - shift.x < action.right + gap - 1) failures.push(label + ': right slot violates measured half-action width/gap');
                                } else if (Math.abs(rect.bottom - shift.y - (action.top - gap)) > 1) failures.push(label + ': stacked block is not anchored to measured action top/gap');
                                const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
                                for (let text = walker.nextNode(); text; text = walker.nextNode()) {
                                    if (!text.textContent.trim()) continue;
                                    const range = document.createRange(); range.selectNodeContents(text);
                                    const glyphs = [...range.getClientRects()];
                                    if (!glyphs.length) failures.push(label + ': footer has no measured text: ' + text.textContent);
                                    const css = getComputedStyle(text.parentElement);
                                    context.font = `${css.fontStyle} ${css.fontWeight} ${css.fontSize} ${css.fontFamily}`;
                                    const metrics = context.measureText(text.textContent);
                                    if (!['fontBoundingBoxAscent', 'fontBoundingBoxDescent', 'actualBoundingBoxAscent', 'actualBoundingBoxDescent'].every(name => Number.isFinite(metrics[name]))) throw Error('Actual footer font ink metrics are required');
                                    for (const fontBox of glyphs) {
                                        const viewport = { left: 0, top: 0, right: innerWidth, bottom: innerHeight };
                                        const ink = footerInkBounds(fontBox, metrics);
                                        // Range includes font leading outside a 20px line box; only actual ink is a vertical clipping criterion.
                                        if (!inside(fontBox, rect)) fontBoxOverhangs++;
                                        if (fontBox.width <= 0 || fontBox.height <= 0 || !insideHorizontal(fontBox, rect) || !inside(fontBox, viewport) || !inside(ink, rect) || !inside(ink, viewport)) failures.push(label + ': footer text/ink escapes slot or viewport: ' + JSON.stringify({ text: text.textContent, fontBox: { left: fontBox.left, right: fontBox.right, top: fontBox.top, bottom: fontBox.bottom }, ink, slot: { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom }, font: css.font }));
                                        for (let ancestor = text.parentElement; ancestor && ancestor !== document.body; ancestor = ancestor.parentElement) {
                                            const parentCss = getComputedStyle(ancestor); const bounds = ancestor.getBoundingClientRect();
                                            const clip = { left: bounds.left + ancestor.clientLeft, right: bounds.left + ancestor.clientLeft + ancestor.clientWidth,
                                                top: bounds.top + ancestor.clientTop, bottom: bounds.top + ancestor.clientTop + ancestor.clientHeight };
                                            if (/^(hidden|clip|auto|scroll)$/.test(parentCss.overflowX) && !insideHorizontal(ink, clip) || /^(hidden|clip|auto|scroll)$/.test(parentCss.overflowY) && (ink.top < clip.top - 1 || ink.bottom > clip.bottom + 1)) failures.push(label + ': ancestor clips actual footer ink: ' + text.textContent);
                                        }
                                        inkChecks++;
                                    }
                                }
                            }
                            if (rects[0].right > rects[1].left + 1) failures.push(label + ': footer slots overlap');
                        }
                        return { failures, counts, inkChecks, fontBoxOverhangs };
                    });
                    evidence.push({ width, height, ...result.counts, inkChecks: result.inkChecks, fontBoxOverhangs: result.fontBoxOverhangs });
                    check(result.inkChecks > 0 && !result.failures.length, result.failures.join('\n') || 'Missing measured footer ink');
                }
                check(evidence.some(value => value.aligned) && evidence.some(value => value.stacked), 'Exercise both measured C footer layouts');
                return { cases: sizes.length * 4, sizes: evidence };
            } finally {
                await probe.evaluate(() => { state.phase = 'off'; state.broadcast = false; state.motion.paused = false; render(); });
            }
        });
        await run('D copy hierarchy and moving, brighter upper-third status', async () => {
            await probe.setViewportSize({ width: 896, height: 592 });
            await probe.evaluate(() => { selectVariant('D'); state.motion.paused = false;
                if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); } else if (state.phase !== 'waiting') setPhase('waiting'); render(); });
            const sample = await probe.evaluate(() => {
                const upper = document.querySelector('.e-channel--upper');
                const lower = document.querySelector('.e-channel--lower');
                const hint = upper.querySelector('.e-stream-row:nth-child(3).e-stream-row--status');
                const visible = node => getComputedStyle(node).display !== 'none' && getComputedStyle(node).visibility !== 'hidden' && node.getClientRects().length;
                const luma = node => {
                    const rgb = getComputedStyle(node).color.match(/[\d.]+/g).map(Number);
                    let opacity = rgb[3] ?? 1;
                    for (let parent = node; parent && parent !== document.querySelector('#e-scene'); parent = parent.parentElement) opacity *= Number(getComputedStyle(parent).opacity);
                    return (rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722) * opacity;
                };
                const copies = [...document.querySelectorAll('.e-stream-row:not(.e-stream-row--status) .e-stream-track .e-copy')].filter((node, index) => index % 2 === 0);
                const variations = copies.map(copy => ({ text: copy.textContent,
                    font: new Set([copy, ...copy.querySelectorAll('span')].map(node => { const css = getComputedStyle(node); return css.fontSize + '/' + css.fontFamily; })).size,
                    brightness: new Set([getComputedStyle(copy).color, ...[...copy.querySelectorAll('span')].map(node => getComputedStyle(node).color)]).size,
                    sparse: [...copy.querySelectorAll('span')].reduce((total, node) => total + node.textContent.length, 0) / copy.textContent.length }));
                const message = [...hint.querySelectorAll('.e-status-message')].find(node => node.dataset.status === (document.querySelector('#stage').dataset.broadcast === 'true' ? document.querySelector('#stage').dataset.phase : 'off'));
                const track = message.querySelector('.e-stream-track');
                const rect = track.getBoundingClientRect();
                window.__v35Hint = track;
                return { upperRows: upper.querySelectorAll('.e-stream-row').length, lowerRows: lower.querySelectorAll('.e-stream-row').length,
                    copies: variations, hintText: hint.innerText, moving: track.getAnimations().some(animation => animation.playState === 'running'),
                    hintBrightness: luma(message.querySelector('.e-key-hint')), copyBrightness: luma(lower.querySelector('.e-stream-row:nth-child(2) .e-copy')),
                    separateHintVisible: visible(document.querySelector('#connection-hint')), separateHintHtml: document.querySelector('#connection-hint').innerHTML,
                    hint: { x: rect.x, y: rect.y, width: rect.width, height: rect.height } };
            });
            check(sample.upperRows === 3 && sample.lowerRows === 2 && sample.moving, 'D has three upper rows, two lower rows, with a moving upper-third-row status');
            check(/Windows/.test(sample.hintText) && /Win\s*\+\s*K/.test(sample.hintText) && /MIRAX\s*·\s*客廳/.test(sample.hintText), 'Upper third row explains the Windows shortcut and receiver choice');
            check(!sample.separateHintVisible && sample.separateHintHtml === '', 'Remove both rendering and markup of the separate top-right Win + K hint in D');
            check(sample.hintBrightness > sample.copyBrightness * 1.15, 'Status guidance must be visibly brighter than packet copy');
            check(sample.copies.length === 4 && new Set(sample.copies.slice(0, 3).map(value => value.text)).size === 3, 'Two upper decorative rows and the lower comment must keep distinct copy');
            const upper = sample.copies.slice(0, 2).map(value => value.text).join(' ');
            check(/R\[\d+\]|register/i.test(upper) && /0x[\da-f]+/i.test(upper) && /\/\/|;/.test(upper), 'Upper decorative rows retain register and hex copy');
            check(/\/\*.*no input sampled[\s\S]*return decoration;.*\*\//.test(sample.copies[2].text), 'Lower first row retains the former upper comment pattern');
            check(/seq=|flags=|packet/i.test(sample.copies[3].text), 'Lower second row retains packet-like copy');
            check(sample.copies.every(value => value.font > 1 && value.brightness > 1 && value.sparse > 0 && value.sparse < .6), 'Sparse fixed spans vary font and brightness without styling the whole line');
            await probe.waitForTimeout(160);
            const after = await probe.evaluate(() => { const rect = window.__v35Hint.getBoundingClientRect(); return { x: rect.x, y: rect.y, width: rect.width, height: rect.height }; });
            check(near(after.width, sample.hint.width, .05) && near(after.height, sample.hint.height, .05) && after.x > sample.hint.x + .01 && after.y < sample.hint.y - .01, 'The native first-row marquee must move bottom-left to top-right without resizing');
            return { upperRows: sample.upperRows, lowerRows: sample.lowerRows, brightnessRatio: sample.hintBrightness / sample.copyBrightness };
        });

        for (const [width, height] of [...sizes, [568, 320]]) {
            await run(`D visible Win + K glyphs/paint and bounded grid ${width}x${height}`, async () => {
                await probe.setViewportSize({ width, height });
                await probe.evaluate(() => { selectVariant('D'); state.home = 'big'; state.motion.paused = true;
                    if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); } else if (state.phase !== 'waiting') setPhase('waiting'); render(); resizeCanvas(); });
                await frames(probe);
                let savedOpacity;
                try {
                    const layout = await probe.evaluate(() => {
                        const rect = value => ({ left: value.left, top: value.top, right: value.right, bottom: value.bottom, width: value.width, height: value.height });
                        const inside = (points, bounds) => points.every(point => point.x >= bounds.left - .75 && point.x <= bounds.right + .75 && point.y >= bounds.top - .75 && point.y <= bounds.bottom + .75);
                        const quad = (bounds, width, height, matrix) => {
                            const vectors = [[0, 0], [width, 0], [width, height], [0, height]].map(([x, y]) => matrix.transformPoint({ x, y }));
                            const x = bounds.left - Math.min(...vectors.map(point => point.x));
                            const y = bounds.top - Math.min(...vectors.map(point => point.y));
                            return vectors.map(point => ({ x: point.x + x, y: point.y + y }));
                        };
                        // Rotation creates an oversized axis-aligned Range box; recover its actual quad.
                        for (const degrees of [0, 45, -45, 90]) {
                            const matrix = new DOMMatrix().rotate(degrees);
                            const expected = [[30, 40], [42, 40], [42, 48], [30, 48]].map(([x, y]) => matrix.transformPoint({ x, y }));
                            const recovered = quad({ left: Math.min(...expected.map(point => point.x)), top: Math.min(...expected.map(point => point.y)) }, 12, 8, matrix);
                            if (recovered.some((point, index) => Math.hypot(point.x - expected[index].x, point.y - expected[index].y) > .001)) throw Error('Glyph quad reconstruction self-check failed');
                        }
                        if (inside([{ x: 468.26, y: 100 }], { left: 0, top: 0, right: 390, bottom: 844 })) throw Error('Offscreen Win + K regression self-check failed');
                        const windows = [...document.querySelectorAll('#e-scene .e-stream-window')].map(window => ({
                            width: window.clientWidth, overflowX: getComputedStyle(window).overflowX, overflowY: getComputedStyle(window).overflowY,
                            rows: [...window.querySelectorAll('.e-stream-row')].map(row => ({ width: row.clientWidth,
                                tracks: [...row.querySelectorAll('.e-stream-track')].filter(track => track.getClientRects().length).map(track => ({ width: track.scrollWidth, whiteSpace: getComputedStyle(track).whiteSpace })) })) }));
                        const statusRow = document.querySelector('.e-channel--upper .e-stream-row:nth-child(3).e-stream-row--status');
                        const streamWindow = statusRow.closest('.e-stream-window');
                        const hints = [...statusRow.querySelectorAll('.e-key-hint')].filter(node => node.getClientRects().length);
                        if (!hints.length) throw Error('No upper-third Win + K text glyphs');
                        const activeMessage = [...streamWindow.querySelectorAll('.e-status-message')].find(node => node.dataset.status === (document.querySelector('#stage').dataset.broadcast === 'true' ? document.querySelector('#stage').dataset.phase : 'off'));
                        const hintAnimation = activeMessage?.querySelector('.e-stream-track')?.getAnimations().find(animation => animation.effect.getTiming().iterations === Infinity);
                        if (!hintAnimation) throw Error('No native status marquee animation');
                        let orientation = new DOMMatrix();
                        for (let node = streamWindow; node; node = node.parentElement) {
                            const transform = getComputedStyle(node).transform;
                            if (transform !== 'none') orientation = new DOMMatrix(transform).multiply(orientation);
                        }
                        orientation = new DOMMatrix([orientation.a, orientation.b, orientation.c, orientation.d, 0, 0]);
                        const windowStyle = getComputedStyle(streamWindow);
                        const windowQuad = quad(streamWindow.getBoundingClientRect(), Number.parseFloat(windowStyle.width), Number.parseFloat(windowStyle.height), orientation);
                        const local = new DOMMatrix([orientation.a, orientation.b, orientation.c, orientation.d, windowQuad[0].x, windowQuad[0].y]).inverse();
                        const viewport = { left: 0, top: 0, right: innerWidth, bottom: innerHeight };
                        // Shortcut occlusion geometry begins. Node self-checks execute this exact helper.
                        function polygonsOverlap(a, b) {
                            return [a, b].every(polygon => polygon.every((point, index) => {
                                const next = polygon[(index + 1) % polygon.length];
                                const dx = next.x - point.x, dy = next.y - point.y;
                                const length = Math.hypot(dx, dy);
                                const project = points => points.map(vertex => (vertex.x * dy - vertex.y * dx) / length);
                                const first = project(a), second = project(b);
                                // Keep one CSS pixel clear of opaque edges and their antialiasing.
                                return Math.max(...first) >= Math.min(...second) - 1 && Math.max(...second) >= Math.min(...first) - 1;
                            }));
                        }
                        // Shortcut occlusion geometry ends.
                        const occluders = [...document.querySelectorAll('.brand, .device, #standby-view .center, #broadcast-dock')]
                            .filter(node => node.getClientRects().length && getComputedStyle(node).visibility === 'visible' && Number(getComputedStyle(node).opacity) > .01)
                            .map(node => { const box = node.getBoundingClientRect(); return [{ x: box.left, y: box.top }, { x: box.right, y: box.top }, { x: box.right, y: box.bottom }, { x: box.left, y: box.bottom }]; });
                        const clip = { left: streamWindow.clientLeft, top: streamWindow.clientTop,
                            right: streamWindow.clientLeft + streamWindow.clientWidth, bottom: streamWindow.clientTop + streamWindow.clientHeight };
                        const css = getComputedStyle(hints[0]);
                        const reference = document.createElement('span');
                        reference.style.cssText = 'all:initial;position:fixed;left:-1000px;top:0;white-space:pre';
                        for (const name of ['fontFamily', 'fontSize', 'fontStyle', 'fontWeight', 'fontStretch', 'fontKerning', 'fontVariant', 'lineHeight', 'letterSpacing']) reference.style[name] = css[name];
                        reference.textContent = hints[0].textContent;
                        document.body.append(reference);
                        try {
                            const referenceRange = document.createRange(); referenceRange.selectNodeContents(reference);
                            const unrotated = referenceRange.getBoundingClientRect();
                            const frame = createDStudyFrame(0);
                        if (!frame) throw Error('Actual original X polygons are required for intentional shortcut occlusion');
                        const offset = stage.getBoundingClientRect();
                        occluders.push(...[...frame.arms, frame.diamond].map(polygon => polygon.map(point => ({ x: point.x + offset.left, y: point.y + offset.top }))));
                        const candidates = [];
                            let sampledFraction = null;
                            // A prime count avoids aliasing the eight repeated phrases into a few positions.
                            const sampleCount = 257;
                            for (let sample = 0; sample < sampleCount && !candidates.length; sample++) {
                                hintAnimation.currentTime = Number(hintAnimation.effect.getComputedTiming().duration) * sample / sampleCount;
                                for (const hint of hints) {
                                    if (hint.textContent !== 'Win + K' || hint.firstChild?.nodeType !== Node.TEXT_NODE) continue;
                                    const range = document.createRange(); range.selectNodeContents(hint);
                                    const bounds = range.getBoundingClientRect();
                                    const points = quad(bounds, unrotated.width, unrotated.height, orientation);
                                    if (!inside(points, viewport) || !inside(points.map(point => local.transformPoint(point)), clip)) continue;
                                    if (occluders.some(polygon => polygonsOverlap(points, polygon))) continue;
                                    const characters = [];
                                    for (let index = 0; index < hint.textContent.length; index++) {
                                        if (!hint.textContent[index].trim()) continue;
                                        referenceRange.setStart(reference.firstChild, index); referenceRange.setEnd(reference.firstChild, index + 1);
                                        range.setStart(hint.firstChild, index); range.setEnd(hint.firstChild, index + 1);
                                        const dimensions = referenceRange.getBoundingClientRect();
                                        const bounds = range.getBoundingClientRect();
                                        characters.push({ text: hint.textContent[index], bounds: rect(bounds), quad: quad(bounds, dimensions.width, dimensions.height, orientation) });
                                    }
                                    candidates.push({ bounds: rect(bounds), characters });
                                    sampledFraction = sample / sampleCount;
                                }
                            }
                            return { windows, candidates, sampledFraction, searchSamples: sampleCount, viewport, documentWidth: document.documentElement.scrollWidth };
                        } finally { reference.remove(); }
                    });
                    for (const window of layout.windows) {
                        check(window.width > 0 && /^(hidden|clip)$/.test(window.overflowX) && /^(hidden|clip)$/.test(window.overflowY), 'Existing stream window must retain its overflow clip');
                        check(window.rows.every(row => Math.abs(row.width - window.width) <= Math.max(2, window.width * .01)), 'Grid min-content must not widen stream rows beyond their window: ' + JSON.stringify(window));
                        check(window.rows.every(row => row.tracks.every(track => track.width >= row.width && track.whiteSpace === 'nowrap')), 'Long moving tracks may overflow only inside the clipped, bounded row/window');
                    }
                    check(layout.documentWidth <= width + 1, 'Decorative tracks must not create page-level horizontal overflow');
                    check(layout.candidates.length > 0, 'After 257 seeks, a complete unoccluded Win + K must fit viewport/rotated clip outside foreground UI and intentional original X masks');
                    const visible = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                    savedOpacity = await probe.evaluate(() => {
                        const row = document.querySelector('.e-stream-row--status');
                        const previous = { value: row.style.getPropertyValue('opacity'), priority: row.style.getPropertyPriority('opacity') };
                        row.style.setProperty('opacity', '0', 'important');
                        return previous;
                    });
                    const hidden = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                    const paint = await probe.evaluate(async ({ visible, hidden, candidates }) => {
                        const decode = async data => {
                            const image = new Image(); image.src = 'data:image/png;base64,' + data; await image.decode();
                            const canvas = document.createElement('canvas'); canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
                            const context = canvas.getContext('2d'); context.drawImage(image, 0, 0);
                            return { width: canvas.width, height: canvas.height, data: context.getImageData(0, 0, canvas.width, canvas.height).data };
                        };
                        const inside = (x, y, quad) => {
                            const cross = quad.map((point, index) => {
                                const next = quad[(index + 1) % quad.length];
                                return (next.x - point.x) * (y - point.y) - (next.y - point.y) * (x - point.x);
                            });
                            return cross.every(value => value >= -.01) || cross.every(value => value <= .01);
                        };
                        const square = [{ x: 0, y: 0 }, { x: 4, y: 0 }, { x: 4, y: 4 }, { x: 0, y: 4 }];
                        if (!inside(2, 2, square) || inside(5, 2, square)) throw Error('Glyph paint mask self-check failed');
                        const [on, off] = await Promise.all([decode(visible), decode(hidden)]);
                        if (on.width !== innerWidth || on.height !== innerHeight || on.width !== off.width || on.height !== off.height) throw Error('Screenshot must use matching CSS-pixel viewport dimensions');
                        const words = candidates.map(candidate => candidate.characters.map(character => {
                            let changedPixels = 0;
                            for (let y = Math.max(0, Math.floor(character.bounds.top)); y < Math.min(on.height, Math.ceil(character.bounds.bottom)); y++) {
                                for (let x = Math.max(0, Math.floor(character.bounds.left)); x < Math.min(on.width, Math.ceil(character.bounds.right)); x++) {
                                    if (!inside(x + .5, y + .5, character.quad)) continue;
                                    const offset = (y * on.width + x) * 4;
                                    const delta = Math.max(...[0, 1, 2].map(channel => Math.abs(on.data[offset + channel] - off.data[offset + channel])));
                                    const brightness = (on.data[offset] + on.data[offset + 1] + on.data[offset + 2]) / 3;
                                    if (delta >= 18 && brightness >= 80) changedPixels++;
                                }
                            }
                            return { text: character.text, changedPixels };
                        }));
                        return { words, completePaintedWords: words.filter(characters => characters.length === 5 && characters.every(character => character.changedPixels >= 2)).length };
                    }, { visible: visible.toString('base64'), hidden: hidden.toString('base64'), candidates: layout.candidates });
                    check(paint.completePaintedWords > 0, 'Actual screenshot pixels must paint every W/i/n/+/K glyph of one complete visible shortcut, not just a DOM row: ' + JSON.stringify(paint.words));
                    return { rowWidths: layout.windows.map(window => ({ window: window.width, rows: window.rows.map(row => row.width) })),
                        sampledFraction: layout.sampledFraction, searchSamples: layout.searchSamples, visibleGlyphWords: layout.candidates.length, paintedWords: paint.completePaintedWords };
                } finally {
                    await probe.evaluate(previous => {
                        const row = document.querySelector('.e-stream-row--status');
                        if (previous && row) {
                            if (previous.value) row.style.setProperty('opacity', previous.value, previous.priority);
                            else row.style.removeProperty('opacity');
                        }
                        state.motion.paused = false; render();
                    }, savedOpacity || null);
                }
            });
        }

        await run('D actual shots, per-emission randomness, quadratic travel, real paint', async () => {
            await probe.emulateMedia({ reducedMotion: 'no-preference' });
            await probe.evaluate(() => { selectVariant('D'); state.home = 'big'; state.motion.paused = false; render(); });
            const evidence = await probe.evaluate(() => new Promise(resolve => {
                const started = performance.now();
                const before = window.MiraxDStreams?.snapshot();
                if (!before) { resolve({ missing: true }); return; }
                const samples = [];
                const observe = () => {
                    const snapshot = window.MiraxDStreams.snapshot();
                    for (const stream of snapshot.streams) {
                        if (stream.phase !== 'shot') continue;
                        const channel = document.querySelector(stream.name.startsWith('upper') ? '.e-channel--upper' : '.e-channel--lower');
                        const edge = stream.name.endsWith('top') ? 'top' : 'bottom';
                        const style = getComputedStyle(channel);
                        const pseudo = getComputedStyle(channel, edge === 'top' ? '::before' : '::after');
                        samples.push({ ...stream, paint: Number(style.getPropertyValue(`--e-${edge}-position`)),
                            alphaPaint: Number(style.getPropertyValue(`--e-${edge}-alpha`)), opacity: Number(pseudo.opacity), transform: pseudo.transform });
                    }
                    if (performance.now() - started < 6500) requestAnimationFrame(observe);
                    else resolve({ before, after: snapshot, samples, wallMs: performance.now() - started });
                };
                requestAnimationFrame(observe);
            }));
            check(!evidence.missing, 'Host must expose and wire window.MiraxDStreams');
            check(evidence.after.active && evidence.after.elapsedSeconds - evidence.before.elapsedSeconds > 3, 'The host must advance D on the real animation clock');
            const names = ['upper-top', 'upper-bottom', 'lower-top', 'lower-bottom'];
            for (const name of names) {
                const shots = evidence.after.history.filter(shot => shot.name === name && shot.startedAtSeconds >= evidence.before.elapsedSeconds);
                check(shots.length >= 2, name + ': observe multiple actual emissions, not a preassigned CSS delay');
                check(new Set(shots.map(shot => shot.gapSeconds)).size > 1, name + ': randomize each actual emission gap');
                check(shots.every(shot => shot.gapSeconds >= .12 && shot.gapSeconds <= .9 && shot.durationSeconds >= .95 && shot.durationSeconds <= 1.60), name + ': retain faster, bounded gaps/durations');
                const samples = evidence.samples.filter(sample => sample.name === name && sample.progress > .12 && sample.progress < .92);
                check(samples.length >= 8, name + ': collect real travel samples');
                check(samples.every(sample => near(sample.position, sample.progress ** 2, .000001) && near(sample.paint, sample.position, .000001) && near(sample.alphaPaint, sample.alpha, .000001) && near(sample.opacity, sample.alpha, .015)), name + ': quadratic controller progress must reach the rendered edge');
                check(new Set(samples.map(sample => sample.transform)).size > 4, name + ': cathode must visibly move, not only update a snapshot');
                const complete = [...new Set(samples.map(sample => sample.shotIndex))].map(shot => {
                    const travel = samples.filter(sample => sample.shotIndex === shot);
                    return { early: travel.find(sample => sample.progress > .2 && sample.progress < .4),
                        late: travel.find(sample => sample.progress > .65 && sample.progress < .85) };
                }).find(travel => travel.early && travel.late);
                check(complete && complete.late.position / complete.late.progress > complete.early.position / complete.early.progress, name + ': later normalized travel must accelerate, not ease out');
            }
            return { seconds: evidence.after.elapsedSeconds - evidence.before.elapsedSeconds, actualShots: evidence.after.history.length, paintSamples: evidence.samples.length };
        });

        await run('D pause/hidden/simple/projected freeze, no resume burst, reduced-motion keeps features', async () => {
            const evidence = [];
            for (const mode of ['paused', 'hidden', 'simple', 'projected']) {
                await probe.evaluate(value => {
                    state.variant = 'D'; state.home = 'big'; state.broadcast = true; state.phase = 'waiting';
                    state.motion.paused = false; state.motion.hidden = false;
                    if (value === 'paused') state.motion.paused = true;
                    if (value === 'simple') state.home = 'simple';
                    if (value === 'projected') { setPhase('connecting'); setPhase('projecting'); }
                    if (value === 'hidden') {
                        Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
                        document.dispatchEvent(new Event('visibilitychange'));
                    }
                    render();
                }, mode);
                const before = await probe.evaluate(() => window.MiraxDStreams.snapshot());
                await probe.waitForTimeout(150);
                const after = await probe.evaluate(() => window.MiraxDStreams.snapshot());
                check(!after.active && JSON.stringify(before) === JSON.stringify(after), mode + ': all stream state and history must freeze');
                const resume = await probe.evaluate(() => {
                    delete document.hidden;
                    document.dispatchEvent(new Event('visibilitychange'));
                    state.motion.paused = false; state.home = 'big'; state.phase = 'waiting'; render();
                    return new Promise(resolve => requestAnimationFrame(() => resolve(window.MiraxDStreams.snapshot())));
                });
                check(resume.elapsedSeconds - after.elapsedSeconds <= .055 && resume.history.length === after.history.length, mode + ': do not replay hidden wall time or burst queued shots');
                evidence.push(mode);
            }
            await probe.emulateMedia({ reducedMotion: 'reduce' });
            const before = await probe.evaluate(() => ({ clock: window.MiraxDStreams.snapshot().elapsedSeconds,
                transform: getComputedStyle(document.querySelector('.e-stream-track')).transform }));
            await probe.waitForTimeout(220);
            const after = await probe.evaluate(() => ({ clock: window.MiraxDStreams.snapshot().elapsedSeconds,
                transform: getComputedStyle(document.querySelector('.e-stream-track')).transform }));
            check(after.clock > before.clock + .08 && after.transform !== before.transform, 'Reduced-motion preference must retain the requested complete animated feature set');
            await probe.emulateMedia({ reducedMotion: 'no-preference' });
            return { frozenModes: evidence, reducedMotionAdvanced: after.clock - before.clock };
        });

        await run('C all ten styles, eight presets, 24 body lines: actual Range/glyph bounds, no font shrink', async () => {
            await probe.evaluate(() => { selectVariant('C'); state.home = 'big'; state.phase = 'off'; state.broadcast = false; state.motion.paused = false; render(); });
            const available = await probe.locator('#c-log-style option').evaluateAll(options => options.map(option => option.value));
            check(JSON.stringify(available) === JSON.stringify(styles), 'Retain all ten C log styles');
            const hudSizes = [[280, 568], ...sizes];
            let presets = 0;
            let lines = 0;
            let measuredLines = 0;
            let measurementRows = 0;
            let contentPools = 0;
            let maximumLineLength = 0;
            for (const [width, height] of hudSizes) {
                await probe.setViewportSize({ width, height });
                await frames(probe);
                const report = await probe.evaluate(styles => {
                    if (state.motion.paused || !shouldShowCHud()) throw Error('HUD geometry requires active motion; paused/hidden stale fallback is not evidence');
                    const failures = [];
                    const nativeRandom = Math.random;
                    const canvas = document.createElement('canvas');
                    const context = canvas.getContext('2d');
                    const within = (a, b) => a.left >= b.left - .8 && a.right <= b.right + .8 && a.top >= b.top - .8 && a.bottom <= b.bottom + .8;
                    const originalMeasurements = [
                        [], ['> OPTICS: 421 THz'], ['> PHASE: <0.08%'], [],
                        ['> DRIFT: -12ps/s', '> BUS: 0.42ms'], ['> APERTURE: 98.2%', '> REFRESH: 60fps'],
                        ['> RF: -138dBm'], ['> COLLISIONS: 0%']
                    ];
                    let count = 0;
                    let lines = 0;
                    let measuredLines = 0;
                    let measurementRows = 0;
                    let contentPools = 0;
                    let maximumLineLength = 0;
                    try {
                        for (const style of styles) {
                            state.cLogStyle = style; render();
                            const generatedBodies = [];
                            for (let index = 0; index < cLogPresets.length; index++) {
                                Math.random = () => (index + .5) / cLogPresets.length;
                                showRandomCHud();
                                const callout = document.querySelector('#c-hud-callout');
                                for (const animation of callout.getAnimations({ subtree: true })) {
                                    if (Number.isFinite(animation.effect.getComputedTiming().endTime)) animation.finish();
                                }
                                updateCHudOrbit(performance.now());
                                const dialog = document.querySelector('#c-hud-dialog');
                                const dialogRect = dialog.getBoundingClientRect();
                                const viewport = { left: 0, top: 0, right: innerWidth, bottom: innerHeight };
                                const label = `${innerWidth}x${innerHeight}/${style}/preset-${index}`;
                                if (callout.hidden || !within(dialogRect, viewport)) failures.push(label + ': plate outside viewport/hidden');
                                const nodes = [...dialog.querySelectorAll('.c-hud-line')];
                                if (cLogPresets.length !== 8 || nodes.length !== 3 || nodes.some((node, line) => node.textContent !== cLogPresets[index].lines[line])) failures.push(label + ': missing/replaced preset lines');
                                const body = nodes.map(node => node.textContent);
                                generatedBodies.push(body);
                                for (const measurement of originalMeasurements[index] ?? []) {
                                    if (!body.slice(0, 2).includes(measurement)) failures.push(label + ': missing original first/second-row display-only measurement: ' + measurement);
                                    else measurementRows++;
                                }
                                const overlayText = [...callout.querySelectorAll('*')].filter(node => !node.children.length && !node.closest('#c-hud-body')).map(node => node.textContent).join(' ');
                                if (/\b\d{1,2}:\d{2}(?::\d{2})?\b|\d+(?:\.\d+)?\s*(?:%|deg\b|°)/i.test(overlayText)) failures.push(label + ': unexpected HUD clock/rotation overlay outside the decorative log body');
                                let previousGlyph;
                                for (const node of nodes) {
                                    const css = getComputedStyle(node);
                                    const range = document.createRange(); range.selectNodeContents(node);
                                    const rects = [...range.getClientRects()].filter(rect => rect.width > 0 && rect.height > 0);
                                    const actual = range.getBoundingClientRect();
                                    context.font = `${css.fontWeight} ${css.fontSize} ${css.fontFamily}`;
                                    const metrics = context.measureText(node.textContent);
                                    const ascent = metrics.fontBoundingBoxAscent ?? metrics.actualBoundingBoxAscent;
                                    const descent = metrics.fontBoundingBoxDescent ?? metrics.actualBoundingBoxDescent;
                                    const baseline = actual.top + (actual.height - ascent - descent) / 2 + ascent;
                                    const tracking = Number.parseFloat(css.letterSpacing) || 0;
                                    const glyph = { left: actual.left - metrics.actualBoundingBoxLeft, right: actual.left + metrics.actualBoundingBoxRight + Math.max(0, node.textContent.length - 1) * tracking,
                                        top: baseline - metrics.actualBoundingBoxAscent, bottom: baseline + metrics.actualBoundingBoxDescent };
                                    const fontSize = Number.parseFloat(css.fontSize);
                                    const lineHeight = Number.parseFloat(css.lineHeight);
                                    const measured = rects.length >= 1 && actual.width > 0 && actual.height > 0 && !callout.hidden && !state.motion.paused && shouldShowCHud();
                                    if (measured) measuredLines++;
                                    const boxes = rects.map(rect => ({ left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom }));
                                    if (!/^> [A-Z]/.test(node.textContent) || /[a-z]|[.!?]$/.test(node.textContent.replace(/THz|ps\/s|ms|fps|dBm/g, '')) || /\d/.test(node.textContent) && !(originalMeasurements[index] ?? []).includes(node.textContent)) failures.push(label + ': conversational or invented measurement row: ' + node.textContent);
                                    maximumLineLength = Math.max(maximumLineLength, node.textContent.length);
                                    if (!measured || rects.length !== 1 || css.whiteSpace === 'nowrap' || Math.abs(fontSize - 10) > .02 || lineHeight < fontSize || boxes.some(box => !within(box, dialogRect) || !within(box, viewport))) failures.push(label + ': zero/stale/wrapped/shrunk/clipped text: ' + node.textContent);
                                    if (previousGlyph && glyph.top < previousGlyph.bottom - .5) failures.push(label + ': adjacent line glyphs overlap');
                                    for (let ancestor = node.parentElement; ancestor && ancestor !== document.body; ancestor = ancestor.parentElement) {
                                        const parentCss = getComputedStyle(ancestor);
                                        if (/(hidden|clip|auto|scroll)/.test(parentCss.overflowX + parentCss.overflowY) && boxes.some(box => !within(box, ancestor.getBoundingClientRect()))) failures.push(label + ': ancestor clips glyphs');
                                    }
                                    previousGlyph = glyph;
                                    lines++;
                                }
                                count++;
                            }
                            const lengths = generatedBodies.map(body => body.map(line => line.length));
                            const orders = new Set(lengths.map(rows => rows.map(length => rows.filter(other => other < length).length).join('-')));
                            const longestRows = new Set(lengths.map(rows => rows.indexOf(Math.max(...rows))));
                            const allShortFrames = lengths.filter(rows => Math.max(...rows) <= 16).length;
                            const mixedFrames = lengths.filter(rows => Math.max(...rows) > 16 && Math.max(...rows) - Math.min(...rows) >= 5).length;
                            if (allShortFrames < 2 || mixedFrames < 2 || orders.size < 4 || longestRows.size !== 3) failures.push(`${innerWidth}x${innerHeight}/${style}: generated popups regress to a uniform length recipe`);
                            contentPools++;
                        }
                    } finally { Math.random = nativeRandom; }
                    return { failures, count, lines, measuredLines, measurementRows, contentPools, maximumLineLength };
                }, styles);
                check(report.count === 80 && report.lines === 240 && report.measuredLines === 240 && report.measurementRows === 80 && report.contentPools === 10, `${width}x${height}: require eight generated presets/three measured lines and original measurements in all ten styles, not stale hidden DOM`);
                check(!report.failures.length, report.failures.slice(0, 12).join('\n'));
                presets += report.count; lines += report.lines; measuredLines += report.measuredLines;
                measurementRows += report.measurementRows; contentPools += report.contentPools;
                maximumLineLength = Math.max(maximumLineLength, report.maximumLineLength);
            }
            check(presets === hudSizes.length * 10 * 8 && lines === 1680 && measuredLines === 1680 && measurementRows === 560 && contentPools === 70, 'Require all 1680 actual single-line Range measurements and 560 original display-only metric rows across seven HUD sizes, including 280px index');
            return { viewports: hudSizes.length, presets, bodyLines: lines, measuredRangeLines: measuredLines, displayOnlyMeasurementRows: measurementRows, contentPools, referenceFontPx: 10, maximumLineLength };
        });

        await run('A celestial size, full-viewport entry/exit, both preferences animate', async () => {
            const evidence = [];
            for (const preference of ['no-preference', 'reduce']) {
                await probe.emulateMedia({ reducedMotion: preference });
                for (const [width, height] of [[390, 844], [480, 375], [2560, 720]]) {
                    await probe.setViewportSize({ width, height });
                    await probe.evaluate(() => { selectVariant('A'); state.home = 'big'; state.broadcast = false; state.phase = 'off'; state.motion.paused = false; render(); resizeCanvas(); });
                    await frames(probe);
                    const value = await probe.evaluate(() => {
                        const svg = document.querySelector('#landscape svg');
                        const sun = svg.querySelector('.a-sun');
                        const moon = svg.querySelector('.a-moon');
                        const moonClone = moon.cloneNode(true); moonClone.removeAttribute('transform');
                        const reference = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                        reference.setAttribute('style', 'position:fixed;left:-1000px;top:0;width:1600px;height:1000px');
                        reference.setAttribute('viewBox', '0 0 1600 1000'); reference.append(moonClone); document.body.append(reference);
                        try {
                            const scale = Math.hypot(svg.getScreenCTM().a, svg.getScreenCTM().b);
                            const oldMoon = moonClone.getBoundingClientRect();
                            const size = { sun: sun.getBoundingClientRect().width / (54 * scale), moon: moon.getBoundingClientRect().width / (oldMoon.width * scale) };
                            const orbitAnimations = () => [...document.querySelectorAll('.a-sun-orbit,.a-moon-orbit')].flatMap(node => node.getAnimations());
                            const seek = fraction => { for (const animation of orbitAnimations()) animation.currentTime = animation.effect.getComputedTiming().duration * fraction; };
                            const rect = node => { const r = node.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, center: (r.left + r.right) / 2 }; };
                            toggleBroadcast(); confirmServiceReady(); void getComputedStyle(sun.parentElement).transform;
                            const animations = orbitAnimations();
                            const durations = animations.map(animation => Number(animation.effect.getComputedTiming().duration));
                            seek(0); const night = { sun: rect(sun), moon: rect(moon) };
                            seek(.5); const middle = { sun: rect(sun), moon: rect(moon) };
                            seek(1); const day = { sun: rect(sun), moon: rect(moon) };
                            toggleBroadcast(); void getComputedStyle(sun.parentElement).transform;
                            seek(1); const nightAgain = { sun: rect(sun), moon: rect(moon) };
                            return { size, sunRadius: Number(sun.getAttribute('r')), durations, night, middle, day, nightAgain };
                        } finally { reference.remove(); }
                    });
                    const label = `${preference}/${width}x${height}`;
                    check(value.sunRadius === 30 && value.size.sun >= 1.08 && value.size.sun <= 1.15 && value.size.moon >= 1.38 && value.size.moon <= 1.44, label + ': sun r30 and moon scale about 38/27, both smaller than the previous v40');
                    check(value.night.moon.left >= -1 && value.night.moon.right <= width + 1 && value.night.moon.top >= -1 && value.night.moon.bottom <= height + 1, label + ': night crescent is fully inside');
                    check(value.day.sun.left >= -1 && value.day.sun.right <= width + 1 && value.day.sun.top >= -1 && value.day.sun.bottom <= height + 1, label + ': day sun stays fully inside');
                    check(value.durations.length >= 2 && value.durations.every(duration => duration >= 3000), label + ': retain full celestial transitions under both preferences');
                    check(value.night.sun.right <= 1 && value.night.moon.center > 0 && value.night.moon.center < width, label + ': sun starts entirely outside the left viewport');
                    check(value.day.moon.left >= width - 1 && value.day.sun.center > 0 && value.day.sun.center < width, label + ': moon exits entirely beyond the right viewport');
                    check(value.middle.sun.center > value.night.sun.center && value.middle.sun.center < value.day.sun.center && value.middle.moon.center > value.night.moon.center, label + ': seek proves actual two-sided travel');
                    check(value.nightAgain.sun.right <= 1 && value.nightAgain.moon.center < width && value.nightAgain.moon.center > 0, label + ': reverse transition returns the night scene');
                    evidence.push(label);
                }
            }
            await probe.emulateMedia({ reducedMotion: 'no-preference' });
            return { transitionCases: evidence };
        });

        await run('A main copy truly fades out, swaps, fades in despite repeated render', async () => {
            await probe.setViewportSize({ width: 1440, height: 1000 });
            await probe.evaluate(() => { selectVariant('A'); state.home = 'big'; state.phase = 'off'; state.broadcast = false; state.motion.paused = false; render(); });
            await probe.waitForTimeout(600);
            const evidence = await probe.evaluate(() => new Promise(resolve => {
                const nodes = ['status-title', 'status-eyebrow', 'connection-hint'].map(id => document.getElementById(id));
                const opacity = node => {
                    let value = 1;
                    // Exclude pre-existing whole-view/body fades: only copy-local opacity is evidence.
                    for (let parent = node; parent && !['standby-view', 'stage'].includes(parent.id) && parent !== document.body; parent = parent.parentElement) value *= Number(getComputedStyle(parent).opacity);
                    return value;
                };
                const old = nodes.map(node => node.textContent);
                toggleBroadcast(); confirmServiceReady();
                const immediate = { phase: state.phase, broadcast: state.broadcast,
                    checked: document.querySelector('#broadcast-toggle').getAttribute('aria-checked'), text: nodes.map(node => node.textContent) };
                const started = performance.now();
                const samples = [];
                const observe = () => {
                    const elapsed = performance.now() - started;
                    if (elapsed < 180) render();
                    samples.push({ elapsed, text: nodes.map(node => node.textContent), opacity: nodes.map(opacity) });
                    if (elapsed < 950) requestAnimationFrame(observe);
                    else resolve({ old, immediate, samples, final: nodes.map(node => node.textContent), expected: [phases.waiting.title, phases.waiting.eyebrow.replace(' · SIMULATED', ''), 'Windows 按 Win + K，選擇「MIRAX · 客廳」'] });
                };
                requestAnimationFrame(observe);
            }));
            check(evidence.immediate.phase === 'waiting' && evidence.immediate.broadcast && evidence.immediate.checked === 'true', 'Application/ARIA state must change immediately, not after the fade');
            for (let index = 0; index < evidence.old.length; index++) {
                check(evidence.immediate.text[index] === evidence.old[index], 'Old copy must remain until fade-out completes');
                const fadeOut = evidence.samples.find(sample => sample.text[index] === evidence.old[index] && sample.opacity[index] > .03 && sample.opacity[index] < .95);
                const swapped = evidence.samples.findIndex(sample => sample.text[index] === evidence.expected[index]);
                check(fadeOut && swapped > 0, 'All changing A main copy must visibly fade out before swapping');
                check(Math.min(evidence.samples[swapped - 1].opacity[index], evidence.samples[swapped].opacity[index]) <= .12, 'Copy swap occurs only near zero local opacity');
                check(evidence.samples.slice(swapped).some(sample => sample.opacity[index] > .03 && sample.opacity[index] < .95), 'New copy must visibly fade in');
                check(evidence.final[index] === evidence.expected[index] && evidence.samples.at(-1).opacity[index] >= .99, 'Repeated render must not restart or strand a copy fade');
            }
            return { samples: evidence.samples.length, swappedTo: evidence.final };
        });

        await run('A rapid changes cannot apply stale text completion', async () => {
            const evidence = await probe.evaluate(() => new Promise(resolve => {
                state.phase = 'off'; state.broadcast = false; render();
                const started = performance.now();
                const samples = [];
                let step = 0;
                const observe = () => {
                    const elapsed = performance.now() - started;
                    if (step === 0) { toggleBroadcast(); confirmServiceReady(); step++; }
                    if (step === 1 && elapsed >= 45) { setPhase('connecting'); step++; }
                    if (step === 2 && elapsed >= 90) { setPhase('failed'); step++; }
                    if (step === 3 && elapsed >= 135) { toggleBroadcast(); step++; }
                    render();
                    samples.push({ elapsed, title: document.querySelector('#status-title').textContent });
                    if (elapsed < 1000) requestAnimationFrame(observe);
                    else resolve({ phase: state.phase, broadcast: state.broadcast, title: document.querySelector('#status-title').textContent,
                        eyebrow: document.querySelector('#status-eyebrow').textContent, expected: phases.off, samples: samples.slice(-8) });
                };
                requestAnimationFrame(observe);
            }));
            check(evidence.phase === 'off' && !evidence.broadcast && evidence.title === evidence.expected.title && evidence.eyebrow === evidence.expected.eyebrow, 'Rapid off/retry/failure must finish on the latest A copy/state');
            check(evidence.samples.every(sample => sample.title === evidence.expected.title), 'A stale fade completion must not flash obsolete text');
            return { settledSamples: evidence.samples.length };
        });

        const waitForAReceivingCopy = async () => {
            try {
                await probe.waitForFunction(() => {
                    const sample = window.__v35ACopySample();
                    const expected = [phases.waiting.title, phases.waiting.eyebrow.replace(' · SIMULATED', ''), 'Windows 按 Win + K，選擇「MIRAX · 客廳」'];
                    return sample.phase === 'waiting' && sample.broadcast && sample.checked === 'true' && sample.copy.every((copy, index) => copy.text === expected[index] && copy.painted && copy.opacity >= .99 && !mainCopyChanges.has(document.getElementById(copy.id)));
                });
            } catch (error) {
                const detail = await probe.evaluate(() => ({ sample: window.__v35ACopySample(),
                    expected: [phases.waiting.title, phases.waiting.eyebrow.replace(' · SIMULATED', ''), 'Windows 按 Win + K，選擇「MIRAX · 客廳」'],
                    pending: ['status-title', 'status-eyebrow', 'connection-hint'].map(id => {
                        const node = document.getElementById(id); const change = mainCopyChanges.get(node);
                        return { id, target: change?.value, key: change?.key, animations: node.getAnimations().map(animation => ({ playState: animation.playState, currentTime: animation.currentTime, duration: animation.effect.getComputedTiming().duration })) };
                    }) }));
                throw Error('A receiving copy did not settle within the bounded 1800ms wait: ' + JSON.stringify(detail) + '; ' + error.message);
            }
            return probe.evaluate(() => window.__v35ACopySample());
        };
        const prepareAReceiving = async () => {
            await probe.setViewportSize({ width: 390, height: 844 });
            await probe.evaluate(() => {
                act('v35-copy-home', () => { state.home = 'big'; state.motion.hidden = false; state.motion.paused = false; });
                selectVariant('A');
                if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); }
                if (state.phase !== 'waiting') setPhase('waiting');
                render();
            });
            await waitForAReceivingCopy();
        };
        await probe.evaluate(() => {
            window.__v35ACopySample = () => ({ phase: state.phase, broadcast: state.broadcast,
                checked: document.querySelector('#broadcast-toggle').getAttribute('aria-checked'),
                copy: ['status-title', 'status-eyebrow', 'connection-hint'].map(id => {
                    const node = document.getElementById(id); const css = getComputedStyle(node);
                    let opacity = 1;
                    for (let parent = node; parent && !['standby-view', 'stage'].includes(parent.id) && parent !== document.body; parent = parent.parentElement) opacity *= Number(getComputedStyle(parent).opacity);
                    const range = document.createRange(); range.selectNodeContents(node);
                    const glyphs = [...range.getClientRects()].filter(rect => rect.width > 0 && rect.height > 0);
                    const bounds = node.getBoundingClientRect();
                    return { id, text: node.textContent, opacity, display: css.display,
                        painted: css.display !== 'none' && css.visibility !== 'hidden' && glyphs.length > 0 && bounds.width > 0 && bounds.height > 0,
                        glyphRects: glyphs.length, glyphHeight: glyphs.reduce((height, rect) => Math.max(height, rect.height), 0) };
                }) });
        });
        await run('A OFF: every visible changing copy exits; existing hint paints before clearing', async () => {
            await prepareAReceiving();
            const evidence = await probe.evaluate(() => new Promise(resolve => {
                const before = window.__v35ACopySample();
                toggleBroadcast(); const immediate = window.__v35ACopySample();
                const started = performance.now(); const samples = [];
                const observe = () => {
                    const elapsed = performance.now() - started;
                    if (elapsed < 120) render();
                    samples.push({ elapsed, ...window.__v35ACopySample() });
                    if (elapsed < 450) requestAnimationFrame(observe);
                    else resolve({ before, immediate, samples, expected: [phases.off.title, phases.off.eyebrow, ''] });
                };
                requestAnimationFrame(observe);
            }));
            check(evidence.before.copy.every(copy => copy.text && copy.painted && copy.glyphHeight > 0), 'OFF regression requires all three pre-existing A copy nodes, including an actually visible hint');
            check(evidence.immediate.phase === 'off' && !evidence.immediate.broadcast && evidence.immediate.checked === 'false', 'OFF phase/broadcast/ARIA state must update immediately while old copy exits');
            check(evidence.samples.every(sample => sample.phase === 'off' && !sample.broadcast && sample.checked === 'false'), 'The text animation must not defer or reverse OFF state');
            for (let index = 0; index < evidence.before.copy.length; index++) {
                const old = evidence.before.copy[index]; const immediate = evidence.immediate.copy[index];
                check(immediate.text === old.text && immediate.painted && immediate.glyphRects > 0, old.id + ': OFF must not hide or clear old glyphs before fade-out');
                const exit = evidence.samples.filter(sample => sample.copy[index].text === old.text && sample.copy[index].painted && sample.copy[index].opacity > .02 && sample.copy[index].opacity < old.opacity * .95);
                check(exit.length >= 2 && exit.some(sample => sample.elapsed >= 45), old.id + ': require multiple real-play painted mid-fade frames, not a body fade or entry/swap-only proof');
                const changed = evidence.samples.find(sample => sample.copy[index].text !== old.text);
                check(changed && changed.elapsed >= 125, old.id + ': retain old text until the 150ms exit is nearly complete');
                const final = evidence.samples.at(-1).copy[index];
                check(final.text === evidence.expected[index] && (index === 2 ? !final.painted : final.opacity >= .99), old.id + ': exit must finish on the current OFF copy; hint becomes empty/unpainted');
            }
            return { paintedExits: evidence.before.copy.map(copy => copy.id), realSamples: evidence.samples.length,
                firstHintEmptyMs: evidence.samples.find(sample => sample.copy[2].text === '')?.elapsed };
        });

        await run('A OFF hint: paused 75ms exit has actual screenshot glyph ink, then clears', async () => {
            await prepareAReceiving();
            let previousVisibility;
            try {
                const mask = await probe.evaluate(() => {
                    state.motion.paused = true; render();
                    const hint = document.querySelector('#connection-hint'); const before = hint.textContent;
                    toggleBroadcast();
                    const animation = hint.getAnimations().find(value => value.effect.target === hint && value.effect.getComputedTiming().duration === 150);
                    if (!before || hint.textContent !== before || !animation || getComputedStyle(hint).display === 'none') throw Error('Existing A hint vanished before its 150ms exit animation');
                    animation.pause(); animation.currentTime = 75; window.__v35HintExitAnimation = animation;
                    const snapshot = window.__v35ACopySample();
                    const characters = [];
                    const walker = document.createTreeWalker(hint, NodeFilter.SHOW_TEXT);
                    for (let text = walker.nextNode(); text; text = walker.nextNode()) {
                        for (let index = 0; index < text.textContent.length; index++) {
                            if (!text.textContent[index].trim()) continue;
                            const range = document.createRange(); range.setStart(text, index); range.setEnd(text, index + 1);
                            const bounds = range.getBoundingClientRect();
                            characters.push({ text: text.textContent[index], left: bounds.left, top: bounds.top, right: bounds.right, bottom: bounds.bottom });
                        }
                    }
                    return { snapshot, characters };
                });
                const hint = mask.snapshot.copy[2];
                check(mask.snapshot.phase === 'off' && mask.snapshot.checked === 'false' && hint.painted && hint.opacity > .02 && hint.opacity < .95 && mask.characters.length > 0, 'At 75ms, the old OFF-exiting hint must still have visible positive-size glyph bounds and partial local opacity');
                const visible = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                previousVisibility = await probe.evaluate(() => {
                    const hint = document.querySelector('#connection-hint');
                    const previous = { value: hint.style.getPropertyValue('visibility'), priority: hint.style.getPropertyPriority('visibility') };
                    hint.style.setProperty('visibility', 'hidden', 'important'); return previous;
                });
                const hidden = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                const paint = await probe.evaluate(async ({ visible, hidden, characters }) => {
                    const decode = async data => {
                        const image = new Image(); image.src = 'data:image/png;base64,' + data; await image.decode();
                        const canvas = document.createElement('canvas'); canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
                        const context = canvas.getContext('2d'); context.drawImage(image, 0, 0);
                        return { width: canvas.width, height: canvas.height, data: context.getImageData(0, 0, canvas.width, canvas.height).data };
                    };
                    const [on, off] = await Promise.all([decode(visible), decode(hidden)]);
                    if (on.width !== innerWidth || on.height !== innerHeight || on.width !== off.width || on.height !== off.height) throw Error('A exit screenshot must retain CSS viewport pixel dimensions');
                    return characters.map(character => {
                        let changedPixels = 0;
                        for (let y = Math.max(0, Math.floor(character.top)); y < Math.min(on.height, Math.ceil(character.bottom)); y++) {
                            for (let x = Math.max(0, Math.floor(character.left)); x < Math.min(on.width, Math.ceil(character.right)); x++) {
                                const offset = (y * on.width + x) * 4;
                                if (Math.max(...[0, 1, 2].map(channel => Math.abs(on.data[offset + channel] - off.data[offset + channel]))) >= 18) changedPixels++;
                            }
                        }
                        return { text: character.text, changedPixels };
                    });
                }, { visible: visible.toString('base64'), hidden: hidden.toString('base64'), characters: mask.characters });
                check(paint.every(character => character.changedPixels >= 2), 'Every existing A hint glyph must still paint during its OFF exit: ' + JSON.stringify(paint));
                await probe.evaluate(previous => {
                    const hint = document.querySelector('#connection-hint');
                    if (previous.value) hint.style.setProperty('visibility', previous.value, previous.priority);
                    else hint.style.removeProperty('visibility');
                    window.__v35HintExitAnimation.play();
                }, previousVisibility);
                previousVisibility = null;
                await probe.waitForFunction(() => document.querySelector('#connection-hint').textContent === '' && !window.__v35ACopySample().copy[2].painted);
                return { sampledExitMs: 75, paintedCharacters: paint.length, clearedAfterResume: true };
            } finally {
                await probe.evaluate(previous => {
                    const hint = document.querySelector('#connection-hint');
                    if (previous) {
                        if (previous.value) hint.style.setProperty('visibility', previous.value, previous.priority);
                        else hint.style.removeProperty('visibility');
                    }
                    if (window.__v35HintExitAnimation?.playState === 'paused') window.__v35HintExitAnimation.play();
                    state.motion.paused = false; render();
                }, previousVisibility || null);
            }
        });

        await run('A quick OFF/ON: existing hint never clears from stale exit and latest copy wins', async () => {
            await prepareAReceiving();
            const evidence = await probe.evaluate(() => new Promise(resolve => {
                const before = window.__v35ACopySample(); toggleBroadcast();
                const off = window.__v35ACopySample(); const started = performance.now();
                const samples = []; let onAt = null; let on;
                const observe = () => {
                    const elapsed = performance.now() - started;
                    if (onAt === null && elapsed >= 45) { toggleBroadcast(); confirmServiceReady(); onAt = elapsed; on = window.__v35ACopySample(); }
                    render(); samples.push({ elapsed, ...window.__v35ACopySample() });
                    if (elapsed < 550) requestAnimationFrame(observe);
                    else resolve({ before, off, on, onAt, samples });
                };
                requestAnimationFrame(observe);
            }));
            check(evidence.off.phase === 'off' && evidence.off.checked === 'false' && evidence.off.copy[2].painted && evidence.off.copy[2].text === evidence.before.copy[2].text, 'Quick-toggle precondition must enter a real visible hint exit');
            check(evidence.on.phase === 'waiting' && evidence.on.checked === 'true', 'ON state/ARIA must win immediately over pending OFF copy');
            check(evidence.samples.filter(sample => sample.elapsed >= evidence.onAt).every(sample => sample.copy[2].text === evidence.before.copy[2].text && sample.copy[2].painted), 'A stale OFF callback/CSS hide must never empty or hide the existing hint after quick ON');
            check(evidence.samples.some(sample => sample.elapsed >= evidence.onAt && sample.copy.some(copy => copy.opacity > .02 && copy.opacity < .99)), 'Quick-toggle evidence must include genuine partial-opacity animation before waiting for completion');
            const capturedFinal = evidence.samples.at(-1);
            const settled = await waitForAReceivingCopy();
            const mismatches = settled.copy.map((copy, index) => ({ id: copy.id, expected: evidence.before.copy[index].text, actual: copy.text, painted: copy.painted, opacity: copy.opacity })).filter(copy => copy.actual !== copy.expected || !copy.painted || copy.opacity < .99);
            check(!mismatches.length, 'All three A main-copy nodes must settle on latest ON after their actual Map/animation completion: ' + JSON.stringify({ onAt: evidence.onAt, capturedFinal, settled, mismatches }));
            return { onAtMs: evidence.onAt, samples: evidence.samples.length, capturedFinal, settled, latestWins: true };
        });

        await run('A OFF-exit hidden/theme/simple/projected flushes latest copy and rejects stale hint finishes', async () => {
            const evidence = [];
            for (const mode of ['theme-C', 'theme-D', 'hidden', 'simple', 'projected']) {
                await prepareAReceiving();
                const result = await probe.evaluate(mode => {
                    const hint = document.querySelector('#connection-hint');
                    const before = hint.textContent; toggleBroadcast();
                    const change = mainCopyChanges.get(hint); const callback = change?.animation.onfinish;
                    if (!before || hint.textContent !== before || !window.__v35ACopySample().copy[2].painted || !change || change.value !== '') throw Error('Flush regression requires the pre-existing hint pending an actual empty-string OFF exit');
                    if (mode === 'theme-C') selectVariant('C');
                    if (mode === 'theme-D') selectVariant('D');
                    if (mode === 'hidden') act('v35-off-copy-hidden', () => { state.motion.hidden = true; });
                    if (mode === 'simple') act('v35-off-copy-simple', () => { state.home = 'simple'; });
                    if (mode === 'projected') { toggleBroadcast(); confirmServiceReady(); setPhase('connecting'); setPhase('projecting'); }
                    const committed = hint.innerHTML;
                    callback?.({ currentTarget: change.animation });
                    return { committed, afterReplay: hint.innerHTML, pending: mainCopyChanges.has(hint),
                        title: document.querySelector('#status-title').textContent, expectedTitle: phases[state.phase].title,
                        valid: mode === 'theme-C' ? Boolean(hint.querySelector('.connection-title')?.textContent && hint.querySelector('.connection-description')?.textContent.includes('Win'))
                            : mode === 'projected' ? hint.textContent.includes('Win') : hint.textContent === '',
                        painted: window.__v35ACopySample().copy[2].painted };
                }, mode);
                check(!result.pending && result.valid && result.afterReplay === result.committed && result.title === result.expectedTitle && (!['hidden', 'simple', 'theme-D'].includes(mode) || !result.painted), mode + ': flush the latest hint target; old OFF completion must not wipe C/ON copy or resurrect hidden A text');
                evidence.push(mode);
            }
            await probe.evaluate(() => { state.home = 'big'; state.motion.hidden = false; if (state.broadcast) { toggleBroadcast(); confirmServiceReady(); } render(); });
            return { flushedOffExitModes: evidence };
        });

        await run('A leaving/hidden/simple/projected commits newest copy and rejects canceled finishes', async () => {
            const evidence = [];
            for (const mode of ['theme', 'hidden', 'simple', 'projected']) {
                await probe.evaluate(() => {
                    if (!(mainCopyChanges instanceof Map) || typeof replaceMainCopy !== 'function' || typeof finishHiddenMainCopyChanges !== 'function') throw Error('Missing verified main-copy fader bindings');
                    state.home = 'big'; state.motion.hidden = false; state.motion.paused = false;
                    selectVariant('A');
                    if (state.broadcast) { toggleBroadcast(); confirmServiceReady(); }
                    render();
                });
                await probe.waitForTimeout(360);
                const result = await probe.evaluate(mode => {
                    const nodes = ['status-title', 'status-eyebrow', 'connection-hint'].map(id => document.getElementById(id));
                    toggleBroadcast(); confirmServiceReady();
                    const pending = nodes.map(node => ({ node, change: mainCopyChanges.get(node), callback: mainCopyChanges.get(node)?.animation.onfinish })).filter(item => item.change);
                    if (mode === 'theme') selectVariant('D');
                    if (mode === 'hidden') act('v35-copy-hidden', () => { state.motion.hidden = true; });
                    if (mode === 'simple') act('v35-copy-simple', () => { state.home = 'simple'; });
                    if (mode === 'projected') { setPhase('connecting'); setPhase('projecting'); }
                    const beforeReplay = nodes.map(node => node.textContent);
                    for (const item of pending) item.callback?.({ currentTarget: item.change.animation });
                    return { pending: pending.length, committed: beforeReplay,
                        unchanged: JSON.stringify(beforeReplay) === JSON.stringify(nodes.map(node => node.textContent)),
                        cleared: nodes.every(node => !mainCopyChanges.has(node)),
                        title: nodes[0].textContent, expectedTitle: phases[state.phase].title,
                        eyebrow: nodes[1].textContent, expectedEyebrow: phases[state.phase].eyebrow.replace(' · SIMULATED', '') };
                }, mode);
                check(result.pending >= 2 && result.cleared && result.unchanged && result.title === result.expectedTitle && result.eyebrow === result.expectedEyebrow, mode + ': cancel and commit latest A copy; replaying a canceled finish must do nothing');
                evidence.push(mode);
            }
            await probe.evaluate(() => { state.home = 'big'; state.motion.hidden = false; if (state.broadcast) { toggleBroadcast(); confirmServiceReady(); } render(); });
            return { canceledModes: evidence };
        });

        await run('A/C/D exact shared-clock recovery, C glitch anchor, freeze, continued episodes and late callbacks', async () => {
            // These are the verified live lexical bindings: particleTime (ms), cFailureStartedAt, animateStandby.
            await probe.evaluate(() => {
                if (typeof particleTime !== 'number' || !(cFailureStartedAt === null || Number.isFinite(cFailureStartedAt)) || typeof animateStandby !== 'function') throw Error('Missing verified shared failure-clock bindings');
                window.__v35StopFrames = () => { if (animationFrame !== null) cancelAnimationFrame(animationFrame); animationFrame = null; };
                window.__v35FailureState = () => ({ phase: state.phase, clock: particleTime, anchor: cFailureStartedAt,
                    elapsed: cFailureStartedAt === null ? null : particleTime - cFailureStartedAt });
                window.__v35Advance = milliseconds => {
                    window.__v35StopFrames();
                    let now = performance.now(); previousFrameTime = now;
                    let remaining = milliseconds;
                    const particlePaint = drawParticles; const dPaint = drawDMotion;
                    // Real paint is covered above; synthetic clock steps omit duplicate rasterization.
                    drawParticles = () => {}; drawDMotion = () => {};
                    try {
                        while (remaining > 0) {
                            const step = Math.min(50, remaining); now += step; remaining -= step;
                            animateStandby(now); window.__v35StopFrames();
                        }
                    } finally {
                        drawParticles = particlePaint; drawDMotion = dPaint;
                        previousFrameTime = performance.now();
                    }
                };
                // Recovery boundary helper begins. Node self-checks reproduce the fractional-clock regression.
                function recoveryBoundaryStep(clock, anchor) {
                    return 7500 - (clock - anchor) + 1e-6;
                }
                // Recovery boundary helper ends.
                window.__v35CrossRecoveryBoundary = anchor => {
                    const elapsed = particleTime - anchor;
                    if (!Number.isFinite(anchor) || !Number.isFinite(elapsed) || elapsed < 0 || elapsed >= 7500 || cFailureStartedAt !== anchor || state.phase !== 'failed') throw Error('Invalid original recovery anchor at boundary');
                    window.__v35Advance(recoveryBoundaryStep(particleTime, anchor));
                    // Production clears its anchor on recovery; measure against the captured original.
                    return { ...window.__v35FailureState(), elapsed: particleTime - anchor };
                };
                window.__v35Fail = variant => {
                    window.__v35StopFrames(); selectVariant(variant);
                    act('v35-active-home', () => { state.home = 'big'; state.motion.paused = false; state.motion.hidden = false; });
                    if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); }
                    if (['connecting', 'projecting'].includes(state.phase)) setPhase('waiting');
                    // A new episode must traverse real phase actions, never a direct failed-state assignment.
                    setPhase('connecting'); setPhase('failed'); window.__v35StopFrames();
                    if (state.phase !== 'failed') throw Error('Failure fixture did not enter through connecting -> failed');
                };
            });
            const waitForCAnchor = () => probe.waitForFunction(() => {
                if (cGlitch?.playState !== 'finished' || cFailureStartedAt === null) return false;
                // Polling may observe a real active-frame offset after onfinish; stop further RAFs, never rewrite the production clock/anchor.
                window.__v35StopFrames();
                window.__v35ObservedCAnchor = window.__v35FailureState();
                return true;
            });
            const evidence = [];
            const clockCases = [];
            for (const variant of ['A', 'C', 'D']) {
                await probe.evaluate(value => window.__v35Fail(value), variant);
                if (variant === 'C') {
                    const glitch = await probe.evaluate(() => ({ ...window.__v35FailureState(), running: cGlitch?.playState === 'running',
                        body: cGlitch?.effect.target === document.body, duration: cGlitch?.effect.getComputedTiming().duration }));
                    check(glitch.phase === 'failed' && glitch.anchor === null && glitch.running && glitch.body && glitch.duration === 250, 'C alone runs its real 250ms body glitch before anchoring recovery');
                    await probe.evaluate(() => window.__v35Advance(1000));
                    check(await probe.evaluate(() => state.phase === 'failed' && cFailureStartedAt === null), 'Active frames during C glitch must not begin the recovery interval');
                    await waitForCAnchor();
                }
                const timing = await probe.evaluate(() => {
                    window.__v35StopFrames(); const start = window.__v35FailureState();
                    if (start.anchor === null || start.elapsed < 0 || start.elapsed >= 7499) throw Error('Recovery boundary fixture already invalid: ' + JSON.stringify(start));
                    const remaining = 7499 - start.elapsed;
                    window.__v35Advance(remaining); const before = window.__v35FailureState();
                    const after = window.__v35CrossRecoveryBoundary(start.anchor);
                    return { start, remaining, before, after, broadcast: state.broadcast };
                });
                clockCases.push({ variant, test: 'recovery', ...timing });
                check(timing.start.anchor !== null && timing.before.phase === 'failed' && near(timing.before.elapsed, 7499, .001) &&
                    timing.after.elapsed >= 7500 && timing.after.elapsed <= 7500 + .001 && timing.after.phase === 'waiting' && timing.after.anchor === null && timing.broadcast,
                    variant + ': recover at the 7500ms boundary with only machine-scale epsilon: ' + JSON.stringify(timing));
                for (const mode of ['paused', 'hidden', 'simple']) {
                    await probe.evaluate(value => window.__v35Fail(value), variant);
                    if (variant === 'C') await waitForCAnchor();
                    const frozen = await probe.evaluate(mode => {
                        window.__v35StopFrames(); const base = window.__v35FailureState();
                        if (base.anchor === null || base.elapsed < 0 || base.elapsed + 2000 >= 7499) throw Error('Freeze fixture already past its test window: ' + JSON.stringify(base));
                        window.__v35Advance(2000);
                        if (mode === 'paused') state.motion.paused = true;
                        if (mode === 'hidden') state.motion.hidden = true;
                        if (mode === 'simple') state.home = 'simple';
                        render(); const before = window.__v35FailureState();
                        window.__v35Advance(20000); const after = window.__v35FailureState();
                        state.motion.paused = false; state.motion.hidden = false; state.home = 'big'; render();
                        const remaining = 7499 - after.elapsed;
                        window.__v35Advance(remaining); const almost = window.__v35FailureState();
                        const recovered = window.__v35CrossRecoveryBoundary(base.anchor);
                        return { base, before, after, remaining, almost, recovered };
                    }, mode);
                    clockCases.push({ variant, test: mode, ...frozen });
                    check(frozen.before.anchor === frozen.base.anchor && near(frozen.before.elapsed - frozen.base.elapsed, 2000, .001) && JSON.stringify(frozen.before) === JSON.stringify(frozen.after) &&
                        frozen.almost.phase === 'failed' && near(frozen.almost.elapsed, 7499, .001) && frozen.recovered.elapsed >= 7500 && frozen.recovered.elapsed <= 7500 + .001 &&
                        frozen.recovered.phase === 'waiting' && frozen.recovered.anchor === null,
                        variant + '/' + mode + ': freeze the exact snapshot, then recover at the unchanged 7500ms anchor boundary: ' + JSON.stringify(frozen));
                }
                for (const action of ['off', 'retry', 'reset', 'projected']) {
                    await probe.evaluate(value => window.__v35Fail(value), variant);
                    const stale = await probe.evaluate(action => {
                        const oldGlitch = cGlitch; const oldFinish = oldGlitch?.onfinish;
                        window.__v35Advance(1000);
                        if (action === 'off') toggleBroadcast();
                        if (action === 'retry') setPhase('connecting');
                        if (action === 'reset') setPhase('waiting');
                        if (action === 'projected') { setPhase('connecting'); setPhase('projecting'); }
                        const before = { phase: state.phase, broadcast: state.broadcast, variant: state.variant };
                        const clock = particleTime;
                        if (oldFinish) oldFinish.call(oldGlitch, { currentTarget: oldGlitch });
                        window.__v35Advance(12000);
                        return { before, after: { phase: state.phase, broadcast: state.broadcast, variant: state.variant }, anchor: cFailureStartedAt, projectedClockFrozen: particleTime === clock };
                    }, action);
                    check(JSON.stringify(stale.before) === JSON.stringify(stale.after) && stale.anchor === null && (action !== 'projected' || stale.projectedClockFrozen), variant + '/' + action + ': clear the episode; reject a late C callback and stale recovery');
                }
                for (const action of ['theme', 'history']) {
                    await probe.evaluate(value => window.__v35Fail(value), variant);
                    if (variant === 'C') await waitForCAnchor();
                    const continued = await probe.evaluate(action => {
                        window.__v35StopFrames(); const base = window.__v35FailureState();
                        if (base.anchor === null || base.elapsed < 0 || base.elapsed + 2000 >= 7499) throw Error('Continuation fixture already past its test window: ' + JSON.stringify(base));
                        window.__v35Advance(2000); const before = window.__v35FailureState();
                        const next = { A: 'C', C: 'D', D: 'A' }[state.variant];
                        if (action === 'theme') selectVariant(next);
                        else {
                            const url = new URL(location.href); url.searchParams.set('variant', next);
                            history.pushState({}, '', url); dispatchEvent(new PopStateEvent('popstate'));
                        }
                        window.__v35StopFrames(); const changed = window.__v35FailureState();
                        const remaining = 7499 - changed.elapsed;
                        window.__v35Advance(remaining); const almost = window.__v35FailureState();
                        const final = window.__v35CrossRecoveryBoundary(base.anchor);
                        return { base, before, changed, remaining, almost, final, variant: state.variant, next };
                    }, action);
                    clockCases.push({ variant, test: action, ...continued });
                    check(near(continued.before.elapsed - continued.base.elapsed, 2000, .001) && continued.changed.anchor === continued.before.anchor && near(continued.changed.elapsed, continued.before.elapsed, .001) &&
                        continued.almost.phase === 'failed' && near(continued.almost.elapsed, 7499, .001) && continued.final.elapsed >= 7500 && continued.final.elapsed <= 7500 + .001 &&
                        continued.final.phase === 'waiting' && continued.final.anchor === null && continued.variant === continued.next,
                        variant + '/' + action + ': continue the exact same episode/elapsed time, never reset or cancel it: ' + JSON.stringify(continued));
                }
                evidence.push(variant);
            }
            await probe.evaluate(() => {
                window.__v35Fail('C');
                const previous = cGlitch; const callback = previous.onfinish;
                setPhase('connecting'); setPhase('failed'); window.__v35StopFrames();
                if (previous === cGlitch || cFailureStartedAt !== null) throw Error('New C episode did not replace its glitch and clear its anchor');
                callback.call(previous, { currentTarget: previous });
                if (cFailureStartedAt !== null) throw Error('A previous glitch completion anchored the new episode');
            });
            await waitForCAnchor();
            const replay = await probe.evaluate(() => {
                window.__v35StopFrames(); const base = window.__v35FailureState();
                const anchor = cFailureStartedAt; const current = cGlitch; const callback = current.onfinish;
                window.__v35Advance(1000); callback.call(current, { currentTarget: current });
                return { base, anchor, after: cFailureStartedAt, elapsed: particleTime - cFailureStartedAt };
            });
            check(replay.anchor === replay.after && near(replay.elapsed - replay.base.elapsed, 1000, .001), 'Replayed current-glitch completion must not replace an existing anchor: ' + JSON.stringify(replay));
            const leavingC = await probe.evaluate(() => {
                window.__v35Fail('C'); const previous = cGlitch; const callback = previous.onfinish;
                selectVariant('A'); window.__v35StopFrames(); const anchor = cFailureStartedAt;
                window.__v35Advance(1000); callback.call(previous, { currentTarget: previous });
                return { anchor, after: cFailureStartedAt, elapsed: particleTime - cFailureStartedAt, canceled: cGlitch === null };
            });
            check(leavingC.canceled && leavingC.anchor !== null && leavingC.after === leavingC.anchor && near(leavingC.elapsed, 1000, .001), 'Leaving C mid-glitch must cancel only the glitch and ignore its late callback, not reset the episode');
            const cap = await probe.evaluate(() => {
                window.__v35Fail('A'); const before = particleTime;
                previousFrameTime = performance.now(); animateStandby(previousFrameTime + 5000); window.__v35StopFrames();
                return particleTime - before;
            });
            check(near(cap, 80, .001), 'Production shared active clock must cap one delayed frame at 80ms');
            await probe.evaluate(() => {
                window.__v35Fail('A'); previousFrameTime = null; animationFrame = requestAnimationFrame(animateStandby);
            });
            const before = await probe.evaluate(() => particleTime);
            await probe.waitForTimeout(150);
            const actual = await probe.evaluate(() => ({ ...window.__v35FailureState(), phase: state.phase }));
            check(actual.clock > before + 30 && actual.phase === 'failed' && actual.anchor !== null, 'Include a short real-play failure proof, not only injected clock checks');
            return { bindings: ['particleTime', 'cFailureStartedAt', 'animateStandby'], variants: evidence, frozenModes: 3,
                clearedActions: 4, continuedActions: 2, guardedLateCallbacks: 3, frameCapMs: cap, realActiveMs: actual.clock - before, clockCases, replay };
        });
        await run('A wide landscape name-to-eyebrow Range gap', async () => {
            const matchesOffset = (raw, px, percent, offset, height) => {
                const compact = String(raw).replace(/\s+/g, '');
                if (compact === `calc(${percent}%-${offset}px)`) return true;
                return Number.isFinite(px) && Math.abs(px - (percent / 100 * height - offset)) <= .6;
            };
            const matchesPercent = (raw, px, percent, height) => String(raw).trim() === percent + '%' || Number.isFinite(px) && Math.abs(px - percent / 100 * height) <= .6;
            const inside = (box, width, height) => box.width > 0 && box.height > 0 && box.left >= -1 && box.top >= -1 && box.right <= width + 1 && box.bottom <= height + 1;
            const readLayout = async (width, height) => {
                await probe.setViewportSize({ width, height });
                const rows = [];
                for (const phase of ['off', 'waiting', 'connecting', 'failed']) {
                    rows.push(await probe.evaluate(async phase => {
                        const px = value => { const n = Number.parseFloat(value); return Number.isFinite(n) ? n : null; };
                        const rangeBox = node => {
                            const range = document.createRange();
                            range.selectNodeContents(node);
                            const rect = range.getBoundingClientRect();
                            return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right, width: rect.width, height: rect.height };
                        };
                        // `finished` can resolve before onfinish writes the next fade, so each pass also waits two frames.
                        const settleStatusCopy = async () => {
                            const ids = ['status-title', 'status-eyebrow', 'connection-hint'];
                            const frame = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                            for (let pass = 0; pass < 8; pass += 1) {
                                const running = [];
                                for (const id of ids) {
                                    const change = mainCopyChanges.get(document.getElementById(id));
                                    if (change && change.animation && change.animation.playState !== 'finished') running.push(change.animation.finished.catch(() => {}));
                                }
                                if (running.length) await Promise.all(running);
                                await frame();
                                if (!ids.some(id => mainCopyChanges.has(document.getElementById(id)))) return;
                            }
                        };
                        state.variant = 'A';
                        state.home = 'big';
                        state.motion.paused = false;
                        state.motion.hidden = false;
                        if (phase === 'off') resizeCanvas();
                        state.broadcast = phase !== 'off';
                        state.phase = phase;
                        render();
                        await settleStatusCopy();
                        const title = document.querySelector('#status-title');
                        const eyebrow = document.querySelector('#status-eyebrow');
                        const name = document.querySelector('#standby-view .device-name');
                        const device = document.querySelector('#standby-view .device');
                        const center = document.querySelector('#standby-view .center');
                        const expectedEyebrow = phases[phase].eyebrow.replace(' \u00b7 SIMULATED', '');
                        if (mainCopyChanges.has(title) || mainCopyChanges.has(eyebrow) || title.textContent !== phases[phase].title || eyebrow.textContent !== expectedEyebrow) {
                            throw Error('stale A copy at ' + phase + ': ' + title.textContent + ' / ' + eyebrow.textContent);
                        }
                        const deviceCss = getComputedStyle(device);
                        const centerCss = getComputedStyle(center);
                        return {
                            phase, gap: rangeBox(eyebrow).top - rangeBox(name).bottom, name: rangeBox(name), eyebrow: rangeBox(eyebrow),
                            titleTop: rangeBox(title).top, centerBoxTop: center.getBoundingClientRect().top,
                            deviceTopRaw: deviceCss.top, deviceTopPx: px(deviceCss.top), centerTopRaw: centerCss.top, centerTopPx: px(centerCss.top),
                            nameFont: getComputedStyle(name).fontSize, eyebrowFont: getComputedStyle(eyebrow).fontSize,
                            deviceDisplay: deviceCss.display, deviceRects: device.getClientRects().length,
                            stageHeight: document.querySelector('#stage').clientHeight, innerWidth, innerHeight
                        };
                    }, phase));
                }
                return { stageHeight: rows[0].stageHeight, innerWidth: rows[0].innerWidth, innerHeight: rows[0].innerHeight, rows };
            };
            const wideSizes = [[1920, 821], [1920, 900], [1920, 1080], [2560, 1080], [2560, 1440], [3440, 1440], [3840, 2160], [901, 900]];
            const wide = [];
            for (const [width, height] of wideSizes) {
                const sample = await readLayout(width, height);
                const label = width + 'x' + height;
                check(sample.innerWidth === width && sample.innerHeight === height && sample.stageHeight === height, label + ': viewport');
                const gaps = sample.rows.map(row => row.gap);
                for (const row of sample.rows) {
                    check(row.deviceDisplay !== 'none' && row.deviceRects > 0, label + ' ' + row.phase + ': device visible');
                    check(row.nameFont === '16px' && row.eyebrowFont === '9px', label + ' ' + row.phase + ': fonts stay 16px/9px');
                    check(matchesPercent(row.centerTopRaw, row.centerTopPx, 43, sample.stageHeight), label + ' ' + row.phase + ': center stays 43%');
                    check(matchesOffset(row.deviceTopRaw, row.deviceTopPx, 43, 111, sample.stageHeight), label + ' ' + row.phase + ': device uses calc(43% - 111px)');
                    check(row.gap >= 24 && row.gap <= 40, label + ' ' + row.phase + ': Range gap ' + row.gap);
                    check(inside(row.name, width, height) && inside(row.eyebrow, width, height), label + ' ' + row.phase + ': name and eyebrow inside viewport');
                }
                check(Math.max(...gaps) - Math.min(...gaps) <= 1.5, label + ': phase gap spread ' + gaps.join(','));
                wide.push({ label, gap: gaps[0] });
            }
            const wideGaps = wide.map(sample => sample.gap);
            check(Math.max(...wideGaps) - Math.min(...wideGaps) <= 1.5, 'wide Range gap is height-independent: ' + JSON.stringify(wide));
            const portrait = await readLayout(390, 844);
            check(portrait.innerWidth === 390 && portrait.innerHeight === 844 && portrait.stageHeight === 844, 'portrait viewport');
            check(portrait.rows.every(row => matchesOffset(row.deviceTopRaw, row.deviceTopPx, 49, 127, portrait.stageHeight) && row.deviceDisplay !== 'none' && row.deviceRects > 0), 'portrait keeps calc(49% - 127px) and a visible device');
            check(portrait.rows.every(row => near(row.gap, 48.9140625, 1) && row.nameFont === '16px' && row.eyebrowFont === '9px' && inside(row.name, 390, 844) && inside(row.eyebrow, 390, 844)), 'portrait Range gap stays about 48.9 inside the viewport: ' + portrait.rows.map(row => row.gap).join(','));
            const short = await readLayout(568, 320);
            check(short.innerWidth === 568 && short.innerHeight === 320, 'short viewport');
            check(short.rows.every(row => row.deviceDisplay === 'none' && row.deviceRects === 0), '568x320 device stays hidden in every phase');
            const redTargets = [[1920, 1080, 6.6171875], [2560, 1440, -14.9921875]];
            const reds = [];
            for (const [width, height, expected] of redTargets) {
                await probe.setViewportSize({ width, height });
                const red = await probe.evaluate(async () => {
                    const px = value => { const n = Number.parseFloat(value); return Number.isFinite(n) ? n : null; };
                    const rangeBox = node => { const range = document.createRange(); range.selectNodeContents(node); return range.getBoundingClientRect(); };
                    // `finished` can resolve before onfinish writes the next fade, so each pass also waits two frames.
                    const settleStatusCopy = async () => {
                        const ids = ['status-title', 'status-eyebrow', 'connection-hint'];
                        const frame = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                        for (let pass = 0; pass < 8; pass += 1) {
                            const running = [];
                            for (const id of ids) {
                                const change = mainCopyChanges.get(document.getElementById(id));
                                if (change && change.animation && change.animation.playState !== 'finished') running.push(change.animation.finished.catch(() => {}));
                            }
                            if (running.length) await Promise.all(running);
                            await frame();
                            if (!ids.some(id => mainCopyChanges.has(document.getElementById(id)))) return;
                        }
                    };
                    state.variant = 'A'; state.home = 'big'; state.motion.paused = false; state.motion.hidden = false; state.broadcast = false; state.phase = 'off';
                    render(); resizeCanvas(); await settleStatusCopy();
                    const device = document.querySelector('#standby-view .device');
                    const center = document.querySelector('#standby-view .center');
                    const title = document.querySelector('#status-title');
                    const eyebrow = document.querySelector('#status-eyebrow');
                    const name = document.querySelector('#standby-view .device-name');
                    const expectedEyebrow = phases.off.eyebrow.replace(' \u00b7 SIMULATED', '');
                    if (mainCopyChanges.has(title) || mainCopyChanges.has(eyebrow) || title.textContent !== phases.off.title || eyebrow.textContent !== expectedEyebrow) {
                        throw Error('stale A copy at off: ' + title.textContent + ' / ' + eyebrow.textContent);
                    }
                    const previous = device.getAttribute('style');
                    const before = { titleTop: rangeBox(title).top, centerBoxTop: center.getBoundingClientRect().top, centerTopRaw: getComputedStyle(center).top, centerTopPx: px(getComputedStyle(center).top) };
                    let during;
                    try {
                        device.style.setProperty('top', 'calc(49% - 151px)');
                        const deviceCss = getComputedStyle(device);
                        const centerCss = getComputedStyle(center);
                        during = { gap: rangeBox(eyebrow).top - rangeBox(name).bottom, titleTop: rangeBox(title).top, centerBoxTop: center.getBoundingClientRect().top,
                            deviceTopRaw: deviceCss.top, deviceTopPx: px(deviceCss.top), centerTopRaw: centerCss.top, centerTopPx: px(centerCss.top) };
                    } finally {
                        if (previous === null) device.removeAttribute('style');
                        else device.setAttribute('style', previous);
                    }
                    await settleStatusCopy();
                    const deviceCss = getComputedStyle(device);
                    return { stageHeight: document.querySelector('#stage').clientHeight, before, during,
                        restored: { gap: rangeBox(eyebrow).top - rangeBox(name).bottom, deviceTopRaw: deviceCss.top, deviceTopPx: px(deviceCss.top),
                            title: title.textContent, eyebrow: eyebrow.textContent, phase: state.phase,
                            pending: mainCopyChanges.has(title) || mainCopyChanges.has(eyebrow) } };
                });
                const label = width + 'x' + height;
                check(matchesOffset(red.during.deviceTopRaw, red.during.deviceTopPx, 49, 151, red.stageHeight), label + ': inline old anchor resolved');
                check(near(red.during.gap, expected, 1) && (red.during.gap < 24 || red.during.gap > 40), label + ': old anchor restores RED gap ' + red.during.gap);
                check(matchesPercent(red.before.centerTopRaw, red.before.centerTopPx, 43, red.stageHeight) && matchesPercent(red.during.centerTopRaw, red.during.centerTopPx, 43, red.stageHeight), label + ': center stays 43% under the old device anchor');
                check(near(red.during.titleTop, red.before.titleTop, .6) && near(red.during.centerBoxTop, red.before.centerBoxTop, .6), label + ': title and center box stay put');
                check(red.restored.gap >= 24 && red.restored.gap <= 40 && matchesOffset(red.restored.deviceTopRaw, red.restored.deviceTopPx, 43, 111, red.stageHeight), label + ': restored gap ' + red.restored.gap);
                check(red.restored.phase === 'off' && red.restored.title === '接收已關閉' && red.restored.eyebrow === 'BROADCAST OFF' && !red.restored.pending, label + ': restored copy is the fresh off phase');
                reds.push({ label, gap: red.during.gap, restored: red.restored.gap });
            }
            await probe.setViewportSize({ width: 1920, height: 1080 });
            const baselines = await probe.evaluate(() => {
                const px = value => { const n = Number.parseFloat(value); return Number.isFinite(n) ? n : null; };
                const read = variant => {
                    state.variant = variant; state.home = 'big'; state.motion.hidden = false; render(); resizeCanvas();
                    const device = document.querySelector('#standby-view .device');
                    const center = document.querySelector('#standby-view .center');
                    const deviceCss = getComputedStyle(device);
                    const centerCss = getComputedStyle(center);
                    return { deviceTopRaw: deviceCss.top, deviceTopPx: px(deviceCss.top), deviceDisplay: deviceCss.display, centerTopRaw: centerCss.top, centerTopPx: px(centerCss.top),
                        stageHeight: document.querySelector('#stage').clientHeight };
                };
                const c = read('C');
                read('D');
                const device = document.querySelector('#standby-view .device');
                const center = document.querySelector('#standby-view .center');
                const beforeStyle = device.getAttribute('style');
                const rectOf = node => {
                    const rect = node.getBoundingClientRect();
                    return { top: rect.top, bottom: rect.bottom, left: rect.left, width: rect.width, height: rect.height };
                };
                const sample = () => {
                    const deviceCss = getComputedStyle(device);
                    const centerCss = getComputedStyle(center);
                    return {
                        rect: rectOf(device),
                        deviceTopRaw: deviceCss.top, deviceTopPx: px(deviceCss.top),
                        deviceBottomRaw: deviceCss.bottom,
                        centerTopRaw: centerCss.top, centerTopPx: px(centerCss.top),
                        stageHeight: document.querySelector('#stage').clientHeight
                    };
                };
                // getComputedStyle().top is the used px when top is auto and bottom is set.
                const before = sample();
                let autoAnchor;
                let leaked;
                try {
                    device.style.setProperty('top', 'auto');
                    autoAnchor = sample();
                    device.style.setProperty('top', 'calc(43% - 111px)');
                    leaked = sample();
                } finally {
                    if (beforeStyle === null) device.removeAttribute('style');
                    else device.setAttribute('style', beforeStyle);
                }
                const restored = sample();
                const result = { C: c, D: { before, autoAnchor, leaked, restored, beforeStyle, afterStyle: device.getAttribute('style') } };
                state.variant = 'A'; state.home = 'big'; state.broadcast = false; state.phase = 'off'; render();
                return result;
            });
            check(baselines.C.deviceDisplay === 'none' && matchesOffset(baselines.C.deviceTopRaw, baselines.C.deviceTopPx, 49, 151, baselines.C.stageHeight) && !matchesOffset(baselines.C.deviceTopRaw, baselines.C.deviceTopPx, 43, 111, baselines.C.stageHeight), 'C keeps the old device rule and stays hidden: ' + baselines.C.deviceTopRaw);
            check(matchesPercent(baselines.C.centerTopRaw, baselines.C.centerTopPx, 49, baselines.C.stageHeight), 'C center stays 49%');
            const d = baselines.D;
            const sameUsed = (left, right) => near(left.top, right.top) && near(left.bottom, right.bottom) && near(left.left, right.left);
            check(d.before.rect.width > 0 && d.before.rect.height > 0, 'D device stays visible: ' + JSON.stringify(d.before.rect));
            check(matchesPercent(d.before.centerTopRaw, d.before.centerTopPx, 47, d.before.stageHeight), 'D center stays 47%: ' + d.before.centerTopRaw);
            check(sameUsed(d.before.rect, d.autoAnchor.rect) && matchesPercent(d.autoAnchor.centerTopRaw, d.autoAnchor.centerTopPx, 47, d.autoAnchor.stageHeight), 'inline top:auto keeps the used bottom/top/left anchor and 47% center; a leaked specified top would move the box: ' + JSON.stringify({ before: d.before.rect, auto: d.autoAnchor.rect }));
            check(matchesOffset(d.leaked.deviceTopRaw, d.leaked.deviceTopPx, 43, 111, d.leaked.stageHeight) && Math.abs(d.leaked.rect.top - d.before.rect.top) > 40 && matchesPercent(d.leaked.centerTopRaw, d.leaked.centerTopPx, 47, d.leaked.stageHeight), 'inline A offset on D resolves and moves the used top off the bottom anchor: ' + d.before.rect.top + ' -> ' + d.leaked.rect.top);
            check(sameUsed(d.before.rect, d.restored.rect) && d.afterStyle === d.beforeStyle && matchesPercent(d.restored.centerTopRaw, d.restored.centerTopPx, 47, d.restored.stageHeight), 'D restores the exact inline style, used box, and 47% center');
            return { wide, portraitGap: portrait.rows.map(row => row.gap), reds };
        });
        reviewAfter = await reviewHash(primary);
        check(reviewBefore === reviewAfter, 'Real review bytes/SHA-256 changed during read-only verification');
        check(blockedWrites.length === 0, 'The UI attempted a write: ' + JSON.stringify(blockedWrites));
        check(pageErrors.length === 0, 'Browser errors: ' + pageErrors.join('\n'));
    } catch (error) {
        failures.push({ name: 'suite/lifecycle', message: String(error.message || error) });
    } finally {
        // Lifecycle is executable, including failure paths; never close the caller's browser/context/page.
        for (const context of ownedContexts) {
            try { await context.close(); }
            catch (error) { failures.push({ name: 'context.close', message: String(error.message || error) }); }
        }
        const remaining = browser.contexts();
        for (const context of ownedContexts) if (remaining.includes(context)) failures.push({ name: 'context leak', message: 'An owned context survived finally' });
        for (const context of initialContexts) if (!remaining.includes(context)) failures.push({ name: 'caller lifecycle', message: 'A pre-existing context was closed' });
    }
    const report = { results, failures, pageErrors, blockedWrites, baselineSha256, reviewBefore, reviewAfter,
        elapsedMs: Date.now() - startedAt, ownedContexts: ownedContexts.size, closedContexts: [...ownedContexts].filter(context => !browser.contexts().includes(context)).length };
    if (failures.length) throw Error('V35 specification failed:\n' + JSON.stringify(report, null, 2));
    return report;
}
