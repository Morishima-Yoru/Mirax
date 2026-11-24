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
    const changeVariant = async selector => {
        await openDrawer();
        await page.locator(selector).click();
        await closeDrawer();
    };
    const errors = [];
    const onError = error => errors.push(error.message);
    page.on('pageerror', onError);
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&v=29');
    try {
        await openDrawer();
        await page.locator('#show-prototype-tools').check();
        await closeDrawer();
        const recover = async label => {
            await closeDrawer();
            const before = await page.evaluate(() => ({ phase: state.phase, start: cFailureStartedAt }));
            check(before.phase === 'failed' && before.start !== null, label + ': the recovery clock must exist');
            await page.evaluate(() => { particleTime = cFailureStartedAt + 7490; });
            await page.waitForFunction(() => state.phase === 'waiting');
            const result = await page.evaluate(() => ({
                status: document.querySelector('#c-status-word').textContent,
                color: cParticleColor(particleTime), broadcast: state.broadcast
            }));
            check(result.status === 'STANDBY' && result.color === '231,231,224' && result.broadcast,
                label + ': the restored C status must be green STANDBY');
        };

        await page.keyboard.press('f');
        await changeVariant('#next-variant');
        await changeVariant('#previous-variant');
        await recover('C to D and back during the glitch');

        await page.keyboard.press('f');
        await openDrawer();
        await page.locator('#home-simple').check();
        await closeDrawer();
        await openDrawer();
        await page.locator('#home-big').check();
        await closeDrawer();
        await recover('simple home and back during the glitch');

        await page.keyboard.press('f');
        await changeVariant('#next-variant');
        await page.goBack();
        await recover('browser history');

        await page.keyboard.press('f');
        await page.evaluate(() => act('test-pause', () => { state.motion.paused = true; }));
        check(await page.evaluate(() => cGlitch.playState === 'paused'), 'Pause freezes the glitch');
        await page.evaluate(() => act('test-resume', () => { state.motion.paused = false; }));
        await page.waitForFunction(() => cFailureStartedAt !== null);
        await recover('pause and resume');

        await page.keyboard.press('f');
        await page.waitForFunction(() => cFailureStartedAt !== null);
        await changeVariant('#next-variant');
        await page.evaluate(() => { particleTime = cFailureStartedAt + 7490; });
        await page.waitForFunction(() => state.phase === 'waiting');
        await changeVariant('#previous-variant');
        check(await page.locator('#c-status-word').textContent() === 'STANDBY', 'Recovery continues while viewing D');

        await page.keyboard.press('f');
        await openDrawer();
        await page.locator('#simulate-connect').click();
        await closeDrawer();
        await page.evaluate(() => { particleTime += 8000; });
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        check(await page.locator('#c-status-word').textContent() === 'ESTABLISHING', 'Recovery cannot cancel a newer connection attempt');

        check(errors.length === 0, 'Browser errors: ' + errors.join('; '));
        return { switches: 'C/D, simple home, browser history', pause: 'passed', recovery: 'STANDBY', newerConnection: 'preserved' };
    } finally { page.off('pageerror', onError); }
}
