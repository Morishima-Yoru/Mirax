// Run with Playwright browser_run_code_unsafe; never submit or change review votes.
async (page) => {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const context = await page.context().browser().newContext({
        viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true,
        deviceScaleFactor: 2, reducedMotion: 'reduce'
    });
    const probe = await context.newPage();
    const errors = [];
    probe.on('pageerror', error => errors.push(error.message));
    let layouts = 0;
    let gates = 0;
    const reset = () => probe.evaluate(() => {
        state.variant = 'D'; state.home = 'big'; state.broadcast = false; state.phase = 'off';
        state.motion.paused = false; state.motion.hidden = false;
        state.media = { kind: 'none', name: null, status: 'none', error: null, playbackBlocked: false };
        render();
        return new Promise(resolve => requestAnimationFrame(() => resolve(window.MiraxDStreams.snapshot())));
    });
    const inspectTracks = (mode, baseline = null) => probe.evaluate(async ({ mode, baseline }) => {
        const root = document.querySelector('#e-scene');
        const tracks = [...root.querySelectorAll('.e-stream-track')].filter(track => {
            const message = track.closest('.e-status-message');
            const stageNode = document.querySelector('#stage');
            const activePhase = stageNode.dataset.broadcast === 'true' ? stageNode.dataset.phase : 'off';
            return !message || message.dataset.status === activePhase;
        });
        const expected = mode === 'snapshot' && !document.querySelector('#stage').classList.contains('e-motion-active') ? 'paused' : 'running';
        // Wall time alone cannot prove that compositor timelines have settled or advanced.
        await new Promise((resolve, reject) => {
            let settledFrames = 0;
            let frame = 0;
            let last = [];
            const timeout = setTimeout(() => {
                cancelAnimationFrame(frame);
                reject(Error('Timed out after 6500ms waiting for ' + mode + ' native readiness/progress: ' + JSON.stringify(last)));
            }, 6500);
            const observe = () => {
                try {
                    const animations = tracks.map(track => track.getAnimations().find(item => item.animationName === 'e-command-flow'));
                    const engine = window.MiraxDStreams.snapshot();
                    last = animations.map(animation => ({ state: animation?.playState ?? null,
                        pending: animation?.pending ?? null, time: animation?.currentTime ?? null }));
                    const ready = tracks.length === 5 && engine.active === (expected === 'running') && tracks.every((track, index) =>
                        getComputedStyle(track).animationPlayState === expected && (animations[index] ?
                            last[index].state === expected && !last[index].pending && Number.isFinite(last[index].time) : expected === 'paused'));
                    settledFrames = ready ? settledFrames + 1 : 0;
                    const advanced = !baseline || (ready && performance.now() - baseline.at >= 250 &&
                        last.every((animation, index) => animation.time - baseline.tracks[index].time >= 250) &&
                        engine.elapsedSeconds - baseline.engine.elapsedSeconds > .1);
                    if (settledFrames >= 2 && advanced) {
                        clearTimeout(timeout);
                        resolve();
                        return;
                    }
                    frame = requestAnimationFrame(observe);
                } catch (error) {
                    clearTimeout(timeout);
                    reject(error);
                }
            };
            frame = requestAnimationFrame(observe);
        });
        if (mode === 'snapshot') {
            return { tracks: tracks.map(node => {
                const style = getComputedStyle(node);
                const animation = node.getAnimations().find(item => item.animationName === 'e-command-flow');
                return { name: style.animationName, state: style.animationPlayState,
                    actualState: animation?.playState ?? null, time: animation?.currentTime ?? null, pending: animation?.pending ?? null,
                    transform: style.transform, opacity: style.opacity };
            }), engine: window.MiraxDStreams.snapshot() };
        }
        if (mode === 'flow') {
            return tracks.map(track => {
                const animation = track.getAnimations().find(item => item.animationName === 'e-command-flow');
                if (!animation) throw Error('Visible marquee has no e-command-flow animation');
                const duration = Number(animation.effect.getComputedTiming().duration);
                const playing = animation.playState;
                const originalTime = animation.currentTime;
                animation.pause();
                const samples = [0, .25, .5, .75].map(fraction => {
                    animation.currentTime = duration * fraction;
                    const rect = track.getBoundingClientRect();
                    return { x: rect.x, y: rect.y };
                });
                const segments = [...track.children];
                animation.currentTime = 0;
                const beginning = segments[1].getBoundingClientRect();
                animation.currentTime = duration - .001;
                const end = segments[0].getBoundingClientRect();
                const seam = Math.hypot(end.x - beginning.x, end.y - beginning.y);
                const equalSegments = segments.length === 2 && segments[0].innerHTML === segments[1].innerHTML &&
                    Math.abs(segments[0].getBoundingClientRect().width - segments[1].getBoundingClientRect().width) < .01;
                animation.currentTime = originalTime;
                animation.play();
                return { duration, playing, samples, seam, equalSegments,
                    status: track.closest('.e-status-message')?.dataset.status ?? null };
            });
        }
        return { at: performance.now(), reduced: matchMedia('(prefers-reduced-motion: reduce)').matches,
            active: document.querySelector('#stage').classList.contains('e-motion-active'),
            tracks: tracks.map(track => {
                const animation = track.getAnimations().find(item => item.animationName === 'e-command-flow');
                if (!animation) throw Error('Visible marquee has no e-command-flow animation');
                const rect = track.getBoundingClientRect();
                return { x: rect.x, y: rect.y, time: animation.currentTime, state: animation.playState, pending: animation.pending };
            }),
            engine: window.MiraxDStreams.snapshot() };
    }, { mode, baseline });
    const snapshot = () => inspectTracks('snapshot');
    const sampleLive = baseline => inspectTracks('live', baseline);
    try {
        await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=E&v=40');
        await probe.bringToFront();
        await probe.waitForFunction(() => dStudies.has('study-118-section-extrusion'));
        check(await probe.evaluate(() => state.variant === 'D' && variants.join(',') === 'A,C,D' && !concepts.E &&
            [...document.querySelectorAll('#theme-select option')].map(option => option.value).join(',') === 'A,C,D' &&
            state.motion.dMode === 'study-118-section-extrusion' && !cGyroscope.enabled),
            'Legacy E must resolve to D; only A/C/D remain, with original 118 C2 as D default');
        const mediaGates = await probe.evaluate(() => {
            const conditions = rules => [...rules].flatMap(rule =>
                [rule.conditionText || '', ...(rule.cssRules ? conditions(rule.cssRules) : [])]);
            return [...document.styleSheets].flatMap(sheet => conditions(sheet.cssRules))
                .filter(condition => condition.includes('prefers-reduced-motion'));
        });
        check(mediaGates.length === 0, 'System motion preference must not gate any stylesheet: ' + mediaGates);
        const originalX = await probe.evaluate(() => {
            const symbol = document.querySelector('#mirax-mark');
            const core = document.querySelector('#hardline-scene .x-core');
            const echo = document.querySelector('#hardline-scene .x-core-line');
            const expected = { href: '#mirax-mark', x: '510', y: '-335', width: '1370', height: '1370',
                stroke: '#d8d8d0', 'stroke-width': '.16' };
            const echoExpected = { ...expected, x: '526', y: '-323', fill: 'none', stroke: '#ffffff3d', 'stroke-width': '.08' };
            const paths = [
                { id: 'mirax-arm-nw', pathLength: '1000', d: 'M26.1 23H31.9L51.8 42.9 43.9 50.8 26.1 32.8Z' },
                { id: 'mirax-arm-ne', pathLength: '1000', d: 'M76 23.1H81.9V32.8L64.2 50.5 56.3 43Z' },
                { id: 'mirax-arm-sw', pathLength: '1000', d: 'M26.1 73.6 44 55.7 51.7 63.5 32 83.3H26.1Z' },
                { id: 'mirax-arm-se', pathLength: '1000', d: 'M64 55.7 82 73.6V83.4H76.1L56.2 63.4Z' },
                { d: 'M54 47.5 60.2 53.4 54 59.4 47.8 53.4Z' }
            ];
            const paint = getComputedStyle(core);
            const main = core.ownerSVGElement;
            const canvas = document.querySelector('#d-study-canvas');
            const overlay = document.querySelector('#d-motion-overlay');
            return { markup: symbol.outerHTML + core.outerHTML + echo.outerHTML,
                unchanged: [[core, expected], [echo, echoExpected]].every(([node, attributes]) =>
                    Object.entries(attributes).every(([key, value]) => node.getAttribute(key) === value)) &&
                    symbol.getAttribute('viewBox') === '0 0 108 108' && symbol.children.length === paths.length &&
                    paths.every((attributes, index) => {
                        const path = symbol.children[index];
                        return path.tagName.toLowerCase() === 'path' && path.getAttributeNames().length === Object.keys(attributes).length &&
                            Object.entries(attributes).every(([key, value]) => path.getAttribute(key) === value);
                    }),
                opaque: paint.fill.replace(/\s+/g, '') === 'rgb(9,9,9)' && Number(paint.fillOpacity) === 1 &&
                    [core, core.parentElement, main].every(node => Number(getComputedStyle(node).opacity) === 1),
                layers: { canvas: Number(getComputedStyle(canvas).zIndex), main: Number(getComputedStyle(main).zIndex),
                    overlay: Number(getComputedStyle(overlay).zIndex), mainPosition: getComputedStyle(main).position,
                    siblings: canvas.parentElement === main.parentElement && overlay.parentElement === main.parentElement },
                header: [...main.querySelectorAll(':scope > path')].some(path => path.getAttribute('d') === 'M0 68H1600' && path.getAttribute('stroke') === '#fff' &&
                    path.getAttribute('stroke-width') === '2' && path.getAttribute('vector-effect') === 'non-scaling-stroke') && main.lastElementChild === core.parentElement,
                cornerAbsent: !document.querySelector('#hardline-scene [d="M1390 0H1444V65H1390Z"]') };
        });
        check(originalX.unchanged, 'Original D main X, echo and all five symbol paths must keep exact geometry and outline paint');
        check(originalX.opaque, 'All four front leaves and center diamond must paint opaque #090909 with full group opacity');
        check(originalX.layers.canvas === 0 && originalX.layers.main === 4 && originalX.layers.overlay === 5 &&
            originalX.layers.mainPosition === 'relative' && originalX.layers.siblings,
            'Live study canvas must stay behind the main SVG, with its overlay above: ' + JSON.stringify(originalX.layers));
        check(originalX.header, 'The brighter 2 CSS-pixel header keeps its original path, before the whole original X group');
        check(originalX.cornerAbsent, 'Remove the extraneous decorative corner path entirely, never the original X contours');
        for (const [width, height, phase] of [[390, 844, 'off'], [320, 568, 'waiting'], [568, 320, 'connecting'], [667, 375, 'failed'], [1440, 1000, 'off'], [2560, 720, 'waiting']]) {
            await probe.setViewportSize({ width, height });
            await probe.evaluate(phase => { state.broadcast = phase !== 'off'; state.phase = phase; render(); }, phase);
            const value = await probe.evaluate(() => {
                const root = document.querySelector('#e-scene');
                const core = document.querySelector('#hardline-scene .x-core');
                const paint = getComputedStyle(core);
                const stageNode = document.querySelector('#stage');
                const stage = stageNode.getBoundingClientRect();
                const upper = root.querySelector('.e-channel--upper');
                const lower = root.querySelector('.e-channel--lower');
                const channels = [...root.querySelectorAll('.e-channel')];
                const rows = [...upper.querySelectorAll('.e-stream-row')];
                const lowerRows = [...lower.querySelectorAll('.e-stream-row')];
                const status = rows[2];
                const statusStyle = getComputedStyle(status);
                const rgb = statusStyle.color.match(/[\d.]+/g).map(Number);
                const phase = stageNode.dataset.broadcast === 'false' ? 'off' : stageNode.dataset.phase;
                const messages = [...status.querySelectorAll('.e-status-message')].filter(node => node.dataset.status === phase);
                const decorRows = [...root.querySelectorAll('.e-stream-row:not(.e-stream-row--status)')];
                const copyMarkers = ['display-only register sketch', 'illustrative bytes', 'no input sampled; glyphs only', 'fiction/no signal data'];
                const band = parseFloat(getComputedStyle(upper).height);
                const receiver = document.querySelector('.device').getBoundingClientRect();
                const receiverInset = Math.min(48, Math.max(24, Math.min(innerWidth, innerHeight) * .03));
                const section = getComputedStyle(document.querySelector('.device'));
                const brand = document.querySelector('.brand'), brandStyle = getComputedStyle(brand), brandBox = brand.getBoundingClientRect();
                const mark = brand.querySelector('.brand-mark').getBoundingClientRect();
                const neutral = channels.every(node => {
                    const rgb = getComputedStyle(node).color.match(/[\d.]+/g).slice(0, 3).map(Number);
                    return Math.max(...rgb) - Math.min(...rgb) <= 2;
                });
                return { root: root.getBoundingClientRect().toJSON(), stage: stage.toJSON(),
                    x: core.getBoundingClientRect().toJSON(),
                    opaqueX: paint.fill.replace(/\s+/g, '') === 'rgb(9,9,9)' && Number(paint.fillOpacity) === 1 &&
                        [core, core.parentElement, core.ownerSVGElement].every(node => Number(getComputedStyle(node).opacity) === 1),
                    button: document.querySelector('#broadcast-toggle').getBoundingClientRect().toJSON(),
                    visible: getComputedStyle(root).display !== 'none', band, phase,
                    receiverSection: Math.abs(receiver.left - receiverInset) <= 5 && receiver.right <= innerWidth - 80 && parseFloat(section.borderLeftWidth) === 2 && parseFloat(section.paddingLeft) === 10,
                    brandSection: Math.abs(brandBox.left - receiverInset) <= 5 && Math.abs(mark.left - brandBox.left - 12) < .1 && Math.abs(mark.width - 30) < .01 && Math.abs(mark.height - 30) < .01 && parseFloat(brandStyle.borderLeftWidth) === 2 && parseFloat(brandStyle.paddingLeft) === 10 && parseFloat(brandStyle.paddingRight) === 12,
                    textLayer: Number(getComputedStyle(root).zIndex) === 3 && root.parentElement.matches('.generated-background') &&
                        Number(getComputedStyle(root.parentElement).zIndex) === -5,
                    channels: channels.length, upperRows: rows.length,
                    rowsFit: [[rows, [16, 16, 24]], [lowerRows, [16, 16]]].every(([lines, heights]) =>
                        lines.length === heights.length && lines.every((row, index) => row.offsetHeight === heights[index] && row.offsetTop >= 0 &&
                            row.offsetTop + row.offsetHeight <= row.parentElement.clientHeight &&
                            (!index || row.offsetTop - lines[index - 1].offsetTop - lines[index - 1].offsetHeight === 3) &&
                            row.querySelector('.e-copy').textContent.length > 0)),
                    lowerRows: lowerRows.length, lowerBand: parseFloat(getComputedStyle(lower).height),
                    status: status.matches('.e-stream-row--status') && root.querySelectorAll('.e-stream-row--status').length === 1 &&
                        lowerRows.every(row => !row.matches('.e-stream-row--status')) && messages.length === 1 && messages[0].dataset.status === phase &&
                        Math.abs(parseFloat(statusStyle.fontSize) - 14.6667) < .001 && Number(statusStyle.fontWeight) === 700 &&
                        Number(statusStyle.opacity) === 1 && (phase === 'failed' ? rgb.slice(0, 3).join(',') === '255,107,107' :
                            Math.min(...rgb.slice(0, 3)) >= 242 && Math.max(...rgb.slice(0, 3)) - Math.min(...rgb.slice(0, 3)) <= 2),
                    font: parseFloat(getComputedStyle(upper).fontSize), neutral,
                    decorative: root.getAttribute('aria-hidden') === 'true' &&
                        getComputedStyle(root).pointerEvents === 'none' && decorRows.length === 4 &&
                        decorRows.every((row, index) => row.querySelectorAll('.e-copy').length === 2 &&
                            [...row.querySelectorAll('.e-copy')].every(node => node.textContent.replace(/\s*\/\s*/g, '/').includes(copyMarkers[index]))),
                    duplicateX: root.querySelectorAll('svg, use, canvas, .e-mark-stack, .e-x-primary').length,
                    controls: root.querySelectorAll('a, button, input, [tabindex]').length,
                    overflow: document.documentElement.scrollWidth > innerWidth + 1 };
            });
            check(value.visible && !value.overflow && Math.abs(value.root.width - width) < 1 &&
                Math.abs(value.root.height - height) < 1 && Math.abs(value.root.x - value.stage.x) < 1 &&
                Math.abs(value.root.y - value.stage.y) < 1,
                'Decoration must remain clipped to the D viewport: ' + JSON.stringify(value));
            check(value.channels === 2 && value.upperRows === 3 && value.lowerRows === 2 && value.rowsFit &&
                value.band === 80 && value.lowerBand === 52 && value.font === 11 && value.neutral && value.status &&
                value.decorative && value.duplicateX === 0 && value.controls === 0,
                'Two bounded channels, upper-third real status, four decorative rows, no duplicate status/X or controls: ' + JSON.stringify(value));
            check(value.receiverSection, 'D receiver must keep its safe-left anchor and left section rule, without entering the review-pencil area');
            check(value.brandSection, 'D MIRAX keeps the same safe anchor and logo size, with its content 12px inside the matching left rule');
            check(value.textLayer, 'Marquee text must remain at z3 inside the z-5 generated background, below the whole solid X at z4/5 and below UI');
            check(value.opaqueX, 'D front faces must remain opaque #090909 at ' + width + 'x' + height);
            check(value.x.width > 100 && value.x.height > 100 && value.x.right > 0 && value.x.left < width &&
                value.x.bottom > 0 && value.x.top < height,
                'Retained D X must intersect every responsive viewport: ' + JSON.stringify(value));
            check(Math.abs(value.button.left + value.button.width / 2 - width / 2) < 1 &&
                value.button.bottom <= height + 1 && value.button.top >= 0 &&
                value.button.width >= 44 && value.button.height >= 44,
                'Former E broadcast frame must remain a bounded bottom-center D action');
            layouts += 1;
        }
        await probe.setViewportSize({ width: 390, height: 844 });
        await reset();
        const live = [];
        for (const preference of ['reduce', 'no-preference']) {
            await probe.emulateMedia({ reducedMotion: preference });
            const first = await sampleLive();
            const second = await sampleLive(first);
            const rows = second.tracks.map((track, index) => ({
                dx: track.x - first.tracks[index].x, dy: track.y - first.tracks[index].y,
                elapsed: track.time - first.tracks[index].time,
                speed: (track.x - first.tracks[index].x) / (track.time - first.tracks[index].time) }));
            check(first.reduced === (preference === 'reduce') && first.active && second.active &&
                second.at - first.at >= 250 && rows.length === 5 && rows.every(row =>
                    row.dx > .25 && row.dy < -.25 && row.elapsed >= 250) &&
                first.tracks.concat(second.tracks).every(track => track.state === 'running' && !track.pending) &&
                first.engine.active && second.engine.active && second.engine.streams.length === 4 &&
                second.engine.elapsedSeconds - first.engine.elapsedSeconds > .1,
                'Five CSS marquees and the native edge engine clock must advance with ' + preference + ': ' + JSON.stringify({ rows, first: first.engine, second: second.engine }));
            live.push({ preference, elapsed: second.at - first.at, engineSeconds: second.engine.elapsedSeconds - first.engine.elapsedSeconds, rows });
        }
        check(live[0].rows.every((row, index) => Math.abs(row.speed / live[1].rows[index].speed - 1) < .03),
            'Reduced and no-preference must retain the same live flow speed: ' + JSON.stringify(live));
        await probe.emulateMedia({ reducedMotion: 'reduce' });
        const flow = await inspectTracks('flow');
        check(flow.length === 5 && flow.filter(item => !item.status && item.duration === 240000).length === 2 &&
            flow.filter(item => !item.status && item.duration === 360000).length === 2 &&
            flow.filter(item => item.status === 'off' && item.duration === 148000).length === 1 && flow.every(item =>
                item.playing === 'running' && item.seam < .02 && item.equalSegments &&
                item.samples.slice(1).every((sample, index) =>
                    sample.x > item.samples[index].x && sample.y < item.samples[index].y)),
            'Two upper 240s tracks, two lower 360s tracks and the active 148s off status must retain real motion and seamless duplicates: ' + JSON.stringify(flow));
        const edgeNames = ['upper-top', 'upper-bottom', 'lower-top', 'lower-bottom'];
        // Observe the host's RAF only; never advance or seek the edge engine from this probe.
        const nativeEdges = await probe.evaluate(names => new Promise(resolve => {
            const engine = window.MiraxDStreams;
            if (!engine) { resolve({ missing: true }); return; }
            const root = document.querySelector('#e-scene');
            const before = engine.snapshot();
            const bindings = engine.streams.map(stream => ({ name: stream.name,
                bound: names.includes(stream.name) && stream.edge === (stream.name.endsWith('top') ? 'top' : 'bottom') &&
                    stream.element === root.querySelector(stream.name.startsWith('upper') ? '.e-channel--upper' : '.e-channel--lower'),
                animation: getComputedStyle(stream.element, stream.edge === 'top' ? '::before' : '::after').animationName }));
            if (bindings.length !== 4 || !names.every(name => bindings.some(binding => binding.name === name && binding.bound && binding.animation === 'none'))) {
                resolve({ invalidBindings: true, bindings }); return;
            }
            const samples = Object.fromEntries(names.map(name => [name, { first: null, last: null, motion: null, count: 0 }]));
            const mismatches = new Set();
            const started = performance.now();
            const observe = () => {
                const after = engine.snapshot();
                for (const stream of engine.streams) {
                    if (stream.phase !== 'shot') continue;
                    const style = getComputedStyle(stream.element);
                    const pseudo = getComputedStyle(stream.element, stream.edge === 'top' ? '::before' : '::after');
                    const sample = { shotIndex: stream.shotIndex, position: stream.position, alpha: stream.alpha,
                        paint: Number(style.getPropertyValue(`--e-${stream.edge}-position`)),
                        alphaPaint: Number(style.getPropertyValue(`--e-${stream.edge}-alpha`)),
                        mappedPosition: Number(pseudo.getPropertyValue('--e-shot-position')),
                        mappedAlpha: Number(pseudo.getPropertyValue('--e-shot-alpha')),
                        opacity: Number(pseudo.opacity), transform: pseudo.transform };
                    if (![[sample.paint, sample.position, .000001], [sample.alphaPaint, sample.alpha, .000001],
                        [sample.mappedPosition, sample.position, .000001], [sample.mappedAlpha, sample.alpha, .000001],
                        [sample.opacity, sample.alpha, .015]].every(([actual, expected, tolerance]) => Number.isFinite(actual) && Math.abs(actual - expected) < tolerance)) {
                        mismatches.add(stream.name);
                    }
                    const record = samples[stream.name];
                    record.count += 1;
                    if (!record.first || record.first.shotIndex !== sample.shotIndex) record.first = sample;
                    if (!record.motion && sample.position > record.first.position + .00001 &&
                        Math.abs(sample.alpha - record.first.alpha) > .01 && sample.transform !== record.first.transform) {
                        record.motion = [record.first, sample];
                    }
                    record.last = sample;
                }
                if ((names.every(name => samples[name].motion) && after.elapsedSeconds - before.elapsedSeconds > .1) || performance.now() - started >= 6500) {
                    resolve({ before, after, bindings, samples, mismatches: [...mismatches], wallMs: performance.now() - started });
                } else requestAnimationFrame(observe);
            };
            requestAnimationFrame(observe);
        }), edgeNames);
        check(!nativeEdges.missing && !nativeEdges.invalidBindings, 'Four native engine edges must bind to the correct channel/pseudo without CSS edge animations: ' + JSON.stringify(nativeEdges));
        check(nativeEdges.before.active && nativeEdges.after.active && nativeEdges.after.elapsedSeconds - nativeEdges.before.elapsedSeconds > .1 &&
            nativeEdges.mismatches.length === 0, 'Native RAF must advance the engine and its CSS position/alpha bindings: ' + JSON.stringify(nativeEdges));
        for (const name of edgeNames) {
            const pair = nativeEdges.samples[name].motion;
            check(pair?.length === 2 && pair[0].shotIndex === pair[1].shotIndex && pair[0].shotIndex > 0 &&
                pair[1].position > pair[0].position + .00001 && Math.abs(pair[1].alpha - pair[0].alpha) > .01 &&
                pair[1].transform !== pair[0].transform && nativeEdges.after.history.some(shot => shot.name === name && shot.shotIndex === pair[0].shotIndex),
                name + ' must show genuine position/alpha and rendered-transform movement within one native shot: ' + JSON.stringify(nativeEdges.samples[name]));
        }
        check(await probe.evaluate(markup => document.querySelector('#mirax-mark').outerHTML +
            [...document.querySelectorAll('#hardline-scene .x-core, #hardline-scene .x-core-line')]
                .map(node => node.outerHTML).join('') === markup, originalX.markup), 'Cathode boundaries must never alter the original X or symbol paths');
        // Discard deliberate WAAPI play/pause overrides before testing untouched CSS lifecycle gates.
        await probe.reload();
        await probe.bringToFront();
        await probe.waitForFunction(() => Boolean(window.MiraxDStreams));
        for (const gate of ['pause', 'hidden', 'simple', 'projecting', 'media', 'nonD']) {
            await reset();
            const gated = await probe.evaluate(gate => {
                if (gate === 'pause') state.motion.paused = true;
                if (gate === 'hidden') state.motion.hidden = true;
                if (gate === 'simple') state.home = 'simple';
                if (gate === 'projecting') { state.broadcast = true; state.phase = 'projecting'; }
                if (gate === 'media') state.media = { ...state.media, kind: 'image', status: 'ready' };
                if (gate === 'nonD') state.variant = 'C';
                render();
                return window.MiraxDStreams.snapshot();
            }, gate);
            check(!gated.active, gate + ' must stop the engine immediately, before waiting for CSS readiness');
            const first = await snapshot();
            await probe.waitForTimeout(300);
            const second = await snapshot();
            check(first.tracks.length === 5 && first.tracks.every(item => item.state === 'paused' && (item.actualState === null || item.actualState === 'paused')) &&
                !first.engine.active && !second.engine.active && JSON.stringify(gated) === JSON.stringify(first.engine) &&
                JSON.stringify(first) === JSON.stringify(second) &&
                !await probe.locator('#stage').evaluate(node => node.classList.contains('e-motion-active')),
                gate + ' must freeze all five CSS rows and the complete native edge clock/positions/alphas/history: ' + JSON.stringify({ first, second }));
            const resume = await reset();
            check(resume.active && resume.elapsedSeconds >= second.engine.elapsedSeconds &&
                resume.elapsedSeconds - second.engine.elapsedSeconds <= .055 && JSON.stringify(resume.history) === JSON.stringify(second.engine.history),
                gate + ' must resume on a native frame without hidden-time catchup or queued shots: ' + JSON.stringify({ frozen: second.engine, resume }));
            gates += 1;
        }
        await reset();
        const resumed = await sampleLive();
        const advancing = await sampleLive(resumed);
        check(resumed.tracks.length === 5 && resumed.tracks.every(item => item.state === 'running' && !item.pending) && resumed.engine.active &&
            advancing.engine.elapsedSeconds - resumed.engine.elapsedSeconds > .1 &&
            advancing.tracks.every((item, index) => item.state === 'running' && !item.pending && item.time - resumed.tracks[index].time >= 250),
            'Returning to D must genuinely resume five CSS animations and the native engine even with reduce');
        await probe.locator('#broadcast-toggle').tap();
        check(await probe.locator('#broadcast-toggle').getAttribute('aria-checked') === 'true', 'D must use the shared broadcast action');
        await probe.locator('#settings-handle').tap();
        check(await probe.locator('#theme-select').inputValue() === 'D' &&
            await probe.locator('#theme-select option').count() === 3, 'Drawer must expose D, never a fourth E theme');
        await probe.evaluate(() => closeSettings());
        check(errors.length === 0, 'Unexpected hybrid D errors: ' + errors.join('; '));
        return { layouts, lifecycleGates: gates, tracks: flow.length, live, nativeEdges, themes: ['A', 'C', 'D'],
            legacyE: 'D', sharedX: 'geometry unchanged; opaque #090909 front; canvas/text/main/overlay z0/3/4/5', study: '118 C2', physicalDevice: 'not tested' };
    } finally {
        await context.close();
    }
}
