// Run with Playwright browser_run_code_unsafe; system motion preferences must not change the phone transition.
async (page) => {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const context = await page.context().browser().newContext({
        viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, reducedMotion: 'reduce'
    });
    const probe = await context.newPage();
    const errors = [];
    probe.on('pageerror', error => errors.push(error.message));
    const openDrawer = async () => {
        if (!(await probe.locator('#settings').evaluate(dialog => dialog.open))) await probe.locator('#settings-handle').tap();
    };
    const closeDrawer = async () => {
        if (await probe.locator('#settings').evaluate(dialog => dialog.open)) await probe.locator('#close-settings').tap();
        await probe.waitForFunction(() => !settings.open && !settings.getClientRects().length);
    };
    const observe = async () => {
        await probe.evaluate(() => {
            const read = () => {
                const sunStyle = getComputedStyle(document.querySelector('.a-sun-orbit'));
                const moonStyle = getComputedStyle(document.querySelector('.a-moon-orbit'));
                const sun = new DOMMatrixReadOnly(sunStyle.transform);
                const moon = new DOMMatrixReadOnly(moonStyle.transform);
                const rect = document.querySelector('.a-moon').getBoundingClientRect();
                const horizon = parseFloat(getComputedStyle(document.querySelector('.a-celestial')).getPropertyValue('--a-horizon-y'));
                const title = document.querySelector('#status-title');
                return { phase: state.phase, broadcast: state.broadcast, title: title.textContent, expectedTitle: phases[state.phase].title,
                    titleOpacity: Number(getComputedStyle(title).opacity), sunX: sun.m41, sunY: sun.m42, moonX: moon.m41, moonY: moon.m42, horizon,
                    transitions: [sunStyle.transition, moonStyle.transition,
                        getComputedStyle(document.querySelector('#a-sky stop')).transition,
                        getComputedStyle(document.querySelector('.a-ridge-far')).transition],
                    moonRect: { x: rect.x, y: rect.y }, sky: getComputedStyle(document.querySelector('#a-sky stop')).stopColor,
                    ridge: getComputedStyle(document.querySelector('.a-ridge-far')).fill };
            };
            window.aPreferenceProbe = { before: read(), frames: [] };
            document.querySelector('#broadcast-toggle').addEventListener('click', () => {
                aPreferenceProbe.immediate = read();
                const start = performance.now();
                const sample = () => {
                    const elapsed = performance.now() - start;
                    aPreferenceProbe.frames.push({ elapsed, ...read() });
                    if (elapsed >= 50 && !('sameString' in aPreferenceProbe)) {
                        const title = document.querySelector('#status-title');
                        const animation = title.getAnimations()[0];
                        render();
                        aPreferenceProbe.sameString = Boolean(animation && title.getAnimations().includes(animation));
                    }
                    if (elapsed < 800) requestAnimationFrame(sample);
                };
                requestAnimationFrame(sample);
            }, { once: true });
        });
        await probe.locator('#broadcast-toggle').tap();
        await probe.waitForFunction(() => aPreferenceProbe.frames.some(frame => frame.elapsed >= 650));
        return await probe.evaluate(() => ({ ...aPreferenceProbe, motion: state.motion, focus: document.hasFocus() }));
    };
    const settled = day => probe.waitForFunction(day => Number(getComputedStyle(document.querySelector(day ? '.a-sun-orbit' : '.a-moon-orbit')).opacity) > .999999, day);
    const samples = [];
    try {
        await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=A&v=35');
        await probe.bringToFront();
        await probe.waitForFunction(() => document.querySelector('#status-title').textContent === phases.off.title &&
            document.querySelector('#status-title').getAnimations().every(animation => animation.playState === 'finished'));
        for (const reducedMotion of ['reduce', 'no-preference']) {
            await probe.emulateMedia({ reducedMotion });
            await probe.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            const result = await observe();
            const moving = result.frames.find(frame => frame.elapsed >= 650);
            check(result.immediate.broadcast && result.immediate.phase === 'waiting' && result.immediate.title === result.before.title,
                'A phase must update immediately while its old DOM title remains for fade-out: ' + JSON.stringify(result.immediate));
            check(result.frames.some(frame => frame.title === result.before.title && frame.titleOpacity > 0 && frame.titleOpacity < 1) &&
                result.frames.some(frame => frame.title === frame.expectedTitle && frame.titleOpacity > 0 && frame.titleOpacity < 1) &&
                moving.title === moving.expectedTitle && moving.title !== result.before.title && moving.titleOpacity > .999 && result.sameString,
                reducedMotion + ' must fade old text out, swap, fade current text in; unchanged renders must not restart it');
            check(moving.sunX > result.before.sunX && moving.sunX < -1 && moving.sunY > 1 && moving.sunY < result.before.sunY &&
                moving.moonX > 1 && moving.moonY > 1 && moving.moonY < result.before.horizon &&
                moving.sky !== result.before.sky && moving.ridge !== result.before.ridge,
                reducedMotion + ' must show intermediate colors and opposite sun/moon motion, not an instant jump: ' + JSON.stringify(result));
            check(!result.motion.paused && !('reduced' in result.motion) && !('aPreviewOverride' in result.motion) && !('cPreviewOverride' in result.motion),
                'Motion state must not retain a system-reduction policy or preview overrides');
            check(moving.moonRect.y > result.before.moonRect.y + 2, 'The moon must visibly descend in viewport pixels');
            samples.push({ reducedMotion, transitions: result.before.transitions, at650ms: moving });
            await settled(true);
            await probe.locator('#broadcast-toggle').tap();
            await settled(false);
        }
        check(JSON.stringify(samples[0].transitions) === JSON.stringify(samples[1].transitions),
            'Both system preferences must use identical celestial and palette transition properties, timing and easing');
        await openDrawer();
        check(await probe.locator('#a-transition-override, #a-transition-override-option, #a-transition-policy, #c-motion-override, #c-motion-override-option, #c-motion-policy').count() === 0,
            'Reduced-motion settings and preview overrides must not exist');
        await probe.locator('#settings-motion-toggle').tap();
        await closeDrawer();
        await probe.locator('#broadcast-toggle').tap();
        check(await probe.evaluate(() => state.motion.paused && getComputedStyle(document.querySelector('.a-sun-orbit')).transitionDuration === '0s'),
            'Explicit pause must still suppress transitions');
        await probe.locator('#broadcast-toggle').tap();
        check(await probe.evaluate(() => state.phase === 'off' && !state.broadcast &&
            new DOMMatrixReadOnly(getComputedStyle(document.querySelector('.a-moon-orbit')).transform).m42 === 0),
            'Explicit pause must settle celestial motion and phase immediately, without requiring an immediate text swap');
        await probe.waitForFunction(() => document.querySelector('#connection-hint').textContent === '' &&
            document.querySelector('#status-title').textContent === phases.off.title &&
            ['#status-title', '#status-eyebrow', '#connection-hint'].every(selector =>
                document.querySelector(selector).getAnimations().every(animation => animation.playState === 'finished')));
        await openDrawer();
        await probe.locator('#settings-motion-toggle').tap();
        await closeDrawer();
        const resumed = await observe();
        check(resumed.frames.some(frame => frame.sunX < -1 && frame.sunX > resumed.before.sunX + 1), 'Resume must permit the next transition');
        for (const variant of ['C', 'D']) {
            await probe.evaluate(variant => { state.variant = variant; render(); }, variant);
            check(await probe.locator('#a-transition-override-option, #c-motion-override-option').count() === 0,
                'Switching themes must not restore reduced-motion settings');
        }
        await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=A&v=35');
        check(await probe.evaluate(() => !('reduced' in state.motion) && !state.motion.paused), 'Reload must not restore a reduced-motion policy');
        check(errors.length === 0, 'Unexpected page errors: ' + errors.join('; '));
        return { preferenceTransitions: samples, pause: 'respected', resume: 'passed', removedSettings: 'absent', physicalPhone: 'not tested' };
    } finally {
        await context.close();
    }
}
