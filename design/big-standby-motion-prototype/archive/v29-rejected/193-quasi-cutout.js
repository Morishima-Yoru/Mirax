(() => {
    'use strict';

    // Hollow window rides grid axes. Passed cells swap one grid edge for the other diagonal.
    function makeDrawer(context) {
        const edges = new Map();
        return {
            edge(x1, y1, x2, y2, alpha) {
                if (!Number.isFinite(x1) || !Number.isFinite(y1) || !Number.isFinite(x2) || !Number.isFinite(y2)) return;
                const ax = Math.round(x1 * 2) / 2;
                const ay = Math.round(y1 * 2) / 2;
                const bx = Math.round(x2 * 2) / 2;
                const by = Math.round(y2 * 2) / 2;
                if (ax === bx && ay === by) return;
                const left = ax < bx || (ax === bx && ay <= by);
                const key = left ? ax + ',' + ay + '|' + bx + ',' + by : bx + ',' + by + '|' + ax + ',' + ay;
                const prev = edges.get(key);
                if (!prev || alpha > prev[4]) edges.set(key, [ax, ay, bx, by, alpha]);
            },
            flush() {
                const buckets = new Map();
                for (const item of edges.values()) {
                    const bucket = Math.max(1, Math.min(72, Math.round(item[4] * 100)));
                    let list = buckets.get(bucket);
                    if (!list) {
                        list = [];
                        buckets.set(bucket, list);
                    }
                    list.push(item);
                }
                for (const bucket of [...buckets.keys()].sort((a, b) => a - b)) {
                    context.beginPath();
                    context.lineWidth = 1;
                    context.strokeStyle = 'rgba(229,233,237,' + (bucket / 100).toFixed(2) + ')';
                    for (const item of buckets.get(bucket)) {
                        context.moveTo(item[0], item[1]);
                        context.lineTo(item[2], item[3]);
                    }
                    context.stroke();
                }
            }
        };
    }

    function layout(frame) {
        const { width, height, field } = frame;
        const top = field.top;
        const bottom = field.bottom;
        const narrow = width <= 480 && height > width;
        const primary = narrow
            ? { x: top.x + 4, y: Math.max(top.y, 64), w: Math.max(16, top.width - 8), h: Math.max(16, Math.min(top.y + top.height, 114) - Math.max(top.y, 64)) }
            : { x: top.x, y: top.y, w: top.width, h: top.height };
        const secondary = bottom && bottom.height > 24
            ? { x: bottom.x + 4, y: bottom.y + 4, w: Math.max(16, bottom.width - 8), h: Math.max(16, bottom.height - 8) }
            : null;
        return { narrow, primary, secondary };
    }

    function aim(frame, rect) {
        const diamond = frame.diamond || [];
        let x = rect.x + rect.w * 0.72;
        let y = rect.y + rect.h + rect.h;
        if (diamond.length) {
            x = 0;
            y = 0;
            for (const point of diamond) {
                x += point.x;
                y += point.y;
            }
            x /= diamond.length;
            y /= diamond.length;
        }
        const dx = x - (rect.x + rect.w * 0.35);
        const dy = y - (rect.y + rect.h * 0.5);
        const length = Math.hypot(dx, dy) || 1;
        return [dx / length, dy / length];
    }

    function span(rect, origin, v, w) {
        const det = v[0] * w[1] - v[1] * w[0] || 1;
        let iMin = Infinity;
        let iMax = -Infinity;
        let jMin = Infinity;
        let jMax = -Infinity;
        const corners = [
            [rect.x, rect.y],
            [rect.x + rect.w, rect.y],
            [rect.x, rect.y + rect.h],
            [rect.x + rect.w, rect.y + rect.h]
        ];
        for (const [x, y] of corners) {
            const px = x - origin[0];
            const py = y - origin[1];
            const i = (px * w[1] - py * w[0]) / det;
            const j = (py * v[0] - px * v[1]) / det;
            iMin = Math.min(iMin, i);
            iMax = Math.max(iMax, i);
            jMin = Math.min(jMin, j);
            jMax = Math.max(jMax, j);
        }
        return {
            i0: Math.max(-40, Math.floor(iMin) - 1),
            i1: Math.min(80, Math.ceil(iMax) + 1),
            j0: Math.max(-30, Math.floor(jMin) - 1),
            j1: Math.min(48, Math.ceil(jMax) + 1)
        };
    }

    function paintField(draw, rect, frame, time, narrow, scale, turn, phase) {
        if (rect.w < 12 || rect.h < 12) return;
        const size = (narrow
            ? Math.max(16, Math.min(24, Math.min(rect.w / 8, rect.h / 1.8)))
            : Math.max(24, Math.min(46, Math.min(rect.w / 12, rect.h / 4.2)))) * scale;
        const [dx, dy] = aim(frame, rect);
        const angle = Math.atan2(dy, dx) + turn;
        const v = [Math.cos(angle) * size, Math.sin(angle) * size];
        const w = [Math.cos(angle + Math.PI / 3) * size, Math.sin(angle + Math.PI / 3) * size];
        const origin = [rect.x + size * 0.2, rect.y + size * 0.2];
        const bounds = span(rect, origin, v, w);
        const point = (i, j) => [origin[0] + v[0] * i + w[0] * j, origin[1] + v[1] * i + w[1] * j];
        const mid = Math.floor((bounds.i0 + bounds.i1) / 2);
        const period = Math.max(6, Math.min(18, bounds.i1 - bounds.i0 - 4));
        const shift = bounds.i0 + 1 + (Math.floor(time * 0.86 + phase) % period);
        const winI = shift;
        const winJ = bounds.j0 + 1;
        const winW = narrow ? 3 : 4;
        const winH = narrow ? 1 : 2;
        const buried = (i, j) => i > winI && i < winI + winW && j > winJ && j < winJ + winH;
        const coarse = (i) => i < mid;
        draw.edge(rect.x, rect.y + rect.h * 0.22, rect.x + rect.w, rect.y + rect.h * 0.78, 0.08);
        draw.edge(rect.x, rect.y + rect.h * 0.78, rect.x + rect.w, rect.y + rect.h * 0.22, 0.08);
        for (let j = bounds.j0; j < bounds.j1; j += 1) {
            for (let i = bounds.i0; i < bounds.i1; i += 1) {
                const a = point(i, j);
                const b = point(i + 1, j);
                const d = point(i, j + 1);
                if (!(coarse(i) && (i & 1))) {
                    if (!buried(i, j) && !buried(i + 1, j)) draw.edge(a[0], a[1], b[0], b[1], coarse(i) ? 0.16 : 0.28);
                }
                if (!(coarse(i) && (j & 1))) {
                    if (!buried(i, j) && !buried(i, j + 1)) draw.edge(a[0], a[1], d[0], d[1], coarse(i) ? 0.16 : 0.28);
                }
                if (i >= winI - 3 && i < winI && j >= winJ && j < winJ + winH) {
                    const c = point(i + 1, j + 1);
                    draw.edge(a[0], a[1], c[0], c[1], 0.5);
                }
            }
        }
        const outline = [point(winI, winJ), point(winI + winW, winJ), point(winI + winW, winJ + winH), point(winI, winJ + winH)];
        for (let k = 0; k < 4; k += 1) {
            draw.edge(outline[k][0], outline[k][1], outline[(k + 1) % 4][0], outline[(k + 1) % 4][1], 0.64);
        }
    }

    window.miraxDStudies.set('study-193-quasi-cutout', {
        id: 'study-193-quasi-cutout',
        title: '193 截面鑲嵌場',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-51-multi-section',
        scope: 'field',
        description: '193｜上下場大小相間的共邊菱格從Ｘ附近讓出空心斜向截面，截面沿格向單向穿過並順次改寫沿路菱面。',
        draw(context, frame) {
            const { narrow, primary, secondary } = layout(frame);
            const draw = makeDrawer(context);
            paintField(draw, primary, frame, frame.time, narrow, 1, Math.PI / 3, 0);
            if (secondary) paintField(draw, secondary, frame, frame.time, narrow, narrow ? 1.15 : 1.35, -Math.PI / 5, 5);
            draw.flush();
        }
    });
})();
