// Run with Playwright browser_run_code_unsafe against the preview server.
async (page) => {
    const check = (value, message) => { if (!value) throw Error(message); };
    const openDrawer = async () => {
        if (!(await page.locator('#settings').evaluate(dialog => dialog.open))) await page.locator('#settings-handle').click();
    };
    const closeDrawer = async () => {
        if (await page.locator('#settings').evaluate(dialog => dialog.open)) await page.locator('#close-settings').click();
        await page.waitForFunction(() => !settings.open && !settings.getClientRects().length);
    };
    const selectStyle = async style => {
        await openDrawer();
        await page.locator('#c-log-style').selectOption(style);
        await closeDrawer();
    };
    const errors = [];
    const onError = error => errors.push(error.message);
    page.on('pageerror', onError);
    const styles = ['rail', 'frame', 'cap', 'index', 'footnote', 'bracket', 'telemetry', 'stamp', 'terminal', 'fold'];
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&log=rail&v=34');
    try {
        await openDrawer();
        await page.locator('#show-prototype-tools').check();
        await closeDrawer();
        const drift = await page.evaluate(() => {
            const selectors = ['.brand', '.connection-hint', '.c-standby-plate'];
            const position = selector => document.querySelector(selector).getBoundingClientRect();
            document.body.style.setProperty('--c-oled-x', '0px');
            document.body.style.setProperty('--c-oled-y', '0px');
            const before = selectors.map(position);
            document.body.style.setProperty('--c-oled-x', '2px');
            document.body.style.setProperty('--c-oled-y', '-2px');
            const after = selectors.map(position);
            document.body.style.setProperty('--c-oled-x', `${cOledDrift.x}px`);
            document.body.style.setProperty('--c-oled-y', `${cOledDrift.y}px`);
            return selectors.map((selector, index) => ({ selector,
                dx: after[index].left - before[index].left, dy: after[index].top - before[index].top }));
        });
        check(drift.every(item => Math.abs(item.dx - 2) < .02 && Math.abs(item.dy + 2) < .02),
            'All static C scene text must share the same OLED offset: ' + JSON.stringify(drift));
        check(await page.evaluate(() => document.querySelector('#standby-view').style.transform === '' &&
            !document.querySelector('#standby-view').getAnimations().length), 'No legacy sine or burst keyframes can override random OLED drift');

        await openDrawer();
        const drawerDrift = await page.evaluate(() => {
            const selectors = ['.prototype-note', '.variant-switcher', '#controls', '#c-review-picker', '#inspector'];
            const position = selector => document.querySelector(selector).getBoundingClientRect();
            document.body.style.setProperty('--c-oled-x', '0px');
            document.body.style.setProperty('--c-oled-y', '0px');
            const before = selectors.map(position);
            document.body.style.setProperty('--c-oled-x', '2px');
            document.body.style.setProperty('--c-oled-y', '-2px');
            const after = selectors.map(position);
            document.body.style.setProperty('--c-oled-x', cOledDrift.x + 'px');
            document.body.style.setProperty('--c-oled-y', cOledDrift.y + 'px');
            return selectors.map((selector, index) => ({ selector, width: before[index].width, height: before[index].height,
                dx: after[index].left - before[index].left, dy: after[index].top - before[index].top }));
        });
        check(drawerDrift.every(item => item.width > 0 && item.height > 0 && Math.abs(item.dx) < .02 && Math.abs(item.dy) < .02),
            'Visible drawer tools stay anchored instead of inheriting scene OLED drift: ' + JSON.stringify(drawerDrift));
        await closeDrawer();

        const ellipse = await page.evaluate(() => {
            let cases = 0;
            let maximumError = 0;
            for (let appearance = 0; appearance < 4; appearance += 1) {
                showRandomCHud();
                for (let step = 0; step < 16; step += 1) {
                    const angle = step * Math.PI / 8;
                    updateCHudOrbit(cHudOrbit.startedAt + step * 5500 / 16);
                    if (cHudOrbit.radiusX > 6 || cHudOrbit.radiusY > 3) throw Error('Orbit exceeds subtle radii');
                    maximumError = Math.max(maximumError,
                        Math.abs(cHudOrbit.x - cHudOrbit.radiusX * Math.cos(angle)),
                        Math.abs(cHudOrbit.y - cHudOrbit.radiusY * Math.sin(angle)));
                    cases += 1;
                }
            }
            return { cases, maximumError, radiusX: cHudOrbit.radiusX, radiusY: cHudOrbit.radiusY };
        });
        check(ellipse.maximumError < 1e-8, 'Every HUD appearance must follow the same analytic ellipse');
        await page.evaluate(() => { showRandomCHud(); });
        await page.waitForFunction(() => getComputedStyle(document.querySelector('#c-hud-callout')).opacity === '1');
        const sway = await page.evaluate(async () => {
            const samples = [];
            const started = performance.now();
            await new Promise(resolve => {
                const observe = () => {
                    const dialog = document.querySelector('#c-hud-dialog').getBoundingClientRect();
                    const anchor = document.querySelector('#c-hud-anchor').getBoundingClientRect();
                    samples.push({ x: dialog.left, y: dialog.top, relativeX: dialog.left - anchor.left,
                        relativeY: dialog.top - anchor.top, visible: cHudActive && !document.querySelector('#c-hud-callout').hidden });
                    if (performance.now() - started < 3300) requestAnimationFrame(observe);
                    else resolve();
                };
                requestAnimationFrame(observe);
            });
            return samples;
        });
        const range = key => Math.max(...sway.map(sample => sample[key])) - Math.min(...sway.map(sample => sample[key]));
        check(sway.every(sample => sample.visible) && range('x') > 6 && range('x') < 12.2 && range('y') > 2 && range('y') < 6.2,
            'A naturally visible dialog must actually sway left/right and up/down during its lifetime');
        check(range('relativeX') < .1 && range('relativeY') < .1, 'The connector and dialog must move together');

        await page.evaluate(() => act('test-pause', () => { state.motion.paused = true; }));
        const paused = await page.evaluate(async () => {
            const before = { clock: particleTime, elapsed: cOledDrift.elapsed };
            await new Promise(resolve => setTimeout(resolve, 350));
            return { before, clock: particleTime, elapsed: cOledDrift.elapsed };
        });
        check(paused.clock === paused.before.clock && paused.elapsed > paused.before.elapsed,
            'OLED protection continues while optional animations are paused');
        await page.evaluate(() => act('test-resume', () => { state.motion.paused = false; }));
        for (const reducedMotion of ['reduce', 'no-preference']) {
            await page.emulateMedia({ reducedMotion });
            await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            await page.evaluate(() => showRandomCHud());
            const before = await page.evaluate(() => ({ clock: particleTime, x: cHudOrbit.x, y: cHudOrbit.y }));
            await page.waitForFunction(before => particleTime > before.clock && cHudActive &&
                Math.hypot(cHudOrbit.x - before.x, cHudOrbit.y - before.y) > .05, before);
            check(await page.evaluate(() => {
                updateCHudOrbit(cHudOrbit.startedAt + 5500 / 8);
                const valid = cHudOrbit.radiusX > 0 && cHudOrbit.radiusY > 0 &&
                    Math.abs(cHudOrbit.x / cHudOrbit.radiusX - Math.SQRT1_2) < 1e-8 &&
                    Math.abs(cHudOrbit.y / cHudOrbit.radiusY - Math.SQRT1_2) < 1e-8 &&
                    getComputedStyle(document.querySelector('#c-hud-callout')).animationName === 'none';
                updateCHudOrbit(performance.now());
                return valid;
            }), 'Both system preferences must retain the full 5.5s HUD ellipse and running particle clock');
        }

        let placements = 0;
        for (const [width, height] of [[1440, 1000], [800, 528], [390, 844], [320, 568], [667, 375], [568, 320]]) {
            await page.setViewportSize({ width, height });
            for (const style of styles) {
                await selectStyle(style);
                const failures = await page.evaluate(() => {
                    const nativeRandom = Math.random;
                    const failures = [];
                    const callout = document.querySelector('#c-hud-callout');
                    callout.style.transition = 'none';
                    const overlaps = (a, b) => Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 &&
                        Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1;
                    try {
                        for (const seed of [.01, .25, .5, .75, .99]) {
                            Math.random = () => seed;
                            showRandomCHud();

                            for (const time of [0, 770, 1540, 2310, 3135, 3905, 4675, 5500]) {
                                updateCHudOrbit(cHudOrbit.startedAt + time);
                                const box = document.querySelector('#c-hud-dialog').getBoundingClientRect();
                                if (box.left < 0 || box.top < 0 || box.right > innerWidth || box.bottom > innerHeight) failures.push({ seed, time, issue: 'outside viewport' });
                                for (const selector of ['.brand', '.connection-hint', '.c-standby-plate']) {
                                    const label = document.querySelector(selector).getBoundingClientRect();
                                    if (label.height && overlaps(box, label)) failures.push({ seed, time, issue: 'overlaps ' + selector });
                                }
                            }
                        }
                    } finally { Math.random = nativeRandom; }
                    return failures;
                });
                check(failures.length === 0, JSON.stringify({ width, height, style, failures }));
                placements += 40;
            }
        }
        check(errors.length === 0, 'Browser errors: ' + errors.join('; '));
        return { oledText: drift.length, drawerAnchors: drawerDrift.length, pausedProtection: 'passed', systemPreferences: 'identical HUD motion',
            actualVisibleSway: { x: range('x'), y: range('y') }, ellipse, placements };
    } finally { page.off('pageerror', onError); }
}
