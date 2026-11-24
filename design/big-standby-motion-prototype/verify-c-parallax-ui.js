// Run with Playwright browser_run_code_unsafe against the preview server.
async (page) => {
    const check = (value, message) => { if (!value) throw Error(message); };
    const openDrawer = async () => {
        if (!(await page.locator('#settings').evaluate(dialog => dialog.open))) await page.locator('#settings-handle').click();
    };
    const closeDrawer = async () => {
        if (await page.locator('#settings').evaluate(dialog => dialog.open)) await page.locator('#close-settings').press('Enter');
        await page.waitForFunction(() => !settings.open && !settings.getClientRects().length);
    };
    const errors = [];
    const onError = error => errors.push(error.message);
    page.on('pageerror', onError);
    const width = 1440;
    const height = 1000;
    await page.setViewportSize({ width, height });
    await page.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&log=rail&v=34');
    try {
        await page.evaluate(() => cGyroscope.disable());
        await openDrawer();
        await page.locator('#show-prototype-tools').check();
        await closeDrawer();
        await page.mouse.move(width / 2, height / 2);
        await page.waitForFunction(() => Math.hypot(cParallax.x, cParallax.y, cParallax.informationX, cParallax.informationY) < .001);
        const cursorBaseline = await page.locator('.c-standby-plate').boundingBox();
        await page.mouse.move(width / 2 + 80, height / 2 + 50);
        await page.waitForFunction(() => Math.abs(cParallax.x - cParallax.targetX) < .001 && Math.abs(cParallax.y - cParallax.targetY) < .001 &&
            Math.abs(cParallax.informationX - cParallax.targetX) < .001 && Math.abs(cParallax.informationY - cParallax.targetY) < .001);
        const cursorMoved = await page.locator('.c-standby-plate').boundingBox();
        const ordinaryCursorMovement = Math.hypot(cursorMoved.x - cursorBaseline.x, cursorMoved.y - cursorBaseline.y);
        check(ordinaryCursorMovement > .5 && ordinaryCursorMovement < 5, 'Ordinary 80px/50px cursor input must produce only gentle information movement, got ' + ordinaryCursorMovement);
        await page.mouse.move(width / 2, height / 2);
        await page.waitForFunction(() => Math.hypot(cParallax.x, cParallax.y, cParallax.informationX, cParallax.informationY) < .001);
        const information = ['.brand', '.connection-hint', '.c-standby-plate'];
        const controls = ['#settings-handle', '#broadcast-dock'];
        check(await page.evaluate(selectors => selectors.every(selector => {
            const box = document.querySelector(selector).getBoundingClientRect();
            return box.width > 0 && box.height > 0;
        }), controls), 'Measure visible standby controls, never hidden drawer proxies');
        const selectors = [...information, ...controls];
        check(await page.evaluate(() => !document.querySelector('#standby-view').contains(document.querySelector('#particle-canvas'))),
            'Particles must not inherit the corner-title layer transform');
        const before = await page.evaluate(selectors => selectors.map(selector => document.querySelector(selector).getBoundingClientRect().toJSON()), selectors);
        for (const [x, y] of [[-.7, -.6], [.7, -.6], [-.7, .6], [.7, .6]]) {
            await page.mouse.move((x + 1) * width / 2, (y + 1) * height / 2);
            await page.waitForFunction(({ x, y }) => Math.abs(cParallax.x - Math.max(-1, Math.min(1, x))) < .025 &&
                Math.abs(cParallax.y - Math.max(-1, Math.min(1, y))) < .025 &&
                Math.abs(cParallax.informationX - x) < .025 && Math.abs(cParallax.informationY - y) < .025, { x, y });
            const sample = await page.evaluate(selectors => ({
                transform: document.querySelector('#particle-canvas').style.transform,
                events: getComputedStyle(document.querySelector('#particle-canvas')).pointerEvents,
                rects: selectors.map(selector => document.querySelector(selector).getBoundingClientRect().toJSON())
            }), selectors);
            check(/^translate3d\([^)]+\)$/.test(sample.transform) && sample.events === 'none',
                'The pointer may only translate the non-interactive particle plane');
            check(sample.rects.slice(0, information.length).every((rect, index) =>
                Math.abs(rect.width - before[index].width) < .05 && Math.abs(rect.height - before[index].height) < .05),
                'Information size must stay constant on both the leading and receding sides');
            check(sample.rects.slice(0, information.length).every((rect, index) => Math.hypot(rect.x - before[index].x, rect.y - before[index].y) > 1),
                'Brand, connection information and status must share their independent title-layer translation');
            check(sample.rects.slice(information.length).every((rect, index) => Math.abs(rect.x - before[index + information.length].x) < .2 &&
                Math.abs(rect.y - before[index + information.length].y) < .2), 'Prototype controls must stay anchored');
        }
        await openDrawer();
        check(await page.locator('#settings').evaluate(node => node.open), 'Settings remain clickable with a moving background');
        await closeDrawer();

        const point = async () => {
            await page.mouse.move(850, 400);
            await page.mouse.move(1150, 600);
            await page.waitForFunction(() => document.querySelector('#particle-canvas').style.transform.startsWith('translate3d('));
        };
        const neutral = async message => check(await page.evaluate(() => document.querySelector('#particle-canvas').style.transform === '' &&
            !document.querySelector('#standby-view').style.getPropertyValue('--c-parallax-transform') &&
            cParallax.informationX === 0 && cParallax.informationY === 0), message);

        await point();
        await page.evaluate(() => act('test-pause', () => { state.motion.paused = true; }));
        await neutral('Pause immediately resets parallax');
        await page.evaluate(() => act('test-resume', () => { state.motion.paused = false; }));
        await neutral('Resume waits for fresh pointer input');

        const preferenceInputs = [];
        for (const reducedMotion of ['reduce', 'no-preference']) {
            await point();
            const target = await page.evaluate(() => ({ x: cParallax.targetX, y: cParallax.targetY }));
            await page.emulateMedia({ reducedMotion });
            await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            await page.waitForFunction(() => Math.abs(cParallax.x - cParallax.targetX) < .001 && Math.abs(cParallax.y - cParallax.targetY) < .001 &&
                Math.abs(cParallax.informationX - cParallax.targetX) < .001 && Math.abs(cParallax.informationY - cParallax.targetY) < .001);
            const sample = await page.evaluate(() => ({ x: cParallax.x, y: cParallax.y,
                informationX: cParallax.informationX, informationY: cParallax.informationY,
                targetX: cParallax.targetX, targetY: cParallax.targetY,
                transform: document.querySelector('#particle-canvas').style.transform }));
            check(sample.targetX === target.x && sample.targetY === target.y && Math.hypot(sample.x, sample.y) > .1 &&
                sample.transform.startsWith('translate3d('), 'System preferences must not reset or block pointer parallax');
            preferenceInputs.push(sample);
        }
        check(['x', 'y', 'informationX', 'informationY'].every(key => Math.abs(preferenceInputs[0][key] - preferenceInputs[1][key]) < .002),
            'Both system preferences must produce the same particle and information-layer displacement');

        await point();
        await openDrawer();
        await page.locator('#next-variant').click();
        await closeDrawer();
        await neutral('D has no C parallax');
        await openDrawer();
        await page.locator('#previous-variant').click();
        await closeDrawer();
        await neutral('Returning to C waits for new input');

        await point();
        await page.evaluate(() => { if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); } setPhase('connecting'); setPhase('projecting'); });
        await neutral('Projection never inherits parallax');
        await page.evaluate(() => setPhase('waiting'));
        await point();
        await page.evaluate(() => act('test-custom-background', () => { state.media.status = 'ready'; }));
        await neutral('Custom backgrounds do not inherit the particle transform');
        await page.evaluate(() => act('test-generated-background', () => { state.media.status = 'none'; }));

        await point();
        await page.evaluate(() => window.dispatchEvent(new Event('blur')));
        await neutral('Losing window focus resets parallax');
        await point();
        await page.evaluate(() => act('test-hidden', () => { state.motion.hidden = true; }));
        await neutral('A hidden page resets parallax');
        await page.evaluate(() => act('test-visible', () => { state.motion.hidden = false; }));
        await point();
        await page.evaluate(() => document.querySelector('#stage').dispatchEvent(new PointerEvent('pointerleave', { pointerType: 'mouse' })));
        await neutral('Pointer leave resets parallax');

        let combinedCases = 0;
        let maximumComposition = 0;
        for (const [width, height] of [[1440, 1000], [800, 528], [390, 844], [320, 568], [667, 375], [568, 320]]) {
            await page.setViewportSize({ width, height });
            await page.waitForFunction(() => canvasWidth === stage.clientWidth && canvasHeight === stage.clientHeight);
            const result = await page.evaluate(() => {
                const nativeRandom = Math.random;
                const failures = [];
                const callout = document.querySelector('#c-hud-callout');
                callout.style.transition = 'none';
                const intersects = (a, b) => Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 &&
                    Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1;
                let cases = 0;
                let maximumDisplacement = 0;
                try {
                    for (const style of cLogStyles) {
                        state.cLogStyle = style;
                        render();
                        for (const [x, y] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) {
                            cParallax.setPointer(x, y);
                            for (let step = 0; step < 60; step += 1) cParallax.update(.1);
                            const canvas = document.querySelector('#particle-canvas');
                            const surface = canvas.getBoundingClientRect();
                            const viewport = document.querySelector('#stage').getBoundingClientRect();
                            if (surface.left > viewport.left || surface.top > viewport.top ||
                                surface.right < viewport.right || surface.bottom < viewport.bottom) {
                                failures.push({ style, x, y, issue: 'translated canvas exposes an edge', surface: surface.toJSON(), viewport: viewport.toJSON(), buffer: cParticlePadding, canvasWidth, canvasHeight });
                            }
                            const ratio = Math.min(devicePixelRatio || 1, 2);
                            const buffer = Math.ceil(Math.min(canvasWidth, canvasHeight) * .02);
                            const drawing = canvas.getContext('2d').getTransform();
                            if (Math.abs(surface.width * ratio - canvas.width) > 1 ||
                                Math.abs(surface.height * ratio - canvas.height) > 1 ||
                                drawing.a !== ratio || drawing.d !== ratio || drawing.b !== 0 || drawing.c !== 0 ||
                                drawing.e !== buffer * ratio || drawing.f !== buffer * ratio) {
                                failures.push({ style, x, y, issue: 'border buffer scales the particle drawing' });
                            }
                            for (const seed of [.01, .5, .99]) {
                                Math.random = () => seed;
                                showRandomCHud();
                                updateCHudOrbit(cHudOrbit.startedAt);
                                const information = document.querySelector('#standby-view');
                                const transform = information.style.getPropertyValue('--c-parallax-transform');
                                information.style.removeProperty('--c-parallax-transform');
                                const selectors = ['.brand', '.connection-hint', '.c-standby-plate', '#c-hud-dialog'];
                                const neutral = selectors.map(selector => document.querySelector(selector).getBoundingClientRect());
                                information.style.setProperty('--c-parallax-transform', transform);
                                // Measure parallax and the smaller local ellipse independently.
                                const translationOnly = selectors.map(selector => document.querySelector(selector).getBoundingClientRect());
                                translationOnly.forEach((rect, index) => {
                                    const baseline = neutral[index];
                                    if (Math.abs(rect.width - baseline.width) > .05 || Math.abs(rect.height - baseline.height) > .05) {
                                        failures.push({ style, x, y, seed, selector: selectors[index], issue: 'parallax changes content size' });
                                    }
                                    const displacement = Math.max(
                                        Math.abs(rect.left - baseline.left) / innerWidth,
                                        Math.abs(rect.right - baseline.right) / innerWidth,
                                        Math.abs(rect.top - baseline.top) / innerHeight,
                                        Math.abs(rect.bottom - baseline.bottom) / innerHeight);
                                    if (displacement >= .1) failures.push({ style, x, y, seed, selector: selectors[index], issue: 'gyro displacement exceeds ten percent' });
                                });
                                for (const time of [770, 1540, 2310, 3135, 3905, 4675]) {
                                    updateCHudOrbit(cHudOrbit.startedAt + time);
                                    const boxes = selectors.map(selector => ({ selector, rect: document.querySelector(selector).getBoundingClientRect() }));
                                    for (const [index, { selector, rect }] of boxes.entries()) {
                                        const baseline = neutral[index];
                                        const displacement = Math.max(
                                            Math.abs(rect.left - baseline.left) / innerWidth,
                                            Math.abs(rect.right - baseline.right) / innerWidth,
                                            Math.abs(rect.top - baseline.top) / innerHeight,
                                            Math.abs(rect.bottom - baseline.bottom) / innerHeight);
                                        maximumDisplacement = Math.max(maximumDisplacement, displacement);
                                        if (rect.left < 0 || rect.top < 0 || rect.right > innerWidth || rect.bottom > innerHeight) {
                                            failures.push({ style, x, y, seed, time, selector, issue: 'clipped', rect: rect.toJSON() });
                                        }
                                    }
                                    const dialog = boxes.at(-1).rect;
                                    for (const { selector, rect } of boxes.slice(0, -1)) {
                                        if (intersects(dialog, rect)) failures.push({ style, x, y, seed, time, selector, issue: 'covered by dialog' });
                                    }
                                    cases += 1;
                                }
                            }
                        }
                    }
                } finally { Math.random = nativeRandom; cParallax.reset(); }
                return { failures, cases, maximumDisplacement };
            });
            check(result.failures.length === 0, JSON.stringify({ width, height, count: result.failures.length, failures: result.failures.slice(0, 8) }));
            combinedCases += result.cases;
            maximumComposition = Math.max(maximumComposition, result.maximumDisplacement);
        }
        check(errors.length === 0, 'Browser errors: ' + errors.join('; '));
        return { pointerDirections: 4, ordinaryCursorMovement, movingInformation: information.length, stationaryControls: controls.length,
            combinedCases, maximumCompositionPercent: maximumComposition * 100, systemPreferences: 'identical parallax',
            clicks: 'passed', resetGates: 'passed', realSensor: 'not requested' };
    } finally { page.off('pageerror', onError); }
}
