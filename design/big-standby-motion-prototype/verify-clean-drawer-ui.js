// Run with Playwright browser_run_code_unsafe; no review data is written.
async (page) => {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const context = await page.context().browser().newContext({
        viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, reducedMotion: 'reduce'
    });
    const probe = await context.newPage();
    const errors = [];
    probe.on('pageerror', error => errors.push(error.message));
    const chrome = '.prototype-note, .variant-switcher, #reveal-hint, #controls, #c-review-picker, #d-motion-picker, #inspector';
    const clean = async () => check(await probe.evaluate(selector =>
        !state.prototypeTools && [...document.querySelectorAll(selector)].every(node => !node.getClientRects().length) &&
        !document.activeElement.closest(selector) && document.querySelectorAll('#broadcast-toggle').length === 1, chrome),
        'Clean mode must hide all unrelated chrome and its keyboard stops, but retain one broadcast action');
    const close = async () => {
        await probe.keyboard.press('Escape');
        await probe.waitForFunction(() => !settings.open && !state.settingsOpen && !settings.getClientRects().length);
        check(await probe.evaluate(() => !settings.open && !state.settingsOpen &&
            document.querySelector('#settings-handle').getAttribute('aria-expanded') === 'false'), 'Drawer close state must stay synchronized');
    };
    let cdp;
    let layouts = 0;
    let swipes = 0;
    const styles = [];
    try {
        for (const variant of ['A', 'C', 'D']) {
            await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=' + variant + '&v=35');
            await probe.bringToFront();
            await probe.locator('#broadcast-toggle').evaluate(async node => {
                for (let round = 0; round < 3; round++) {
                    await Promise.allSettled(node.getAnimations({ subtree: true }).map(animation => animation.finished));
                    await new Promise(resolve => requestAnimationFrame(resolve));
                }
            });
            await clean();
            styles.push(await probe.locator('#broadcast-toggle').evaluate(node => {
                const frames = [...node.querySelectorAll('.broadcast-frame')].filter(item => {
                    const style = getComputedStyle(item);
                    return item.getClientRects().length && style.visibility === 'visible' && Number(style.opacity) > .9;
                });
                if (frames.length !== 1) throw Error('Exactly one themed broadcast frame must be painted');
                return frames[0].innerHTML;
            }));
            for (const [width, height] of [[390, 844], [320, 568], [667, 375], [568, 320], [1440, 1000], [2560, 720]]) {
                await probe.setViewportSize({ width, height });
                await clean();
                const value = await probe.evaluate(() => {
                    const button = document.querySelector('#broadcast-toggle').getBoundingClientRect();
                    const handle = document.querySelector('#settings-handle').getBoundingClientRect();
                    const overlaps = ['.brand', '.center', '.connection-hint', '.c-standby-plate', '.hardline-caption'].filter(selector => {
                        const rect = document.querySelector(selector).getBoundingClientRect();
                        return rect.width && rect.height && Math.min(button.right, rect.right) - Math.max(button.left, rect.left) > 1 &&
                            Math.min(button.bottom, rect.bottom) - Math.max(button.top, rect.top) > 1;
                    });
                    const art = document.querySelector('#settings-handle svg').getBoundingClientRect();
                    const shell = [...document.querySelectorAll('#settings-handle .handle-shell')].find(node => {
                        for (let ancestor = node; ancestor; ancestor = ancestor.parentElement) {
                            const style = getComputedStyle(ancestor);
                            if (!ancestor.getClientRects().length || style.visibility !== 'visible' || Number(style.opacity) === 0) return false;
                        }
                        return true;
                    });
                    return { b: button.toJSON(), h: handle.toJSON(), art: art.toJSON(),
                        shell: shell?.getBoundingClientRect().toJSON(), overlaps };
                });
                const { b, h } = value;
                check(Math.abs(b.left + b.width / 2 - width / 2) < 1 && b.left >= 0 && b.right <= width && b.bottom <= height && b.height >= 44 &&
                    h.left >= 0 && h.left < 1 && Math.abs(h.top + h.height / 2 - height / 2) < 1,
                    'Primary action and handle must be visible, bottom-center/left-center: ' + JSON.stringify(value));
                check(Math.abs(h.width - 44) < .1 && Math.abs(h.height - 88) < .1 && value.art.width <= 24 &&
                    value.shell?.width > 0 && value.shell.width <= (variant === 'A' ? 22 : 21) + .1 &&
                    Math.abs(value.art.left - h.left) < 1,
                    'The handle must paint a slim edge-aligned tab without shrinking its accessible touch area: ' + JSON.stringify(value));
                check(value.overlaps.length === 0, variant + ' primary action covers information: ' + JSON.stringify(value));
                await probe.locator('#broadcast-toggle').click();
                check(await probe.evaluate(() => state.broadcast && state.phase === 'waiting' &&
                    document.querySelector('#broadcast-toggle').getAttribute('aria-checked') === 'true' &&
                    document.querySelector('#broadcast-label').textContent === '停止廣播'), 'The themed primary must start broadcasting');
                await probe.locator('#broadcast-toggle').click();
                check(await probe.evaluate(() => !state.broadcast && state.phase === 'off'), 'The themed primary must stop broadcasting');
                layouts += 1;
            }
            await probe.setViewportSize({ width: 390, height: 844 });
            await probe.locator('#settings-handle').tap({ position: { x: 36, y: 44 } });
            check(await probe.evaluate(() => settings.open && state.settingsOpen),
                'The transparent part of the unchanged 44px touch target must still open settings');
            await probe.locator('#settings').evaluate(async node => {
                await Promise.allSettled(node.getAnimations().map(animation => animation.finished));
            });
            const drawer = await probe.locator('#settings').boundingBox();
            check(drawer && Math.abs(drawer.x) < 1 && Math.abs(drawer.y) < 1 && drawer.width <= 390 && Math.abs(drawer.height - 844) < 2,
                'Settings must be a full-height left drawer');
            for (let step = 0; step < 8; step += 1) {
                await probe.keyboard.press('Tab');
                const focus = await probe.evaluate(() => ({ inside: settings.contains(document.activeElement), open: settings.open,
                    active: document.activeElement.outerHTML.slice(0, 180), focused: document.hasFocus() }));
                check(!focus.focused || focus.inside, 'Modal focus may reach browser chrome but not background page controls at tab ' + step + ': ' + JSON.stringify(focus));
            }
            await probe.locator('#show-prototype-tools').check();
            check(await probe.locator('.prototype-note').isVisible() && await probe.locator('.variant-switcher').isVisible() &&
                await probe.locator(variant === 'D' ? '#d-motion-picker' : '#c-review-picker').isVisible(), 'One setting must restore applicable tools inside the drawer');
            check(await probe.evaluate(selector => [...document.querySelectorAll(selector)].every(node => settings.contains(node)), chrome),
                'All prototype tools must live inside settings, never overlaying the scene');
            await probe.locator('#close-settings').tap();
            await probe.waitForFunction(() => !settings.open && !settings.getClientRects().length);
            check(await probe.evaluate(selector => state.prototypeTools &&
                [...document.querySelectorAll(selector)].every(node => !node.getClientRects().length), chrome),
                'Closing settings must conceal every enabled tool without changing its preference');
            await probe.locator('#settings-handle').tap();
            await probe.locator('#show-prototype-tools').uncheck();
            await close();
            await clean();
            check(await probe.evaluate(() => document.activeElement.id === 'settings-handle'), 'Closing must return focus to the handle');
        }
        check(new Set(styles).size === 3, 'A/C/D broadcast buttons must have different shapes, not just colors');
        await probe.locator('#settings-handle').tap();
        check(JSON.stringify(await probe.locator('#theme-select option').evaluateAll(nodes => nodes.map(node => node.value))) === '["A","C","D"]',
            'Only A/C/D may be selected');
        for (const variant of ['A', 'C', 'D']) {
            await probe.locator('#theme-select').selectOption(variant);
            check(await probe.evaluate(variant => state.variant === variant && new URL(location.href).searchParams.get('variant') === variant &&
                document.querySelector('#variant-counter').textContent.endsWith('/ 3'), variant), 'Theme state, URL and counter must agree');
        }
        await close();
        await probe.keyboard.press('ArrowRight');
        check(await probe.evaluate(() => state.variant === 'A'), 'D must wrap to A without B or E');
        await probe.keyboard.press('ArrowLeft');
        check(await probe.evaluate(() => state.variant === 'D'), 'A must wrap to D');
        cdp = await context.newCDPSession(probe);
        const touch = async (x1, y1, x2, y2) => {
            await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: x1, y: y1 }] });
            for (let step = 1; step <= 5; step += 1) {
                await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x1 + (x2 - x1) * step / 5, y: y1 + (y2 - y1) * step / 5 }] });
            }
            await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
        };
        for (const variant of ['A', 'C', 'D']) {
            await probe.locator('#settings-handle').tap();
            await probe.locator('#theme-select').selectOption(variant);
            await close();
            for (const [x, y] of [[55, 230], [160, 510]]) {
                await touch(x, y, x + 150, y + 5);
                await probe.waitForFunction(() => settings.open, null, { timeout: 3000 });
                check(await probe.evaluate(() => state.settingsOpen && !state.broadcast && !state.prototypeTools),
                    'Native background swipe must open only settings, without changing broadcast/debug state');
                await close();
                swipes += 1;
            }
        }
        const primary = await probe.locator('#broadcast-toggle').boundingBox();
        await touch(primary.x + primary.width / 2, primary.y + primary.height / 2,
            primary.x + primary.width / 2 + 120, primary.y + primary.height / 2);
        check(await probe.evaluate(() => !settings.open && !state.broadcast),
            'Dragging the primary action must neither hijack the gesture nor accidentally broadcast');
        await probe.locator('#broadcast-toggle').focus();
        await probe.keyboard.press('Space');
        check(await probe.evaluate(() => state.broadcast), 'Keyboard Space must activate the broadcast switch');
        await probe.keyboard.press('Space');
        check(await probe.evaluate(() => !state.broadcast), 'Keyboard Space must also stop broadcasting');
        await touch(100, 250, 105, 410);
        check(!(await probe.evaluate(() => settings.open)), 'Vertical movement must not open settings');
        await touch(280, 250, 100, 253);
        check(!(await probe.evaluate(() => settings.open)), 'Leftward movement must not open settings');
        await probe.evaluate(() => {
            const stage = document.querySelector('#stage');
            const emit = (type, x, y, id = 1, primary = true) => stage.dispatchEvent(new PointerEvent(type,
                { bubbles: true, pointerType: 'touch', pointerId: id, isPrimary: primary, button: 0, clientX: x, clientY: y }));
            emit('pointerdown', 100, 200); emit('pointerup', 150, 200);
            emit('pointerdown', 100, 200); emit('pointercancel', 160, 200); emit('pointerup', 250, 200);
            emit('pointerdown', 100, 200); emit('pointerdown', 140, 200, 2, false);
            emit('pointerup', 250, 200); emit('pointerup', 250, 200, 2, false);
        });
        check(!(await probe.evaluate(() => settings.open)), 'Short, cancelled and multi-touch gestures must not open settings');
        await probe.locator('#settings-handle').tap();
        await probe.evaluate(() => {
            const input = document.createElement('input');
            input.id = 'gesture-test-input'; input.value = 'editable text';
            document.querySelector('.dialog-body').prepend(input);
        });
        check(await probe.evaluate(() => settings.open && state.settingsOpen), 'The handle must reopen settings after rejected gestures: ' +
            JSON.stringify(await probe.evaluate(() => ({ open: settings.open, stateOpen: state.settingsOpen, focus: document.activeElement.id, last: state.lastAction }))));
        await probe.locator('#gesture-test-input').fill('keep this text');
        await probe.keyboard.press('ArrowRight');
        check(await probe.locator('#gesture-test-input').inputValue() === 'keep this text' && await probe.evaluate(() => state.variant === 'D'),
            'Editing inside settings must not navigate themes');
        await close();
        await probe.setViewportSize({ width: 1440, height: 1000 });
        const compatibilityClick = await probe.evaluate(async () => {
            const stage = document.querySelector('#stage');
            const emit = (target, type, x, id, detail = 0) => target.dispatchEvent(new PointerEvent(type,
                { bubbles: true, pointerType: 'touch', pointerId: id, isPrimary: true, button: 0,
                    clientX: x, clientY: 250, detail }));
            emit(stage, 'pointerdown', 100, 81);
            emit(stage, 'pointerup', 700, 81);
            const opened = settings.open;
            emit(settings, 'click', 700, 81, 1);
            const retained = settings.open;
            emit(settings, 'pointerdown', 700, 82);
            emit(settings, 'pointerup', 700, 82);
            emit(settings, 'click', 700, 82, 1);
            await Promise.allSettled(settings.getAnimations().map(animation => animation.finished));
            await new Promise(resolve => requestAnimationFrame(resolve));
            return { opened, retained, nextBackdropCloses: !settings.open };
        });
        check(compatibilityClick.opened && compatibilityClick.retained && compatibilityClick.nextBackdropCloses,
            'The opening swipe must consume its compatibility click, not the next intentional backdrop tap: ' + JSON.stringify(compatibilityClick));
        await probe.locator('#settings-handle').click();
        await probe.mouse.click(700, 300);
        await probe.waitForFunction(() => !settings.open);
        check(!(await probe.evaluate(() => settings.open)), 'Backdrop click must close the drawer');
        await probe.locator('#settings-handle').focus();
        await probe.keyboard.press('Enter');
        check(await probe.evaluate(() => settings.open), 'Keyboard Enter must open the drawer handle');
        await close();
        await probe.evaluate(() => { state.home = 'simple'; render(); });
        await probe.locator('#broadcast-dock').waitFor({ state: 'hidden' });
        check(!(await probe.locator('#broadcast-toggle').isVisible()), 'The dock must not overlay simple home');
        await probe.evaluate(() => { state.home = 'big'; toggleBroadcast(); setPhase('connecting'); setPhase('projecting'); });
        await probe.locator('#broadcast-dock').waitFor({ state: 'hidden' });
        check(!(await probe.locator('#broadcast-toggle').isVisible()), 'The dock must not overlay projection');
        for (const motion of ['', '&motion=unknown']) {
            await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=E' + motion + '&v=35');
            check(await probe.evaluate(() => state.variant === 'D' && !Object.hasOwn(concepts, 'E') &&
                document.querySelector('#stage').dataset.variant === 'D' && !document.querySelector('.broadcast-frame-e') &&
                state.motion.dMode === 'study-118-section-extrusion' &&
                document.querySelector('#d-motion').value === 'study-118-section-extrusion'),
                'Legacy E must resolve to D with source 118 when motion is absent or invalid');
            await clean();
        }
        await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=B&v=35');
        check(await probe.evaluate(() => state.variant === 'A' && !document.querySelector('#architecture') && !Object.hasOwn(concepts, 'B') &&
            dReviewIds.has('concept-b')), 'B must fall back to A while historical B review compatibility remains');
        await clean();
        check(errors.length === 0, 'Unexpected page errors: ' + errors.join('; '));
        return { cleanThemes: 3, layoutCases: layouts, distinctBroadcastDesigns: styles.length, nativeTouchSwipes: swipes,
            rejectedGestures: 'vertical, left, short, cancelled, multi-touch', editing: 'preserved', modalFocus: 'passed',
            toolToggle: 'passed', slimHandle: 'A cloud <=22px, C/D 21px; 44x88px hit target', removedB: 'fallback A, history retained',
            mergedE: 'fallback D, source 118 default', projectionAndSimple: 'preserved', physicalAndroid: 'not tested' };
    } finally {
        await cdp?.detach();
        await context.close();
    }
}
