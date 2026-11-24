// Run with Playwright browser_run_code_unsafe; permissions and sensor events are mocked.
async (page) => {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const probe = await page.context().newPage();
    const errors = [];
    const waitForStatus = async expected => {
        try {
            await probe.waitForFunction(value => cGyroscope.status === value, expected, { timeout: 5000 });
        } catch (error) {
            const current = await probe.evaluate(() => ({ status: cGyroscope.status, enabled: cGyroscope.enabled,
                listening: cGyroscope.listening, focused: document.hasFocus(), requests: gyroPermissionCalls }));
            throw Error('Expected gyro ' + expected + ', got ' + JSON.stringify(current), { cause: error });
        }
    };
    probe.on('pageerror', error => errors.push(error.message));
    try {
        await probe.emulateMedia({ reducedMotion: 'no-preference' });
        await probe.setViewportSize({ width: 1440, height: 1000 });
        await probe.addInitScript(() => {
            window.gyroPermissionResult = 'granted';
            window.gyroPermissionCalls = [];
            window.DeviceOrientationEvent = class extends Event {
                constructor(type, sample) { super(type); Object.assign(this, sample); }
                static requestPermission() {
                    window.gyroPermissionCalls.push(navigator.userActivation.isActive);
                    return Promise.resolve(window.gyroPermissionResult);
                }
            };
        });
        await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&log=rail&v=34');
        await probe.bringToFront();
        check(await probe.evaluate(() => !cGyroscope.enabled && gyroPermissionCalls.length === 0),
            'A permission-gated API must not request sensor permission on load');
        await probe.locator('#settings-handle').click();
        await probe.locator('#enable-c-gyro').focus();
        await probe.keyboard.press('Enter');
        await waitForStatus('waiting');
        const emit = sample => probe.evaluate(([alpha, beta, gamma]) => {
            window.dispatchEvent(new DeviceOrientationEvent('deviceorientation', { alpha, beta, gamma }));
            return { status: cGyroscope.status, x: cParallax.targetX, y: cParallax.targetY };
        }, sample);
        const seed = async () => {
            const result = await emit([20, 89, 0]);
            check(result.status === 'active' && Math.hypot(result.x, result.y) < 1e-9,
                'First resumed sample must seed history without a jump');
        };
        await probe.locator('#close-settings').click();
        const motion = await probe.evaluate(() => {
            const descriptor = Object.getOwnPropertyDescriptor(performance, 'now');
            const originalNow = performance.now.bind(performance);
            let time = 0;
            const read = () => ({ status: cGyroscope.status, x: cParallax.targetX, y: cParallax.targetY });
            const sample = (at, beta, gamma, alpha = 0) => {
                time = at;
                window.dispatchEvent(new DeviceOrientationEvent('deviceorientation', { alpha, beta, gamma }));
                return read();
            };
            // Keep the clock override synchronous so no rAF callback observes synthetic time.
            Object.defineProperty(performance, 'now', { configurable: true, value: () => time });
            try {
                const first = sample(0, 45, 0);
                const before = document.querySelector('.c-standby-plate').getBoundingClientRect();
                const forward = sample(100, 45, 3);
                cParallax.update(.05);
                const after = document.querySelector('.c-standby-plate').getBoundingClientRect();
                const sensorMovement = Math.hypot(after.x - before.x, after.y - before.y);
                const particleMatrix = new DOMMatrixReadOnly(getComputedStyle(document.querySelector('#particle-canvas')).transform);
                const titleMatrix = new DOMMatrixReadOnly(getComputedStyle(document.querySelector('#standby-view')).transform);
                const layers = { particleResponse: cParallax.x / forward.x, titleResponse: cParallax.informationX / forward.x,
                    particleDx: particleMatrix.m41, titleDx: titleMatrix.m41 };
                const stage = document.querySelector('#stage');
                stage.dispatchEvent(new PointerEvent('pointermove', { pointerType: 'mouse', clientX: 30, clientY: 30 }));
                stage.dispatchEvent(new PointerEvent('pointerleave', { pointerType: 'mouse' }));
                const afterMouse = read();
                const reverse = sample(200, 45, 1.5);
                const still = sample(300, 45, 1.5);
                sample(400, 45, 4.5);
                cParallax.update(.05);
                cGyroscope.resetMotion();
                const reset = read();
                const resetSeed = sample(500, 89, 0, 20);
                sample(600, 89, 3, 20);
                if (typeof screen.orientation?.dispatchEvent === 'function') screen.orientation.dispatchEvent(new Event('change'));
                else window.dispatchEvent(new Event('orientationchange'));
                const screenSeed = sample(700, 15, 12, 80);
                const staticPose = sample(800, 15, 12, 80);
                cGyroscope.resetMotion();
                sample(900, 45, 0);
                const down = sample(1000, 48, 0);
                const up = sample(1100, 46.5, 0);
                cGyroscope.resetMotion();
                sample(1200, 45, 0);
                const idleStartedAt = originalNow();
                const beforeIdle = sample(1300, 45, 3);
                cParallax.update(.05);
                return { first, forward, reverse, still, afterMouse, reset, resetSeed, screenSeed, staticPose,
                    down, up, beforeIdle, idleStartedAt, sensorMovement, layers };
            } finally {
                if (descriptor) Object.defineProperty(performance, 'now', descriptor);
                else delete performance.now;
            }
        });
        check(motion.first.status === 'active' && Math.hypot(motion.first.x, motion.first.y) < 1e-9,
            'First sample must activate with zero input');
        check(Math.abs(motion.forward.x - 1 / 3) < .001 && Math.abs(motion.reverse.x + 1 / 6) < .001,
            '30deg/s must produce one third; reversing on the same side must produce -15deg/s: ' + JSON.stringify(motion));
        check(Math.abs(motion.down.y - 1 / 3) < .001 && Math.abs(motion.up.y + 1 / 6) < .001,
            'Vertical input must follow angular velocity, not a fixed posture');
        check([motion.still, motion.staticPose].every(sample => Math.hypot(sample.x, sample.y) < 1e-9),
            'Repeated static poses must emit zero input');
        check(motion.afterMouse.x === motion.forward.x && motion.afterMouse.y === motion.forward.y,
            'Mouse movement and leave must not override an enabled sensor');
        check(Math.hypot(motion.reset.x, motion.reset.y) < 1e-9,
            'resetMotion must clear previous movement input');
        check([motion.resetSeed, motion.screenSeed].every(sample => sample.status === 'active' &&
            Math.hypot(sample.x, sample.y) < 1e-9), 'Motion and screen resets must discard the previous sample');
        check(motion.sensorMovement > .1 && motion.sensorMovement < 25, 'Sensor input must gently move the foreground');
        check(Math.abs(motion.layers.particleResponse - (1 - Math.exp(-.2))) < 1e-12 &&
            Math.abs(motion.layers.titleResponse - (1 - Math.exp(-.1))) < 1e-12 &&
            motion.layers.particleDx > motion.layers.titleDx * 1.89 && motion.layers.titleDx > 0,
            'A real sensor-to-DOM update must give particles and titles independent velocities: ' + JSON.stringify(motion.layers));
        check(Math.abs(motion.beforeIdle.x - 1 / 3) < .001, 'Idle verification must start with the reduced-gain nonzero input');
        await probe.waitForFunction(() => cParallax.targetX === 0 && cParallax.targetY === 0, null, { timeout: 2000 });
        const idle = await probe.evaluate(startedAt => ({ elapsed: performance.now() - startedAt,
            active: cGyroscope.status === 'active' && cGyroscope.enabled && cGyroscope.listening,
            output: Math.hypot(cParallax.x, cParallax.y), titleOutput: Math.hypot(cParallax.informationX, cParallax.informationY) }), motion.idleStartedAt);
        check(idle.elapsed >= 125 && idle.active && idle.output > .001 && idle.titleOutput > .001,
            '150ms without samples must zero input, retain active status, and leave parallax to decay: ' + JSON.stringify(idle));
        await probe.waitForFunction(() => Math.hypot(cParallax.x, cParallax.y, cParallax.informationX, cParallax.informationY) < .01,
            null, { timeout: 3000 });
        await probe.locator('#settings-handle').click();
        check((await probe.locator('#c-gyro-status').textContent()).includes('已啟用'), 'Active UI needs a valid sample');
        const suspended = async label => {
            check(await probe.evaluate(() => cGyroscope.status === 'suspended' && !cGyroscope.listening &&
                cParallax.targetX === 0 && cParallax.targetY === 0 && !document.querySelector('#particle-canvas').style.transform), label + ' must stop and reset');
            await emit([80, 15, 12]);
            check(await probe.evaluate(() => cParallax.targetX === 0 && cParallax.targetY === 0), label + ' must ignore samples');
        };
        for (const [name, enter, leave] of [
            ['pause', 'toggleMotion()', 'toggleMotion()'],
            ['blur', "window.dispatchEvent(new Event('blur'))", "window.dispatchEvent(new Event('focus'))"],
            ['hidden', "Object.defineProperty(document, 'hidden', { configurable: true, value: true }); document.dispatchEvent(new Event('visibilitychange'))", "delete document.hidden; document.dispatchEvent(new Event('visibilitychange'))"],
            ['theme', "state.variant = 'D'; render()", "state.variant = 'C'; render()"],
            ['home', "state.home = 'simple'; render()", "state.home = 'big'; render()"],
            ['projection', "state.phase = 'projecting'; render()", "state.phase = 'off'; render()"],
            ['background', "state.media.status = 'ready'; render()", "state.media.status = 'none'; render()"]
        ]) {
            await probe.evaluate(code => { Function(code)(); }, enter);
            await suspended(name);
            await probe.evaluate(code => { Function(code)(); }, leave);
            await seed();
        }
        for (const reducedMotion of ['reduce', 'no-preference']) {
            await probe.emulateMedia({ reducedMotion });
            await probe.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            check(await probe.evaluate(() => cGyroscope.enabled && cGyroscope.listening && cGyroscope.status === 'active'),
                'System preferences must not suspend or reset an active sensor');
            await probe.evaluate(() => cGyroscope.resetMotion());
            await seed();
            // Supply a real sensor interval without changing the browser clock.
            await probe.waitForTimeout(50);
            const forward = await emit([20, 89, 3]);
            check(forward.status === 'active' && forward.x > .1, 'Both system preferences must retain movement input');
        }
        check(await probe.locator('#a-transition-override, #c-motion-override').count() === 0,
            'System preference changes must not restore removed settings');
        check(await probe.evaluate(() => gyroPermissionCalls.length === 1 && gyroPermissionCalls[0]),
            'Keyboard permission must run in a genuine user gesture, without resume reprompts');
        await probe.locator('#enable-c-gyro').click();
        check(await probe.evaluate(() => !cGyroscope.enabled && !cGyroscope.listening), 'Stop must release input');
        await probe.reload();
        check(await probe.evaluate(() => !cGyroscope.enabled && gyroPermissionCalls.length === 0),
            'Reload must not request gesture-gated sensor permission');
        await probe.evaluate(() => { gyroPermissionResult = 'denied'; });
        await probe.locator('#settings-handle').click();
        await probe.locator('#enable-c-gyro').click();
        await waitForStatus('denied');
        check((await probe.locator('#c-gyro-status').textContent()).includes('未獲准'), 'Denial needs explicit feedback');
        await probe.evaluate(() => { gyroPermissionResult = 'granted'; });
        await probe.locator('#enable-c-gyro').click();
        await waitForStatus('waiting');
        await emit([null, 0, 0]);
        await waitForStatus('unavailable');
        check(await probe.evaluate(() => !cGyroscope.enabled && !cGyroscope.listening && gyroPermissionCalls.every(Boolean)),
            'Missing data must fall back; permission retries must retain user activation');
        check(errors.length === 0, 'Page errors: ' + errors.join('; '));
        return { simulatedSensorDirections: 4, sameSideReversal: 'passed', staticPose: 'passed', idle150ms: 'passed',
            sensorMovement: motion.sensorMovement, independentLayerVelocities: motion.layers, lifecycleGates: 7,
            systemPreferences: 'sensor remains active', keyboardPermission: 'passed',
            resetHistory: 'passed', denial: 'passed', noDataTimeout: 'passed', physicalDevice: 'not tested' };
    } finally {
        await probe.close();
        await page.bringToFront();
    }
}
