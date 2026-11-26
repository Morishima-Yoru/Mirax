// Run with Playwright browser_run_code_unsafe({ filename: 'design/big-standby-motion-prototype/verify-rwd.js' }).
async (page) => {
    const sizes = [[800, 528], [800, 600], [900, 650], [1024, 600], [600, 800],
        [390, 844], [390, 700], [320, 568], [667, 375], [568, 320],
        [1024, 768], [1440, 1000], [2560, 720], [800, 800], [900, 820],
        [901, 821], [601, 680], [600, 600], [390, 820], [390, 821]];
    const selectors = ['.brand', '.device', '.center', '.connection-hint',
        '.hardline-caption', '.field-caption', '.c-standby-plate', '#broadcast-dock'];
    const toolSelectors = ['.prototype-note', '.variant-switcher', '#controls', '#c-review-picker',
        '#d-motion-picker', '#inspector', '#reveal-hint'];
    const openDrawer = async () => {
        if (!(await page.locator('#settings').evaluate(dialog => dialog.open))) await page.locator('#settings-handle').click();
        await page.locator('#settings').evaluate(async dialog => {
            await Promise.all(dialog.getAnimations().map(animation => animation.finished));
            await new Promise(resolve => requestAnimationFrame(resolve));
        });
    };
    const closeDrawer = async () => {
        if (await page.locator('#settings').evaluate(dialog => dialog.open)) await page.locator('#close-settings').click();
        await page.waitForFunction(() => !settings.open && !settings.getClientRects().length);
    };
    const failures = [];
    const errors = [];
    const onError = error => errors.push(error.message);
    page.on('pageerror', onError);
    let cases = 0;
    let navigationChecks = 0;
    let drawerChecks = 0;
    let brandChecks = 0;
    const brandBaselines = new Map();
    const brandGeometryReference = new Map();
    // Brand section geometry begins. Node self-checks exercise this exact D-only expectation.
    function brandSectionBounds(reference) {
        return reference.map((bounds, index) => bounds.map((value, axis) =>
            value + (index === 0 && axis === 2 ? 24 : index > 0 && axis === 0 ? 12 : 0)));
    }
    // Brand section geometry ends.
    try {
        for (const variant of ['A', 'C', 'D']) {
            await page.setViewportSize({ width: 800, height: 528 });
            await page.goto(`http://127.0.0.1:8765/big-standby-prototype.html?variant=${variant}&motion=off&v=41&rwd=${Date.now()}`);
            await page.evaluate(() => document.fonts.ready);
            await openDrawer();
            await page.locator('#show-prototype-tools').check();
            await page.locator('#motion-toggle').click();
            if (variant === 'D') {
                const navigation = await page.evaluate(() => {
                    const picker = document.querySelector('#d-motion');
                    const ids = Array.from(picker.options, option => option.value);
                    const groups = Array.from(picker.querySelectorAll('optgroup'), group => Array.from(group.querySelectorAll('option'), option => option.value)).filter(ids => ids[0]?.startsWith('study-'));
                    const first = ids.find(id => id.startsWith('study-'));
                    if (!first) throw Error('No study available for navigation regression');
                    return { first, next: ids[(ids.indexOf(first) + 1) % ids.length], last: ids.at(-1),
                        crossFrom: groups[0].at(-1), crossTo: groups[1]?.[0] ?? ids[0] };
                });
                await page.selectOption('#d-motion', navigation.first);
                const expectMotion = async id => {
                    await closeDrawer();
                    const snapshot = await page.evaluate(() => {
                        const frame = createDStudyFrame(0);
                        const viewport = document.querySelector('#stage').getBoundingClientRect();
                        const dock = document.querySelector('#broadcast-dock').getBoundingClientRect();
                        return { mode: document.querySelector('#d-motion').value,
                            urlMode: new URL(location.href).searchParams.get('motion'),
                            variant: new URL(location.href).searchParams.get('variant'),
                            paused: document.querySelector('#motion-toggle').getAttribute('aria-pressed'),
                            time: Number(document.querySelector('#stage').dataset.dMotionTime),
                            drawerOpen: document.querySelector('#settings').open,
                            viewport: { width: viewport.width, height: viewport.height }, dockTop: dock.top - viewport.top,
                            frame: frame ? { width: frame.width, height: frame.height, top: frame.field.top, bottom: frame.field.bottom } : null };
                    });
                    if (snapshot.mode !== id || snapshot.urlMode !== id || snapshot.variant !== 'D' || snapshot.paused !== 'true' || snapshot.time !== 0) {
                        throw new Error('Motion navigation mismatch: ' + JSON.stringify(snapshot));
                    }
                    if (snapshot.drawerOpen || (id.startsWith('study-') && (!snapshot.frame ||
                        Math.abs(snapshot.frame.width - snapshot.viewport.width) > 1 ||
                        Math.abs(snapshot.frame.height - snapshot.viewport.height) > 1 ||
                        snapshot.frame.top.x !== 0 || snapshot.frame.top.width !== snapshot.frame.width ||
                        snapshot.frame.bottom.x !== 0 || snapshot.frame.bottom.width !== snapshot.frame.width ||
                        snapshot.frame.bottom.height <= 0 ||
                        Math.abs(snapshot.frame.bottom.y + snapshot.frame.bottom.height - snapshot.frame.height) > 1 ||
                        snapshot.frame.bottom.y + snapshot.frame.bottom.height <= snapshot.dockTop))) {
                        throw new Error('Closed-drawer study frame mismatch: ' + JSON.stringify(snapshot));
                    }
                    navigationChecks += 1;
                    await openDrawer();
                };
                await page.locator('#next-d-motion').click();
                await expectMotion(navigation.next);
                await page.goBack();
                await expectMotion(navigation.first);
                await page.goForward();
                await expectMotion(navigation.next);
                await page.locator('#previous-d-motion').click();
                await expectMotion(navigation.first);
                await page.locator('#next-d-motion').focus();
                await page.keyboard.press('ArrowRight');
                await expectMotion(navigation.next);
                await page.locator('#next-d-motion').focus();
                await page.keyboard.press('ArrowLeft');
                await expectMotion(navigation.first);
                await page.locator('#next-d-motion').focus();
                await page.keyboard.press('Space');
                await expectMotion(navigation.next);
                await page.selectOption('#d-motion', navigation.last);
                await page.locator('#next-d-motion').click();
                await expectMotion('off');
                await page.locator('#previous-d-motion').click();
                await expectMotion(navigation.last);
                await page.selectOption('#d-motion', navigation.crossFrom);
                await page.locator('#next-d-motion').click();
                await expectMotion(navigation.crossTo);
                await page.selectOption('#d-motion', navigation.first);
            }
            await closeDrawer();
            for (const phase of ['off', 'failed']) {
                if (phase === 'failed') {
                    await page.locator('#broadcast-toggle').click();
                    await openDrawer();
                    await page.locator('#confirm-service-ready').click();
                    await page.locator('#simulate-connect').click();
                    await page.locator('#simulate-fail').click();
                    await closeDrawer();
                }
                await page.waitForFunction(() => document.querySelector('#status-title').textContent === phases[state.phase].title &&
                    ['#status-title', '#status-eyebrow', '#connection-hint'].every(selector =>
                        document.querySelector(selector).getAnimations().every(animation => animation.playState === 'finished')));
                for (const [width, height] of sizes) {
                    await page.setViewportSize({ width, height });
                    await page.waitForFunction(() => canvasWidth === stage.clientWidth && canvasHeight === stage.clientHeight);
                    const brandSample = await page.evaluate(() => {
                        const selectors = ['.brand', '.brand-mark', '.brand-name', '.brand small'];
                        const origin = document.querySelector('.brand').getBoundingClientRect();
                        const paint = selectors.map(selector => {
                            const css = getComputedStyle(document.querySelector(selector));
                            return ['font-size', 'font-weight', 'letter-spacing', 'line-height', 'font-family', 'color',
                                'opacity', 'width', 'height', 'gap', 'margin-top'].map(property => css.getPropertyValue(property));
                        });
                        const bounds = selectors.map(selector => { const rect = document.querySelector(selector).getBoundingClientRect(); return [rect.left - origin.left, rect.top - origin.top, rect.width, rect.height]; });
                        const name = document.createRange(), version = document.createRange();
                        name.selectNodeContents(document.querySelector('.brand-name').firstChild); version.selectNodeContents(document.querySelector('.brand small'));
                        return { paint, bounds, versionGap: version.getBoundingClientRect().top - name.getBoundingClientRect().bottom };
                    });
                    const branding = JSON.stringify(brandSample.paint);
                    const brandKey = width + 'x' + height;
                    if (variant === 'A') {
                        brandBaselines.set(brandKey, branding);
                        brandGeometryReference.set(brandKey, brandSample);
                    } else {
                        const reference = brandGeometryReference.get(brandKey);
                        if (variant === 'D') {
                            if (Math.abs(parseFloat(brandSample.paint[0][7]) - parseFloat(reference.paint[0][7]) - 24) > .02) throw Error('D brand width must add the 2px rule plus 10px left and 12px right inner padding at ' + brandKey);
                            const normalized = brandSample.paint.map(values => values.slice()); normalized[0][7] = reference.paint[0][7];
                            if (JSON.stringify(normalized) !== brandBaselines.get(brandKey)) throw Error('D must retain every other A brand paint/size/gap at ' + brandKey);
                            const expected = brandSectionBounds(reference.bounds);
                            if (brandSample.bounds.some((bounds, index) => bounds.some((value, axis) => Math.abs(value - expected[index][axis]) > .05)) || Math.abs(brandSample.versionGap - reference.versionGap) > .05)
                                throw Error('D brand content/mark/text/version geometry drift at ' + brandKey);
                        } else if (branding !== brandBaselines.get(brandKey)) throw Error(variant + ' must keep responsive A branding at ' + brandKey + ': ' + branding);
                    }
                    brandChecks += 1;
                    for (const prototypeTools of [false, true]) {
                        const issues = await page.evaluate(({ selectors, toolSelectors, prototypeTools }) => {
                            const geometry = () => selectors.map(selector => {
                                const rect = document.querySelector(selector).getBoundingClientRect();
                                return [rect.x, rect.y, rect.width, rect.height];
                            });
                            const before = geometry();
                            state.prototypeTools = prototypeTools;
                            state.controlsOpen = prototypeTools;
                            render();
                            const after = geometry();
                            const isD = document.querySelector('#stage').classList.contains('v-D');
                            const isC = document.querySelector('#stage').classList.contains('v-C');
                            const aOff = state.variant === 'A' && !state.broadcast;
                            const plates = isD ? ['.brand', '.device', '.center'] : ['.brand', '.device', '.center', '.connection-hint'];
                            const visible = selectors.flatMap(selector => {
                                const node = document.querySelector(selector);
                                const rect = node.getBoundingClientRect();
                                return rect.width && rect.height ? [{ selector, rect }] : [];
                            });
                            const issues = [];
                            if (document.querySelector('#settings').open) issues.push('scene checked behind an open drawer');
                            for (const selector of toolSelectors) {
                                if (document.querySelector(selector).getClientRects().length) issues.push(selector + ' leaked onto standby');
                            }
                            for (const [index, selector] of selectors.entries()) {
                                if (after[index].some((value, axis) => Math.abs(value - before[index][axis]) > .02)) {
                                    issues.push(selector + ' geometry changed with prototype tools');
                                }
                            }
                            for (const { selector, rect } of visible) {
                                if (rect.left < -6 || rect.right > innerWidth + 6 || rect.top < 0 || rect.bottom > innerHeight + 1) {
                                    issues.push(`${selector} outside viewport`);
                                }
                            }
                            for (let i = 0; i < visible.length; i += 1) {
                                for (const b of visible.slice(i + 1)) {
                                    const a = visible[i];
                                    // Stepped backplates may intersect; their text must stay visible.
                                    if (isD && (plates.includes(a.selector) || plates.includes(b.selector))) continue;
                                    const width = Math.min(a.rect.right, b.rect.right) - Math.max(a.rect.left, b.rect.left);
                                    const height = Math.min(a.rect.bottom, b.rect.bottom) - Math.max(a.rect.top, b.rect.top);
                                    if (width > 1 && height > 1) issues.push(`${a.selector} overlaps ${b.selector}`);
                                }
                            }
                            if (document.documentElement.scrollWidth > innerWidth || document.documentElement.scrollHeight > innerHeight) {
                                issues.push('document overflow');
                            }
                            const receiver = document.querySelector('#connection-hint .receiver-name');
                            if (!isC && !isD && !aOff && receiver?.textContent !== '「MIRAX · 客廳」') issues.push('receiver quotes can wrap independently');
                            if (isC && document.querySelector('#connection-hint .connection-title')?.textContent !== document.querySelector('.device-name').textContent) {
                                issues.push('C connection title does not match the receiver');
                            }
                            if (aOff && (document.querySelector('#connection-hint').textContent.trim() || getComputedStyle(document.querySelector('#connection-hint')).display !== 'none')) {
                                issues.push('A connection tooltip must be empty when broadcast is off');
                            }
                            for (const selector of aOff || isD ? [] : isC ? ['.shortcut'] : ['.shortcut', '.receiver-name']) {
                                const node = document.querySelector(`#connection-hint ${selector}`);
                                if (!node || getComputedStyle(node).whiteSpace !== 'nowrap') issues.push(`${selector} can split across lines`);
                            }
                            if (isD) {
                                for (const selector of plates.concat('.brand small')) {
                                    const node = document.querySelector(selector);
                                    const rect = node.getBoundingClientRect();
                                    let shown = Boolean(rect.width && rect.height);
                                    for (let ancestor = node; ancestor; ancestor = ancestor.parentElement) {
                                        const style = getComputedStyle(ancestor);
                                        shown = shown && style.display !== 'none' && style.visibility === 'visible' && Number(style.opacity) > 0;
                                    }
                                    if (!shown) issues.push(selector + ' hidden instead of masked responsively');
                                }
                                const box = selector => document.querySelector(selector).getBoundingClientRect();
                                if (box('.brand').left > innerWidth * .15) issues.push('brand lost its left anchor');
                                const device = box('.device');
                                const inset = Math.min(48, Math.max(24, Math.min(innerWidth, innerHeight) * .03));
                                const section = getComputedStyle(document.querySelector('.device'));
                                if (Math.abs(device.left - inset) > 5 || device.top < innerHeight * .6 || section.textAlign !== 'left') issues.push('receiver lost its original safe-left bottom anchor');
                                if (parseFloat(section.borderLeftWidth) !== 2 || parseFloat(section.paddingLeft) !== 10) issues.push('receiver must declare its section with a left rule and inner padding only');
                                for (const selector of ['#broadcast-dock', '.center']) {
                                    const other = box(selector);
                                    if (Math.min(device.right, other.right) > Math.max(device.left, other.left) && Math.min(device.bottom, other.bottom) > Math.max(device.top, other.top)) issues.push('receiver overlaps ' + selector);
                                }
                                const caption = box('.hardline-caption');
                                if (caption.width || caption.height) issues.push('removed D lower-left content is still visible');
                                const hint = document.querySelector('.connection-hint');
                                if (hint.getClientRects().length || hint.textContent.trim()) issues.push('D foreground connection hint must be hidden and empty');
                                const upper = document.querySelector('.e-channel--upper');
                                const lower = document.querySelector('.e-channel--lower');
                                const rows = [...lower.querySelectorAll('.e-stream-row')];
                                const upperRows = upper.querySelectorAll('.e-stream-row');
                                const hintRow = upperRows[2];
                                if (upperRows.length !== 3 || rows.length !== 2 || !hintRow?.matches('.e-stream-row--status') || !hintRow.querySelector('.e-stream-track') || !rows.every(row => !row.matches('.e-stream-row--status') && row.querySelector('.e-stream-track'))) {
                                    issues.push('D must retain three upper rows with moving status third, and two decorative lower rows');
                                }
                                const clip = upper.querySelector('.e-stream-window').getBoundingClientRect();
                                const walker = document.createTreeWalker(hintRow, NodeFilter.SHOW_TEXT);
                                const active = [];
                                let visibleText = false;
                                while (walker.nextNode()) {
                                    const text = walker.currentNode;
                                    if (!text.textContent.trim() || !text.parentElement.getClientRects().length) continue;
                                    let shown = true;
                                    for (let ancestor = text.parentElement; ancestor; ancestor = ancestor.parentElement) {
                                        const css = getComputedStyle(ancestor);
                                        if (css.display === 'none' || css.visibility !== 'visible' || Number(css.opacity) === 0) shown = false;
                                    }
                                    if (!shown) continue;
                                    const range = document.createRange(); range.selectNodeContents(text);
                                    const rect = range.getBoundingClientRect();
                                    if (!rect.width || !rect.height) continue;
                                    active.push(text.textContent);
                                    const css = getComputedStyle(text.parentElement);
                                    const color = css.color.match(/[\d.]+/g)?.map(Number) ?? [];
                                    const normal = Math.min(...color.slice(0, 3)) >= 241.5;
                                    const failed = color[0] >= 200 && color[0] > color[1] * 1.3 && color[0] > color[2] * 1.3;
                                    if (Number.parseFloat(css.fontSize) < 14.65 || Number(css.fontWeight) < 700 || !(state.phase === 'failed' ? failed : normal)) issues.push('D status font/color violates the retained state contract');
                                    visibleText ||= rect.right > Math.max(0, clip.left) && rect.bottom > Math.max(0, clip.top) && rect.left < Math.min(innerWidth, clip.right) && rect.top < Math.min(innerHeight, clip.bottom);
                                }
                                const activeText = active.join(' ');
                                if (!visibleText) issues.push('moving upper-third status has no visible text intersection');
                                if (state.phase === 'off' && (/Win\s*\+\s*K/.test(activeText) || !/broadcast|廣播/i.test(activeText))) issues.push('OFF status must guide broadcast, not Win + K');
                                if (state.phase === 'failed' && !/retry|try again|重試|再試/i.test(activeText)) issues.push('failed status must explain retry');
                                if (document.querySelector('.device .micro-label').textContent.trim() !== 'RECEIVER') {
                                    issues.push('D receiver label must not contain prototype wording');
                                }
                                for (const selector of plates) {
                                    const node = document.querySelector(selector);
                                    const rect = node.getBoundingClientRect();
                                    if (!rect.width || !rect.height) continue;
                                    const pointer = node.style.getPropertyValue('pointer-events');
                                    const priority = node.style.getPropertyPriority('pointer-events');
                                    // Art is non-interactive; enable its label only to check paint order.
                                    node.style.setProperty('pointer-events', 'auto', 'important');
                                    try {
                                        const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
                                        let text;
                                        let blocked = false;
                                        while (!blocked && (text = walker.nextNode())) {
                                            let offset = 0;
                                            for (const character of text.data) {
                                                const end = offset + character.length;
                                                if (character.trim()) {
                                                    const range = document.createRange();
                                                    range.setStart(text, offset);
                                                    range.setEnd(text, end);
                                                    const glyph = range.getBoundingClientRect();
                                                    for (const fraction of [.35, .65]) {
                                                        const x = glyph.x + glyph.width / 2;
                                                        const y = glyph.y + glyph.height * fraction;
                                                        const front = document.elementFromPoint(x, y);
                                                        if (!glyph.width || !glyph.height || !front || !node.contains(front)) {
                                                            issues.push(selector + ' glyph ' + character + ' is clipped/covered');
                                                            blocked = true;
                                                            break;
                                                        }
                                                    }
                                                }
                                                offset = end;
                                                if (blocked) break;
                                            }
                                        }
                                    } finally {
                                        if (pointer) node.style.setProperty('pointer-events', pointer, priority);
                                        else node.style.removeProperty('pointer-events');
                                    }
                                }
                            }
                            return issues;
                        }, { selectors, toolSelectors, prototypeTools });
                        cases += 1;
                        failures.push(...issues.map(issue => variant + ' ' + phase + ' ' + width + 'x' + height + ' tools=' + prototypeTools + ': ' + issue));
                    }
                    await openDrawer();
                    const drawerIssues = await page.evaluate(toolSelectors => {
                        const drawer = document.querySelector('#settings');
                        const panel = document.querySelector('#prototype-tools-panel');
                        const rect = drawer.getBoundingClientRect();
                        const issues = [];
                        if (!drawer.open || !rect.width || !rect.height || rect.left < 0 || rect.top < 0 ||
                            rect.right > innerWidth + 1 || rect.bottom > innerHeight + 1) issues.push('drawer outside viewport');
                        if (!panel.getClientRects().length) issues.push('expanded tools panel is not visible inside drawer');
                        if (drawer.scrollWidth > drawer.clientWidth + 1 || panel.scrollWidth > panel.clientWidth + 1) {
                            issues.push('drawer content overflows horizontally');
                        }
                        const panelRect = panel.getBoundingClientRect();
                        const tools = toolSelectors.flatMap(selector => {
                            const node = document.querySelector(selector);
                            if (!panel.contains(node)) issues.push(selector + ' escaped the tools panel');
                            const rect = node.getBoundingClientRect();
                            return rect.width && rect.height ? [{ selector, rect }] : [];
                        });
                        for (const [index, tool] of tools.entries()) {
                            if (tool.rect.left < panelRect.left - 1 || tool.rect.right > panelRect.right + 1) {
                                issues.push(tool.selector + ' outside drawer content');
                            }
                            for (const other of tools.slice(index + 1)) {
                                if (Math.min(tool.rect.right, other.rect.right) - Math.max(tool.rect.left, other.rect.left) > 1 &&
                                    Math.min(tool.rect.bottom, other.rect.bottom) - Math.max(tool.rect.top, other.rect.top) > 1) {
                                    issues.push(tool.selector + ' overlaps ' + other.selector + ' inside drawer');
                                }
                            }
                        }
                        const overflow = drawer.scrollHeight - drawer.clientHeight;
                        drawer.scrollTop = drawer.scrollHeight;
                        if (overflow > 1 && (!['auto', 'scroll'].includes(getComputedStyle(drawer).overflowY) ||
                            drawer.scrollTop < overflow - 1)) issues.push('drawer content cannot scroll to its bottom');
                        drawer.scrollTop = 0;
                        const picker = document.querySelector('#d-motion-picker').getBoundingClientRect();
                        if (picker.width && picker.height) {
                            for (const selector of ['#previous-d-motion', '#d-motion', '#next-d-motion']) {
                                const rect = document.querySelector(selector).getBoundingClientRect();
                                const minimum = selector === '#d-motion' ? 64 : 28;
                                if (rect.width < minimum || rect.height < 28 || rect.left < picker.left || rect.right > picker.right) {
                                    issues.push(selector + ' is squeezed or outside its picker');
                                }
                            }
                        }
                        return issues;
                    }, toolSelectors);
                    drawerChecks += 1;
                    failures.push(...drawerIssues.map(issue => variant + ' ' + phase + ' ' + width + 'x' + height + ' drawer: ' + issue));
                    await closeDrawer();
                }
            }
        }
    } finally {
        page.off('pageerror', onError);
    }
    failures.push(...errors);
    if (failures.length) throw new Error(`${failures.length} RWD failures in ${cases} cases:\n${failures.join('\n')}`);
    return { cases, navigationChecks, drawerChecks, brandChecks, failures };
}
