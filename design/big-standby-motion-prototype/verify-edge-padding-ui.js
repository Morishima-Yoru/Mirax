// Run with Playwright browser_run_code_unsafe; exercise visible CSS bounds without writing reviews.
async (page) => {
    const context = await page.context().browser().newContext({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' });
    const probe = await context.newPage();
    const failures = [];
    const errors = [];
    probe.on('pageerror', error => errors.push(error.message));
    let cases = 0;
    try {
        for (const variant of ['A', 'C', 'D']) {
            await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=' + variant + '&v=40');
            await probe.bringToFront();
            await probe.evaluate(() => { cGyroscope.disable(); state.motion.paused = true; render(); });
            for (const [width, height] of [[896, 592], [480, 800], [479, 800], [401, 800], [400, 800], [390, 844], [320, 568], [568, 320], [667, 375], [1440, 1000], [2560, 720], [2160, 3840]]) {
                await probe.setViewportSize({ width, height });
                for (const phase of ['off', 'waiting', 'connecting', 'failed']) {
                    await probe.evaluate(phase => { state.broadcast = phase !== 'off'; state.phase = phase; render(); }, phase);
                    const issues = await probe.evaluate(() => {
                        const padding = Math.min(48, Math.max(24, Math.min(innerWidth, innerHeight) * .03));
                        const issues = [];
                        const box = selector => document.querySelector(selector).getBoundingClientRect();
                        const near = (actual, expected, name) => {
                            if (Math.abs(actual - expected) > 4) issues.push(name + '=' + actual.toFixed(2) + ', expected ' + expected.toFixed(2));
                        };
                        const brand = box('.brand');
                        near(brand.left, padding, 'brand left padding');
                        near(brand.top, padding, 'brand top padding');
                        const dock = box('#broadcast-dock');
                        if (state.variant === 'D') {
                            const brandStyle = getComputedStyle(document.querySelector('.brand'));
                            const mark = box('.brand-mark');
                            if (parseFloat(brandStyle.borderLeftWidth) !== 2 || parseFloat(brandStyle.paddingLeft) !== 10 || parseFloat(brandStyle.paddingRight) !== 12 || Math.abs(mark.left - brand.left - 12) > .1 || !(Math.abs(mark.width - 30) < .01 && Math.abs(mark.height - 30) < .01)) issues.push('D brand content must move 12px inside its unchanged safe anchor, with original logo size');
                            const receiver = document.querySelector('.device');
                            const device = receiver.getBoundingClientRect();
                            const insetProbe = document.createElement('div');
                            insetProbe.style.cssText = 'position:fixed;visibility:hidden;pointer-events:none;padding:env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left)';
                            document.body.append(insetProbe);
                            const insets = getComputedStyle(insetProbe);
                            const safe = ['Top', 'Right', 'Bottom', 'Left'].map(side => parseFloat(insets['padding' + side]));
                            insetProbe.remove();
                            near(device.left, padding + safe[3], 'receiver original safe-left anchor');
                            const section = getComputedStyle(receiver);
                            if (parseFloat(section.borderLeftWidth) !== 2 || section.borderLeftStyle !== 'solid' || section.borderLeftColor.replace(/\s+/g, '') !== 'rgb(238,238,238)' ||
                                parseFloat(section.paddingLeft) !== 10 || ['Top', 'Right', 'Bottom'].some(side => parseFloat(section['padding' + side]) !== 0 || parseFloat(section['border' + side + 'Width']) !== 0)) issues.push('receiver section needs only a 2px left rule and 10px inner-left padding');
                            // Mirror the CSS query exactly: narrow, or portrait up to 600px.
                            if (innerWidth <= 440 || (innerHeight >= innerWidth && innerWidth <= 600)) {
                                const transform = getComputedStyle(document.querySelector('#standby-view')).transform;
                                const oled = transform === 'none' ? new DOMMatrix() : new DOMMatrix(transform);
                                if (!oled.is2D || ![oled.a, oled.b, oled.c, oled.d].every((value, index) =>
                                    Number.isFinite(value) && Math.abs(value - [1, 0, 0, 1][index]) < .000001) ||
                                    ![oled.m41, oled.m42].every(Number.isFinite)) issues.push('OLED offset must be a pure translation without scale or rotation');
                                // OLED moves standby content, not the fixed sibling dock.
                                const nominalBottom = device.bottom - oled.m42;
                                if (Math.abs(dock.top - nominalBottom - 12) > 1) issues.push('narrow receiver nominal dock gap must remain 12px after accounting for OLED translation');
                                if (dock.top - device.bottom < 8) issues.push('narrow receiver actual dock separation must remain at least 8px');
                            } else near(innerHeight - device.bottom, padding + safe[2], 'receiver bottom safe padding');
                            const button = box('#broadcast-toggle');
                            if (Math.abs(button.width - 84) > .1 || Math.abs(button.height - 84) > .1) issues.push('D dock action must remain 84px');
                            const bounds = [device];
                            for (const [selector, copy, font] of [['.micro-label', 'RECEIVER', 8], ['.device-name', 'MIRAX · 客廳', 16]]) {
                                const label = receiver.querySelector(selector);
                                const style = getComputedStyle(label);
                                if (label.textContent !== copy || parseFloat(style.fontSize) !== font || style.visibility !== 'visible' || style.display === 'none' || Number(style.opacity) !== 1) {
                                    issues.push(selector + ' receiver copy must remain visible and unshrunk');
                                }
                                const range = document.createRange();
                                range.selectNodeContents(label);
                                const glyphs = [...range.getClientRects()];
                                if (!glyphs.length || glyphs.some(rect => rect.width <= 0 || rect.height <= 0 || rect.left < device.left - 1 || rect.right > device.right + 1 || rect.top < device.top - 1 || rect.bottom > device.bottom + 1)) {
                                    issues.push(selector + ' actual receiver glyphs are cropped or outside their slot');
                                }
                                bounds.push(label.getBoundingClientRect(), ...glyphs);
                            }
                            if ([receiver, ...receiver.querySelectorAll('*')].some(node => {
                                const style = getComputedStyle(node);
                                return style.transform !== 'none' || style.scale !== 'none' || Number(style.zoom) !== 1;
                            })) issues.push('receiver must not scale or transform its unchanged text');
                            for (const rect of bounds) {
                                if (rect.width <= 0 || rect.height <= 0 || rect.left < padding + safe[3] - 1 || rect.right > innerWidth - padding - safe[1] + 1 || rect.top < padding + safe[0] - 1 || rect.bottom > innerHeight - safe[2] + 1) {
                                    issues.push('receiver actual bounds leave the viewport safe area');
                                }
                                for (const obstacle of [dock, box('.center')]) {
                                    if (Math.min(rect.right, obstacle.right) - Math.max(rect.left, obstacle.left) > 1 && Math.min(rect.bottom, obstacle.bottom) - Math.max(rect.top, obstacle.top) > 1) {
                                        issues.push('receiver actual bounds overlap the broadcast action or status plate');
                                    }
                                }
                            }
                        }
                        near(dock.left + dock.width / 2, innerWidth / 2, 'broadcast center');
                        const footer = state.variant === 'C' ? ['.connection-hint', '.c-standby-plate'] : [];
                        const aligned = stage.dataset.cFooterLayout === 'aligned';
                        const actionGap = parseFloat(getComputedStyle(stage).getPropertyValue('--c-action-gap'));
                        if (state.variant === 'C') {
                            near(parseFloat(stage.style.getPropertyValue('--c-action-half-width')), dock.width / 2, 'measured action half-width');
                            if (actionGap !== 16) issues.push('C action gap must remain 16 CSS pixels');
                        }
                        const footerInset = padding + (aligned ? Math.min(40, Math.max(16, innerWidth * .03)) : 0);
                        for (const selector of footer) {
                            const rect = box(selector);
                            if (aligned) near(rect.top + rect.height / 2, dock.top + dock.height / 2, selector + ' level with action');
                            else near(rect.bottom, dock.top - actionGap, selector + ' raised-row gap above actual action');
                            near(selector === '.c-standby-plate' ? innerWidth - rect.right : rect.left, footerInset, selector + ' side padding');
                            const expectedWidth = aligned ? Math.min(selector === '.c-standby-plate' ? 280 : 320, innerWidth / 2 - footerInset - dock.width / 2 - actionGap) :
                                innerWidth / 2 - footerInset - 8;
                            near(rect.width, expectedWidth, selector + ' width after true inset');
                            const overlap = Math.min(rect.right, dock.right) - Math.max(rect.left, dock.left) > 1 &&
                                Math.min(rect.bottom, dock.bottom) - Math.max(rect.top, dock.top) > 1;
                            if (overlap) issues.push(selector + ' collides with the fixed broadcast action');
                            for (const child of document.querySelector(selector).querySelectorAll('*')) {
                                const actual = child.getBoundingClientRect();
                                if (actual.left < rect.left - 1 || actual.right > rect.right + 1) {
                                    issues.push(selector + ' actual text overflows its safe slot: ' + child.className);
                                }
                            }
                            const walker = document.createTreeWalker(document.querySelector(selector), NodeFilter.SHOW_TEXT);
                            let text;
                            while ((text = walker.nextNode())) {
                                if (!text.data.trim()) continue;
                                const range = document.createRange();
                                range.selectNodeContents(text);
                                if ([...range.getClientRects()].some(glyph => glyph.left < rect.left - 1 || glyph.right > rect.right + 1)) {
                                    issues.push(selector + ' actual glyphs overflow their safe slot: ' + text.data);
                                }
                            }
                        }
                        if (state.variant === 'C') {
                            const hint = document.querySelector('.connection-hint');
                            const hintRect = hint.getBoundingClientRect();
                            const style = getComputedStyle(hint);
                            const rail = parseFloat(style.borderLeftWidth);
                            const expectedInset = 14;
                            if (Math.abs(parseFloat(style.paddingLeft) - expectedInset) > .1) {
                                issues.push('connection rail inner padding=' + style.paddingLeft + ', expected 14px');
                            }
                            for (const selector of ['.connection-title', '.connection-description']) {
                                const inset = box(selector).left - hintRect.left - rail;
                                if (Math.abs(inset - expectedInset) > .1) {
                                    issues.push(selector + ' touches its rail: inner inset=' + inset.toFixed(2));
                                }
                            }
                            if (Math.abs(parseFloat(getComputedStyle(document.querySelector('.c-standby-plate')).paddingRight) - expectedInset) > .1) {
                                issues.push('C corner rails must use matching inner padding');
                            }
                        }
                        return issues;
                    });
                    failures.push(...issues.map(issue => variant + ' ' + width + 'x' + height + ' ' + phase + ': ' + issue));
                    cases += 1;
                }
            }
        }
        await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&v=40');
        await probe.setViewportSize({ width: 480, height: 375 });
        await probe.waitForFunction(() => canvasWidth === stage.clientWidth && canvasHeight === stage.clientHeight);
        const contentDecision = await probe.evaluate(() => {
            cGyroscope.disable(); state.motion.paused = true; state.broadcast = false; state.phase = 'off'; render();
            const off = stage.dataset.cFooterLayout;
            state.broadcast = true; state.phase = 'connecting'; render();
            return { off, connecting: stage.dataset.cFooterLayout };
        });
        if (contentDecision.off !== 'aligned' || contentDecision.connecting !== 'stacked') {
            failures.push('One viewport must change layout based on actual status text, not a hard-coded width: ' + JSON.stringify(contentDecision));
        }
        if (errors.length || failures.length) throw Error(JSON.stringify({ cases, failures: failures.slice(0, 16), count: failures.length, errors }));
        return { themes: 3, cases, padding: 'clamp(24px, 3vmin, 48px) plus safe area', wideC: 'measured-fit row, exact center alignment, inset clamp(16px,3vw,40px)', narrowC: 'measured non-fit, 16px above actual action top, actual glyphs contained', receiverD: 'original safe-left bottom anchor; 2px #eee left section rule and 10px inner-left padding only; stacked <=440px or portrait <=600px with 12px nominal gap, >=8px actual separation with OLED translation above unchanged 84px dock; actual text contained, no status/action overlap', cRailInnerPadding: '14px, aligned title and description', errors, physicalDevice: 'not tested' };
    } finally {
        await context.close();
    }
}
