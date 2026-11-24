// Run with Playwright browser_run_code_unsafe({ filename: 'design/big-standby-motion-prototype/verify-c-failure-ui.js' }).
async (page) => {
    const check = (value, message) => { if (!value) throw Error(message); };
    const openDrawer = async () => {
        if (!(await page.locator('#settings').evaluate(dialog => dialog.open))) await page.locator('#settings-handle').click();
    };
    const closeDrawer = async () => {
        if (await page.locator('#settings').evaluate(dialog => dialog.open)) await page.locator('#close-settings').click();
        await page.waitForFunction(() => !settings.open && !settings.getClientRects().length);
    };
    const changeVariant = async selector => {
        await openDrawer();
        await page.locator(selector).click();
        await closeDrawer();
    };
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&v=29');
    await page.evaluate(() => {
        window.__nativeBodyAnimate = document.body.animate;
        window.__failureProbes = [];
        document.body.animate = function (frames, timing) {
            const probe = { settingsOpen: document.querySelector('#settings').open,
                wholeScreen: this === document.body, duration: timing.duration, samples: [],
                noBands: frames.every(frame => !frame.clipPath),
                dispersion: frames.some(frame => frame.filter === 'url(#c-error-dispersion)') };
            window.__failureProbes.push(probe);
            const animation = window.__nativeBodyAnimate.call(this, frames, timing);
            const started = performance.now();
            const observe = () => {
                const rgb = context.fillStyle.match(/[0-9.]+/g).slice(0, 3).map(Number);
                probe.samples.push(rgb);
                if (performance.now() - started < 1200) requestAnimationFrame(observe);
                else probe.finished = true;
            };
            requestAnimationFrame(observe);
            return animation;
        };
    });
    const errors = [];
    const onError = error => errors.push(error.message);
    page.on('pageerror', onError);
    try {
        await openDrawer();
        await page.locator('#show-prototype-tools').check();
        await page.locator('#settings-broadcast-toggle').click();
        const gatherDuration = await page.evaluate(() => cParticleAssembly.duration);
        check(gatherDuration === 6, 'Broadcast must gather the X in six seconds');
        await page.locator('#confirm-service-ready').click();
        await page.locator('#simulate-connect').click();
        await page.locator('#simulate-fail').click();
        await page.waitForFunction(() => window.__failureProbes[0]?.finished);
        const first = await page.evaluate(() => ({ probe: window.__failureProbes[0],
            settingsOpen: document.querySelector('#settings').open,
            lightRight: document.querySelector('.c-status-light').getBoundingClientRect().right,
            titleLeft: document.querySelector('#c-status-word').getBoundingClientRect().left }));
        check(!first.probe.settingsOpen && !first.settingsOpen, 'Dismiss settings before the glitch starts, not after it ends');
        check(first.probe.wholeScreen && first.probe.duration === 250, 'Glitch must affect the full display for 250ms');
        check(first.probe.noBands && first.probe.dispersion, 'Use full-page optical dispersion, never television dropouts');
        check(first.lightRight < first.titleLeft, 'Status light must precede the title on its left');
        check(first.probe.samples.some(([, green]) => green > 64 && green < 231), 'Actual canvas paints intermediate white-to-red colors');
        check(first.probe.samples.some(([red, green]) => red === 255 && green === 64), 'Attack reaches full red');
        check(await page.locator('#c-status-word').textContent() === 'ERROR', 'The error remains visible while particles are red');
        await page.waitForFunction(() => state.phase === 'waiting', null, { timeout: 12000 });
        const recovered = await page.evaluate(() => ({
            status: document.querySelector('#c-status-word').textContent,
            light: getComputedStyle(document.querySelector('.c-status-light')).backgroundColor,
            color: cParticleColor(particleTime), broadcast: state.broadcast
        }));
        check(recovered.status === 'STANDBY' && recovered.light === 'rgb(126, 245, 155)' && recovered.color === '231,231,224' && recovered.broadcast, 'Recovery must restore white particles and green STANDBY while broadcasting');

        await page.locator('#broadcast-toggle').click();
        await page.locator('#broadcast-toggle').click();
        await openDrawer();
        await page.locator('#confirm-service-ready').click();
        await page.locator('#simulate-c-failure').click();
        let count = await page.evaluate(() => window.__failureProbes.length);
        check(count === 2, 'Drawer FAIL button works after confirming service ready');
        await page.keyboard.press('f');
        const shortcut = await page.evaluate(() => ({ count: window.__failureProbes.length,
            phase: state.phase, settingsOpen: document.querySelector('#settings').open }));
        check(shortcut.count === 3 && shortcut.phase === 'failed' && !shortcut.settingsOpen, 'F retriggers failure without opening settings');

        await page.evaluate(() => {
            const editor = document.querySelector('#c-review-note');
            editor.dispatchEvent(new KeyboardEvent('keydown', { key: 'f', bubbles: true }));
            document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'f', repeat: true, bubbles: true }));
            document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'f', ctrlKey: true, bubbles: true }));
        });
        count = await page.evaluate(() => window.__failureProbes.length);
        check(count === 3, 'Typing, held keys and modified shortcuts never trigger failure');
        await changeVariant('#next-variant');
        await page.keyboard.press('f');
        const other = await page.evaluate(() => ({ count: window.__failureProbes.length,
            variant: state.variant, center: getComputedStyle(document.querySelector('#standby-view > .center')).display }));
        check(other.variant === 'D' && other.count === 3 && other.center !== 'none', 'F and the C status layout must not change D');
        await changeVariant('#previous-variant');
        check(errors.length === 0, 'No browser errors: ' + errors.join('; '));
        return { settingsRoute: 'passed', failButton: 'passed', shortcut: 'passed',
            whiteToRed: 'intermediate canvas colors observed', recovery: 'green STANDBY', glitchMilliseconds: first.probe.duration,
            gatherSeconds: gatherDuration, lamp: 'left of title', shortcutGuards: 'passed', dIsolation: 'passed' };
    } finally {
        page.off('pageerror', onError);
        await page.evaluate(() => {
            document.body.animate = window.__nativeBodyAnimate;
            delete window.__nativeBodyAnimate;
            delete window.__failureProbes;
        });
    }
}
