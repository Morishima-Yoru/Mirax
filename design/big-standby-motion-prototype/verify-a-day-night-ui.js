// Run with Playwright browser_run_code_unsafe; all review data remains read-only.
async (page) => {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const context = await page.context().browser().newContext({ reducedMotion: 'no-preference' });
    const probe = await context.newPage();
    const errors = [];
    probe.on('pageerror', error => errors.push(error.message));
    const openDrawer = async () => {
        if (!(await probe.locator('#settings').evaluate(dialog => dialog.open))) await probe.locator('#settings-handle').click();
    };
    const closeDrawer = async () => {
        if (await probe.locator('#settings').evaluate(dialog => dialog.open)) await probe.locator('#close-settings').click();
        await probe.waitForFunction(() => !settings.open && !settings.getClientRects().length);
    };
    const sample = () => probe.evaluate(() => {
        const style = selector => getComputedStyle(document.querySelector(selector));
        const orbit = selector => {
            const value = style(selector);
            const matrix = new DOMMatrixReadOnly(value.transform);
            const rect = document.querySelector(selector).querySelector('.a-sun, .a-moon').getBoundingClientRect();
            return { x: matrix.m41, y: matrix.m42, opacity: Number(value.opacity),
                left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, center: (rect.left + rect.right) / 2 };
        };
        const sky = document.querySelector('#landscape');
        const viewBox = sky.querySelector('svg').viewBox.baseVal;
        const celestial = sky.querySelector('.a-celestial').transform.baseVal.consolidate().matrix;
        const moon = sky.querySelector('.a-moon').transform.baseVal.consolidate().matrix;
        return { broadcast: state.broadcast, phase: state.phase, sky: style('#a-sky stop').stopColor,
            sunRadius: sky.querySelector('.a-sun').r.baseVal.value, moonScale: [moon.a, moon.d],
            celestialY: new DOMPoint(1110, 204).matrixTransform(celestial).y,
            viewBox: { y: viewBox.y, width: viewBox.width, height: viewBox.height },
            statusTop: parseFloat(style('.center').top),
            skyStops: [...document.querySelectorAll('#a-sky stop')].map(node => getComputedStyle(node).stopColor),
            ridge: style('#landscape .a-ridge-far').fill, moonOrbit: orbit('.a-moon-orbit'),
            sunOrbit: orbit('.a-sun-orbit'), panorama: style('#landscape').display,
            viewport: document.querySelector('#stage').getBoundingClientRect().toJSON(),
            hint: document.querySelector('#connection-hint').textContent.trim(), hintDisplay: style('#connection-hint').display };
    });
    const settled = async day => {
        await probe.waitForFunction(day => {
            const opacity = selector => Number(getComputedStyle(document.querySelector(selector)).opacity);
            return day ? opacity('.a-sun-orbit') > .999999 && opacity('.a-moon-orbit') < .000001 :
                opacity('.a-moon-orbit') > .999999 && opacity('.a-sun-orbit') < .000001;
        }, day);
        await probe.waitForFunction(() => document.querySelector('#status-title').textContent === phases[state.phase].title &&
            ['#status-title', '#status-eyebrow', '#connection-hint', '.device .micro-label'].every(selector =>
                document.querySelector(selector).getAnimations().every(animation => animation.playState === 'finished')));
        const value = await sample();
        check(value.broadcast === day && value.panorama === 'block', 'The visible A scene must follow the broadcast switch');
        check(day ? value.hint.includes('Windows') && value.hintDisplay !== 'none' : value.hint === '' && value.hintDisplay === 'none',
            'The A connection tooltip must contain text only while broadcasting');
        const active = day ? value.sunOrbit : value.moonOrbit;
        check(Math.abs(active.center - (value.viewport.left + value.viewport.width / 2)) < value.viewport.width * .03 &&
            active.left >= value.viewport.left && active.right <= value.viewport.right,
            'The active body must be centered in the visible sky, not anchored to the cropped SVG right side: ' + JSON.stringify(value));
        check(day ? value.moonOrbit.left >= value.viewport.right + 15.5 : value.sunOrbit.right <= value.viewport.left - 15.5,
            'The inactive sun/moon must clear the actual viewport edge by at least 16 CSS pixels: ' + JSON.stringify(value));
        check(value.sunRadius === 33 && value.moonScale.every(scale => Math.abs(scale - 11 / 9) < .00001),
            'A must retain its enlarged 33-unit sun and 11/9 crescent scale');
        const skyScale = Math.max(value.viewport.width / value.viewBox.width, value.viewport.height / value.viewBox.height);
        const visibleHeight = value.viewport.height / skyScale;
        const visibleTop = value.viewBox.y + (value.viewBox.height - visibleHeight) / 2;
        const portraitY = Math.max(visibleTop + 48, Math.min(visibleTop + visibleHeight * .25, value.viewBox.y + value.viewBox.height * .25));
        if (value.viewport.width > value.viewport.height) {
            check(Math.abs(value.statusTop / value.viewport.height - .43) < .001 && value.celestialY < portraitY &&
                value.celestialY >= visibleTop + 42 - .01, 'Landscape must lift the status to 43% and the celestial bodies above their portrait anchor without clipping: ' + JSON.stringify(value));
        } else check(Math.abs(value.celestialY - portraitY) < .01, 'Portrait celestial anchoring must remain unchanged');
        return value;
    };
    const luminance = color => {
        const rgb = color.match(/[\d.]+/g).slice(0, 3).map(value => Number(value) / 255)
            .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
        return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722;
    };
    let checkedViewports = 0;
    try {
        for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
            await probe.setViewportSize(viewport);
            await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=A&v=35');
            await probe.bringToFront();
            const night = await settled(false);
            check(night.phase === 'off', 'Cold A load must be night with reception off');
            await probe.locator('#broadcast-toggle').click();
            await probe.waitForFunction(() => {
                const opacity = Number(getComputedStyle(document.querySelector('.a-sun-orbit')).opacity);
                return opacity > .4 && opacity < .6;
            });
            const rising = await sample();
            const day = await settled(true);
            check(rising.sunOrbit.x > night.sunOrbit.x && rising.sunOrbit.x < day.sunOrbit.x &&
                rising.sunOrbit.y < night.sunOrbit.y && rising.sunOrbit.y > day.sunOrbit.y &&
                rising.moonOrbit.x > night.moonOrbit.x && rising.moonOrbit.x < day.moonOrbit.x &&
                rising.moonOrbit.y > night.moonOrbit.y && rising.moonOrbit.y < day.moonOrbit.y,
                'The sun must rise from lower left while the moon sets toward lower right');
            check(rising.sunOrbit.center < rising.viewport.left + rising.viewport.width * .4 &&
                rising.moonOrbit.center > rising.viewport.left + rising.viewport.width * .6 &&
                rising.sunOrbit.left >= rising.viewport.left && rising.moonOrbit.right <= rising.viewport.right,
                'Mid-transition bodies must occupy opposite visible halves of the screen, not a shared right-side area');
            check(rising.skyStops.every((color, index) => color !== night.skyStops[index] && color !== day.skyStops[index]) &&
                rising.ridge !== night.ridge && rising.ridge !== day.ridge,
                'Every sky gradient stop and the mountain color must visibly interpolate, not jump between endpoint palettes');
            check(day.phase === 'waiting' && luminance(day.sky) > luminance(night.sky) * 2 &&
                luminance(day.ridge) > luminance(night.ridge) * 2, 'Broadcasting must visibly lighten both sky and mountains before any connection');
            for (const [phase, selector] of [
                ['connecting', '#simulate-connect'], ['failed', '#simulate-fail'], ['waiting', '#simulate-reset'],
                ['connecting', '#simulate-connect'], ['projecting', '#simulate-project'], ['waiting', '#simulate-disconnect']
            ]) {
                await openDrawer();
                await probe.locator(selector).click();
                await closeDrawer();
                const phaseScene = await sample();
                check(phaseScene.phase === phase && phaseScene.broadcast && phaseScene.sunOrbit.opacity > .999999 && phaseScene.moonOrbit.opacity < .000001 && phaseScene.sky === day.sky,
                    'Waiting, connecting, failure and projection must retain the broadcast-driven day palette');
            }
            await openDrawer();
            await probe.locator('#settings-broadcast-toggle').click();
            await closeDrawer();
            await probe.waitForFunction(() => {
                const opacity = Number(getComputedStyle(document.querySelector('.a-moon-orbit')).opacity);
                return opacity > .4 && opacity < .6;
            });
            const setting = await sample();
            check(setting.sunOrbit.x < day.sunOrbit.x && setting.sunOrbit.y > day.sunOrbit.y &&
                setting.moonOrbit.x < day.moonOrbit.x && setting.moonOrbit.y < day.moonOrbit.y,
                'The sun must set toward lower left while the moon rises from lower right');
            await settled(false);
            await probe.evaluate(() => toggleBroadcast());
            await probe.waitForFunction(() => {
                const opacity = Number(getComputedStyle(document.querySelector('.a-sun-orbit')).opacity);
                return opacity > .05 && opacity < .6;
            });
            await probe.evaluate(() => toggleBroadcast());
            await settled(false);
            await probe.evaluate(() => { toggleMotion(); toggleBroadcast(); });
            check((await settled(true)).sky === day.sky, 'Paused decoration must not block the status-driven daytime appearance');
            await probe.evaluate(() => { toggleBroadcast(); toggleMotion(); });
            await settled(false);
            await probe.emulateMedia({ reducedMotion: 'reduce' });
            await probe.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            await probe.evaluate(() => toggleBroadcast());
            await probe.waitForFunction(() => {
                const opacity = Number(getComputedStyle(document.querySelector('.a-sun-orbit')).opacity);
                return opacity > .4 && opacity < .6;
            });
            const preferred = await sample();
            check(preferred.sunOrbit.x > night.sunOrbit.x && preferred.sunOrbit.x < day.sunOrbit.x &&
                preferred.sunOrbit.y < night.sunOrbit.y && preferred.sunOrbit.y > day.sunOrbit.y &&
                preferred.skyStops.every((color, index) => color !== night.skyStops[index] && color !== day.skyStops[index]) &&
                preferred.ridge !== night.ridge && preferred.ridge !== day.ridge,
                'System reduction must retain the same rising trajectory and full palette interpolation');
            check((await settled(true)).sky === day.sky, 'Both system preferences must reach the identical day palette');
            await probe.evaluate(() => { toggleBroadcast(); toggleBroadcast(); toggleBroadcast(); });
            await settled(false);
            await probe.emulateMedia({ reducedMotion: 'no-preference' });
            await probe.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            checkedViewports += 1;
        }
        const regression = await probe.evaluate(() => {
            const result = [];
            for (const [variant, selector] of [['C', '#particle-scene'], ['D', '#hardline-scene']]) {
                state.variant = variant; render();
                const scene = document.querySelector(selector);
                const signature = () => {
                    const style = getComputedStyle(scene);
                    return [style.display, style.backgroundColor, style.filter, scene.innerHTML].join('|');
                };
                const before = signature();
                toggleBroadcast();
                const after = signature();
                const hint = document.querySelector('#connection-hint');
                const correctHint = variant === 'D' ? !hint.getClientRects().length && !hint.textContent.trim() : Boolean(hint.textContent.trim());
                result.push(before === after && getComputedStyle(document.querySelector('#landscape')).display === 'none' && correctHint);
                toggleBroadcast();
            }
            state.variant = 'A'; render();
            return result;
        });
        check(regression.every(Boolean), 'Day/night changes must not modify C/D scene markup or visibility');
        await settled(false);
        await probe.evaluate(() => { state.motion.paused = true; render(); });
        for (const [width, height] of [[320, 568], [667, 375], [2560, 720], [800, 800]]) {
            await probe.setViewportSize({ width, height });
            await probe.waitForFunction(() => canvasWidth === document.querySelector('#stage').clientWidth);
            await settled(false);
            await probe.evaluate(() => toggleBroadcast());
            await settled(true);
            await probe.evaluate(() => toggleBroadcast());
        }
        check(errors.length === 0, 'Unexpected page errors: ' + errors.join('; '));
        return { viewports: checkedViewports, initialNight: 'passed', broadcastDay: 'passed',
            connectionPhases: 'broadcast-driven', bothSwitches: 'passed', pause: 'passed',
            systemPreferences: 'identical transitions', rapidReversal: 'passed', gradientInterpolation: 'all sky stops and mountains',
            sunMoon: 'full visible left/right viewport edges', edgeViewports: 6, broadcastOnlyTooltip: 'passed', otherThemes: 'preserved', physicalDevice: 'not tested' };
    } finally {
        await context.close();
    }
}
