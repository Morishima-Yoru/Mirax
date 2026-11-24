// Run with Playwright browser_run_code_unsafe; Chromium emulates native orientation input.
async (page) => {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const context = await page.context().browser().newContext({
        viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, reducedMotion: 'reduce'
    });
    const probe = await context.newPage();
    const errors = [];
    probe.on('pageerror', error => errors.push(error.message));
    let cdp;
    let step = 'initial HUD';
    try {
        await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&log=rail&v=34');
        await probe.bringToFront();
        // Isolate settings interactions from the independently tested slow OLED drift.
        await probe.addStyleTag({ content: '#settings { translate: 0px 0px !important; }' });
        await probe.locator('#settings-handle').tap();
        check(await probe.evaluate(() => !('reduced' in state.motion) && !('aPreviewOverride' in state.motion) && !('cPreviewOverride' in state.motion)),
            'Motion state must not retain system reduction or preview overrides');
        check(await probe.locator('#a-transition-override, #a-transition-override-option, #a-transition-policy, #c-motion-override, #c-motion-override-option, #c-motion-policy').count() === 0,
            'Reduced-motion settings and preview overrides must not exist');
        check(await probe.locator('#enable-c-gyro').isEnabled(), 'System preferences must not block an explicit sensor request');
        check(await probe.evaluate(() => (typeof DeviceOrientationEvent.requestPermission === 'function'
                ? !cGyroscope.enabled && !cGyroscope.listening : cGyroscope.enabled && cGyroscope.listening)),
            'Default C motion must automatically subscribe when no gesture permission is required');
        await probe.locator('#close-settings').tap();
        await probe.evaluate(() => showRandomCHud());
        await probe.waitForFunction(() => getComputedStyle(document.querySelector('#c-hud-callout')).opacity === '1');
        const orbit = await probe.evaluate(async () => {
            const callout = document.querySelector('#c-hud-callout');
            const samples = [];
            const started = performance.now();
            await new Promise(resolve => {
                const observe = () => {
                    const rect = document.querySelector('#c-hud-dialog').getBoundingClientRect();
                    const translate = getComputedStyle(callout).translate;
                    const [tx = 0, ty = 0] = translate === 'none' ? [0, 0] : translate.split(/\s+/).map(parseFloat);
                    samples.push({ x: cHudOrbit.x, y: cHudOrbit.y, tx, ty, rectX: rect.x, rectY: rect.y });
                    if (performance.now() - started < 1800) requestAnimationFrame(observe);
                    else resolve();
                };
                requestAnimationFrame(observe);
            });
            const points = [];
            const startedAt = cHudOrbit.startedAt;
            try {
                for (const turn of [0, .25, .5, .75, 1]) {
                    updateCHudOrbit(startedAt + turn * 5500);
                    points.push({ turn, x: cHudOrbit.x, y: cHudOrbit.y });
                }
            } finally {
                updateCHudOrbit(performance.now());
            }
            const span = axis => Math.max(...samples.map(sample => sample[axis])) - Math.min(...samples.map(sample => sample[axis]));
            return { samples, points, movement: [span('rectX'), span('rectY')],
                animation: getComputedStyle(callout).animationName, animations: callout.getAnimations().length };
        });
        const rx = orbit.points[0].x;
        const ry = orbit.points[1].y;
        check(rx > 0 && rx <= 6.01 && ry > 0 && ry <= 3.01, 'HUD ellipse radii must stay within 6px by 3px');
        check(orbit.points.every(point => Math.abs(point.x - rx * Math.cos(point.turn * 2 * Math.PI)) < .02 &&
            Math.abs(point.y - ry * Math.sin(point.turn * 2 * Math.PI)) < .02), 'HUD must complete one cosine/sine orbit every 5.5 seconds');
        check(orbit.samples.length > 2 && orbit.samples.every(sample =>
            Math.abs((sample.x / rx) ** 2 + (sample.y / ry) ** 2 - 1) < .03 &&
            Math.abs(sample.tx - sample.x) < .02 && Math.abs(sample.ty - sample.y) < .02),
            'Natural rAF samples and CSS translate must follow the same bounded ellipse');
        check(orbit.movement[0] > .1 && orbit.movement[0] <= 12.1 && orbit.movement[1] > .1 && orbit.movement[1] <= 6.1,
            'The default mobile HUD must move gently over 1800ms: ' + JSON.stringify(orbit.movement));
        check(orbit.animation === 'none' && orbit.animations === 0, 'HUD orbit must use the existing rAF, not CSS or WAAPI animations');

        await probe.locator('#settings-handle').tap();
        step = 'enable';
        cdp = await context.newCDPSession(probe);
        if (!(await probe.evaluate(() => cGyroscope.enabled))) await probe.locator('#enable-c-gyro').tap();
        await probe.waitForFunction(() => cGyroscope.listening, null, { timeout: 5000 });
        await probe.evaluate(() => {
            window.reducedGyroTrace = [];
            window.addEventListener('deviceorientation', event => {
                if (![event.alpha, event.beta, event.gamma].every(Number.isFinite)) return;
                reducedGyroTrace.push({ trusted: event.isTrusted, time: performance.now(), gamma: event.gamma, x: cParallax.targetX, y: cParallax.targetY });
            });
        });
        await cdp.send('DeviceOrientation.setDeviceOrientationOverride', { alpha: 0, beta: 45, gamma: 0 });
        await probe.waitForFunction(() => cGyroscope.status === 'active', null, { timeout: 5000 });
        check(await probe.evaluate(() => reducedGyroTrace.length > 0 && reducedGyroTrace[0].trusted &&
            Math.hypot(reducedGyroTrace[0].x, reducedGyroTrace[0].y) < 1e-9), 'The first native sample must seed history');
        // This delay supplies a real external sensor interval without changing the browser clock.
        await probe.waitForTimeout(50);
        await cdp.send('DeviceOrientation.setDeviceOrientationOverride', { alpha: 0, beta: 45, gamma: 3 });
        await probe.waitForFunction(() => reducedGyroTrace.some(sample => sample.trusted && sample.x > .1), null, { timeout: 5000 });
        const inspector = await probe.evaluate(() => { updateInspector(); return JSON.parse(document.querySelector('#state-json').textContent); });
        check(!('reduced' in inspector.motion) && !('effectiveReduced' in inspector.motion) && !('cPreviewOverride' in inspector.motion) &&
            inspector.gyroscope.secureContext && inspector.gyroscope.orientationApi,
            'Diagnostics must retain sensor capability without obsolete reduced-motion policy');

        step = 'pause';
        await probe.locator('#settings-motion-toggle').tap();
        check(await probe.evaluate(() => state.motion.paused && cGyroscope.status === 'suspended' && !cGyroscope.listening &&
            cParallax.targetX === 0 && cParallax.targetY === 0 && !document.querySelector('#particle-canvas').style.transform &&
            document.querySelector('#c-hud-callout').hidden), 'Explicit pause must still stop input and hide the HUD');
        await cdp.send('DeviceOrientation.setDeviceOrientationOverride', { alpha: 0, beta: 44, gamma: 0 });
        await probe.locator('#settings-motion-toggle').tap();
        await cdp.send('DeviceOrientation.setDeviceOrientationOverride', { alpha: 0, beta: 45, gamma: 0 });
        await probe.waitForFunction(() => cGyroscope.status === 'active' && cParallax.targetX === 0 && cParallax.targetY === 0,
            null, { timeout: 5000 });
        check(await probe.evaluate(() => !state.motion.paused && cGyroscope.enabled && cGyroscope.listening),
            'Resume must restore the explicitly enabled sensor');
        step = 'hidden';
        await probe.evaluate(() => {
            Object.defineProperty(document, 'hidden', { configurable: true, value: true });
            document.dispatchEvent(new Event('visibilitychange'));
        });
        check(await probe.evaluate(() => state.motion.hidden && cGyroscope.status === 'suspended' && !cGyroscope.listening &&
            cParallax.targetX === 0 && cParallax.targetY === 0 && document.querySelector('#c-hud-callout').hidden),
            'Hidden pages must still suspend sensors and hide the HUD');
        await probe.evaluate(() => { delete document.hidden; document.dispatchEvent(new Event('visibilitychange')); });
        await cdp.send('DeviceOrientation.setDeviceOrientationOverride', { alpha: 0, beta: 46, gamma: 0 });
        await probe.waitForFunction(() => cGyroscope.status === 'active' && cParallax.targetX === 0 && cParallax.targetY === 0,
            null, { timeout: 5000 });
        step = 'system preferences';
        await probe.evaluate(() => showRandomCHud());
        for (const reducedMotion of ['no-preference', 'reduce']) {
            const before = await probe.evaluate(() => ({ clock: particleTime, x: cHudOrbit.x, y: cHudOrbit.y }));
            await probe.emulateMedia({ reducedMotion });
            await probe.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            await probe.waitForFunction(before => particleTime > before.clock && cHudActive &&
                Math.hypot(cHudOrbit.x - before.x, cHudOrbit.y - before.y) > .01, before);
            check(await probe.evaluate(() => {
                updateCHudOrbit(cHudOrbit.startedAt + 5500 / 8);
                const valid = cGyroscope.enabled && cGyroscope.listening && cGyroscope.status === 'active' &&
                    cHudOrbit.radiusX > 0 && cHudOrbit.radiusY > 0 &&
                    Math.abs(cHudOrbit.x / cHudOrbit.radiusX - Math.SQRT1_2) < 1e-8 &&
                    Math.abs(cHudOrbit.y / cHudOrbit.radiusY - Math.SQRT1_2) < 1e-8;
                updateCHudOrbit(performance.now());
                return valid;
            }), 'Both system preferences must retain native input, particle motion and the identical 5.5s HUD ellipse');
        }
        for (const variant of ['A', 'D']) {
            check(await probe.evaluate(value => {
                state.variant = value;
                render();
                return cGyroscope.status === 'suspended' && !cGyroscope.listening && document.querySelector('#c-gyro-settings').hidden;
            }, variant), 'C sensor input must remain theme-scoped for ' + variant);
            check(await probe.locator('#a-transition-override, #c-motion-override').count() === 0,
                'Switching themes must not restore removed settings');
        }
        await probe.evaluate(() => { state.variant = 'C'; render(); });
        await cdp.send('DeviceOrientation.setDeviceOrientationOverride', { alpha: 0, beta: 45, gamma: 0 });
        await probe.waitForFunction(() => cGyroscope.status === 'active' && cParallax.targetX === 0 && cParallax.targetY === 0,
            null, { timeout: 5000 });
        step = 'manual stop';
        await probe.locator('#enable-c-gyro').tap();
        await cdp.send('DeviceOrientation.setDeviceOrientationOverride', { alpha: 0, beta: 45, gamma: 6 });
        check(await probe.evaluate(() => !cGyroscope.enabled && !cGyroscope.listening && cParallax.targetX === 0 && cParallax.targetY === 0),
            'Manual stop must release and ignore sensor input');
        for (const reducedMotion of ['no-preference', 'reduce']) {
            await probe.emulateMedia({ reducedMotion });
            await probe.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            check(await probe.evaluate(() => !cGyroscope.enabled && !cGyroscope.listening), 'System preferences must not undo manual sensor stop');
        }
        await probe.reload();
        check(await probe.evaluate(() => !('reduced' in state.motion) &&
            (typeof DeviceOrientationEvent.requestPermission === 'function'
                ? !cGyroscope.enabled && !cGyroscope.listening : cGyroscope.enabled && cGyroscope.listening)),
            'Reload must restore native sensor subscription without a reduced-motion policy');
        check(await probe.locator('#a-transition-override, #c-motion-override').count() === 0, 'Reload must not restore removed settings');
        check(errors.length === 0, 'Page errors: ' + errors.join('; '));
        return { defaultMotion: 'enabled', systemPreferences: 'identical motion', hudMovement1800ms: orbit.movement,
            hudOrbit: 'bounded 5.5s ellipse', nativeSensorInput: 'passed', pause: 'passed', hidden: 'passed',
            manualStop: 'preserved across system changes', otherThemes: 'A/D sensor gates preserved',
            reload: 'native sensor policy preserved', physicalDevice: 'not tested' };
    } catch (error) {
        const current = await probe.evaluate(() => ({ gyro: cGyroscope.status, enabled: cGyroscope.enabled,
            listening: cGyroscope.listening, motion: state.motion, focused: document.hasFocus(), target: [cParallax.targetX, cParallax.targetY], trace: window.reducedGyroTrace?.slice(-8) }));
        throw Error(step + ': ' + error.message + '; ' + JSON.stringify(current), { cause: error });
    } finally {
        if (cdp) {
            await cdp.send('DeviceOrientation.clearDeviceOrientationOverride');
            await cdp.detach();
        }
        await context.close();
        await page.bringToFront();
    }
}
