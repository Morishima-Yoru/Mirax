// Open the HTTPS preview, then run with Playwright browser_run_code_unsafe.
// Chromium emulates native sensor events; this does not verify physical hardware.
async (page) => {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const url = new URL(page.url());
    check(url.protocol === 'https:', 'Open the HTTPS preview before running this verification');
    url.pathname = '/big-standby-prototype.html';
    url.search = '?variant=C&log=rail&v=34';
    url.hash = '';
    const loads = [];
    let movement = 0;
    let nativeSamples = 0;
    try {
        for (let run = 0; run < 2; run += 1) {
            const context = await page.context().browser().newContext({
                viewport: { width: 1440, height: 1000 }, reducedMotion: run === 0 ? 'no-preference' : 'reduce'
            });
            const probe = await context.newPage();
            const failures = [];
            const errors = [];
            let scripts = 0;
            let cdp;
            probe.on('pageerror', error => errors.push(error.message));
            probe.on('requestfailed', request => failures.push(request.url() + ': ' + request.failure()?.errorText));
            probe.on('response', response => {
                if (response.request().resourceType() === 'script' && response.ok()) scripts += 1;
                if (response.status() >= 400) failures.push(response.url() + ': ' + response.status());
            });
            try {
                await probe.goto(url.href);
                await probe.bringToFront();
                check(!failures.length && !errors.length, 'Cold HTTPS load failed: ' + [...failures, ...errors].join('; '));
                const expectedScripts = await probe.locator('script[src]').count();
                check(expectedScripts === await probe.evaluate(() => dStudies.size + 5) && scripts === expectedScripts,
                    'Every active study and the five shared/E scripts must load successfully');
                check(await probe.evaluate(() => isSecureContext && typeof DeviceOrientationEvent === 'function' &&
                    Function.prototype.toString.call(DeviceOrientationEvent).includes('[native code]')),
                    'HTTPS must expose the native sensor API without replacement');
                check(await probe.evaluate(() => (typeof DeviceOrientationEvent.requestPermission === 'function'
                        ? !cGyroscope.enabled && !cGyroscope.listening : cGyroscope.enabled && cGyroscope.listening)),
                    'A cold load must auto-subscribe only when the native API does not require gesture permission');
                loads.push(scripts);
                cdp = await context.newCDPSession(probe);
                await probe.locator('#settings-handle').click();
                if (!(await probe.evaluate(() => cGyroscope.enabled))) await probe.locator('#enable-c-gyro').click();
                await probe.waitForFunction(() => cGyroscope.listening, null, { timeout: 5000 });
                await probe.locator('#close-settings').click();
                await probe.evaluate(() => {
                    window.nativeGyroTrace = [];
                    // Register after enable so the controller handles each native event first.
                    window.addEventListener('deviceorientation', event => {
                        if (![event.alpha, event.beta, event.gamma].every(Number.isFinite)) return;
                        const targetX = cParallax.targetX;
                        const targetY = cParallax.targetY;
                        cParallax.update(.05);
                        const rect = document.querySelector('.c-standby-plate').getBoundingClientRect();
                        nativeGyroTrace.push({ trusted: event.isTrusted, gamma: event.gamma,
                            status: cGyroscope.status, targetX, targetY, x: rect.x, y: rect.y,
                            background: Boolean(document.querySelector('#particle-canvas').style.transform),
                            foreground: Boolean(document.querySelector('#standby-view').style.getPropertyValue('--c-parallax-transform')) });
                    });
                });
                await cdp.send('DeviceOrientation.setDeviceOrientationOverride', { alpha: 0, beta: 45, gamma: 0 });
                await probe.waitForFunction(() => nativeGyroTrace.some(sample => sample.status === 'active'), null, { timeout: 5000 });
                for (const gamma of [2, 4, 6, 8, 6, 4]) {
                    // This delay is the external sensor cadence, not a target-state poll.
                    await probe.waitForTimeout(50);
                    await cdp.send('DeviceOrientation.setDeviceOrientationOverride', { alpha: 0, beta: 45, gamma });
                }
                await probe.waitForFunction(() => nativeGyroTrace.some(sample => Math.abs(sample.gamma - 4) < .001 &&
                    sample.targetX < -.1), null, { timeout: 5000 });
                await probe.waitForFunction(() => cGyroscope.status === 'active' && cGyroscope.listening &&
                    cParallax.targetX === 0 && cParallax.targetY === 0 && Math.hypot(cParallax.x, cParallax.y, cParallax.informationX, cParallax.informationY) < .01,
                    null, { timeout: 5000 });
                const trace = await probe.evaluate(() => nativeGyroTrace);
                nativeSamples = trace.length;
                check(trace.length > 1 && trace.every(sample => sample.trusted), 'Evidence must come from native browser sensor events');
                check(trace[0].status === 'active' && Math.hypot(trace[0].targetX, trace[0].targetY) < 1e-9,
                    'First native sample must seed history with zero input');
                check(trace.some(sample => sample.targetX > .1) && trace.some(sample => sample.targetX < -.1 && sample.gamma > 0),
                    'Native rate input must move forward, then reverse while still on the same side: ' + JSON.stringify(trace));
                movement = Math.max(...trace.map(sample => Math.hypot(sample.x - trace[0].x, sample.y - trace[0].y)));
                check(movement > .1 && movement < 25, 'Native sensor events must gently move the foreground: ' + movement);
                check(trace.some(sample => Math.abs(sample.targetX) > .1 && sample.background && sample.foreground),
                    'Both foreground and background must follow the native event trajectory');
                check(!failures.length && !errors.length, 'HTTPS integration failed: ' + [...failures, ...errors].join('; '));
                const rejectedPayload = await probe.evaluate(async () => {
                    const response = await fetch('/api/d-reviews', {
                        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
                    });
                    return response.status;
                });
                check(rejectedPayload === 400, 'HTTPS Origin must reach payload validation, not fail with 403');
                check(!errors.length, 'Page errors: ' + errors.join('; '));
            } finally {
                if (cdp) {
                    await cdp.send('DeviceOrientation.clearDeviceOrientationOverride');
                    await cdp.detach();
                }
                await context.close();
            }
        }
        return { coldHttpsScriptLoads: loads, nativeSensorMovement: movement, nativeSamples,
            systemPreferences: ['no-preference', 'reduce'], sameSideReversal: 'passed', settledInput: [0, 0], httpsOrigin: 'accepted',
            invalidPayload: 'rejected without writing', evidence: 'Chromium sensor emulation', physicalDevice: 'not tested' };
    } finally {
        await page.bringToFront();
    }
}
