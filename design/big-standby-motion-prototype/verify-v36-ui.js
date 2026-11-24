// Run with Playwright browser_run_code_unsafe; the supplied page is never changed.
async (sourcePage) => {
    const browser = sourcePage.context().browser();
    if (!browser) throw Error('V41 requires an isolated browser context');
    const base = 'http://127.0.0.1:8765';
    const started = Date.now();
    const sizes = [[320, 568], [390, 844], [480, 375], [568, 320], [667, 375], [896, 592], [1440, 1000], [2560, 720]];
    const protectedHash = '7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036';
    const failures = [];
    const errors = [];
    const blockedWrites = [];
    const results = [];
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const run = async (name, action) => {
        try { results.push({ name, evidence: await action() }); }
        catch (error) { failures.push({ name, message: String(error.message || error) }); }
    };
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1,
        reducedMotion: 'no-preference', serviceWorkers: 'block' });
    let probe;
    let beforeHash;
    let afterHash;
    const reviewHash = async () => {
        const response = await context.request.get(base + '/big-standby-motion-prototype/review-votes.json');
        check(response.ok(), 'Protected review fixture must be readable');
        return probe.evaluate(async text => [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)))].map(byte => byte.toString(16).padStart(2, '0')).join(''), await response.text());
    };
    try {
        context.setDefaultTimeout(1800);
        context.setDefaultNavigationTimeout(6000);
        await context.route('**/*', async route => {
            const request = route.request();
            if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
                blockedWrites.push({ method: request.method(), url: request.url() });
                await route.fulfill({ status: 405, contentType: 'application/json', body: '{"error":"read-only verification"}' });
            } else if (new URL(request.url()).pathname === '/api/d-reviews') {
                await route.fulfill({ status: 200, contentType: 'application/json', body: '{"schemaVersion":1,"votes":{},"notes":{}}' });
            } else await route.continue();
        });
        probe = await context.newPage();
        probe.on('pageerror', error => errors.push(error.message));
        probe.on('console', message => { if (['error', 'assert'].includes(message.type())) errors.push(message.text()); });
        await probe.goto(base + '/big-standby-prototype.html?variant=D&v=41');
        await probe.bringToFront();
        await probe.evaluate(() => {
            cGyroscope.disable();
            // Pure geometry begins. Node self-checks execute this same block.
            function polygonArea(points) {
                return Math.abs(points.reduce((sum, point, index) => {
                    const next = points[(index + 1) % points.length];
                    return sum + point.x * next.y - point.y * next.x;
                }, 0)) / 2;
            }
            function clipPolygon(subject, boundary) {
                let output = subject;
                const winding = Math.sign(boundary.reduce((sum, point, index) => {
                    const next = boundary[(index + 1) % boundary.length];
                    return sum + point.x * next.y - point.y * next.x;
                }, 0)) || 1;
                for (let index = 0; index < boundary.length && output.length; index++) {
                    const a = boundary[index];
                    const b = boundary[(index + 1) % boundary.length];
                    const distance = point => winding * ((b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x));
                    const input = output;
                    output = [];
                    for (let cursor = 0; cursor < input.length; cursor++) {
                        const p = input[cursor];
                        const q = input[(cursor + 1) % input.length];
                        const dp = distance(p);
                        const dq = distance(q);
                        if (dp >= -1e-8) output.push(p);
                        if ((dp >= 0) !== (dq >= 0)) {
                            const t = dp / (dp - dq);
                            output.push({ x: p.x + t * (q.x - p.x), y: p.y + t * (q.y - p.y) });
                        }
                    }
                }
                return output;
            }
            function marqueeAxis(width, height) {
                const portrait = width <= 600 && height >= width;
                const shortLandscape = width > height && height <= 500;
                const size = portrait ? Math.min(width * .84, height * .48) : shortLandscape ? Math.min(width * .44, height * .74) : Math.min(width * .54, height * .78);
                const originX = portrait ? width * .59 : shortLandscape ? width * .75 : width * .72;
                const originY = portrait ? height * .38 : shortLandscape ? height * .47 : height * .48;
                const offset = size * .59 - 16;
                const k = 1.414214;
                const start = Math.max(-originX * k - offset, (originY - height) * k + offset);
                const end = Math.min((width - originX) * k - offset, originY * k + offset);
                return { start, end, width: Math.max(0, end - start), oldStart: -Math.min(1600, Math.max(width, height) * 1.6) / 2 };
            }
            function visibleAxisLength(center, width, viewportWidth, viewportHeight) {
                let start = -width / 2;
                let end = width / 2;
                for (const [origin, direction, maximum] of [[center.x, Math.SQRT1_2, viewportWidth], [center.y, -Math.SQRT1_2, viewportHeight]]) {
                    const a = -origin / direction;
                    const b = (maximum - origin) / direction;
                    start = Math.max(start, Math.min(a, b));
                    end = Math.min(end, Math.max(a, b));
                }
                return Math.max(0, end - start);
            }
            function v35BandLayout(width, height) {
                const portrait = width <= 600 && height >= width;
                const shortLandscape = width > height && height <= 500;
                const size = portrait ? Math.min(width * .84, height * .48) : shortLandscape ? Math.min(width * .44, height * .74) : Math.min(width * .54, height * .78);
                const origin = portrait ? { x: width * .59, y: height * .38 } : shortLandscape ? { x: width * .75, y: height * .47 } : { x: width * .72, y: height * .48 };
                const offset = size * .59 * Math.SQRT1_2;
                return { lower: { x: origin.x + offset, y: origin.y + offset }, upper: { x: origin.x - offset, y: origin.y - offset } };
            }
            function completeKeyIds(glyphs) {
                const complete = glyphs.filter(glyph => glyph.keyId >= 0 && glyph.visibleFraction >= .995);
                return [...new Set(complete.map(glyph => glyph.keyId))].filter(keyId =>
                    'Win+K'.split('').every(character => complete.some(glyph => glyph.keyId === keyId && glyph.character === character)));
            }
            function enteringKeyIds(glyphs) {
                const keys = glyphs.filter(glyph => glyph.keyId >= 0 && glyph.visibleFraction >= .1);
                return [...new Set(keys.map(glyph => glyph.keyId))].filter(keyId => {
                    const group = keys.filter(glyph => glyph.keyId === keyId);
                    return group.some(glyph => glyph.character === 'K') && !group.some(glyph => glyph.character === 'W');
                });
            }
            // Pure geometry ends.
            // Face sampling geometry begins. Node self-checks execute this exact helper.
            function insetContains(point, polygon, inset) {
                const winding = Math.sign(polygon.reduce((sum, a, index) => {
                    const b = polygon[(index + 1) % polygon.length]; return sum + a.x * b.y - a.y * b.x;
                }, 0)) || 1;
                return polygon.every((a, index) => {
                    const b = polygon[(index + 1) % polygon.length];
                    return winding * ((b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)) >= inset * Math.hypot(b.x - a.x, b.y - a.y);
                });
            }
            // Face sampling geometry ends.
            window.__v36InsetContains = insetContains;
            // Glyph paint mask begins. Node self-checks execute this exact PNG counter.
            function glyphPaintPixels(before, after, glyphs, face = null) {
                const inside = (x, y, points) => points.every((point, index) => {
                    const next = points[(index + 1) % points.length];
                    return (next.x - point.x) * (y - point.y) - (next.y - point.y) * (x - point.x) >= -.001;
                });
                return glyphs.map(glyph => {
                    const inkCutoff = glyph.inkCutoff ?? 200;
                    let changed = 0;
                    const left = Math.max(0, Math.floor(Math.min(...glyph.quad.map(point => point.x))));
                    const right = Math.min(before.width, Math.ceil(Math.max(...glyph.quad.map(point => point.x))));
                    const top = Math.max(0, Math.floor(Math.min(...glyph.quad.map(point => point.y))));
                    const bottom = Math.min(before.height, Math.ceil(Math.max(...glyph.quad.map(point => point.y))));
                    for (let y = top; y < bottom; y++) for (let x = left; x < right; x++) {
                        if (!inside(x + .5, y + .5, glyph.quad) || face && !insetContains({ x: x + .5, y: y + .5 }, face, 4)) continue;
                        const index = (y * before.width + x) * 4;
                        if (Math.min(before.pixels[index], before.pixels[index + 1], before.pixels[index + 2]) >= inkCutoff && before.pixels[index] - after.pixels[index] >= 16) changed++;
                    }
                    return { character: glyph.character, changedPixels: changed };
                });
            }
            // Glyph paint mask ends.
            // Rule profile begins. Node self-checks execute this exact PNG sampler.
            function lineInkProfile(image, point, normal = { x: 0, y: 1 }) {
                const values = [];
                for (let offset = -4; offset <= 4; offset++) {
                    const x = Math.floor(point.x + normal.x * offset), y = Math.floor(point.y + normal.y * offset);
                    if (x < 0 || y < 0 || x >= image.width || y >= image.height) continue;
                    const index = (y * image.width + x) * 4;
                    values.push(Math.min(...image.pixels.slice(index, index + 3)));
                }
                return { peak: Math.max(0, ...values), energy: values.reduce((sum, value) => sum + Math.max(0, value - 24) / 231, 0) };
            }
            // Rule profile ends.
            window.__v36LineInkProfile = lineInkProfile;
            // Receiver gap geometry begins. Node self-checks use the same Range calculation.
            function receiverRangeGap(label, name) { return name.top - label.bottom; }
            // Receiver gap geometry ends.
            window.__v39ReceiverRangeGap = receiverRangeGap;
            window.__v36DecodePng = async data => {
                const image = new Image(); image.src = 'data:image/png;base64,' + data; await image.decode();
                if (image.width !== innerWidth || image.height !== innerHeight) throw Error('PNG probes require matching CSS-pixel viewport rasters');
                const canvas = document.createElement('canvas'); canvas.width = image.width; canvas.height = image.height;
                const context = canvas.getContext('2d'); context.drawImage(image, 0, 0);
                return { width: image.width, height: image.height, pixels: context.getImageData(0, 0, image.width, image.height).data };
            };
            window.__v36GlyphPixels = async ({ visible, hidden, glyphs, face = null }) => {
                const before = await window.__v36DecodePng(visible), after = await window.__v36DecodePng(hidden);
                if (before.width !== after.width || before.height !== after.height) throw Error('Glyph PNG pairs must use matching viewport rasters');
                return glyphPaintPixels(before, after, glyphs, face);
            };
            const rectangle = (left, top, right, bottom) => [{ x: left, y: top }, { x: right, y: top }, { x: right, y: bottom }, { x: left, y: bottom }];
            const basis = node => {
                let matrix = new DOMMatrix();
                for (let ancestor = node; ancestor; ancestor = ancestor.parentElement) {
                    const transform = getComputedStyle(ancestor).transform;
                    if (transform !== 'none') matrix = new DOMMatrix(transform).multiply(matrix);
                }
                return new DOMMatrix([matrix.a, matrix.b, matrix.c, matrix.d, 0, 0]);
            };
            const quad = (bounds, width, height, matrix) => {
                const points = rectangle(0, 0, width, height).map(point => matrix.transformPoint(point));
                const x = bounds.left - Math.min(...points.map(point => point.x));
                const y = bounds.top - Math.min(...points.map(point => point.y));
                return points.map(point => ({ x: point.x + x, y: point.y + y }));
            };
            const elementFrame = node => {
                const css = getComputedStyle(node);
                const matrix = basis(node);
                const points = quad(node.getBoundingClientRect(), parseFloat(css.width), parseFloat(css.height), matrix);
                const inverse = new DOMMatrix([matrix.a, matrix.b, matrix.c, matrix.d, points[0].x, points[0].y]).inverse();
                return { points, inverse, width: parseFloat(css.width), height: parseFloat(css.height) };
            };
            window.__v36ChannelQuads = () => [...document.querySelectorAll('#e-scene .e-channel')].map(node => elementFrame(node).points);
            window.__v36ElementFrame = elementFrame;
            const shown = (node, stop = null) => {
                for (let ancestor = node; ancestor && ancestor !== stop; ancestor = ancestor.parentElement) {
                    const css = getComputedStyle(ancestor);
                    if (css.display === 'none' || css.visibility !== 'visible' || Number(css.opacity) <= .01) return false;
                }
                return Boolean(node.getClientRects().length);
            };
            const rawText = root => {
                const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                const nodes = [];
                while (walker.nextNode()) if (walker.currentNode.textContent.trim()) nodes.push(walker.currentNode);
                return nodes;
            };
            const textNodes = root => {
                const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                const nodes = [];
                while (walker.nextNode()) if (walker.currentNode.textContent.trim() && shown(walker.currentNode.parentElement)) nodes.push(walker.currentNode);
                return nodes;
            };
            const luminance = channels => channels.slice(0, 3).map(value => value / 255).map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
            const textStyle = node => {
                const css = getComputedStyle(node);
                const rgb = css.color.match(/[\d.]+/g)?.map(Number) ?? [];
                let opacity = rgb[3] ?? 1;
                let background = [0, 0, 0];
                for (let ancestor = node; ancestor; ancestor = ancestor.parentElement) {
                    const style = getComputedStyle(ancestor);
                    opacity *= Number(style.opacity);
                    const color = style.backgroundColor.match(/[\d.]+/g)?.map(Number) ?? [];
                    if ((color[3] ?? 1) >= .99 && color.length >= 3) { background = color; break; }
                }
                const effective = rgb.slice(0, 3).map((value, index) => value * opacity + background[index] * (1 - opacity));
                return { size: parseFloat(css.fontSize), weight: Number(css.fontWeight), lineHeight: parseFloat(css.lineHeight), rgb, opacity,
                    contrast: (luminance(effective) + .05) / (luminance(background) + .05) };
            };
            const canvas = document.createElement('canvas').getContext('2d');
            window.__v36Read = () => {
                const lower = document.querySelector('.e-channel--lower');
                const upper = document.querySelector('.e-channel--upper');
                const rows = [...lower.querySelectorAll('.e-stream-row')];
                const upperRows = [...upper.querySelectorAll('.e-stream-row')];
                const row = upperRows[2];
                const activeMessages = [...(row?.querySelectorAll('.e-status-message') ?? [])].filter(message => message.dataset.status === document.querySelector('#stage').dataset.phase);
                if (activeMessages.length !== 1) throw Error('CSS must select exactly one real-state status container');
                const track = activeMessages[0].querySelector('.e-stream-track');
                if (!track) throw Error('The third upper row must contain an active moving status .e-stream-track');
                const streamWindow = row.closest('.e-stream-window');
                const animation = track.getAnimations().find(item => item.effect.getTiming().iterations === Infinity);
                const selected = rawText(activeMessages[0]);
                const segments = [...track.querySelectorAll('.e-stream-segment')];
                const activeText = selected.map(node => node.textContent).join(' ').replace(/\s+/g, ' ').trim();
                const styles = selected.map(node => textStyle(node.parentElement));
                const glyphs = [];
                const keyNodes = [...activeMessages[0].querySelectorAll('.e-key-hint')];
                const verticalClips = [];
                if (shown(row)) {
                    const frame = elementFrame(streamWindow);
                    const rowFrame = elementFrame(row);
                    const viewport = rectangle(0, 0, innerWidth, innerHeight);
                    const matrix = basis(track);
                    const windowBounds = streamWindow.getBoundingClientRect();
                    for (const text of selected) {
                        const full = document.createRange();
                        full.selectNodeContents(text);
                        const bounds = full.getBoundingClientRect();
                        if (bounds.right < 0 || bounds.bottom < 0 || bounds.left > innerWidth || bounds.top > innerHeight || bounds.right < windowBounds.left || bounds.left > windowBounds.right) continue;
                        const css = getComputedStyle(text.parentElement);
                        const reference = document.createElement('span');
                        reference.style.cssText = 'all:initial;position:fixed;left:-10000px;top:0;white-space:pre';
                        for (const name of ['fontFamily', 'fontSize', 'fontStyle', 'fontWeight', 'fontStretch', 'fontKerning', 'fontVariant', 'lineHeight', 'letterSpacing']) reference.style[name] = css[name];
                        reference.textContent = text.textContent;
                        document.body.append(reference);
                        try {
                            const referenceRange = document.createRange();
                            canvas.font = `${css.fontWeight} ${css.fontSize} ${css.fontFamily}`;
                            for (let index = 0; index < text.length; index++) {
                                const character = text.textContent[index];
                                if (!character.trim()) continue;
                                full.setStart(text, index); full.setEnd(text, index + 1);
                                referenceRange.setStart(reference.firstChild, index); referenceRange.setEnd(reference.firstChild, index + 1);
                                const dimensions = referenceRange.getBoundingClientRect();
                                const points = quad(full.getBoundingClientRect(), dimensions.width, dimensions.height, matrix);
                                const metrics = canvas.measureText(character);
                                const ascent = metrics.fontBoundingBoxAscent ?? metrics.actualBoundingBoxAscent;
                                const descent = metrics.fontBoundingBoxDescent ?? metrics.actualBoundingBoxDescent;
                                const baseline = (dimensions.height - ascent - descent) / 2 + ascent;
                                const ink = rectangle(-metrics.actualBoundingBoxLeft, baseline - metrics.actualBoundingBoxAscent,
                                    metrics.actualBoundingBoxRight, baseline + metrics.actualBoundingBoxDescent).map(point => {
                                    const transformed = matrix.transformPoint(point);
                                    return { x: transformed.x + points[0].x, y: transformed.y + points[0].y };
                                });
                                const visible = clipPolygon(clipPolygon(ink, frame.points), viewport);
                                const area = polygonArea(visible);
                                if (area <= .02) continue;
                                for (const [label, target] of [['row', rowFrame], ['window', frame]]) {
                                    const local = ink.map(point => target.inverse.transformPoint(point));
                                    if (local.some(point => point.y < -.6 || point.y > target.height + .6)) verticalClips.push({ label, character, local: local.map(point => ({ x: point.x, y: point.y })), height: target.height });
                                }
                                glyphs.push({ character, x: ink.reduce((sum, point) => sum + point.x, 0) / 4,
                                    y: ink.reduce((sum, point) => sum + point.y, 0) / 4, visibleFraction: area / Math.max(.001, polygonArea(ink)),
                                    keyId: keyNodes.indexOf(text.parentElement.closest('.e-key-hint')), text: text.textContent, quad: ink });
                            }
                        } finally { reference.remove(); }
                    }
                }
                const center = node => { const bounds = node.getBoundingClientRect(); return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 }; };
                const baseline = v35BandLayout(innerWidth, innerHeight);
                const oldLower = baseline.lower;
                const oldUpper = baseline.upper;
                const lowerCenter = center(lower);
                const upperCenter = center(upper);
                const windowWidth = parseFloat(getComputedStyle(streamWindow).width);
                return { phase: state.phase, broadcast: state.broadcast, dMode: state.motion.dMode, dataset: { phase: stage.dataset.phase, broadcast: stage.dataset.broadcast },
                    selectedText: activeText, visibleText: glyphs.filter(glyph => glyph.visibleFraction >= .2).map(glyph => glyph.character).join(''), styles, glyphs,
                    completeKeyIds: completeKeyIds(glyphs), enteringKeyIds: enteringKeyIds(glyphs), verticalClips, shown: shown(row),
                    lowerRows: rows.length, upperRows: upperRows.length,
                    thirdIsStatus: row.matches('.e-stream-row--status'), activeStatus: activeMessages[0].dataset.status,
                    decorativeTracks: rows.every(row => !row.matches('.e-stream-row--status') && row.querySelector('.e-stream-track')),
                    animation: animation ? { currentTime: Number(animation.currentTime), duration: Number(animation.effect.getComputedTiming().duration), playState: animation.playState } : null,
                    transform: getComputedStyle(track).transform, rowWidth: parseFloat(getComputedStyle(row).width), windowWidth,
                    overflow: [getComputedStyle(streamWindow).overflowX, getComputedStyle(streamWindow).overflowY], documentWidth: document.documentElement.scrollWidth,
                    segments: segments.map(segment => ({ width: parseFloat(getComputedStyle(segment).width), text: rawText(segment).map(node => node.textContent).join(' ').replace(/\s+/g, ' ').trim() })),
                    lowerShift: { x: lowerCenter.x - oldLower.x, y: lowerCenter.y - oldLower.y },
                    upperShift: { x: upperCenter.x - oldUpper.x, y: upperCenter.y - oldUpper.y }, upperHeight: parseFloat(getComputedStyle(upper).height), lowerHeight: parseFloat(getComputedStyle(lower).height),
                    exposedLength: windowWidth,
                    oldExposedLength: visibleAxisLength(oldLower, Math.min(1600, Math.max(innerWidth, innerHeight) * 1.6), innerWidth, innerHeight) };
            };
            window.__v36Seek = fraction => {
                const message = [...document.querySelectorAll('.e-channel--upper .e-status-message')].find(node => node.dataset.status === (document.querySelector('#stage').dataset.broadcast === 'true' ? document.querySelector('#stage').dataset.phase : 'off'));
                const track = message?.querySelector('.e-stream-track');
                const animation = track?.getAnimations().find(item => item.effect.getTiming().iterations === Infinity);
                if (!animation) throw Error('Status marquee requires a native infinite animation');
                animation.currentTime = Number(animation.effect.getComputedTiming().duration) * fraction;
                return window.__v36Read();
            };
            window.__v36StopFrames = () => { if (animationFrame !== null) cancelAnimationFrame(animationFrame); animationFrame = null; };
            let clock = previousFrameTime ?? performance.now();
            window.__v36Advance = milliseconds => {
                window.__v36StopFrames();
                let remaining = milliseconds;
                while (remaining > 0) {
                    const step = Math.min(80, remaining);
                    previousFrameTime = clock;
                    clock += step;
                    animateStandby(clock);
                    window.__v36StopFrames();
                    remaining -= step;
                }
            };
            window.__v36Frames = milliseconds => new Promise(resolve => {
                const start = performance.now();
                const step = () => performance.now() - start >= milliseconds ? resolve() : requestAnimationFrame(step);
                requestAnimationFrame(step);
            });
        });
        beforeHash = await reviewHash();
        const read = () => probe.evaluate(() => window.__v36Read());
        const captureGlyphPaint = async (glyphs, face = null) => {
            let savedOpacity = null;
            try {
                const visible = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                savedOpacity = await probe.evaluate(() => {
                    const row = document.querySelector('.e-stream-row--status');
                    const previous = { value: row.style.getPropertyValue('opacity'), priority: row.style.getPropertyPriority('opacity') };
                    row.style.setProperty('opacity', '0', 'important'); return previous;
                });
                const hidden = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                return await probe.evaluate(input => window.__v36GlyphPixels(input), {
                    visible: visible.toString('base64'), hidden: hidden.toString('base64'), glyphs, face
                });
            } finally {
                if (savedOpacity !== null) await probe.evaluate(previous => {
                    const row = document.querySelector('.e-stream-row--status');
                    if (previous.value) row.style.setProperty('opacity', previous.value, previous.priority);
                    else row.style.removeProperty('opacity');
                }, savedOpacity);
            }
        };
        const checkStatus = (value, phase, visible = true) => {
            check(value.dMode === 'study-118-section-extrusion', 'D must retain original X118 as its default study');
            check(value.phase === phase && value.dataset.phase === phase && value.dataset.broadcast === String(value.broadcast), 'Real state and unconditional inspector datasets must agree: ' + phase);
            check(value.activeStatus === value.phase, 'The visible CSS-selected container must follow real stage phase');
            if (!visible) { check(!value.shown && !value.glyphs.length, phase + ': background status must not paint'); return; }
            const visibleLetters = value.glyphs.filter(glyph => /[A-Za-z\u3400-\u9fff]/.test(glyph.character)).length;
            check(value.shown && visibleLetters >= 6, phase + ': real active-status letters must paint immediately, not just exist in DOM: ' + value.visibleText);
            check(!value.verticalClips.length, phase + ': actual font ink is vertically clipped: ' + JSON.stringify(value.verticalClips.slice(0, 2)));
            check(value.styles.length && value.styles.every(style => style.size >= 14.65 && style.weight >= 700 && style.lineHeight >= style.size), phase + ': status must use 12px + 2pt, bold, adequate line height');
            const text = value.selectedText;
            const forbidden = /failed|failure|失敗|未成功|retry|重試|再試/i;
            if (phase === 'off') check(/broadcast|廣播/i.test(text) && /press|按|點/i.test(text) && /receiv|接收/i.test(text) && !/Win\s*\+\s*K/i.test(text), 'OFF must guide starting broadcast, never a Win + K ghost: ' + text.slice(0, 250));
            if (phase === 'arming') check(/starting|啟動/i.test(text) && /not ready|尚未/i.test(text) && !/Win\s*\+\s*K/i.test(text), 'ARMING must not look like a live Win + K target: ' + text.slice(0, 250));
            if (phase === 'wifi-paused') check(/Wi-Fi/i.test(text) && /retained|保留|paused|暫停|off/i.test(text) && !/Win\s*\+\s*K/i.test(text), 'WIFI_PAUSED must explain Wi-Fi pause while retaining intent');
            if (phase === 'frozen') check(/unavailable|尚未就緒|不可用/i.test(text) && !/offer_frozen|offer frozen|Win\s*\+\s*K/i.test(text), 'FROZEN must mean receiver unavailable, not offer-frozen or Win + K');
            if (phase === 'waiting') check(/Windows/i.test(text) && /Win\s*\+\s*K/.test(text) && /MIRAX\s*·\s*客廳/.test(text) && /choose|select|選/i.test(text), 'Waiting must explain Windows shortcut and receiver choice');
            if (phase === 'connecting') check(/connect|連/i.test(text) && /wait|稍候|等待|請稍/i.test(text), 'Connecting must give meaningful wait guidance');
            if (phase === 'failed') {
                check(/fail|失敗|未成功/i.test(text) && /retry|try again|重試|再試/i.test(text), 'Failure must explain retry, not just ERROR');
                check(value.styles.every(style => style.rgb[0] >= 200 && style.rgb[0] > style.rgb[1] * 1.3 && style.rgb[0] > style.rgb[2] * 1.3 && style.contrast >= 4.5), 'Failure guidance must paint readable red');
            } else {
                check(!forbidden.test(text), phase + ': stale failure copy');
                check(value.styles.every(style => Math.min(...style.rgb.slice(0, 3)) >= 241.5 && Math.max(...style.rgb.slice(0, 3)) - Math.min(...style.rgb.slice(0, 3)) <= 2 && style.opacity >= .94 && style.contrast >= 4.5), phase + ': normal guidance must return bright white, not stale red');
            }
        };
        await run('D matching brand/receiver sections: fixed anchors, 4px tighter Range gap and real left-rule PNG', async () => {
            const viewport = probe.viewportSize();
            const saved = await probe.evaluate(() => { const previous = { paused: state.motion.paused, broadcast: state.broadcast, phase: state.phase }; state.motion.paused = true; render(); return previous; });
            const evidence = [];
            try {
                await probe.waitForFunction(() => {
                    const label = document.querySelector('.device .micro-label');
                    return label.textContent === 'RECEIVER' && Number(getComputedStyle(label).opacity) >= .99;
                });
                for (const [width, height] of [...sizes, [400, 800], [401, 800]]) {
                    await probe.setViewportSize({ width, height });
                    for (const phase of ['off', 'waiting', 'connecting', 'failed']) {
                        await probe.evaluate(next => { if (state.broadcast !== (next !== 'off')) { toggleBroadcast(); confirmServiceReady(); } if (next !== 'off') setPhase(next); }, phase);
                        const value = await probe.evaluate(() => {
                            const box = node => node.getBoundingClientRect().toJSON();
                            const device = document.querySelector('.device');
                            const section = getComputedStyle(device);
                            const brand = document.querySelector('.brand'), brandStyle = getComputedStyle(brand);
                            const mark = brand.querySelector('.brand-mark');
                            const labels = [...device.querySelectorAll('.micro-label, .device-name')];
                            const type = labels.map(node => {
                                const css = getComputedStyle(node);
                                const range = document.createRange(); range.selectNodeContents(node);
                                return { text: node.textContent, size: parseFloat(css.fontSize), family: css.fontFamily, weight: css.fontWeight,
                                    rgb: css.color.match(/[\d.]+/g)?.slice(0, 3).map(Number) ?? [],
                                    lines: Math.ceil((node.getBoundingClientRect().height - .5) / parseFloat(css.lineHeight)), bounds: box(range), visible: css.visibility === 'visible' && Number(css.opacity) >= .99 };
                            });
                            const transform = getComputedStyle(document.querySelector('#standby-view')).transform;
                            const oled = transform === 'none' ? new DOMMatrix() : new DOMMatrix(transform);
                            return { device: box(device), brand: box(brand),
                                brandPaint: { border: parseFloat(brandStyle.borderLeftWidth), color: brandStyle.borderLeftColor, padding: ['Top', 'Right', 'Bottom', 'Left'].map(side => parseFloat(brandStyle['padding' + side])),
                                    mark: box(mark), nameSize: parseFloat(getComputedStyle(brand.querySelector('.brand-name')).fontSize), versionSize: parseFloat(getComputedStyle(brand.querySelector('small')).fontSize),
                                    versionGap: parseFloat(getComputedStyle(brand.querySelector('small')).marginTop), markNameGap: brand.querySelector('.brand-name').getBoundingClientRect().left - mark.getBoundingClientRect().right },
                                dock: box(document.querySelector('#broadcast-dock')),
                                primary: box(document.querySelector('#standby-view .center')), type, align: getComputedStyle(device).textAlign,
                                controls: [...document.querySelectorAll('button, [role="button"]')].filter(node => node.getClientRects().length && getComputedStyle(node).visibility === 'visible').map(box),
                                oled: { a: oled.a, b: oled.b, c: oled.c, d: oled.d, x: oled.m41, y: oled.m42 },
                                overflow: device.scrollWidth > device.clientWidth + 1, family: getComputedStyle(document.documentElement).fontFamily,
                                inset: Math.min(48, Math.max(24, Math.min(innerWidth, innerHeight) * .03)),
                                section: { width: parseFloat(section.borderLeftWidth), style: section.borderLeftStyle, color: section.borderLeftColor,
                                    padding: ['Top', 'Right', 'Bottom', 'Left'].map(side => parseFloat(section['padding' + side])),
                                    otherBorders: ['Top', 'Right', 'Bottom'].map(side => parseFloat(section['border' + side + 'Width'])) } };
                        });
                        const label = `${width}x${height} ${phase}`;
                        const anchorError = value.device.left - value.oled.x - value.inset;
                        check(value.section.width === 2 && value.section.style === 'solid' && value.section.color.replace(/\s+/g, '') === 'rgb(238,238,238)' &&
                            value.section.padding.join(',') === '0,0,0,10' && value.section.otherBorders.every(border => border === 0), label + ': only a left section rule and inner padding, never an outside offset');
                        const overlap = other => Math.min(value.device.right, other.right) > Math.max(value.device.left, other.left) && Math.min(value.device.bottom, other.bottom) > Math.max(value.device.top, other.top);
                        check(value.type[0].text === 'RECEIVER' && value.type[1].text === 'MIRAX · 客廳', label + ': exact receiver copy must survive');
                        check(value.type[0].size === 8 && value.type[1].size === 16 && value.type[1].weight === '400' &&
                            value.type.every(type => type.family === value.family && type.visible && type.lines === 1 && type.rgb.length === 3 && type.rgb.every(channel => Number.isFinite(channel) && channel >= 0 && channel <= 255)), label + ': unchanged family/size/weight, one visible line and real computed color per label');
                        check([value.oled.a, value.oled.b, value.oled.c, value.oled.d].every((number, index) => Math.abs(number - [1, 0, 0, 1][index]) < 1e-6), label + ': retain pure OLED translation');
                        check(value.align === 'left' && Math.abs(anchorError) <= 1 && value.device.top >= height * .6 &&
                            value.device.bottom <= height - value.inset + 5 && value.device.right <= width - value.inset + 5, label + ': original safe-left bottom anchor with retained OLED translation');
                        check(!value.overflow && value.type.every(type => type.bounds.left >= value.device.left - .5 && type.bounds.right <= value.device.right + 1 &&
                            type.bounds.top >= value.device.top - .5 && type.bounds.bottom <= value.device.bottom + .5), label + ': actual Range ink cannot wrap/crop');
                        check(Math.abs(value.brand.left - value.inset) <= 5 && Math.abs(value.brand.top - value.inset) <= 5, label + ': brand container remains at the original top-left anchor');
                        check(value.brandPaint.border === 2 && value.brandPaint.color.replace(/\s+/g, '') === 'rgb(238,238,238)' && value.brandPaint.padding.join(',') === '0,12,0,10' &&
                            Math.abs(value.brandPaint.mark.left - value.brand.left - 12) < .1 && Math.abs(value.brandPaint.mark.width - 30) < .01 && Math.abs(value.brandPaint.mark.height - 30) < .01 &&
                            value.brandPaint.nameSize === 18 && value.brandPaint.versionSize === 8 && value.brandPaint.versionGap === 7 && Math.abs(value.brandPaint.markNameGap - 8) < .1,
                            label + ': matching brand rule moves only content by 12px, never the original logo/font/version gaps');
                        check(value.dock.width === 84 && value.dock.height === 84 && !overlap(value.dock) && !overlap(value.primary) && !value.controls.some(overlap) && value.device.right <= width - 80,
                            label + ': avoid unchanged dock, primary, visible controls and bottom-right pencil area');
                        check(width <= 400 ? Math.abs(value.dock.top - (value.device.bottom - value.oled.y) - 12) <= 1 && value.dock.top - value.device.bottom >= 8 : value.device.right <= value.dock.left - 8,
                            label + ': <=400px stacks at nominal 12px/actual >=8px; other viewports retain the original left side of dock');
                        let spacing = null;
                        if (phase === 'waiting' && width === 896) {
                            spacing = await probe.evaluate(() => {
                                const label = document.querySelector('.device .micro-label'), name = document.querySelector('.device .device-name');
                                const previous = label.getAttribute('style');
                                const measure = () => {
                                    const labelRange = document.createRange(), nameRange = document.createRange(); labelRange.selectNodeContents(label); nameRange.selectNodeContents(name);
                                    const a = labelRange.getBoundingClientRect(), b = nameRange.getBoundingClientRect(), device = label.parentElement.getBoundingClientRect();
                                    return { gap: window.__v39ReceiverRangeGap(a, b), label: a.toJSON(), name: b.toJSON(), left: device.left, bottom: device.bottom,
                                        fonts: [parseFloat(getComputedStyle(label).fontSize), parseFloat(getComputedStyle(name).fontSize), getComputedStyle(label).fontFamily, getComputedStyle(name).fontFamily] };
                                };
                                const current = measure();
                                try { label.style.setProperty('margin-bottom', '7px'); return { current, old: measure() }; }
                                finally { if (previous === null) label.removeAttribute('style'); else label.setAttribute('style', previous); }
                            });
                            check(Math.abs(spacing.old.gap - spacing.current.gap - 4) < .05 && JSON.stringify(spacing.current.fonts) === JSON.stringify(spacing.old.fonts) &&
                                spacing.current.fonts[0] === 8 && spacing.current.fonts[1] === 16 && Math.abs(spacing.current.label.width - spacing.old.label.width) < .05 &&
                                Math.abs(spacing.current.name.width - spacing.old.name.width) < .05 && Math.abs(spacing.current.left - spacing.old.left) < .05 && Math.abs(spacing.current.bottom - spacing.old.bottom) < .05,
                                label + ': actual receiver Range gap must be 4px tighter than sealed-v38 margin 7, without moving anchors or changing fonts: ' + JSON.stringify(spacing));
                        }
                        let backing = null;
                        if (phase === 'waiting' && (width === 320 || width === 401 || width === 568 || width === 896)) {
                            try {
                                await probe.evaluate(() => {
                                    const device = document.querySelector('.device'), style = device.getAttribute('style');
                                    const labels = [...device.querySelectorAll('.micro-label, .device-name')].map(node => [node, node.getAttribute('style')]);
                                    const rect = device.getBoundingClientRect(), magenta = document.createElement('div');
                                    magenta.style.cssText = `position:fixed;left:${rect.left}px;top:${rect.top}px;width:${rect.width}px;height:${rect.height}px;background:#ff00ff;z-index:10;pointer-events:none`;
                                    window.__v38RestoreReceiver = () => { magenta.remove(); if (style === null) device.removeAttribute('style'); else device.setAttribute('style', style); for (const [node, value] of labels) { if (value === null) node.removeAttribute('style'); else node.setAttribute('style', value); } };
                                    window.__v38ReceiverProbe = magenta;
                                });
                                const normal = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                                const borderStyles = await probe.evaluate(() => [document.querySelector('.device'), ...(innerWidth === 896 ? [document.querySelector('.brand')] : [])].map(node => {
                                    const previous = { selector: node.classList.contains('brand') ? '.brand' : '.device', style: node.getAttribute('style') };
                                    node.style.setProperty('border-left-color', 'transparent', 'important'); return previous;
                                }));
                                let noBorder;
                                try { noBorder = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 }); }
                                finally { await probe.evaluate(previous => { for (const item of previous) { const node = document.querySelector(item.selector); if (item.style === null) node.removeAttribute('style'); else node.setAttribute('style', item.style); } }, borderStyles); }
                                await probe.evaluate(() => document.querySelector('.generated-background').append(window.__v38ReceiverProbe));
                                const blocked = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                                await probe.evaluate(() => document.querySelector('.device').style.setProperty('background', 'transparent', 'important'));
                                const exposed = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                                await probe.evaluate(() => { document.querySelector('.device').style.removeProperty('background'); for (const node of document.querySelectorAll('.device .micro-label, .device .device-name')) node.style.setProperty('opacity', '0', 'important'); });
                                const hidden = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                                backing = await probe.evaluate(async ({ pngs, rect, type, brand }) => {
                                    const [normal, blocked, exposed, hidden] = await Promise.all(pngs.map(window.__v36DecodePng));
                                    // Receiver backing mask begins. Node self-checks execute this exact block.
                                    let qualified = 0, maxDelta = 0;
                                    for (let y = Math.ceil(rect.top + 2); y < rect.bottom - 2; y++) for (let x = Math.ceil(rect.left + 2); x < rect.right - 2; x++) {
                                        const index = (y * normal.width + x) * 4;
                                        if (Math.min(exposed.pixels[index], exposed.pixels[index + 2]) < 170 || exposed.pixels[index + 1] > 60) continue;
                                        qualified++; for (const channel of [0, 1, 2]) maxDelta = Math.max(maxDelta, Math.abs(normal.pixels[index + channel] - blocked.pixels[index + channel]));
                                    }
                                    // Receiver backing mask ends.
                                    // Receiver AA ink uses 60% of its real CSS color; white marquee probes keep the default 200.
                                    const glyphs = type.map(item => ({ character: item.text, inkCutoff: Math.ceil(Math.min(...item.rgb) * .6), quad: [{ x: item.bounds.left, y: item.bounds.top }, { x: item.bounds.right, y: item.bounds.top }, { x: item.bounds.right, y: item.bounds.bottom }, { x: item.bounds.left, y: item.bounds.bottom }] }));
                                    const ink = await window.__v36GlyphPixels({ visible: pngs[1], hidden: pngs[3], glyphs });
                                    const rules = [{ character: 'receiver-left-rule', rect }, ...(brand ? [{ character: 'brand-left-rule', rect: brand }] : [])];
                                    const leftRule = await window.__v36GlyphPixels({ visible: pngs[0], hidden: pngs[4], glyphs: rules.map(({ character, rect }) => ({ character, quad: [
                                        { x: rect.left, y: rect.top + 4 }, { x: rect.left + 2, y: rect.top + 4 }, { x: rect.left + 2, y: rect.bottom - 4 }, { x: rect.left, y: rect.bottom - 4 }
                                    ] })) });
                                    return { qualified, maxDelta, ink, leftRule };
                                }, { pngs: [normal, blocked, exposed, hidden, noBorder].map(png => png.toString('base64')), rect: value.device, type: value.type, brand: width === 896 ? value.brand : null });
                                check(backing.qualified >= 40 && backing.maxDelta <= 3 && backing.ink.length === 2 && backing.ink.every(item => item.changedPixels >= 2) &&
                                    backing.leftRule.length === (width === 896 ? 2 : 1) && backing.leftRule.every(rule => rule.changedPixels >= 8),
                                    label + ': qualified backing, both labels and each actual left rule versus transparent-border control must survive: ' + JSON.stringify(backing));
                            } finally {
                                await probe.evaluate(() => { try { window.__v38RestoreReceiver?.(); } finally { delete window.__v38RestoreReceiver; delete window.__v38ReceiverProbe; } });
                            }
                        }
                        evidence.push({ width, height, phase, ...value, spacing, backing });
                    }
                }
                return evidence;
            } finally {
                try { await probe.evaluate(previous => { state.motion.paused = previous.paused; state.broadcast = previous.broadcast; state.phase = previous.phase; render(); syncMotion(); }, saved); }
                finally { await probe.setViewportSize(viewport); }
            }
        });
        await run('D opaque original front faces: qualified PNG interiors block actual waves and magenta', async () => {
            const viewport = probe.viewportSize();
            const evidence = [];
            try {
                await probe.evaluate(() => {
                    if (state.variant !== 'D' || state.motion.dMode !== 'study-118-section-extrusion') throw Error('Exercise the actual default X118 renderer');
                    const paused = state.motion.paused;
                    const style = xCore.getAttribute('style');
                    const fill = xCore.getAttribute('fill');
                    const freeze = document.createElement('style');
                    freeze.textContent = '*,*::before,*::after{animation-play-state:paused!important;transition:none!important}#toast{visibility:hidden!important}';
                    const magenta = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
                    magenta.id = 'v37-face-magenta';
                    for (const [name, value] of Object.entries({ x: '-10000', y: '-10000', width: '20000', height: '20000', fill: '#ff00ff', display: 'none' })) magenta.setAttribute(name, value);
                    window.__v37RestoreFaces = () => {
                        if (style === null) xCore.removeAttribute('style'); else xCore.setAttribute('style', style);
                        if (fill === null) xCore.removeAttribute('fill'); else xCore.setAttribute('fill', fill);
                        magenta.remove(); freeze.remove(); state.motion.paused = paused; render();
                        drawDMotion(Math.max(0, particleTime * .001 - dMotionStartedAt)); syncMotion();
                    };
                    state.motion.paused = true; render(); document.head.append(freeze);
                    xPlane.before(magenta);
                });
                for (const [width, height] of [[800, 800], [1440, 1000]]) {
                    await probe.setViewportSize({ width, height });
                    const frame = await probe.evaluate(() => {
                        document.querySelector('#v37-face-magenta').setAttribute('display', 'none');
                        drawDMotion(0);
                        const frame = createDStudyFrame(0);
                        if (!frame || frame.arms.length !== 4 || frame.diamond.length !== 4 || studyCanvas.hidden) throw Error('Actual original projected polygons and study canvas are required');
                        return frame;
                    });
                    const normal0 = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                    await probe.evaluate(() => drawDMotion(13));
                    const normal13 = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                    await probe.evaluate(() => document.querySelector('#v37-face-magenta').removeAttribute('display'));
                    const opaqueProbe = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                    const savedStyle = await probe.evaluate(() => { const previous = xCore.getAttribute('style'); xCore.style.setProperty('fill', 'none', 'important'); return previous; });
                    let transparentProbe;
                    try { transparentProbe = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 }); }
                    finally { await probe.evaluate(style => { if (style === null) xCore.removeAttribute('style'); else xCore.setAttribute('style', style); }, savedStyle); }
                    const pixels = await probe.evaluate(async ({ pngs, frame }) => {
                        const insetContains = window.__v36InsetContains;
                        const decode = async data => {
                            const image = new Image(); image.src = 'data:image/png;base64,' + data; await image.decode();
                            const canvas = document.createElement('canvas'); canvas.width = image.width; canvas.height = image.height;
                            const context = canvas.getContext('2d'); context.drawImage(image, 0, 0);
                            return { width: image.width, height: image.height, data: context.getImageData(0, 0, image.width, image.height).data };
                        };
                        const [normal0, normal13, opaque, transparent] = await Promise.all(pngs.map(decode));
                        if ([normal0, normal13, opaque, transparent].some(image => image.width !== innerWidth || image.height !== innerHeight)) throw Error('PNG samples must be matching CSS-pixel viewport rasters');
                        const delta = (a, b, index) => Math.max(...[0, 1, 2].map(channel => Math.abs(a.data[index + channel] - b.data[index + channel])));
                        const faces = [['NE', frame.arms[1]], ['SE', frame.arms[3]], ['diamond', frame.diamond]].map(([name, polygon]) => {
                            let qualified = 0, covered = 0, dark = 0, maxWaveDelta = 0, maxProbeDelta = 0;
                            for (let y = Math.max(0, Math.floor(Math.min(...polygon.map(point => point.y)))); y < Math.min(innerHeight, Math.max(...polygon.map(point => point.y))); y += 4) {
                                for (let x = Math.max(0, Math.floor(Math.min(...polygon.map(point => point.x)))); x < Math.min(innerWidth, Math.max(...polygon.map(point => point.x))); x += 4) {
                                    if (!insetContains({ x: x + .5, y: y + .5 }, polygon, 4)) continue;
                                    const index = (y * normal0.width + x) * 4;
                                    if (Math.min(transparent.data[index], transparent.data[index + 2]) < 170 || transparent.data[index + 1] > 60) { covered++; continue; }
                                    qualified++;
                                    if (Math.max(normal0.data[index], normal0.data[index + 1], normal0.data[index + 2]) <= 24) dark++;
                                    maxWaveDelta = Math.max(maxWaveDelta, delta(normal0, normal13, index));
                                    maxProbeDelta = Math.max(maxProbeDelta, delta(normal13, opaque, index));
                                }
                            }
                            return { name, qualified, covered, darkFraction: qualified ? dark / qualified : 0, maxWaveDelta, maxProbeDelta };
                        });
                        let outsideChanged = 0, outsideMaxDelta = 0;
                        for (let y = 0; y < innerHeight; y += 4) for (let x = 0; x < innerWidth; x += 4) {
                            if ([...frame.arms, frame.diamond].some(polygon => insetContains({ x: x + .5, y: y + .5 }, polygon, -4))) continue;
                            const difference = delta(normal0, normal13, (y * normal0.width + x) * 4);
                            if (difference > 3) outsideChanged++;
                            outsideMaxDelta = Math.max(outsideMaxDelta, difference);
                        }
                        return { faces, outsideChanged, outsideMaxDelta };
                    }, { pngs: [normal0, normal13, opaqueProbe, transparentProbe].map(png => png.toString('base64')), frame });
                    for (const face of pixels.faces.filter(face => face.qualified)) {
                        check(face.maxWaveDelta <= 3 && face.maxProbeDelta <= 3, `${width}x${height} ${face.name}: opaque interiors must block waves and magenta, not merely dim them: ${JSON.stringify(face)}`);
                        check(face.darkFraction >= .9, `${width}x${height} ${face.name}: front faces must stay deep background, not white blobs`);
                    }
                    check(pixels.outsideChanged >= 20 && pixels.outsideMaxDelta >= 10, `${width}x${height}: actual default118 waves/extrusion must still change outside original front faces: ${JSON.stringify(pixels)}`);
                    evidence.push({ width, height, ...pixels });
                }
                for (const name of ['NE', 'SE', 'diamond']) check(evidence.some(value => value.faces.some(face => face.name === name && face.qualified >= 40)), name + ': require genuine visible interior coverage, never count a foreground-covered arm as proof');
                return evidence;
            } finally {
                try { await probe.evaluate(() => { try { window.__v37RestoreFaces?.(); } finally { delete window.__v37RestoreFaces; } }); }
                finally { await probe.setViewportSize(viewport); }
            }
        });
        await run('immediate real OFF/ON/connect/fail/retry/OFF guidance and native ink motion', async () => {
            const evidence = [];
            for (const phase of ['off', 'arming', 'waiting', 'wifi-paused', 'frozen', 'waiting', 'connecting', 'failed', 'connecting', 'off']) {
                const value = await probe.evaluate(next => {
                    if (next === 'off') { if (state.phase === 'frozen') previewNativePhase('READY'); else if (state.broadcast) toggleBroadcast(); }
                    else if (next === 'arming') { if (state.phase === 'frozen') previewNativePhase('READY'); if (!state.broadcast) toggleBroadcast(); else previewNativePhase('ARMING'); }
                    else if (next === 'waiting') { if (state.phase === 'arming') confirmServiceReady(); else previewNativePhase('ADVERTISING'); }
                    else if (next === 'wifi-paused') previewNativePhase('WIFI_PAUSED');
                    else if (next === 'frozen') previewNativePhase('FROZEN');
                    else setPhase(next);
                    return window.__v36Read();
                }, phase);
                check(value.activeStatus === value.phase && value.selectedText.length > 0, phase + ': active status copy is selected immediately');
                const mid = await probe.evaluate(async () => { await window.__v36Frames(90); return window.__v36Read(); });
                check(mid.styles.some(style => style.opacity > .05 && style.opacity < .98), phase + ': status fade shows an intermediate alpha');
                const after = await probe.evaluate(async () => { await window.__v36Frames(120); return window.__v36Read(); });
                checkStatus(after, phase);
                check(after.animation?.playState === 'running' && after.animation.currentTime > value.animation.currentTime + 60 && after.transform !== value.transform, phase + ': native RAF animation transform must advance');
                check(value.glyphs.some(glyph => after.glyphs.some(next => next.character === glyph.character && Math.hypot(next.x - glyph.x, next.y - glyph.y) > .05 && Math.hypot(next.x - glyph.x, next.y - glyph.y) < 60)), phase + ': visible glyph ink must move with the native clock');
                evidence.push({ phase, visibleGlyphs: value.glyphs.length, text: value.selectedText.slice(0, 170), font: value.styles[0], advancedMs: after.animation.currentTime - value.animation.currentTime });
            }
            return evidence;
        });
        await run('eight sizes: two rows, lower north-west, upper unchanged, bounded ink, full shortcut passage and continuous seam', async () => {
            await probe.evaluate(() => { if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); } else if (state.phase !== 'waiting') setPhase('waiting'); });
            const evidence = [];
            for (const [width, height] of sizes) {
                await probe.setViewportSize({ width, height });
                await probe.evaluate(() => window.__v36Frames(200));
                const value = await read();
                checkStatus(value, 'waiting');
                check(value.lowerRows === 2 && value.upperRows === 3 && value.thirdIsStatus && value.decorativeTracks, `${width}x${height}: upper third status + two decorative lower rows`);
                check(value.documentWidth <= width + 1 && Math.abs(value.rowWidth - value.windowWidth) < 2 && value.overflow.every(mode => /^(hidden|clip)$/.test(mode)), 'Marquee must stay in its bounded clipping window');
                check(value.lowerShift.x <= -7 && value.lowerShift.x >= -17 && value.lowerShift.y <= -7 && value.lowerShift.y >= -17, 'Both lower rows must move slightly north-west versus v35: ' + JSON.stringify(value.lowerShift));
                check(Math.abs(value.upperShift.x) < .6 && Math.abs(value.upperShift.y) < .6 && value.upperHeight === 80 && value.lowerHeight === 52, 'Upper center stays put at height 80; lower band stays 52');
                check(value.exposedLength >= value.oldExposedLength - .6, 'Lower shift must not reduce visible text length');
                check(value.segments.length === 2 && value.segments[0].text === value.segments[1].text && value.segments[0].text && Math.abs(value.segments[0].width - value.segments[1].width) < .2, 'Two equal active-text duplicates must close the marquee seam');
                const passage = await probe.evaluate(() => {
                    // Shortcut occlusion geometry begins. Node self-checks execute this exact helper.
                    function polygonsOverlap(a, b) {
                        return [a, b].every(polygon => polygon.every((point, index) => {
                            const next = polygon[(index + 1) % polygon.length];
                            const dx = next.x - point.x, dy = next.y - point.y;
                            const length = Math.hypot(dx, dy);
                            const project = points => points.map(vertex => (vertex.x * dy - vertex.y * dx) / length);
                            const first = project(a), second = project(b);
                            // Keep one CSS pixel clear of opaque edges and their antialiasing.
                            return Math.max(...first) >= Math.min(...second) - 1 && Math.max(...second) >= Math.min(...first) - 1;
                        }));
                    }
                    // Shortcut occlusion geometry ends.
                    const occluders = [...document.querySelectorAll('.brand, .device, #standby-view .center, #broadcast-dock')]
                        .filter(node => node.getClientRects().length && getComputedStyle(node).visibility === 'visible' && Number(getComputedStyle(node).opacity) > .01)
                        .map(node => { const box = node.getBoundingClientRect(); return [{ x: box.left, y: box.top }, { x: box.right, y: box.top }, { x: box.right, y: box.bottom }, { x: box.left, y: box.bottom }]; });
                    const frame = createDStudyFrame(0);
                    if (!frame) throw Error('Actual original X polygons are required for intentional shortcut occlusion');
                    const offset = stage.getBoundingClientRect();
                    occluders.push(...[...frame.arms, frame.diamond].map(polygon => polygon.map(point => ({ x: point.x + offset.left, y: point.y + offset.top }))));
                    // A prime count avoids aliasing the eight repeated phrases into a few positions.
                    const sampleCount = 257;
                    for (let index = 0; index < sampleCount; index++) {
                        const sample = window.__v36Seek(index / sampleCount);
                        const keyId = sample.completeKeyIds.find(keyId => sample.glyphs.filter(glyph => glyph.keyId === keyId).every(glyph =>
                            !occluders.some(polygon => polygonsOverlap(glyph.quad, polygon))));
                        if (keyId !== undefined) return { fraction: index / sampleCount, keyId, searchSamples: sampleCount,
                            glyphs: sample.glyphs.filter(glyph => glyph.keyId === keyId && glyph.visibleFraction >= .995).length, verticalClips: sample.verticalClips };
                    }
                    return null;
                });
                check(passage && !passage.verticalClips.length, `${width}x${height}: After 257 seeks, complete unoccluded Win + K ink must pass viewport/clip outside foreground UI and intentional original X masks`);
                let paintedGlyphPixels = null;
                if (width === 390 && height === 844 || width === 568 && height === 320 || width === 480 && height === 375) {
                    try {
                        const glyphs = await probe.evaluate(async ({ fraction, keyId }) => {
                            // Keep CSS in charge of play state while seeking the native clock.
                            if (!state.motion.paused) toggleMotion();
                            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                            return window.__v36Seek(fraction).glyphs.filter(glyph => glyph.keyId === keyId && glyph.visibleFraction >= .995);
                        }, passage);
                        paintedGlyphPixels = await captureGlyphPaint(glyphs);
                        check(paintedGlyphPixels.length === 5 && paintedGlyphPixels.every(glyph => glyph.changedPixels >= 2), 'Every visible W/i/n/+/K glyph must have actual bright screenshot ink: ' + JSON.stringify(paintedGlyphPixels));
                    } finally {
                        await probe.evaluate(() => { if (state.motion.paused) toggleMotion(); });
                    }
                }
                const seam = await probe.evaluate(() => {
                    const before = window.__v36Seek(.999999);
                    const after = window.__v36Seek(.000001);
                    const complete = before.glyphs.filter(glyph => glyph.visibleFraction > .999);
                    const distances = complete.map(glyph => Math.min(...after.glyphs.filter(next => next.character === glyph.character).map(next => Math.hypot(next.x - glyph.x, next.y - glyph.y))));
                    return { compared: distances.length, unmatched: distances.filter(distance => distance > .7).length, maximum: Math.max(0, ...distances), clipped: before.verticalClips.length + after.verticalClips.length };
                });
                check(seam.compared >= 3 && seam.unmatched === 0 && !seam.clipped, 'Duplicate reset must preserve the visible glyph field without a seam gap: ' + JSON.stringify(seam));
                evidence.push({ width, height, lowerShift: value.lowerShift, upperShift: value.upperShift, visibleGlyphs: value.glyphs.length,
                    exposedLength: value.exposedLength, oldExposedLength: value.oldExposedLength, passage, paintedGlyphPixels, seam });
            }
            check(evidence.some(value => value.exposedLength > value.oldExposedLength + 8), 'North-west movement must expose measurably more text on a constrained viewport');
            return evidence;
        });
        await run('whole X masks crossing text/header; removed corner stub: PNG stack/order/absence controls', async () => {
            const viewport = probe.viewportSize();
            try {
                await probe.setViewportSize({ width: 1440, height: 1000 });
                await probe.evaluate(async () => {
                    if (state.phase !== 'waiting' || !state.broadcast) throw Error('Real waiting guidance is required for the crossing-word probe');
                    const root = document.querySelector('#e-scene');
                    if (root.parentElement !== document.querySelector('.generated-background') || getComputedStyle(root.parentElement).zIndex !== '-5' || getComputedStyle(root).zIndex !== '3' ||
                        getComputedStyle(xCore.ownerSVGElement).zIndex !== '4' || getComputedStyle(dMotionOverlay).zIndex !== '5') throw Error('Whole X at 4/5 must stay above marquee 3 inside generated background -5');
                    const row = document.querySelector('.e-channel--upper .e-stream-row:nth-child(3).e-stream-row--status');
                    const message = [...row.querySelectorAll('.e-status-message')].find(node => node.dataset.status === (document.querySelector('#stage').dataset.broadcast === 'true' ? document.querySelector('#stage').dataset.phase : 'off'));
                    const animation = message.querySelector('.e-stream-track').getAnimations().find(item => item.effect.getTiming().iterations === Infinity);
                    if (!animation) throw Error('Crossing-word probe requires the real native status animation');
                    const paused = state.motion.paused, time = animation.currentTime;
                    const rootStyle = root.getAttribute('style'), rowStyle = row.getAttribute('style');
                    window.__v36RestoreTextFace = () => {
                        if (rootStyle === null) root.removeAttribute('style'); else root.setAttribute('style', rootStyle);
                        if (rowStyle === null) row.removeAttribute('style'); else row.setAttribute('style', rowStyle);
                        animation.currentTime = time;
                        if (state.motion.paused !== paused) toggleMotion();
                        syncMotion();
                    };
                    if (!state.motion.paused) toggleMotion();
                    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                });
                const crossing = await probe.evaluate(() => {
                    const frame = createDStudyFrame(0);
                    if (!frame) throw Error('Crossing-word probe requires actual original X polygons');
                    const offset = stage.getBoundingClientRect();
                    const polygons = [...frame.arms, frame.diamond].map(polygon => polygon.map(point => ({ x: point.x + offset.left, y: point.y + offset.top })));
                    const ui = [...document.querySelectorAll('.brand, .device, #standby-view .center, #broadcast-dock')]
                        .filter(node => node.getClientRects().length && getComputedStyle(node).visibility === 'visible' && Number(getComputedStyle(node).opacity) > .01)
                        .map(node => node.getBoundingClientRect());
                    for (let index = 0; index < 257; index++) {
                        const sample = window.__v36Seek(index / 257);
                        for (const keyId of sample.completeKeyIds) {
                            const glyphs = sample.glyphs.filter(glyph => glyph.keyId === keyId && glyph.visibleFraction >= .995);
                            if (glyphs.length !== 5 || glyphs.some(glyph => ui.some(box => Math.max(...glyph.quad.map(point => point.x)) > box.left - 1 &&
                                Math.min(...glyph.quad.map(point => point.x)) < box.right + 1 && Math.max(...glyph.quad.map(point => point.y)) > box.top - 1 && Math.min(...glyph.quad.map(point => point.y)) < box.bottom + 1))) continue;
                            if (glyphs.every(glyph => glyph.quad.every(point => window.__v36InsetContains(point, polygons[0], 4))))
                                return { fraction: index / 257, keyId, faceName: 'NW', face: polygons[0], glyphs, searchSamples: 257 };
                        }
                    }
                    return null;
                });
                check(crossing !== null, 'No complete upper-third Win + K fits a genuine original face interior after 257 seeks; do not waive actual crossing ink');
                const fixed = await captureGlyphPaint(crossing.glyphs, crossing.face);
                check(fixed.length === 5 && fixed.every(glyph => glyph.changedPixels < 2), 'Whole opaque X must intentionally mask every W/i/n/+/K inside its real 4px-inset face: ' + JSON.stringify(fixed));
                await probe.evaluate(() => document.querySelector('#e-scene').style.setProperty('z-index', '6', 'important'));
                const textAbove = await captureGlyphPaint(crossing.glyphs, crossing.face);
                check(textAbove.length === 5 && textAbove.every(glyph => glyph.changedPixels >= 2), 'Stack-only negative control must reproduce all five painted glyphs above X, still inside the background enclosure: ' + JSON.stringify(textAbove));
                await probe.evaluate(() => window.__v36RestoreTextFace());
                let header;
                try {
                    const geometry = await probe.evaluate(() => {
                        const main = xCore.ownerSVGElement;
                        const rule = [...main.children].find(node => node.getAttribute('d') === 'M0 68H1600');
                        if (!rule || document.querySelector('#hardline-scene [d="M1390 0H1444V65H1390Z"]') || main.lastElementChild !== xPlane || rule.getAttribute('stroke') !== '#fff' || rule.getAttribute('stroke-width') !== '2' || rule.getAttribute('vector-effect') !== 'non-scaling-stroke') throw Error('Retain the original bright header behind X; the extraneous corner path must be absent');
                        const corner = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                        for (const [name, value] of Object.entries({ d: 'M1390 0H1444V65H1390Z', fill: 'none', stroke: '#eeeee6', 'stroke-width': '1' })) corner.setAttribute(name, value);
                        const parent = xPlane.parentNode, next = xPlane.nextSibling;
                        const attributes = ['stroke', 'stroke-width', 'visibility'].map(name => [name, rule.getAttribute(name)]);
                        const paused = state.motion.paused;
                        window.__v38RestoreHeader = () => { corner.remove(); parent.insertBefore(xPlane, next); for (const [name, value] of attributes) { if (value === null) rule.removeAttribute(name); else rule.setAttribute(name, value); } if (state.motion.paused !== paused) toggleMotion(); syncMotion(); };
                        window.__v38Header = rule;
                        window.__v38CornerProbe = corner;
                        if (!state.motion.paused) toggleMotion();
                        const matrix = rule.getScreenCTM();
                        if (!matrix || Math.abs(matrix.b) > 1e-8 || Math.abs(matrix.c) > 1e-8) throw Error('Use the actual horizontal header screen matrix');
                        const frame = createDStudyFrame(0), offset = stage.getBoundingClientRect();
                        const faces = [...frame.arms, frame.diamond].map(polygon => polygon.map(point => ({ x: point.x + offset.left, y: point.y + offset.top })));
                        const ui = [...document.querySelectorAll('.brand, .device, #standby-view .center, #broadcast-dock')].map(node => node.getBoundingClientRect());
                        const channels = window.__v36ChannelQuads();
                        const excluded = point => ui.some(box => point.x >= box.left - 4 && point.x <= box.right + 4 && point.y >= box.top - 4 && point.y <= box.bottom + 4) || channels.some(polygon => window.__v36InsetContains(point, polygon, -4));
                        const y = new DOMPoint(0, 68).matrixTransform(matrix).y;
                        const columns = [];
                        for (let x = 12.5; x < innerWidth - 12; x += 2) {
                            const point = { x, y };
                            if (excluded(point)) continue;
                            if ([-4, 4].every(dy => window.__v36InsetContains({ x, y: y + dy }, faces[1], 4))) columns.push({ ...point, inside: true });
                            else if (!faces.some(polygon => window.__v36InsetContains(point, polygon, -8))) columns.push({ ...point, inside: false });
                        }
                        const corners = [];
                        for (let y = 6; y <= 59; y += 2) {
                            const point = new DOMPoint(1390, y).matrixTransform(matrix);
                            if (!excluded(point) && !faces.some(polygon => window.__v36InsetContains(point, polygon, -8))) corners.push({ x: point.x, y: point.y });
                        }
                        return { y, columns, corners, cornerAbsent: true, matrix: { a: matrix.a, d: matrix.d, e: matrix.e, f: matrix.f } };
                    });
                    const normal = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                    await probe.evaluate(() => window.__v38Header.setAttribute('visibility', 'hidden'));
                    const noRule = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                    await probe.evaluate(() => { window.__v38Header.removeAttribute('visibility'); window.__v38Header.before(xPlane); });
                    const oldOrder = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                    await probe.evaluate(() => { window.__v38Header.setAttribute('stroke-width', '1'); window.__v38Header.setAttribute('stroke', '#eeeee6'); });
                    const thinRule = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                    await probe.evaluate(() => {
                        window.__v38Header.setAttribute('stroke-width', '2'); window.__v38Header.setAttribute('stroke', '#fff');
                        xPlane.parentNode.append(xPlane); xPlane.before(window.__v38CornerProbe);
                    });
                    const oldCorner = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                    header = await probe.evaluate(async ({ pngs, geometry }) => {
                        const [fixed, hidden, wrongOrder, thin, oldCorner] = await Promise.all(pngs.map(window.__v36DecodePng));
                        const sample = window.__v36LineInkProfile;
                        const inside = [], outside = [];
                        for (const point of geometry.columns) {
                            if (sample(hidden, point).peak > 24) continue;
                            const value = { point, fixed: sample(fixed, point), wrongOrder: sample(wrongOrder, point), thin: sample(thin, point) };
                            (point.inside ? inside : outside).push(value);
                        }
                        const corner = geometry.corners.map(point => ({ fixed: sample(fixed, point, { x: 1, y: 0 }), oldCorner: sample(oldCorner, point, { x: 1, y: 0 }) })).filter(value => value.fixed.peak <= 24);
                        return { y: geometry.y, matrix: geometry.matrix, inside: inside.length, outside: outside.length, corner: corner.length, cornerAbsent: geometry.cornerAbsent,
                            insideDark: inside.every(value => value.fixed.peak <= 24 && value.wrongOrder.peak >= 180),
                            outsideThicker: outside.every(value => value.fixed.peak >= 200 && value.fixed.energy >= 1.5 && value.fixed.energy > value.thin.energy + .4),
                            cornerClean: corner.every(value => value.oldCorner.peak >= 70),
                            exampleInside: inside[0], exampleOutside: outside[0], exampleCorner: corner[0] };
                    }, { pngs: [normal, noRule, oldOrder, thinRule, oldCorner].map(png => png.toString('base64')), geometry });
                    check(header.inside >= 20 && header.outside >= 20 && header.corner >= 8 && header.insideDark && header.outsideThicker && header.cornerAbsent && header.cornerClean,
                        'Mapped header stays thicker outside/dark inside X; removed outside-face corner stub must be clean versus the test-only old-path control, with genuine coverage: ' + JSON.stringify(header));
                } finally {
                    await probe.evaluate(() => { try { window.__v38RestoreHeader?.(); } finally { delete window.__v38RestoreHeader; delete window.__v38Header; delete window.__v38CornerProbe; } });
                }
                return { width: 1440, height: 1000, faceName: crossing.faceName, fraction: crossing.fraction, searchSamples: crossing.searchSamples, insetPx: 4, fixed, textAbove, header };
            } finally {
                try { await probe.evaluate(() => { try { window.__v36RestoreTextFace?.(); } finally { delete window.__v36RestoreTextFace; } }); }
                finally { await probe.setViewportSize(viewport); }
            }
        });
        await probe.setViewportSize({ width: 390, height: 844 });
        await run('native Win + K entry: one partial word becomes fully visible on real RAFs', async () => {
            await probe.bringToFront();
            await probe.evaluate(() => { if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); } else if (state.phase !== 'waiting') setPhase('waiting'); });
            const evidence = await probe.evaluate(async () => {
                try {
                    if (!state.motion.paused) toggleMotion();
                    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                    const message = [...document.querySelectorAll('.e-status-message')].find(node => node.dataset.status === (document.querySelector('#stage').dataset.broadcast === 'true' ? document.querySelector('#stage').dataset.phase : 'off'));
                    const animation = message.querySelector('.e-stream-track').getAnimations().find(item => item.effect.getTiming().iterations === Infinity);
                    let initial = null;
                    let keyId = null;
                    for (let index = 0; index < 160 && keyId === null; index++) {
                        const sample = window.__v36Seek(index / 160);
                        const candidate = sample.enteringKeyIds[0];
                        if (candidate !== undefined) { initial = sample; keyId = candidate; }
                    }
                    if (keyId === null) throw Error('No entering partial shortcut found for native crossing proof');
                    const startK = initial.glyphs.find(glyph => glyph.keyId === keyId && glyph.character === 'K');
                    const startTime = Number(animation.currentTime);
                    if (state.motion.paused) toggleMotion();
                    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                    const started = performance.now();
                    const samples = [];
                    let lastRead = -Infinity;
                    let final = initial;
                    await new Promise((resolve, reject) => {
                        const observe = () => {
                            try {
                                const elapsed = performance.now() - started;
                                if (elapsed - lastRead >= 90 || elapsed >= 3900) {
                                    final = window.__v36Read();
                                    const group = final.glyphs.filter(glyph => glyph.keyId === keyId);
                                    samples.push({ elapsed, phase: final.phase, complete: final.completeKeyIds.includes(keyId),
                                        visibleCharacters: group.map(glyph => glyph.character).join(''), clipped: final.verticalClips.length });
                                    lastRead = elapsed;
                                }
                                if (elapsed < 3900) requestAnimationFrame(observe);
                                else resolve();
                            } catch (error) { reject(error); }
                        };
                        requestAnimationFrame(observe);
                    });
                    const endK = final.glyphs.find(glyph => glyph.keyId === keyId && glyph.character === 'K');
                    const advancedMs = Number(animation.currentTime) - startTime;
                    const dx = endK ? endK.x - startK.x : null;
                    const dy = endK ? endK.y - startK.y : null;
                    return { keyId, initialComplete: initial.completeKeyIds.includes(keyId), initialCharacters: initial.glyphs.filter(glyph => glyph.keyId === keyId).map(glyph => glyph.character).join(''),
                        advancedMs, dx, dy, speedCssPxPerSecond: endK ? Math.hypot(dx, dy) / (advancedMs / 1000) : null,
                        nativeTransformChanged: final.transform !== initial.transform, playState: animation.playState, samples };
                } finally { if (state.motion.paused) toggleMotion(); }
            });
            check(!evidence.initialComplete && evidence.samples.some(sample => sample.complete), 'The same partial shortcut must enter fully during native animation, not a second seek');
            check(evidence.samples.every(sample => sample.phase === 'waiting' && !sample.clipped), 'Native entry must retain real waiting copy without vertical clipping');
            check(evidence.playState === 'running' && evidence.advancedMs >= 2500 && evidence.nativeTransformChanged && evidence.dx > 20 && evidence.dy < -20, 'Native RAF time, transform and the same K glyph must move bottom-left to top-right');
            check(evidence.speedCssPxPerSecond >= 18 && evidence.speedCssPxPerSecond <= 32, 'Native status glyph speed must remain near its actual 24 CSS px/s cadence');
            return evidence;
        });
        await run('theme/history, projection synchronization and genuine CSS pause gates', async () => {
            await probe.evaluate(() => { if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); } if (state.phase !== 'waiting') setPhase('waiting'); setPhase('connecting'); setPhase('failed'); selectVariant('A'); toggleBroadcast(); confirmServiceReady(); });
            await probe.goBack();
            await probe.waitForFunction(() => state.variant === 'D');
            checkStatus(await read(), 'off');
            await probe.evaluate(() => { selectVariant('C'); toggleBroadcast(); confirmServiceReady(); selectVariant('D'); });
            checkStatus(await read(), 'waiting');
            const evidence = [];
            for (const mode of ['paused', 'hidden', 'simple', 'projected', 'media']) {
                const before = await probe.evaluate(async value => {
                    if (value === 'paused') toggleMotion();
                    if (value === 'hidden') { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); }
                    if (value === 'simple') document.querySelector('#home-simple').click();
                    if (value === 'projected') { setPhase('connecting'); setPhase('projecting'); }
                    if (value === 'media') {
                        const canvas = document.createElement('canvas'); canvas.width = canvas.height = 2;
                        canvas.getContext('2d').fillRect(0, 0, 2, 2);
                        const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
                        if (!blob) throw Error('Read-only media fixture could not be encoded');
                        loadFile(new File([blob], 'v37-read-only-media.png', { type: 'image/png' }));
                        await mediaHost.querySelector('img').decode();
                    }
                    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                    return window.__v36Read();
                }, mode);
                const after = await probe.evaluate(async () => { await window.__v36Frames(150); return window.__v36Read(); });
                check(before.animation?.playState === 'paused' && after.animation?.playState === 'paused' && Math.abs(after.animation.currentTime - before.animation.currentTime) < 1 && after.transform === before.transform, mode + ': real CSS animation state, time and transform must freeze');
                if (mode !== 'paused') checkStatus(after, mode === 'projected' ? 'projecting' : 'waiting', false);
                await probe.evaluate(value => {
                    if (value === 'paused') toggleMotion();
                    if (value === 'hidden') { delete document.hidden; document.dispatchEvent(new Event('visibilitychange')); }
                    if (value === 'simple') document.querySelector('#home-big').click();
                    if (value === 'projected') setPhase('waiting');
                    if (value === 'media') clearMedia();
                }, mode);
                checkStatus(await read(), 'waiting');
                evidence.push({ mode, playState: after.animation.playState, deltaMs: after.animation.currentTime - before.animation.currentTime });
            }
            const hidden = await probe.evaluate(() => { setPhase('connecting'); setPhase('projecting'); selectVariant('A'); toggleBroadcast(); selectVariant('D'); return window.__v36Read(); });
            checkStatus(hidden, 'off');
            return evidence;
        });
        await run('7500 active-ms recovery boundary, gated active clock and no stale episode', async () => {
            const evidence = await probe.evaluate(() => {
                if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); }
                if (state.phase !== 'waiting') setPhase('waiting');
                setPhase('connecting'); setPhase('failed');
                window.__v36StopFrames();
                const start = particleTime - cFailureStartedAt;
                if (cFailureStartedAt === null || start < 0 || start >= 7499) throw Error('Invalid recovery fixture: ' + start);
                toggleMotion(); window.__v36Advance(16000);
                const paused = { phase: state.phase, elapsed: particleTime - cFailureStartedAt };
                toggleMotion(); window.__v36StopFrames();
                window.__v36Advance(7499 - start);
                const before = window.__v36Read();
                window.__v36Advance(1);
                const after = window.__v36Read();
                setPhase('connecting'); setPhase('failed'); window.__v36StopFrames(); window.__v36Advance(2000);
                toggleBroadcast(); toggleBroadcast(); confirmServiceReady(); window.__v36StopFrames(); window.__v36Advance(8000);
                const stale = window.__v36Read();
                previousFrameTime = null; syncMotion();
                return { start, paused, before, after, stale };
            });
            check(evidence.paused.phase === 'failed' && evidence.paused.elapsed === evidence.start, 'Paused wall-time-shaped steps cannot advance active recovery');
            checkStatus(evidence.before, 'failed');
            checkStatus(evidence.after, 'waiting');
            checkStatus(evidence.stale, 'waiting');
            return { start: evidence.start, paused: evidence.paused, before: evidence.before.phase, after: evidence.after.phase,
                afterText: evidence.after.selectedText.slice(0, 180), stale: evidence.stale.phase, normalColor: evidence.after.styles[0]?.rgb };
        });

        await run('D handle tracks the visible primary centerline; hidden primary stays at 50%', async () => {
            const saved = await probe.evaluate(() => ({ variant: state.variant, home: state.home, phase: state.phase, broadcast: state.broadcast, paused: state.motion.paused, media: state.media.status }));
            const viewport = probe.viewportSize();
            const evidence = [];
            try {
                const measure = () => probe.evaluate(() => {
                    resizeCanvas();
                    const primary = document.querySelector('#standby-view .center').getBoundingClientRect();
                    const handle = document.querySelector('#settings-handle');
                    const box = handle.getBoundingClientRect();
                    const primaryCenter = (primary.top + primary.bottom) / 2;
                    const handleCenter = (box.top + box.bottom) / 2;
                    const tracked = Math.abs(primaryCenter - handleCenter);
                    handle.style.removeProperty('top');
                    const counterfactual = handle.getBoundingClientRect();
                    const counterCenter = (counterfactual.top + counterfactual.bottom) / 2;
                    applyAmbientTransforms();
                    const restored = document.querySelector('#settings-handle').getBoundingClientRect();
                    return { tracked, counterError: Math.abs(primaryCenter - counterCenter), restored: Math.abs(primaryCenter - (restored.top + restored.bottom) / 2),
                        outside: !document.querySelector('#standby-view').contains(handle) };
                });
                for (const [width, height] of sizes) {
                    await probe.setViewportSize({ width, height });
                    for (const phase of ['off', 'waiting', 'connecting', 'failed']) {
                        await probe.evaluate(next => {
                            selectVariant('D');
                            state.home = 'big';
                            state.media.status = 'idle';
                            if (state.broadcast !== (next !== 'off')) { toggleBroadcast(); confirmServiceReady(); }
                            if (next === 'connecting') setPhase('connecting');
                            if (next === 'failed') { setPhase('connecting'); setPhase('failed'); }
                            render();
                        }, phase);
                        const sample = await measure();
                        check(sample.outside && sample.tracked <= 1 && sample.restored <= 1 && sample.counterError > 1, width + 'x' + height + ' ' + phase + ' handle alignment ' + JSON.stringify(sample));
                        evidence.push({ width, height, phase, ...sample });
                    }
                }
                await probe.setViewportSize({ width: 800, height: 800 });
                await probe.evaluate(() => { selectVariant('D'); state.home = 'big'; state.media.status = 'idle'; if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); } state.motion.paused = false; render(); syncMotion(); });
                await probe.evaluate(() => new Promise(resolve => { let left = 4; const step = () => { if (--left) requestAnimationFrame(step); else resolve(); }; requestAnimationFrame(step); }));
                const live = await measure();
                await probe.evaluate(() => { state.motion.paused = true; syncMotion(); });
                const paused = await measure();
                check(live.tracked <= 1 && paused.tracked <= 1, 'Native OLED frames and pause keep the handle on the primary center: ' + JSON.stringify({ live, paused }));
                const hidden = await probe.evaluate(() => {
                    const read = () => {
                        resizeCanvas();
                        const handle = document.querySelector('#settings-handle');
                        const box = handle.getBoundingClientRect();
                        return { top: handle.style.top, center: (box.top + box.bottom) / 2, outside: !document.querySelector('#standby-view').contains(handle) };
                    };
                    const result = {};
                    state.media.status = 'ready'; render(); result.ready = read();
                    state.media.status = 'idle'; state.home = 'simple'; render(); result.simple = read();
                    state.home = 'big'; if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); } setPhase('connecting'); setPhase('projecting'); result.projecting = read();
                    state.phase = 'off'; state.broadcast = false; render();
                    return result;
                });
                check(['ready', 'simple', 'projecting'].every(name => hidden[name].top === '' && hidden[name].outside && Math.abs(hidden[name].center - 400) <= 1), 'Hidden D primary restores CSS 50%: ' + JSON.stringify(hidden));
                const others = await probe.evaluate(() => {
                    const read = variant => {
                        selectVariant(variant); resizeCanvas();
                        const handle = document.querySelector('#settings-handle');
                        const box = handle.getBoundingClientRect();
                        return { top: handle.style.top, center: (box.top + box.bottom) / 2 };
                    };
                    const result = { A: read('A'), C: read('C') };
                    selectVariant('D'); render();
                    return result;
                });
                check(others.A.top === '' && others.C.top === '' && Math.abs(others.A.center - 400) <= 1 && Math.abs(others.C.center - 400) <= 1, 'A/C handles stay at 50%: ' + JSON.stringify(others));
                return { cases: evidence.length, live, paused, hidden, others };
            } finally {
                await probe.setViewportSize(viewport);
                await probe.evaluate(previous => { state.variant = previous.variant; state.home = previous.home; state.phase = previous.phase; state.broadcast = previous.broadcast; state.motion.paused = previous.paused; state.media.status = previous.media; render(); resizeCanvas(); syncMotion(); }, saved);
            }
        });
        await run('v40 moon, pill, diamond, brand inset, opaque D fill and header-to-bottom study field', async () => {
            const saved = await probe.evaluate(() => ({ variant: state.variant, paused: state.motion.paused, mode: state.motion.dMode, broadcast: state.broadcast }));
            const viewport = probe.viewportSize();
            try {
                const moon = [];
                for (const preference of ['no-preference', 'reduce']) {
                    await probe.emulateMedia({ reducedMotion: preference });
                    for (const [width, height] of sizes) {
                        await probe.setViewportSize({ width, height });
                        const sample = await probe.evaluate(() => {
                            selectVariant('A');
                            if (state.broadcast) { toggleBroadcast(); confirmServiceReady(); }
                            render(); resizeCanvas();
                            const moonNode = document.querySelector('.a-moon');
                            const sun = document.querySelector('.a-sun');
                            const box = node => { const rect = node.getBoundingClientRect(); return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: rect.width, height: rect.height }; };
                            const night = box(moonNode);
                            const oldTransform = moonNode.getAttribute('transform');
                            moonNode.setAttribute('transform', 'translate(1110 204) scale(1.7) translate(-1110 -204)');
                            const oldWidth = moonNode.getBoundingClientRect().width;
                            moonNode.setAttribute('transform', oldTransform);
                            const seek = fraction => {
                                for (const orbit of document.querySelectorAll('.a-sun-orbit,.a-moon-orbit')) {
                                    for (const animation of orbit.getAnimations()) {
                                        const duration = Number(animation.effect.getComputedTiming().duration);
                                        if (duration) animation.currentTime = duration * fraction;
                                    }
                                }
                            };
                            toggleBroadcast(); confirmServiceReady();
                            seek(1);
                            const dayMoon = box(moonNode), daySun = box(sun);
                            toggleBroadcast(); confirmServiceReady();
                            seek(1);
                            const rect = document.querySelector('.broadcast-frame-a rect');
                            const live = ['x', 'y', 'width', 'height', 'rx'].map(name => rect.getAttribute(name)).join(',');
                            const liveBox = rect.getBoundingClientRect();
                            rect.setAttribute('y', '16'); rect.setAttribute('height', '64'); rect.setAttribute('rx', '32');
                            const oldBox = rect.getBoundingClientRect();
                            rect.setAttribute('y', '13'); rect.setAttribute('height', '70'); rect.setAttribute('rx', '35');
                            return { night, oldWidth, dayMoon, daySun, sunRadius: Number(sun.getAttribute('r')), live, liveHeight: liveBox.height, oldHeight: oldBox.height, liveWidth: liveBox.width, oldWidthPill: oldBox.width,
                                hit: document.querySelector('#broadcast-toggle').getBoundingClientRect().height };
                        });
                        check(sample.sunRadius === 30 && sample.night.left >= -1 && sample.night.right <= width + 1 && sample.night.top >= -1 && sample.night.bottom <= height + 1, preference + ' ' + width + 'x' + height + ' night moon inside');
                        check(sample.night.width / sample.oldWidth >= .78 && sample.night.width / sample.oldWidth <= .90, preference + ' moon is smaller than scale 1.7: ' + sample.night.width / sample.oldWidth);
                        check(sample.dayMoon.left >= width - 1 && sample.daySun.left >= -1 && sample.daySun.right <= width + 1 && sample.daySun.top >= -1 && sample.daySun.bottom <= height + 1, preference + ' day exit/sun');
                        check(sample.live === '7,13,86,70,35' && Math.abs(sample.liveWidth - sample.oldWidthPill) <= 1 && sample.liveHeight / sample.oldHeight >= 1.08 && sample.liveHeight / sample.oldHeight <= 1.12 && sample.hit === 96, 'A pill is taller than the previous v40 rect');
                        moon.push(preference + ':' + width + 'x' + height);
                    }
                }
                await probe.emulateMedia({ reducedMotion: 'no-preference' });
                const diamond = [];
                for (const [width, height] of [[568, 320], [800, 800], [1440, 1000]]) {
                    await probe.setViewportSize({ width, height });
                    const sample = await probe.evaluate(() => {
                        selectVariant('D'); state.motion.paused = true; render(); resizeCanvas(); layoutDCurrents();
                        const node = document.querySelector('#d-corner-diamond');
                        const main = [...document.querySelectorAll('#hardline-scene > svg:not(.d-motion-overlay) path')].some(path => path.getAttribute('d') === 'M340 720 372 752 340 784 308 752Z');
                        node.setAttribute('visibility', 'visible');
                        const stageBox = document.querySelector('#stage').getBoundingClientRect();
                        const receiver = document.querySelector('#standby-view .device').getBoundingClientRect();
                        const x = receiver.left - stageBox.left;
                        const y = receiver.top - stageBox.top;
                        node.setAttribute('d', `M${x + 13} ${y}L${x + 26} ${y + 13}L${x + 13} ${y + 26}L${x} ${y + 13}Z`);
                        const fake = node.getBoundingClientRect();
                        const fakeOverlap = fake.left < receiver.right && fake.right > receiver.left && fake.top < receiver.bottom && fake.bottom > receiver.top;
                        layoutDCurrents();
                        const placed = node.getBoundingClientRect();
                        const overlaps = selector => {
                            const other = document.querySelector(selector);
                            if (!other || !other.getClientRects().length) return false;
                            const rect = other.getBoundingClientRect();
                            return placed.left < rect.right && placed.right > rect.left && placed.top < rect.bottom && placed.bottom > rect.top;
                        };
                        return { hidden: node.getAttribute('visibility') === 'hidden', size: Math.max(placed.width, placed.height), main, fakeOverlap,
                            receiver: overlaps('#standby-view .device'), dock: overlaps('#broadcast-dock'), pencil: overlaps('#toggle-d-review') };
                    });
                    check(!sample.main && sample.fakeOverlap, width + ' removed main diamond; overlap control fires');
                    if (width <= 600) check(sample.hidden, width + ' decorative diamond hides');
                    else check(!sample.hidden && sample.size >= 24 && sample.size <= 28 && !sample.receiver && !sample.dock && !sample.pencil, width + ' diamond anchor ' + JSON.stringify(sample));
                    diamond.push(sample.size);
                }
                await probe.setViewportSize({ width: 896, height: 592 });
                const brand = await probe.evaluate(() => {
                    selectVariant('D'); render(); resizeCanvas();
                    const node = document.querySelector('#standby-view .brand');
                    const mark = node.querySelector('.brand-mark').getBoundingClientRect();
                    const name = node.querySelector('.brand-name').getBoundingClientRect();
                    const before = node.getBoundingClientRect();
                    const previous = node.getAttribute('style');
                    node.style.paddingRight = '0px';
                    const after = node.getBoundingClientRect();
                    const markAfter = node.querySelector('.brand-mark').getBoundingClientRect();
                    if (previous === null) node.removeAttribute('style'); else node.setAttribute('style', previous);
                    return { gain: before.width - after.width, content: Math.abs(mark.left - markAfter.left), name: name.left };
                });
                check(Math.abs(brand.gain - 12) <= 1 && brand.content < .5, 'Brand right inset is 12px without moving content: ' + JSON.stringify(brand));
                const fills = await probe.evaluate(() => {
                    const read = variant => {
                        selectVariant(variant); render();
                        const button = document.querySelector('#broadcast-toggle').getBoundingClientRect();
                        if (variant === 'C') return { points: document.querySelector('.broadcast-frame-c polygon').getAttribute('points'), hit: button.height };
                        const path = document.querySelector('.broadcast-frame-d path');
                        const paint = getComputedStyle(path);
                        return { d: path.getAttribute('d'), fill: paint.fill.replace(/\s+/g, ''), opacity: Number(paint.fillOpacity), hit: button.height };
                    };
                    return { C: read('C'), D: read('D') };
                });
                check(fills.C.hit === 96 && fills.D.hit === 84 && fills.D.d === 'M34 18H86V58L62 82H10V42Z' && fills.D.fill === 'rgb(38,42,40)' && fills.D.opacity === 1, 'C/D art unchanged; D fill opaque: ' + JSON.stringify(fills));
                await probe.setViewportSize({ width: 800, height: 800 });
                const blocked = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                await probe.evaluate(() => { document.querySelector('.broadcast-frame-d path').style.fill = 'none'; document.querySelector('.broadcast-frame-d path').style.fillOpacity = '0'; });
                const exposedShot = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                const under = await probe.evaluate(() => {
                    const button = document.querySelector('#broadcast-toggle').getBoundingClientRect();
                    const plate = document.createElement('div');
                    plate.id = 'v40-fill-magenta';
                    plate.style.cssText = 'position:fixed;z-index:24;background:#f0f;left:' + button.left + 'px;top:' + button.top + 'px;width:' + button.width + 'px;height:' + button.height + 'px';
                    document.body.append(plate);
                    return { left: button.left, top: button.top, right: button.right, bottom: button.bottom };
                });
                const exposed = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                await probe.evaluate(() => {
                    document.querySelector('#v40-fill-magenta').remove();
                    const path = document.querySelector('.broadcast-frame-d path');
                    path.style.removeProperty('fill'); path.style.removeProperty('fill-opacity');
                });
                const qualified = await probe.evaluate(async ({ blockedPng, exposedPng, rect }) => {
                    const decode = async data => window.__v36DecodePng(data);
                    const normal = await decode(blockedPng), open = await decode(exposedPng);
                    let qualified = 0, maxDelta = 0;
                    for (let y = Math.floor(rect.top) + 8; y < Math.ceil(rect.bottom) - 8; y += 1) {
                        for (let x = Math.floor(rect.left) + 8; x < Math.ceil(rect.right) - 8; x += 1) {
                            const index = (y * normal.width + x) * 4;
                            const delta = Math.max(Math.abs(normal.pixels[index] - 38), Math.abs(normal.pixels[index + 1] - 42), Math.abs(normal.pixels[index + 2] - 40));
                            const magenta = open.pixels[index] > 180 && open.pixels[index + 2] > 180 && open.pixels[index + 1] < 80;
                            const bright = normal.pixels[index] > 180 && normal.pixels[index + 1] > 180 && normal.pixels[index + 2] > 180;
                            if (!bright && magenta && delta <= 40) { qualified += 1; maxDelta = Math.max(maxDelta, delta); }
                        }
                    }
                    return { qualified, maxDelta };
                }, { blockedPng: blocked.toString('base64'), exposedPng: exposed.toString('base64'), rect: under });
                check(qualified.qualified >= 40 && qualified.maxDelta <= 3, 'D fill blocks magenta: ' + JSON.stringify(qualified));
                const fields = [];
                for (const [width, height] of [[800, 800], [568, 320]]) {
                    await probe.setViewportSize({ width, height });
                    const sample = await probe.evaluate(async () => {
                        selectVariant('D');
                        state.home = 'big'; state.phase = 'off'; state.broadcast = false; state.media.status = 'idle';
                        state.motion.dMode = 'study-118-section-extrusion'; state.motion.paused = true; state.motion.hidden = false;
                        render(); resizeCanvas();
                        const headerY = createDStudyFrame(0).field.top.y;
                        const sampleBand = () => {
                            const frame = createDStudyFrame(0);
                            const context = studyCanvas.getContext('2d');
                            const ratio = studyPixelRatio;
                            const brand = document.querySelector('#standby-view .brand').getBoundingClientRect();
                            const x0 = Math.max(0, Math.floor(brand.right + 10));
                            const x1 = Math.floor(canvasWidth / 2);
                            let above = 0;
                            const yMax = Math.max(0, Math.floor(headerY * ratio));
                            for (let y = 0; y < yMax; y += 2) {
                                const row = context.getImageData(Math.floor(x0 * ratio), y, Math.max(1, Math.floor((x1 - x0) * ratio)), 1).data;
                                for (let index = 3; index < row.length; index += 8) if (row[index] > 0) above += 1;
                            }
                            const y0 = Math.floor((canvasHeight - 16) * ratio);
                            const strip = context.getImageData(0, y0, studyCanvas.width, Math.max(1, studyCanvas.height - y0)).data;
                            let bottom = 0, signature = 0;
                            for (let index = 3; index < strip.length; index += 32) {
                                if (strip[index] > 0) bottom += 1;
                                signature = (signature + strip[index] * (index + 1)) % 1000003;
                            }
                            return { above, bottom, signature, top: frame.field.top.y, end: frame.field.bottom.y + frame.field.bottom.height, height: frame.height };
                        };
                        drawDMotion(0);
                        const native = sampleBand();
                        drawDMotion(13);
                        const moved = sampleBand();
                        const nativeFrame = createDStudyFrame;
                        let old;
                        try {
                            createDStudyFrame = time => {
                                const frame = nativeFrame(time);
                                const band = centerInformation.getBoundingClientRect();
                                const bounds = stage.getBoundingClientRect();
                                const upper = Math.max(0, Math.min(canvasHeight, band.top - bounds.top));
                                const lower = Math.max(0, Math.min(canvasHeight, band.bottom - bounds.top));
                                const dock = document.querySelector('#broadcast-dock').getBoundingClientRect();
                                const dockTop = dock.width && dock.height ? dock.top - bounds.top : canvasHeight;
                                const fieldBottom = Math.min(canvasHeight - 12, dockTop - 12);
                                frame.field = {
                                    top: { x: 14, y: 14, width: Math.max(0, canvasWidth - 28), height: Math.max(0, upper - 28) },
                                    bottom: { x: 14, y: lower + 12, width: Math.max(0, canvasWidth - 28), height: Math.max(0, fieldBottom - lower - 12) }
                                };
                                return frame;
                            };
                            drawDMotion(0);
                            old = sampleBand();
                        } finally { createDStudyFrame = nativeFrame; }
                        drawDMotion(0);
                        const restored = sampleBand();
                        return { native, moved, old, restored };
                    });
                    check(sample.native.above === 0 && sample.native.bottom > 0 && sample.moved.signature !== sample.native.signature, width + ' header-clean live bottom mesh ' + JSON.stringify(sample.native) + ' moved ' + JSON.stringify(sample.moved));
                    check(Math.abs(sample.native.end - sample.native.height) <= 1, width + ' field reaches the canvas bottom');
                    if (width === 800) check(sample.old.above > 0 && sample.old.bottom === 0, 'Old field bounds reproduce the cutoff: ' + JSON.stringify(sample.old));
                    check(sample.restored.above === 0 && sample.restored.bottom > 0, width + ' restore keeps the live field: ' + JSON.stringify(sample.restored));
                    fields.push(sample.native);
                }
                const wave = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                await probe.evaluate(() => drawDMotion(13));
                const waveLater = await probe.screenshot({ type: 'png', scale: 'css', animations: 'allow', timeout: 3000 });
                const waveDelta = await probe.evaluate(async ({ first, second }) => {
                    const before = await window.__v36DecodePng(first), after = await window.__v36DecodePng(second);
                    let changed = 0;
                    for (let y = before.height - 36; y < before.height; y += 1) {
                        for (let x = 0; x < before.width; x += 2) {
                            if (Math.abs(x - before.width / 2) < 70) continue;
                            const index = (y * before.width + x) * 4;
                            const delta = Math.abs(before.pixels[index] - after.pixels[index]) + Math.abs(before.pixels[index + 1] - after.pixels[index + 1]) + Math.abs(before.pixels[index + 2] - after.pixels[index + 2]);
                            if (delta > 3) changed += 1;
                        }
                    }
                    return changed;
                }, { first: wave.toString('base64'), second: waveLater.toString('base64') });
                check(waveDelta >= 8, 'Bottom study mesh must change in the actual PNG, not only the raw canvas: ' + waveDelta);
                return { moon: moon.length, diamond, brand, fills, fields, qualified, waveDelta };
            } finally {
                await probe.emulateMedia({ reducedMotion: 'no-preference' });
                await probe.setViewportSize(viewport);
                await probe.evaluate(previous => {
                    const path = document.querySelector('.broadcast-frame-d path');
                    if (path) { path.style.removeProperty('fill'); path.style.removeProperty('fill-opacity'); }
                    document.querySelector('#v40-fill-magenta')?.remove();
                    const rect = document.querySelector('.broadcast-frame-a rect');
                    if (rect) { rect.setAttribute('y', '13'); rect.setAttribute('height', '70'); rect.setAttribute('rx', '35'); }
                    const moonNode = document.querySelector('.a-moon');
                    if (moonNode && !moonNode.getAttribute('transform').includes('1.4074074074')) moonNode.setAttribute('transform', 'translate(1110 204) scale(1.4074074074) translate(-1110 -204)');
                    state.variant = previous.variant; state.motion.paused = previous.paused; state.motion.dMode = previous.mode; state.broadcast = previous.broadcast;
                    if (!state.broadcast) state.phase = 'off';
                    render(); resizeCanvas(); syncMotion();
                }, saved);
            }
        });
        await run('v40 marquee entry, A failed bang, D fade latest-wins', async () => {
            const wide = [];
            for (const [width, height] of [[1920, 1080], [2560, 720]]) {
                await probe.setViewportSize({ width, height });
                const sample = await probe.evaluate(async () => {
                    selectVariant('D');
                    if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); }
                    if (state.phase !== 'waiting') setPhase('waiting');
                    render(); resizeCanvas();
                    await window.__v36Frames(220);
                    const windowNode = document.querySelector('.e-channel--lower .e-stream-window');
                    const track = windowNode.querySelector('.e-stream-track');
                    const animation = track.getAnimations().find(item => item.effect.getTiming().iterations === Infinity);
                    if (!animation) throw Error('lower marquee animation missing');
                    const firstGlyph = copy => {
                        const walker = document.createTreeWalker(copy, NodeFilter.SHOW_TEXT);
                        walker.nextNode();
                        const range = document.createRange();
                        range.setStart(walker.currentNode, 0);
                        range.setEnd(walker.currentNode, 1);
                        return range.getBoundingClientRect();
                    };
                    const copies = [...track.querySelectorAll('.e-copy')];
                    const atStart = fraction => {
                        animation.currentTime = Number(animation.effect.getComputedTiming().duration) * fraction;
                        const frame = window.__v36ElementFrame(windowNode);
                        const glyph = firstGlyph(copies[fraction === 0 ? 1 : 0]);
                        const local = frame.inverse.transformPoint({ x: (glyph.left + glyph.right) / 2, y: (glyph.top + glyph.bottom) / 2 });
                        return { localX: local.x, localY: local.y, frameWidth: frame.width };
                    };
                    const entry = atStart(0);
                    const seam = atStart(1);
                    const previous = windowNode.style.cssText;
                    windowNode.style.left = '50%';
                    windowNode.style.width = '1600px';
                    windowNode.style.transform = 'translateX(-50%)';
                    const counterfactual = windowNode.getBoundingClientRect().left;
                    windowNode.style.cssText = previous;
                    const segmentWidth = parseFloat(getComputedStyle(copies[1].parentElement).width);
                    return { entry, seam, counterfactual, liveLeft: windowNode.getBoundingClientRect().left, segmentWidth,
                        same: copies[0].textContent === copies[1].textContent && copies[0].textContent.length > 20 };
                });
                check(Math.abs(sample.entry.localX) <= 12 && Math.abs(sample.seam.localX) <= 12, width + ' first stroke meets the axis start: ' + JSON.stringify(sample));
                check(sample.segmentWidth > sample.entry.frameWidth && sample.same, width + ' full duplicate string can cross the window');
                check(sample.liveLeft > sample.counterfactual + 8, width + ' start is not the old centered window');
                wide.push(sample.entry.localX);
            }
            await probe.setViewportSize({ width: 1440, height: 1000 });
            const failed = await probe.evaluate(async () => {
                selectVariant('A');
                state.home = 'big';
                if (!state.broadcast) { toggleBroadcast(); confirmServiceReady(); }
                setPhase('failed');
                render();
                await window.__v36Frames(220);
                const warning = document.querySelector('.broadcast-symbol-warning');
                const paint = getComputedStyle(warning);
                const glyph = warning.getBoundingClientRect();
                const button = document.querySelector('#broadcast-toggle').getBoundingClientRect();
                return { symbol: document.querySelector('#broadcast-toggle').dataset.symbol, color: paint.color.replace(/\s+/g, ''), opacity: Number(paint.opacity),
                    inside: glyph.width > 0 && glyph.left >= button.left - 1 && glyph.right <= button.right + 1 && glyph.top >= button.top - 1 && glyph.bottom <= button.bottom + 1 };
            });
            check(failed.symbol === 'warning' && failed.color === 'rgb(255,255,255)' && failed.opacity > .9 && failed.inside, 'A failed bang is white inside the button: ' + JSON.stringify(failed));
            const fade = await probe.evaluate(async () => {
                selectVariant('D');
                state.home = 'big'; state.broadcast = false; state.phase = 'off'; state.motion.paused = false; state.media.status = 'none';
                render();
                const title = document.querySelector('#status-title');
                const read = () => ({ text: title.textContent, opacity: Number(getComputedStyle(title).opacity), phase: document.querySelector('#stage').dataset.phase,
                    play: Number(getComputedStyle(document.querySelector('.broadcast-symbol-play')).opacity),
                    message: Number(getComputedStyle(document.querySelector('.e-status-message[data-status="waiting"]')).opacity) });
                toggleBroadcast(); confirmServiceReady();
                setPhase('waiting');
                await window.__v36Frames(45);
                const mid = read();
                setPhase('failed');
                setPhase('connecting');
                await window.__v36Frames(420);
                const titles = { connecting: phases.connecting.title, failed: phases.failed.title };
                const settled = title.textContent;
                setPhase('failed');
                await window.__v36Frames(420);
                return { mid, settled, later: title.textContent, titles, phase: document.querySelector('#stage').dataset.phase };
            });
            check(fade.mid.play > .05 && fade.mid.play < .98 && fade.mid.opacity > .05 && fade.mid.opacity < .98 && fade.mid.message > .05 && fade.mid.message < .98, 'D glyph, center and status share a visible mid fade: ' + JSON.stringify(fade.mid));
            check(fade.settled === fade.titles.connecting && fade.later === fade.titles.failed && fade.settled !== fade.later, 'rapid failed then connecting keeps the latest title');
            return { wide, failed: failed.symbol, fade: fade.phase };
        });
        await run('D upper band inner breathing', async () => {
            const phases = ['off', 'waiting', 'connecting', 'failed'];
            const evidence = [];
            for (const [width, height] of sizes) {
                await probe.setViewportSize({ width, height });
                const sample = await probe.evaluate(phaseList => {
                    const box = node => {
                        const rect = node.getBoundingClientRect();
                        return [rect.x, rect.y, rect.width, rect.height];
                    };
                    const centerOf = node => {
                        const rect = node.getBoundingClientRect();
                        return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
                    };
                    const maxDelta = (left, right) => Math.max(...left.map((value, index) => Math.abs(value - right[index])));
                    const measure = () => {
                        const upper = document.querySelector('.e-channel--upper');
                        const lower = document.querySelector('.e-channel--lower');
                        const streamWindow = upper.querySelector('.e-stream-window');
                        const rows = [...streamWindow.children].filter(node => node.matches('.e-stream-row'));
                        const frame = window.__v36ElementFrame(streamWindow);
                        const localTop = row => frame.inverse.transformPoint(window.__v36ElementFrame(row).points[0]).y;
                        const localBottom = row => frame.height - frame.inverse.transformPoint(window.__v36ElementFrame(row).points[3]).y;
                        const copy = rows[0].querySelector('.e-copy');
                        const range = document.createRange();
                        range.selectNodeContents(copy);
                        const rangeRect = range.getBoundingClientRect();
                        const upperCss = getComputedStyle(upper);
                        const windowCss = getComputedStyle(streamWindow);
                        const statusCss = getComputedStyle(rows[2]);
                        return {
                            height: parseFloat(upperCss.height),
                            align: windowCss.alignContent,
                            gap: parseFloat(windowCss.rowGap || windowCss.gap),
                            rowHeights: rows.map(row => row.offsetHeight),
                            rowGaps: [1, 2].map(index => rows[index].offsetTop - rows[index - 1].offsetTop - rows[index - 1].offsetHeight),
                            offsetTop: rows[0].offsetTop,
                            offsetBottom: streamWindow.clientHeight - rows[2].offsetTop - rows[2].offsetHeight,
                            localTop: localTop(rows[0]),
                            localBottom: localBottom(rows[2]),
                            rangeWidth: rangeRect.width,
                            rangeHeight: rangeRect.height,
                            rowScreen: rows.map(box),
                            upperCenter: centerOf(upper),
                            lowerBox: box(lower),
                            lowerHeight: parseFloat(getComputedStyle(lower).height),
                            lowerRows: lower.querySelectorAll('.e-stream-row').length,
                            nameFont: parseFloat(getComputedStyle(rows[0]).fontSize),
                            statusFont: parseFloat(statusCss.fontSize),
                            statusLine: parseFloat(statusCss.lineHeight),
                            decorDuration: getComputedStyle(rows[0].querySelector('.e-stream-track')).animationDuration,
                            lowerDuration: getComputedStyle(lower.querySelector('.e-stream-track')).animationDuration
                        };
                    };
                    const core = document.querySelector('#hardline-scene .x-core');
                    const stack = {
                        text: Number(getComputedStyle(document.querySelector('#e-scene')).zIndex),
                        x: Number(getComputedStyle(core.ownerSVGElement).zIndex)
                    };
                    return phaseList.map(phase => {
                        state.variant = 'D';
                        state.home = 'big';
                        state.motion.hidden = false;
                        state.broadcast = phase !== 'off';
                        state.phase = phase;
                        render();
                        const upper = document.querySelector('.e-channel--upper');
                        const beforeStyle = upper.getAttribute('style');
                        const before = measure();
                        let during;
                        try {
                            upper.style.setProperty('--e-channel-height', '64px');
                            during = measure();
                        } finally {
                            if (beforeStyle === null) upper.removeAttribute('style');
                            else upper.setAttribute('style', beforeStyle);
                        }
                        const restored = measure();
                        const rowDelta = Math.max(...before.rowScreen.map((rect, index) => maxDelta(rect, during.rowScreen[index])));
                        return {
                            phase, stack, before, during, restored, rowDelta,
                            centerDelta: Math.hypot(before.upperCenter.x - during.upperCenter.x, before.upperCenter.y - during.upperCenter.y),
                            lowerDelta: maxDelta(before.lowerBox, during.lowerBox),
                            beforeStyle, afterStyle: upper.getAttribute('style')
                        };
                    });
                }, phases);
                check(sample[0].stack.text === 3 && sample[0].stack.x === 4, width + 'x' + height + ': opaque X stays above the marquee');
                for (const row of sample) {
                    const label = width + 'x' + height + ' ' + row.phase;
                    const live = row.before;
                    check(live.height === 80 && live.align === 'center' && live.gap === 3, label + ': upper height 80, centered, gap 3');
                    check(live.rowHeights.join(',') === '16,16,24' && live.rowGaps.every(gap => gap === 3), label + ': row boxes stay 16/16/24');
                    check(live.offsetTop >= 7.5 && live.offsetTop <= 8.5 && live.offsetBottom >= 7.5 && live.offsetBottom <= 8.5, label + ': local inset ' + live.offsetTop + '/' + live.offsetBottom);
                    check(live.localTop >= 7.5 && live.localBottom >= 7.5 && Math.abs(live.localTop - live.offsetTop) <= 1.5 && Math.abs(live.localBottom - live.offsetBottom) <= 1.5, label + ': oriented row quad inset ' + live.localTop + '/' + live.localBottom);
                    check(live.rangeWidth > 0 && live.rangeHeight > 0, label + ': first-row Range has glyph bounds');
                    check(live.lowerHeight === 52 && live.lowerRows === 2, label + ': lower band stays 52');
                    check(Math.abs(live.nameFont - 11) < .01 && Math.abs(live.statusFont - 14.6667) < .001 && live.statusLine === 24, label + ': type stays 11px and status 12px+2pt/24');
                    check(live.decorDuration === '240s' && live.lowerDuration === '360s', label + ': marquee durations stay 240s/360s');
                    check(row.during.height === 64 && row.during.offsetTop <= .6 && row.during.offsetBottom <= .6 && row.during.offsetTop < 8, label + ': inline height 64 restores a 0 inset and fails the 8px contract: ' + row.during.offsetTop);
                    check(row.during.rowHeights.join(',') === '16,16,24' && row.during.rowGaps.every(gap => gap === 3) && row.during.rangeWidth > 0, label + ': old height keeps the same rows and a live Range');
                    check(row.rowDelta <= .05 && row.centerDelta <= .05 && row.lowerDelta <= .05, label + ': rows, upper center and lower box stay put: ' + JSON.stringify({ row: row.rowDelta, center: row.centerDelta, lower: row.lowerDelta }));
                    check(row.restored.height === 80 && row.restored.offsetTop >= 7.5 && row.restored.offsetBottom >= 7.5 && row.afterStyle === row.beforeStyle, label + ': restored 80px inset and the exact inline style captured before the override');
                }
                const tops = sample.map(row => row.before.offsetTop);
                check(Math.max(...tops) - Math.min(...tops) <= .5, width + 'x' + height + ': phase inset spread ' + tops.join(','));
                evidence.push({ width, height, top: tops[0], bottom: sample[0].before.offsetBottom, redTop: sample[0].during.offsetTop, rowDelta: sample[0].rowDelta, centerDelta: sample[0].centerDelta });
            }
            return evidence;
        });
        afterHash = await reviewHash();
        check(beforeHash === protectedHash && afterHash === protectedHash, 'Protected reviews/notes SHA-256 changed');
    } catch (error) {
        failures.push({ name: 'setup/audit', message: String(error.message || error) });
    } finally {
        await context.close();
    }
    return { ok: !failures.length && !errors.length && !blockedWrites.length, elapsedMs: Date.now() - started,
        results, failures, errors, blockedWrites, beforeHash, afterHash, ownedContexts: 1, closedContexts: 1 };
}
