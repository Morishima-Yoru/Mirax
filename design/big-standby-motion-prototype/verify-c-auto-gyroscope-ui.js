// Run with Playwright browser_run_code_unsafe; simulate the no-gesture-permission platform.
async (page) => {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const context = await page.context().browser().newContext();
    const probe = await context.newPage();
    const errors = [];
    probe.on('pageerror', error => errors.push(error.message));
    await context.addInitScript(() => {
        window.DeviceOrientationEvent = class extends Event {
            constructor(type, sample) { super(type); Object.assign(this, sample); }
        };
    });
    const active = async () => {
        await probe.waitForFunction(() => cGyroscope.enabled && cGyroscope.listening);
        await probe.evaluate(() => window.dispatchEvent(new DeviceOrientationEvent('deviceorientation', { alpha: 0, beta: 45, gamma: 0 })));
    };
    try {
        await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&v=29');
        await probe.bringToFront();
        await active();
        check(await probe.evaluate(() => cGyroscope.status === 'active'), 'C must subscribe without a click on a no-prompt platform');
        await probe.locator('#settings-handle').click();
        await probe.locator('#enable-c-gyro').click();
        await probe.locator('#close-settings').click();
        await probe.evaluate(() => {
            render();
            window.dispatchEvent(new Event('blur'));
            window.dispatchEvent(new Event('focus'));
            state.variant = 'D'; render();
            state.variant = 'C'; render();
        });
        check(await probe.evaluate(() => !cGyroscope.enabled && !cGyroscope.listening),
            'A manual stop must survive render, focus and theme changes');
        await probe.reload();
        await active();
        await probe.evaluate(() => { state.motion.paused = true; render(); });
        check(await probe.evaluate(() => cGyroscope.enabled && !cGyroscope.listening && cGyroscope.status === 'suspended'),
            'Pause must suspend the default sensor without discarding intent');
        await probe.evaluate(() => { state.motion.paused = false; render(); });
        await active();
        await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=A&v=29');
        check(await probe.evaluate(() => !cGyroscope.enabled && !cGyroscope.listening && !cGyroscopeDefaultAttempted),
            'Other themes must not auto-enable the sensor or consume the C default');
        await probe.evaluate(() => { state.variant = 'C'; render(); });
        await active();
        check(errors.length === 0, 'Unexpected page errors: ' + errors.join('; '));
        return { defaultC: 'auto-enabled', manualStop: 'preserved', reload: 'auto-enabled',
            pauseResume: 'passed', otherThemes: 'off', enterC: 'auto-enabled',
            evidence: 'mocked no-prompt sensor', physicalDevice: 'not tested' };
    } finally {
        await context.close();
    }
}
