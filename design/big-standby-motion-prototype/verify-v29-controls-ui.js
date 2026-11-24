// Run with Playwright browser_run_code_unsafe; reviews remain read-only.
async (page) => {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const context = await page.context().browser().newContext({
        viewport: { width: 390, height: 844 }, deviceScaleFactor: 2,
        hasTouch: true, isMobile: true, reducedMotion: 'no-preference'
    });
    const probe = await context.newPage();
    const errors = [];
    probe.on('pageerror', error => errors.push(error.message));
    const base = 'http://127.0.0.1:8765/big-standby-prototype.html';
    const settle = selector => probe.locator(selector).evaluate(async node => {
        const animations = node.getAnimations({ subtree: true }).filter(animation => animation.effect?.getTiming().iterations !== Infinity);
        await Promise.allSettled(animations.map(animation => animation.finished));
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
    const close = async () => {
        await probe.evaluate(() => { if (settings.open) closeSettings(); });
        await probe.waitForFunction(() => !settings.open && !state.settingsOpen && !settings.getClientRects().length);
    };
    const readGlyph = () => probe.locator('#broadcast-toggle').evaluate(node =>
        [...node.querySelectorAll('.broadcast-symbols path, .broadcast-symbols polygon, .broadcast-symbols rect, .broadcast-symbols line, .broadcast-symbols circle')]
            .filter(shape => {
                for (let ancestor = shape; ancestor; ancestor = ancestor.parentElement) {
                    const style = getComputedStyle(ancestor);
                    if (!ancestor.getClientRects().length || style.visibility !== 'visible' || Number(style.opacity) === 0) return false;
                }
                return true;
            }).map(shape => {
                const style = getComputedStyle(shape);
                const box = shape.getBBox();
                return { tag: shape.tagName, path: shape.getAttribute('d'), points: shape.getAttribute('points'),
                    width: box.width, height: box.height, fill: style.fill, stroke: style.stroke,
                    rounded: Number(shape.getAttribute('rx')) > 0 || /[aqc]/i.test(shape.getAttribute('d') || '') ||
                        (style.strokeLinejoin === 'round' && style.stroke !== 'none') };
            }));
    const hasTone = (glyph, tone) => glyph.some(shape => [shape.fill, shape.stroke].some(color => {
        if (!color.startsWith('rgb(')) return false;
        const [r, g, b] = color.slice(4, -1).split(',').map(Number);
        return tone === 'green' ? g > r + 20 && g > b + 20 && g > 100 : r > g + 20 && r > b + 20 && r > 100;
    }));
    const symbols = [];
    let sharedSymbols;
    let edgeCases = 0;
    try {
        for (const variant of ['A', 'C', 'D']) {
            await probe.goto(base + '?variant=' + variant + '&v=40');
            await probe.bringToFront();
            await settle('#broadcast-toggle');
            if (variant === 'A') {
                check(await probe.evaluate(() => {
                    const rect = document.querySelector('.broadcast-frame-a rect');
                    return rect && ['x', 'y', 'width', 'height', 'rx'].map(name => Number(rect.getAttribute(name))).join(',') === '7,16,86,64,32';
                }), 'A must use its taller 86x64 pill at 7,16 with the original width');
            } else if (variant === 'D') {
                check(await probe.locator('.broadcast-frame-d path').getAttribute('d') === 'M34 18H86V58L62 82H10V42Z',
                    'Merged D must retain the former E slash-cut broadcast frame');
            }
            const playGlyph = await readGlyph();
            const button = await probe.locator('#broadcast-toggle').evaluate(node => {
                const rect = node.getBoundingClientRect();
                const label = document.querySelector('#broadcast-label');
                const text = label.getBoundingClientRect();
                return { rect: rect.toJSON(), labelRect: text.toJSON(), labelClass: label.className,
                    role: node.getAttribute('role'), checked: node.getAttribute('aria-checked'),
                    name: node.getAttribute('aria-label'), symbol: node.dataset.symbol };
            });
            check(button.rect.width >= 44 && button.rect.height >= 44 &&
                Math.abs(button.rect.left + button.rect.width / 2 - 195) < 1 && button.rect.bottom <= 844,
                'The icon-only action must keep its touch target at bottom center: ' + JSON.stringify(button));
            check(button.labelClass.includes('sr-only') && button.labelRect.width <= 1 && button.labelRect.height <= 1,
                'Broadcast text must be visually absent, not removed from accessibility');
            check(button.role === 'switch' && button.checked === 'false' && button.name && button.symbol === 'play',
                'The OFF icon must retain a named, unchecked switch');
            await probe.locator('#broadcast-toggle').tap();
            check(await probe.locator('#broadcast-toggle').getAttribute('data-symbol') === 'stop' &&
                await probe.locator('#broadcast-toggle').getAttribute('aria-checked') === 'true',
                'The broadcast action must switch immediately to the stop icon');
            await settle('#broadcast-toggle');
            const stopGlyph = await readGlyph();
            check(playGlyph.length > 0 && stopGlyph.length > 0 && JSON.stringify(playGlyph) !== JSON.stringify(stopGlyph),
                'Starting broadcast must change the actual painted glyph, not only its data-symbol');
            if (variant === 'A') {
                check(hasTone(playGlyph, 'green') && playGlyph.some(shape => shape.rounded) && hasTone(stopGlyph, 'red') &&
                    stopGlyph.some(shape => shape.rounded && Math.abs(shape.width - shape.height) < .1),
                    'A needs its own rounded green play and rounded red square: ' + JSON.stringify({ playGlyph, stopGlyph }));
            } else {
                const pair = JSON.stringify([playGlyph, stopGlyph]);
                if (variant === 'C') sharedSymbols = pair;
                else check(pair === sharedSymbols, 'C/D must keep shared triangle/X glyphs, separate from A: ' + JSON.stringify({ expected: sharedSymbols, actual: pair }));
            }
            if (variant !== 'C') {
                check(await probe.evaluate(() => {
                    const detail = document.querySelector('#status-detail');
                    const text = ['.brand', '.device', '.center', '.connection-hint'].map(selector =>
                        document.querySelector(selector).innerText).join(' ');
                    return !detail.getClientRects().length && !/SIMULATED|prototype|模擬|僅為網頁/i.test(text);
                }), variant + ' must not show center descriptions or prototype disclaimers');
            }
            if (variant === 'D') {
                check(await probe.evaluate(() => {
                    const hint = document.querySelector('#connection-hint');
                    const caption = document.querySelector('.hardline-caption').getBoundingClientRect();
                    const device = document.querySelector('.device').getBoundingClientRect();
                    const inset = Math.min(48, Math.max(24, Math.min(innerWidth, innerHeight) * .03));
                    const section = getComputedStyle(document.querySelector('.device'));
                    const upper = document.querySelector('.e-channel--upper');
                    const lower = document.querySelector('.e-channel--lower');
                    const rows = [...lower.querySelectorAll('.e-stream-row')];
                    const shortcut = upper.querySelectorAll('.e-stream-row')[2];
                    const activeMessage = [...shortcut.querySelectorAll('.e-status-message')].find(node => getComputedStyle(node).display !== 'none');
                    const walker = document.createTreeWalker(activeMessage, NodeFilter.SHOW_TEXT);
                    let visibleGlyphs = false;
                    while (walker.nextNode()) {
                        const text = walker.currentNode;
                        if (!text.textContent.trim()) continue;
                        const range = document.createRange(); range.selectNodeContents(text);
                        const rect = range.getBoundingClientRect();
                        const css = getComputedStyle(text.parentElement);
                        const color = css.color.match(/[\d.]+/g)?.map(Number) ?? [];
                        visibleGlyphs ||= rect.width > 0 && rect.height > 0 &&
                            rect.right > 0 && rect.bottom > 0 && rect.left < innerWidth && rect.top < innerHeight &&
                            Number.parseFloat(css.fontSize) >= 14.65 && Number(css.fontWeight) >= 700 &&
                            color[0] >= 241.5 && color[0] === color[1] && color[1] === color[2];
                    }
                    return !caption.width && !caption.height && !hint.getClientRects().length && !hint.textContent.trim() &&
                        Math.abs(device.left - inset) <= 5 && parseFloat(section.borderLeftWidth) === 2 && parseFloat(section.paddingLeft) === 10 &&
                        device.top >= innerHeight * .6 && device.bottom <= innerHeight - 12 &&
                        getComputedStyle(document.querySelector('.device')).textAlign === 'left' &&
                        document.querySelector('.device .micro-label').textContent.trim() === 'RECEIVER' &&
                        upper.querySelectorAll('.e-stream-row').length === 3 && rows.length === 2 &&
                        shortcut.matches('.e-stream-row--status') && Boolean(shortcut.querySelector('.e-stream-track')) &&
                        rows.every(row => !row.matches('.e-stream-row--status') && row.querySelector('.e-stream-track')) && activeMessage.dataset.status === 'waiting' &&
                        /Win\s*\+\s*K/.test(activeMessage.textContent) && /MIRAX\s*·\s*客廳/.test(activeMessage.textContent) && visibleGlyphs;
                }), 'D must retain the bottom safe-left receiver with its left section rule, hide its old hint, and paint bright bold waiting guidance in moving upper row three');
            }
            symbols.push(variant + ': play -> stop');
            await probe.locator('#broadcast-toggle').tap();
            await settle('#broadcast-toggle');
        }
        await probe.goto(base + '?variant=C&log=rail&v=40');
        await probe.bringToFront();
        for (const [width, height] of [[390, 844], [320, 568], [568, 320], [2560, 720]]) {
            await probe.setViewportSize({ width, height });
            await settle('#broadcast-toggle');
            const triangle = await probe.evaluate(() => {
                const frame = document.querySelector('.broadcast-frame-c');
                const polygon = frame.matches('polygon') ? frame : frame.querySelector('polygon');
                const matrix = polygon.getScreenCTM();
                const points = [...polygon.points].map(point => new DOMPoint(point.x, point.y).matrixTransform(matrix));
                const sorted = [...points].sort((a, b) => a.y - b.y);
                const tip = sorted.at(-1);
                const topLeft = sorted.slice(0, 2).sort((a, b) => a.x - b.x)[0];
                const edge = { x: tip.x - topLeft.x, y: tip.y - topLeft.y };
                const line = document.querySelector('.broadcast-c-double-edge');
                const length = line.getTotalLength();
                const transform = line.getScreenCTM();
                const start = line.getPointAtLength(length * .1).matrixTransform(transform);
                const end = line.getPointAtLength(length * .9).matrixTransform(transform);
                const double = { x: end.x - start.x, y: end.y - start.y };
                const magnitude = value => Math.hypot(value.x, value.y);
                const signedDistance = point => (edge.y * (point.x - topLeft.x) - edge.x * (point.y - topLeft.y)) / magnitude(edge);
                const center = { x: points.reduce((sum, point) => sum + point.x, 0) / 3,
                    y: points.reduce((sum, point) => sum + point.y, 0) / 3 };
                const sides = points.map((point, index) => Math.hypot(point.x - points[(index + 1) % 3].x,
                    point.y - points[(index + 1) % 3].y));
                return { count: points.length, sides, downward: tip.y > sorted[1].y && Math.abs(sorted[0].y - sorted[1].y) < .1,
                    parallel: Math.abs(edge.x * double.x + edge.y * double.y) / (magnitude(edge) * magnitude(double)),
                    offset: signedDistance(start), endOffset: signedDistance(end), centerOffset: signedDistance(center),
                    base: [...polygon.points].map(point => [point.x, point.y]),
                    rail: [line.getPointAtLength(0), line.getPointAtLength(length)].map(point => [point.x, point.y]) };
            });
            const nearPoints = (actual, expected) => actual.length === expected.length && actual.every((point, index) =>
                point.every((value, axis) => Math.abs(value - expected[index][axis]) < .00001));
            check(triangle.count === 3 && triangle.downward && Math.max(...triangle.sides) / Math.min(...triangle.sides) < 1.01 &&
                triangle.parallel > .995 && triangle.offset < -1 && triangle.offset > -10 &&
                triangle.endOffset < -1 && triangle.endOffset > -10 && triangle.centerOffset > 0 &&
                nearPoints(triangle.base, [[12, 18], [88, 18], [50, 83.81793]]) && nearPoints(triangle.rail, [[10, 28], [38, 76.49742]]),
                'C must keep its equilateral base with a parallel rail outside the left slant, opposite the center: ' + JSON.stringify(triangle));
            await probe.evaluate(() => { state.cLogStyle = 'rail'; render(); showRandomCHud(); });
            const title = await probe.evaluate(() => {
                const style = node => getComputedStyle(document.querySelector(node));
                return { size: parseFloat(style('.c-hud-title').fontSize), base: parseFloat(style('.c-hud-header').fontSize),
                    weight: Number(style('.c-hud-title').fontWeight), color: style('.c-hud-title').color,
                    opacity: style('.c-hud-dialog').opacity, bodyOpacity: style('.c-hud-body').opacity, titleOpacity: style('.c-hud-title').opacity };
            });
            check(Math.abs(title.size - title.base - 4 / 3) < .04 && title.weight >= 700 && title.color === 'rgb(255, 255, 255)' &&
                title.opacity === '1' && title.bodyOpacity === '0.5' && title.titleOpacity === '1', 'Rail title must keep +1pt bold white above the half-opacity body: ' + JSON.stringify(title));
            edgeCases += 1;
        }
        await probe.setViewportSize({ width: 390, height: 844 });
        await probe.locator('#broadcast-toggle').tap();
        await probe.evaluate(() => { confirmServiceReady(); setPhase('connecting'); setPhase('failed'); });
        check(await probe.locator('#broadcast-toggle').getAttribute('data-symbol') === 'warning' &&
            await probe.locator('#broadcast-toggle').getAttribute('aria-checked') === 'true',
            'A temporary C failure must show a warning without stopping broadcast');
        await settle('#broadcast-toggle');
        const warning = await probe.locator('.broadcast-symbol-warning').evaluate(node => {
            const style = getComputedStyle(node);
            return { color: style.color, stroke: style.stroke, opacity: Number(style.opacity), shown: Boolean(node.getClientRects().length) && style.display !== 'none' };
        });
        const red = (warning.color + warning.stroke).match(/rgb\((\d+),\s*(\d+),\s*(\d+)\)/g) || [];
        check(red.some(value => {
            const channels = value.match(/\d+/g).map(Number);
            return channels[0] > 200 && channels[1] < 130 && channels[2] < 130;
        }) && warning.shown && warning.opacity > .9, 'The temporary warning glyph must be visibly red: ' + JSON.stringify(warning));
        await probe.waitForFunction(() => cFailureStartedAt !== null);
        await probe.evaluate(() => { particleTime = cFailureStartedAt + 7510; });
        await probe.waitForFunction(() => state.phase === 'waiting' && document.querySelector('#broadcast-toggle').dataset.symbol === 'stop');
        await probe.locator('#broadcast-toggle').tap();
        check(await probe.locator('#broadcast-toggle').getAttribute('data-symbol') === 'play', 'Stopping after recovery must restore the upward triangle');
        symbols.push('C: red warning -> automatic X recovery -> upward triangle');
        await probe.goto(base + '?variant=C&log=rail&v=41');
        await probe.bringToFront();
        const animatedSwitch = await probe.evaluate(() => {
            toggleBroadcast();
            return document.querySelector('#broadcast-toggle').getAnimations({ subtree: true }).some(animation =>
                Number(animation.effect?.getComputedTiming().duration) > 0 &&
                animation.effect.getKeyframes().some(frame => frame.opacity !== undefined || frame.transform !== undefined));
        });
        check(animatedSwitch, 'Broadcast symbol changes must have an actual opacity or transform transition');
        await settle('#broadcast-toggle');
        const opening = await probe.evaluate(() => {
            document.querySelector('#settings-handle').click();
            const animation = settings.getAnimations().find(item => Number(item.effect?.getComputedTiming().duration) > 0);
            if (animation) animation.currentTime = Number(animation.effect.getComputedTiming().duration) * .25;
            return { left: settings.getBoundingClientRect().left, open: settings.open, stateOpen: state.settingsOpen, animated: Boolean(animation),
                duration: Number(animation?.effect.getComputedTiming().duration),
                frames: animation?.effect.getKeyframes().map(frame => frame.transform) };
        });
        check(opening.open && opening.stateOpen && opening.animated && opening.left < -.5,
            'Drawer opening must visibly slide from the left, not just set open: ' + JSON.stringify(opening));
        await settle('#settings');
        check(Math.abs((await probe.locator('#settings').boundingBox()).x) < 1, 'Drawer opening must settle exactly at the left edge');
        const closing = await probe.evaluate(() => {
            document.querySelector('#close-settings').click();
            const animation = settings.getAnimations().find(item => Number(item.effect?.getComputedTiming().duration) > 0);
            if (animation) animation.currentTime = Number(animation.effect.getComputedTiming().duration) * .25;
            return { left: settings.getBoundingClientRect().left, animated: Boolean(animation) };
        });
        check(closing.animated && closing.left < -.5, 'Drawer closing must also visibly slide, not disappear immediately: ' + JSON.stringify(closing));
        await close();
        for (let pass = 0; pass < 3; pass += 1) {
            await probe.evaluate(() => { openSettings(); closeSettings(); openSettings(); });
            await settle('#settings');
            check(await probe.evaluate(() => settings.open && state.settingsOpen &&
                document.querySelector('#settings-handle').getAttribute('aria-expanded') === 'true'),
                'Reopening during close must cancel the old completion callback');
            await probe.evaluate(() => { closeSettings(); openSettings(); closeSettings(); });
            await close();
            check(await probe.evaluate(() => !settings.contains(document.activeElement)), 'A closed drawer must not retain keyboard focus');
        }
        await probe.evaluate(() => { openSettings(); closeSettings(false, true); openSettings(); });
        await settle('#settings');
        check(await probe.evaluate(() => settings.open && state.settingsOpen),
            'A queued native close event must not invalidate a drawer reopened in the same turn');
        await close();
        await probe.locator('#settings-handle').tap();
        await probe.setViewportSize({ width: 568, height: 320 });
        await close();
        await probe.goto(base + '?variant=D&motion=study-209-cathode-extrusion&v=41');
        await probe.bringToFront();
        const enter = await probe.evaluate(() => {
            toggleBroadcast(); confirmServiceReady();
            setPhase('connecting');
            setPhase('projecting');
            return { phase: state.phase, focus: document.activeElement.id,
                animated: document.querySelector('#projection').getAnimations({ subtree: true }).some(animation =>
                    Number(animation.effect?.getComputedTiming().duration) > 0) };
        });
        check(enter.phase === 'projecting' && enter.animated && enter.focus === 'disconnect-projection',
            'Projection entry must animate and move focus to its usable return action: ' + JSON.stringify(enter));
        await settle('#projection');
        check(!(await probe.locator('#broadcast-toggle').isVisible()), 'The broadcast action must not overlay playing content');
        await probe.locator('#disconnect-projection').click();
        const returning = await probe.evaluate(() => ({ phase: state.phase,
            animated: document.querySelector('#standby-view').getAnimations({ subtree: true }).some(animation =>
                Number(animation.effect?.getComputedTiming().duration) > 0) }));
        check(returning.phase === 'waiting' && returning.animated, 'Returning from playback must animate into standby');
        await settle('#standby-view');
        check(await probe.locator('#broadcast-toggle').isVisible() && await probe.evaluate(() => !document.querySelector('#projection').contains(document.activeElement)),
            'Returning from playback must restore the main action and not leave focus in hidden projection');
        await probe.setViewportSize({ width: 390, height: 844 });
        await probe.emulateMedia({ reducedMotion: 'reduce' });
        await probe.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        const preferred = await probe.evaluate(() => {
            document.querySelector('#settings-handle').click();
            const animation = settings.getAnimations().find(item => Number(item.effect?.getComputedTiming().duration) > 0);
            if (animation) animation.currentTime = Number(animation.effect.getComputedTiming().duration) * .25;
            return { left: settings.getBoundingClientRect().left, open: settings.open, stateOpen: state.settingsOpen,
                duration: Number(animation?.effect.getComputedTiming().duration),
                frames: animation?.effect.getKeyframes().map(frame => frame.transform) };
        });
        check(preferred.open && preferred.stateOpen && preferred.left < -.5 && preferred.duration === opening.duration &&
            JSON.stringify(preferred.frames) === JSON.stringify(opening.frames),
            'Both system preferences must retain identical drawer translation and timing: ' + JSON.stringify({ opening, preferred }));
        check(await probe.locator('#a-transition-override, #c-motion-override').count() === 0, 'The drawer must not expose reduced-motion settings');
        await settle('#settings');
        await close();
        const preferenceSwitch = await probe.evaluate(() => {
            toggleBroadcast();
            return document.querySelector('#broadcast-toggle').getAnimations({ subtree: true }).some(animation =>
                Number(animation.effect?.getComputedTiming().duration) > 0 &&
                animation.effect.getKeyframes().some(frame => frame.opacity !== undefined || frame.transform !== undefined));
        });
        check(preferenceSwitch, 'System reduction must retain the actual broadcast symbol transition');
        await settle('#broadcast-toggle');
        await probe.evaluate(() => { state.motion.paused = true; render(); openSettings(); });
        check(await probe.locator('#settings').evaluate(node => node.getAnimations({ subtree: true }).some(animation =>
            Number(animation.effect?.getComputedTiming().duration) === 320)), 'Pausing decoration must preserve ordinary interactive drawer feedback');
        await settle('#settings');
        await close();
        check(await probe.evaluate(() => !settings.open && !state.settingsOpen && !settings.getClientRects().length),
            'Preference changes must never leave a transparent blocking modal');
        check(errors.length === 0, 'Unexpected page errors: ' + errors.join('; '));
        return { symbols, edgeCases, drawerTransitions: 'open, close, rapid reversal, resize, preference-independent, paused', playback: 'entry and return', physicalDevice: 'not tested' };
    } finally {
        await context.close();
    }
}
