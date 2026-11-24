// Run with Playwright browser_run_code_unsafe({ filename: 'design/big-standby-motion-prototype/verify-c-log-styles.js' }).
async (page) => {
    const check = (value, message) => { if (!value) throw Error(message); };
    const openDrawer = async () => {
        if (!(await page.locator('#settings').evaluate(dialog => dialog.open))) await page.locator('#settings-handle').click();
    };
    const closeDrawer = async () => {
        if (await page.locator('#settings').evaluate(dialog => dialog.open)) await page.locator('#close-settings').click();
        await page.waitForFunction(() => !settings.open && !settings.getClientRects().length);
    };
    const selectStyle = async style => {
        await openDrawer();
        await page.locator('#c-log-style').selectOption(style);
        await closeDrawer();
    };
    const styles = ['rail', 'frame', 'cap', 'index', 'footnote', 'bracket', 'telemetry', 'stamp', 'terminal', 'fold'];
    const errors = [];
    let railPresetCases = 0;
    const onError = error => errors.push(error.message);
    page.on('pageerror', onError);
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&log=rail&v=33');
    try {
        await openDrawer();
        await page.locator('#show-prototype-tools').check();
        await closeDrawer();
        const choices = await page.locator('#c-log-style option').evaluateAll(options => options.map(option => option.value));
        check(JSON.stringify(choices) === JSON.stringify(styles), 'Provide ten directly selectable styles');
        for (const style of styles) {
            await selectStyle(style);
            await page.waitForFunction(() => getComputedStyle(document.querySelector('#c-hud-callout')).opacity === '1');
            const snapshot = await page.evaluate(() => {
                const dialog = document.querySelector('#c-hud-dialog');
                const css = getComputedStyle(dialog);
                return { style: state.cLogStyle, url: new URL(location.href).searchParams.get('log'),
                    opacity: css.opacity, sides: [css.borderTopWidth, css.borderRightWidth, css.borderBottomWidth, css.borderLeftWidth],
                    before: getComputedStyle(dialog, '::before').content, after: getComputedStyle(dialog, '::after').content,
                    english: [...dialog.textContent].every(character => character.charCodeAt(0) < 128) };
            });
            check(snapshot.style === style && snapshot.url === style, 'Selection and shareable URL agree');
            check(snapshot.opacity === (style === 'rail' ? '1' : '0.5') && snapshot.english, 'Only rail lifts its header; all logs stay English-only');
            if (style === 'rail') {
                check(JSON.stringify(snapshot.sides) === JSON.stringify(['0px', '0px', '0px', '1px']), 'Rail has only one left border');
                check(snapshot.before === 'none' && snapshot.after === 'none', 'No extra corner ornaments on Rail');
                const presets = await page.evaluate(() => {
                    const nativeRandom = Math.random;
                    try {
                        return cLogPresets.map((preset, index) => {
                            Math.random = () => (index + .5) / cLogPresets.length;
                            showRandomCHud();
                            const title = document.querySelector('.c-hud-title');
                            const badge = document.querySelector('.c-hud-badge');
                            const header = getComputedStyle(document.querySelector('.c-hud-header'));
                            const titleStyle = getComputedStyle(title);
                            const badgeStyle = getComputedStyle(badge);
                            const badgeRect = badge.getBoundingClientRect();
                            return { preset: preset.header, title: title.textContent, badge: badge.textContent, expectedBadge: preset.badge,
                                titleColor: titleStyle.color, titleOpacity: titleStyle.opacity, headerColor: header.color, headerOpacity: header.opacity,
                                badgeColor: badgeStyle.color, badgeOpacity: badgeStyle.opacity, badgeVisible: badgeRect.width > 0 && badgeRect.height > 0,
                                dialogOpacity: getComputedStyle(document.querySelector('#c-hud-dialog')).opacity, bodyOpacity: getComputedStyle(document.querySelector('#c-hud-body')).opacity };
                        });
                    } finally { Math.random = nativeRandom; }
                });
                check(presets.length > 0 && presets.every(preset => preset.title === preset.preset && preset.badge === preset.expectedBadge &&
                    preset.headerColor === 'rgb(255, 255, 255)' && preset.titleColor === 'rgb(255, 255, 255)' &&
                    preset.headerOpacity === '1' && preset.titleOpacity === '1' && preset.dialogOpacity === '1' && preset.bodyOpacity === '0.5' &&
                    preset.badgeVisible && preset.badgeOpacity === '1' && preset.badgeColor.startsWith('rgb(') && preset.badgeColor !== 'rgb(255, 255, 255)'),
                    'Every Rail preset needs a white opaque header/title and an actual nonwhite badge at unchanged half dialog opacity: ' + JSON.stringify(presets));
                railPresetCases = presets.length;
            }
            await page.screenshot({ path: '.playwright-mcp/c-log-' + style + '.png', scale: 'css' });
        }
        await page.goBack();
        await page.waitForFunction(() => state.cLogStyle === 'terminal');
        check(await page.locator('#c-log-style').inputValue() === 'terminal', 'History restores the selected style');
        await page.goForward();
        await page.waitForFunction(() => state.cLogStyle === 'fold');

        let placementCases = 0;
        for (const [width, height] of [[1440, 1000], [1920, 1080], [800, 528], [601, 820], [600, 821], [390, 844], [320, 568], [667, 375], [568, 320]]) {
            await page.setViewportSize({ width, height });
            const header = await page.evaluate(() => {
                const brand = document.querySelector('.brand');
                const box = brand.getBoundingClientRect();
                return {
                    receiverHidden: getComputedStyle(document.querySelector('.device')).display === 'none',
                    receiverName: document.querySelector('.device-name').textContent,
                    connectionName: document.querySelector('.connection-title').textContent,
                    brandSize: parseFloat(getComputedStyle(document.querySelector('.brand-name')).fontSize),
                    versionSize: parseFloat(getComputedStyle(brand.querySelector('small')).fontSize),
                    inViewport: box.left >= 0 && box.top >= 0 && box.right <= innerWidth && box.bottom <= innerHeight,
                    accessible: [brand, document.querySelector('.connection-title')].every(element => !element.closest('[hidden], [aria-hidden="true"]'))
                };
            });
            check(header.receiverHidden, 'C hides the upper receiver block at ' + width + 'x' + height);
            check(header.receiverName.trim() && header.connectionName === header.receiverName && await page.locator('.connection-title').isVisible(), 'Keep the lower-left receiver name visible');
            check(header.brandSize >= 18 && header.versionSize >= 7 && header.inViewport, 'Keep shared A branding readable without clipping');
            check(header.accessible, 'Brand and lower-left receiver name remain accessible');
            for (const style of styles) {
                await selectStyle(style);
                const failures = await page.evaluate(() => {
                    const nativeRandom = Math.random;
                    const failures = [];
                    const intersects = (a, b) => Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1;
                    try {
                        for (const seed of [.01, .25, .5, .75, .99]) {
                            Math.random = () => seed;
                            showRandomCHud();
                            const box = document.querySelector('#c-hud-dialog').getBoundingClientRect();
                            if (box.left < 4 || box.top < 4 || box.right > innerWidth - 4 || box.bottom > innerHeight - 4) failures.push({ seed, issue: 'outside viewport', box: box.toJSON() });
                            for (const selector of ['.brand', '.connection-hint', '.c-standby-plate']) {
                                const label = document.querySelector(selector).getBoundingClientRect();
                                if (label.height && intersects(box, label)) failures.push({ seed, issue: 'overlaps ' + selector, box: box.toJSON() });
                            }
                        }
                    } finally { Math.random = nativeRandom; }
                    return failures;
                });
                check(failures.length === 0, JSON.stringify({ width, height, style, failures }));
                placementCases += 5;
            }
        }
        const copiesReceiverName = await page.evaluate(() => {
            const receiver = document.querySelector('.device-name');
            const original = receiver.textContent;
            try {
                receiver.textContent = 'MIRAX · Alternate receiver';
                render();
                return document.querySelector('.connection-title').textContent === receiver.textContent;
            } finally { receiver.textContent = original; render(); }
        });
        check(copiesReceiverName, 'C keeps using the existing receiver-name DOM source');
        await page.setViewportSize({ width: 1440, height: 1000 });
        await page.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&log=unknown&v=33');
        await openDrawer();
        await page.locator('#show-prototype-tools').check();
        await closeDrawer();
        check(await page.locator('#c-log-style').inputValue() === 'rail', 'Unknown URL style falls back to Rail');
        for (const variant of ['D', 'A']) {
            await openDrawer();
            await page.locator('#next-variant').click();
            check(await page.locator('#stage').getAttribute('data-variant') === variant, 'Select theme ' + variant);
            check(!await page.locator('#c-log-style').isVisible(), 'C style controls do not appear in ' + variant);
            await closeDrawer();
            check(await page.locator('.device-name').isVisible(), variant + ' keeps its receiver block');
            const sizes = await page.evaluate(() => [
                getComputedStyle(document.querySelector('.brand-name')).fontSize,
                getComputedStyle(document.querySelector('.brand small')).fontSize
            ]);
            check(parseFloat(sizes[0]) >= 16 && parseFloat(sizes[1]) >= 8, variant + ' retains readable shared A branding');
        }
        check(errors.length === 0, 'No browser errors: ' + errors.join('; '));
        return { styles, default: 'rail', railPresetCases, placementCases, opacity: 'rail body 50%, header 100%; other styles 50%', history: 'passed', dIsolation: 'passed' };
    } finally { page.off('pageerror', onError); }
}
