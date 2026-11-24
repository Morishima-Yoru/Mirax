// Run with Playwright browser_run_code_unsafe against the preview server.
async (page) => {
    const check = (value, message) => { if (!value) throw Error(message); };
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&log=rail&v=35');
    await page.bringToFront();
    await page.evaluate(() => document.fonts.ready);
    const result = await page.evaluate(async () => {
        const nativeRandom = Math.random;
        const failures = [];
        let cases = 0;
        const white = 'rgb(255, 255, 255)';
        const railStatus = {
            ONLINE: 'rgb(104, 220, 148)', LOCKED: 'rgb(104, 220, 148)', CLEAN: 'rgb(104, 220, 148)', SECURE: 'rgb(104, 220, 148)',
            SYNC: 'rgb(101, 207, 227)', ACTIVE: 'rgb(101, 207, 227)', LIVE: 'rgb(101, 207, 227)', STANDBY: 'rgb(239, 196, 109)'
        };
        try {
            for (const style of cLogStyles) {
                state.cLogStyle = style;
                render();
                for (let index = 0; index < cLogPresets.length; index += 1) {
                    Math.random = () => (index + .5) / cLogPresets.length;
                    showRandomCHud();
                    const callout = document.querySelector('#c-hud-callout');
                    await Promise.allSettled(callout.getAnimations().map(animation => animation.finished));
                    await new Promise(resolve => requestAnimationFrame(resolve));
                    const preset = cLogPresets[index];
                    const dialog = document.querySelector('#c-hud-dialog');
                    const dialogStyle = getComputedStyle(dialog);
                    const header = getComputedStyle(document.querySelector('.c-hud-header'));
                    const title = getComputedStyle(document.querySelector('#c-hud-title'));
                    const body = getComputedStyle(document.querySelector('#c-hud-body'));
                    const badge = getComputedStyle(document.querySelector('#c-hud-badge')).color;
                    const accent = 'rgb(' + [1, 3, 5].map(offset => parseInt(preset.accent.slice(offset, offset + 2), 16)).join(', ') + ')';
                    const colored = style === 'rail' || style === 'fold';
                    const expected = style === 'rail' ? railStatus[preset.badge] : style === 'fold' ? accent : style === 'cap' ? 'rgb(8, 8, 8)' : white;
                    if (badge !== expected) failures.push({ style, badge: preset.badge, expected, actual: badge });
                    if (dialogStyle.opacity !== (style === 'rail' ? '1' : '0.5')) failures.push({ style, issue: 'dialog opacity changed' });
                    if (colored && (title.color !== white || body.color !== white)) failures.push({ style, issue: 'non-status copy is colored' });
                    if (style === 'rail') {
                        if (header.color !== white || header.opacity !== '1' || title.opacity !== '1' || body.opacity !== '0.5') {
                            failures.push({ style, issue: 'Rail must retain opaque white header/title and half-opacity white body' });
                        }
                        const stroke = dialogStyle.borderLeftColor.match(/[\d.]+/g)?.map(Number) ?? [];
                        if (stroke.length !== 4 || stroke.slice(0, 3).some(channel => channel !== 255) ||
                            Math.abs(stroke[3] - 128 / 255) > .002 || dialogStyle.borderLeftStyle !== 'solid' || dialogStyle.borderLeftWidth !== '1px') {
                            failures.push({ style, issue: 'Rail must retain its 1px half-alpha white stroke', actual: dialogStyle.borderLeftColor });
                        }
                    }
                    if (style === 'fold') {
                        const fold = getComputedStyle(dialog, '::after');
                        if (fold.borderLeftColor !== badge || fold.borderBottomColor !== badge) failures.push({ style, issue: 'fold and badge differ' });
                    }
                    const calloutStyle = getComputedStyle(callout);
                    if (callout.hidden || calloutStyle.display === 'none' || calloutStyle.visibility !== 'visible' || Number(calloutStyle.opacity) < .999) {
                        failures.push({ style, badge: preset.badge, issue: 'HUD must be visibly settled before checking its text' });
                    }
                    const bounds = dialog.getBoundingClientRect();
                    const nodes = dialog.querySelectorAll('#c-hud-title, .c-hud-line');
                    if (nodes.length !== preset.lines.length + 1) failures.push({ style, badge: preset.badge, issue: 'Missing title or body lines' });
                    for (const node of nodes) {
                        const slot = node.getBoundingClientRect();
                        const range = document.createRange();
                        range.selectNodeContents(node);
                        const glyphs = [...range.getClientRects()];
                        if (!glyphs.length || node.scrollWidth > node.clientWidth + 1 || glyphs.some(rect => !rect.width || !rect.height ||
                            rect.left < Math.max(0, bounds.left, slot.left) - .5 || rect.right > Math.min(innerWidth, bounds.right, slot.right) + .5 ||
                            rect.top < Math.max(0, bounds.top, slot.top) - .5 || rect.bottom > Math.min(innerHeight, bounds.bottom, slot.bottom) + .5)) {
                            failures.push({ style, badge: preset.badge, issue: 'Actual title/body glyphs must fit their visible slot and dialog', text: node.textContent });
                        }
                    }
                    cases += 1;
                }
            }
        } finally { Math.random = nativeRandom; }
        return { cases, styles: cLogStyles.length, presets: cLogPresets.length, failures };
    });
    check(result.styles === 10 && result.presets === 8 && result.cases === 80, 'All ten styles and eight presets must remain covered: ' + JSON.stringify(result));
    check(result.failures.length === 0, JSON.stringify(result.failures));
    return { badgeStyleCases: result.cases, visibleTextCases: result.cases, rail: 'saturated status, opaque white header, half-opacity white body and stroke', fold: 'matching status and folded corner', otherStyles: 'monochrome' };
}
