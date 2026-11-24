// Run with Playwright browser_run_code_unsafe; no reviews or device preferences are changed.
async (page) => {
    const browser = page.context().browser();
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const base = 'http://127.0.0.1:8765/big-standby-prototype.html';
    const baselines = new Map();
    const fontBaselines = new Map();
    let layouts = 0;
    let manualScales = 0;
    for (const isMobile of [false, true]) {
        for (const deviceScaleFactor of [1, 3]) {
            const context = await browser.newContext({ isMobile, deviceScaleFactor, hasTouch: true, reducedMotion: 'reduce' });
            const probe = await context.newPage();
            try {
                for (const variant of ['A', 'C', 'D']) {
                    await probe.goto(base + '?variant=' + variant + '&v=35');
                    await probe.evaluate(() => { cGyroscope.disable(); state.motion.paused = true; render(); });
                    await probe.locator('#broadcast-toggle').evaluate(async node => {
                        for (let round = 0; round < 3; round += 1) {
                            await Promise.allSettled(node.getAnimations({ subtree: true }).map(animation => animation.finished));
                            await new Promise(resolve => requestAnimationFrame(resolve));
                        }
                    });
                    for (const [width, height] of [[390, 844], [844, 390], [480, 568]]) {
                        await probe.setViewportSize({ width, height });
                        await probe.waitForFunction(() => canvasWidth === stage.clientWidth && canvasHeight === stage.clientHeight);
                        const result = await probe.evaluate(() => {
                            const root = getComputedStyle(document.documentElement);
                            const viewport = document.querySelector('meta[name=viewport]').content;
                            const fieldSizes = [...document.querySelectorAll('#settings select, #settings textarea, #settings input:not([type="checkbox"], [type="radio"])')]
                                .map(node => parseFloat(getComputedStyle(node).fontSize));
                            const button = document.querySelector('#broadcast-toggle').getBoundingClientRect();
                            const art = document.querySelector('.broadcast-art');
                            const frame = document.querySelector('.broadcast-frame-' + state.variant.toLowerCase());
                            const glyph = document.querySelector('.broadcast-symbol-play .broadcast-glyph-' + (state.variant === 'A' ? 'a' : 'default'));
                            const scale = node => {
                                const matrix = node.getScreenCTM();
                                return [Math.hypot(matrix.a, matrix.b), Math.hypot(matrix.c, matrix.d)];
                            };
                            const artOrigin = new DOMPoint(0, 0).matrixTransform(art.getScreenCTM());
                            const iconOrigin = new DOMPoint(0, 0).matrixTransform(document.querySelector('.broadcast-symbols').getScreenCTM());
                            const action = { button: [button.width, button.height], art: [art.getBoundingClientRect().width, art.getBoundingClientRect().height],
                                frameScale: scale(frame), glyphScale: scale(glyph), fill: getComputedStyle(frame.firstElementChild).fill,
                                offset: [iconOrigin.x - artOrigin.x, iconOrigin.y - artOrigin.y] };
                            return { adjustment: root.textSizeAdjust || root.webkitTextSizeAdjust, viewport, fieldSizes, action,
                                touch: getComputedStyle(stage).touchAction,
                                sizes: ['.brand-mark', '.brand-name', '.brand small', '#broadcast-toggle', '.device-name',
                                    '#status-title', '.connection-hint', '.c-hud-title', '#settings-handle'].map(selector => {
                                    const node = document.querySelector(selector);
                                    const css = getComputedStyle(node);
                                    const rect = node.getBoundingClientRect();
                                    return [selector, css.fontSize, css.lineHeight, Number(rect.width.toFixed(2)), Number(rect.height.toFixed(2))];
                                }) };
                        });
                        check(result.fieldSizes.every(size => size >= 16) && result.adjustment === '100%' && result.touch === 'pan-y' && result.viewport.includes('maximum-scale=1') && result.viewport.includes('user-scalable=no'), 'Default presentation must lock ordinary zoom and automatic inflation');
                        const action = result.action;
                        const near = (actual, expected) => Math.abs(actual - expected) < .02;
                        check(action.button.every(size => near(size, variant === 'D' ? 84 : 96)) && action.art.every(size => near(size, 84)),
                            variant + ' must enlarge only A/C touch targets to 96px while all art remains 84px: ' + JSON.stringify(action));
                        check(action.frameScale.every(scale => near(scale, .84 * (variant === 'D' ? 1 : 1.12))) &&
                            action.glyphScale.every(scale => near(scale, .84)), 'Only A/C frames may grow 1.12x; actual glyph CTMs stay .84: ' + JSON.stringify(action));
                        check(near(action.offset[0], variant === 'D' ? -2 : 0) && near(action.offset[1], variant === 'D' ? 2 : variant === 'C' ? -5.88 : 0),
                            'D icon offset must be exactly (-2,+2) CSS pixels; A/C glyph positions stay unchanged: ' + JSON.stringify(action));
                        if (variant !== 'D') check(action.fill === (variant === 'A' ? 'rgb(25, 59, 48)' : 'rgb(38, 42, 40)'),
                            variant + ' frame must retain its non-black dark fill: ' + action.fill);
                        const fonts = JSON.stringify(result.sizes.map(size => [size[0], size[1]]));
                        if (!fontBaselines.has(variant)) fontBaselines.set(variant, fonts);
                        check(fonts === fontBaselines.get(variant), 'Viewport width must not change any reference font size for ' + variant + ': ' + fonts);
                        const key = variant + width + 'x' + height;
                        const sizes = JSON.stringify(result.sizes);
                        if (!baselines.has(key)) baselines.set(key, sizes);
                        check(sizes === baselines.get(key), 'Desktop/mobile/DPR CSS sizes differ at ' + key + ': ' + sizes);
                        layouts += 1;
                    }
                }
                const blocked = await probe.evaluate(() => {
                    const key = new KeyboardEvent('keydown', { key: '+', ctrlKey: true, bubbles: true, cancelable: true });
                    const wheel = new WheelEvent('wheel', { ctrlKey: true, deltaY: -80, bubbles: true, cancelable: true });
                    document.dispatchEvent(key); document.dispatchEvent(wheel);
                    return key.defaultPrevented && wheel.defaultPrevented;
                });
                check(blocked, 'Presentation mode must consume ordinary keyboard/wheel zoom');
                if (isMobile) {
                    const cdp = await context.newCDPSession(probe);
                    const pinch = async () => {
                        await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 145, y: 350, id: 1 }, { x: 245, y: 350, id: 2 }] });
                        for (let step = 1; step <= 8; step++) {
                            await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 145 - step * 6, y: 350, id: 1 }, { x: 245 + step * 6, y: 350, id: 2 }] });
                            await probe.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
                        }
                        await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
                        await probe.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
                        return probe.evaluate(() => visualViewport.scale);
                    };
                    check(Math.abs(await pinch() - 1) < .01, 'Native multi-touch pinch must not enlarge locked presentation');
                    await probe.locator('#settings-handle').tap();
                    await probe.locator('#preview-size-lock').uncheck();
                    await probe.locator('#close-settings').tap();
                    await probe.waitForFunction(() => !settings.open);
                    check(await pinch() > 1.1, 'Accessible escape must actually restore native pinch zoom');
                    await probe.evaluate(() => { state.sizeLocked = true; render(); });
                    await probe.waitForFunction(() => Math.abs(visualViewport.scale - 1) < .01);
                    manualScales += 1;
                }
            } finally { await context.close(); }
        }
    }
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, reducedMotion: 'no-preference' });
    const probe = await context.newPage();
    const settle = () => probe.evaluate(async () => {
        const node = document.querySelector('#broadcast-toggle');
        for (let round = 0; round < 3; round++) {
            const animations = node.getAnimations({ subtree: true }).filter(animation => animation.effect.getTiming().iterations !== Infinity);
            await Promise.allSettled(animations.map(animation => animation.finished));
            await new Promise(resolve => requestAnimationFrame(resolve));
        }
    });
    let themeTransitions = 0;
    try {
        await probe.emulateMedia({ reducedMotion: 'reduce' });
        await probe.goto(base + '?variant=A&v=35');
        await probe.bringToFront();
        await settle();
        for (const variant of ['C', 'D', 'A']) {
            await probe.evaluate(value => { state.variant = value; render(); }, variant);
            check(await probe.locator('#broadcast-toggle').evaluate(node => node.getAnimations({ subtree: true })
                .some(animation => animation.transitionProperty === 'opacity' && animation.effect.getTiming().duration > 0)),
                'Theme artwork must crossfade, never hard-swap display');
            await settle();
            const selected = await probe.locator('.broadcast-frame').evaluateAll(nodes => nodes.filter(node =>
                getComputedStyle(node).opacity === '1' && getComputedStyle(node).visibility === 'visible').map(node => node.classList[1]));
            check(selected.join() === 'broadcast-frame-' + variant.toLowerCase(), 'Exactly one settled theme frame');
            themeTransitions += 1;
        }
        await probe.locator('#broadcast-toggle').click();
        check(await probe.locator('.broadcast-symbol-play').evaluate(node => node.getAnimations().some(animation =>
            animation.transitionProperty === 'opacity')), 'The disappearing play glyph must fade out');
        await probe.evaluate(() => { state.broadcast = false; state.phase = 'off'; render(); });
        await settle();
        check(await probe.locator('#broadcast-toggle').getAttribute('data-symbol') === 'play', 'Fast reverse must settle to current state');
        const dockFade = await probe.evaluate(async () => {
            state.home = 'simple'; render();
            const dock = document.querySelector('#broadcast-dock');
            void getComputedStyle(dock).opacity;
            const fade = dock.getAnimations().find(animation => animation.transitionProperty === 'opacity');
            if (!fade) return false;
            fade.pause(); fade.currentTime = 100;
            const opacity = Number(getComputedStyle(dock).opacity);
            const painted = getComputedStyle(dock).display !== 'none';
            const inaccessible = dock.inert && dock.getAttribute('aria-hidden') === 'true';
            state.home = 'big'; render();
            return painted && inaccessible && opacity > 0 && opacity < 1;
        });
        check(dockFade, 'Projection/simple-home exits must paint a real fade rather than instantly hiding the action');
        await settle();
        for (const variant of ['A', 'C', 'D']) {
            await probe.evaluate(variant => { state.variant = variant; state.broadcast = false; state.phase = 'off'; render(); }, variant);
            await settle();
            await probe.locator('#broadcast-toggle').click();
            const blend = await probe.locator('#broadcast-toggle').evaluate(node => {
                const animations = node.getAnimations({ subtree: true });
                animations.forEach(animation => { animation.pause(); animation.currentTime = 90; });
                const opacity = ['play', 'stop'].map(name => Number(getComputedStyle(node.querySelector('.broadcast-symbol-' + name)).opacity));
                animations.forEach(animation => animation.finish());
                return opacity;
            });
            check(blend.every(opacity => opacity > 0 && opacity < 1), variant + ' must paint both glyphs during a real intermediate fade even with reduce: ' + blend);
        }
        check(await probe.evaluate(() => {
            state.motion.paused = true; render();
            const allowed = uiMotionAllowed() && getComputedStyle(document.querySelector('.broadcast-symbol-play')).transitionDuration !== '0s';
            state.motion.paused = false; render();
            return allowed;
        }), 'Pausing decoration must not remove basic interactive feedback');
        for (const gate of ['hidden']) {
            check(await probe.evaluate(gate => {
                state.motion[gate] = true; render();
                const nodes = document.querySelectorAll('#broadcast-dock, .broadcast-frame, .broadcast-symbol, .broadcast-glyph, .broadcast-symbols, .broadcast-art path');
                const stopped = [...nodes].every(node => getComputedStyle(node).transitionDuration.split(',').every(value => parseFloat(value) === 0));
                state.motion[gate] = false; render();
                return stopped;
            }, gate), 'Every broadcast transition must obey ' + gate);
        }
        return { layouts, nativeLockedAndUnlockedPinches: manualScales, themeTransitions, dockFade: 'passed', intermediateGlyphs: 'A/C/D at 90ms', automaticInflation: 'disabled', physicalSizeAcrossDevices: 'not promised' };
    } finally { await context.close(); }
}
