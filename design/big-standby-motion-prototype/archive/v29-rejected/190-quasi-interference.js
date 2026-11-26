(() => {
    'use strict';

    // Zigzag seam is the only shared boundary. Boundary rhombs swing; interior rows stay.
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

    function unit(angle, length) {
        return [Math.cos(angle) * length, Math.sin(angle) * length];
    }

    function add(p, v) {
        return [p[0] + v[0], p[1] + v[1]];
    }

    function rhomb(draw, a, b, extrude, alpha) {
        const c = add(b, extrude);
        const d = add(a, extrude);
        draw.edge(a[0], a[1], b[0], b[1], alpha);
        draw.edge(b[0], b[1], c[0], c[1], alpha);
        draw.edge(c[0], c[1], d[0], d[1], alpha);
        draw.edge(d[0], d[1], a[0], a[1], alpha);
    }

    function paintDomains(draw, rect, time, narrow, swap) {
        if (rect.w < 12 || rect.h < 12) return;
        const fine = narrow
            ? Math.max(14, Math.min(18, rect.h * 0.32))
            : Math.max(24, Math.min(40, Math.min(rect.w / 16, rect.h / 5.5)));
        const amp = narrow ? 0.32 : 0.45;
        const s0 = unit(swap ? amp : -amp, fine);
        const s1 = unit(swap ? -amp : amp, fine);
        const pts = [[rect.x - fine, rect.y + rect.h * 0.5]];
        for (let k = 0; k < 96; k += 1) {
            const step = (Math.floor(k / 2) % 2 === 0) ? s0 : s1;
            const prev = pts[pts.length - 1];
            pts.push(add(prev, step));
            if (pts[pts.length - 1][0] > rect.x + rect.w + fine) break;
        }
        const n = pts.length - 1;
        if (n < 4) return;
        const sign = swap ? -1 : 1;
        const extrudeA = unit(sign * 1.75, fine);
        const flipA = unit(sign * 2.45, fine);
        const extrudeB = unit(-sign * 1.75, fine * 2);
        const flipB = unit(-sign * 2.45, fine * 2);
        const frontA = Math.floor(time * 1.15) % n;
        const pairs = Math.floor(n / 2);
        const frontB = Math.floor(time * 0.73 + pairs * 0.41) % Math.max(1, pairs);
        const rowsA = Math.max(1, Math.min(narrow ? 1 : 4, Math.floor((rect.h * 0.46) / (Math.abs(extrudeA[1]) || fine))));
        const rowsB = Math.max(1, Math.min(narrow ? 1 : 2, Math.floor((rect.h * 0.42) / (Math.abs(extrudeB[1]) || (fine * 2)))));
        const seen = (point) => point[0] >= rect.x - fine && point[0] <= rect.x + rect.w + fine
            && point[1] >= rect.y - fine && point[1] <= rect.y + rect.h + fine;
        const edgeY = Math.min(8, rect.h * 0.18);
        draw.edge(rect.x + 8, rect.y + edgeY, rect.x + rect.w - 8, rect.y + edgeY, 0.08);
        draw.edge(rect.x + 8, rect.y + rect.h - edgeY, rect.x + rect.w - 8, rect.y + rect.h - edgeY, 0.08);
        function place(a, b, extrude, alpha) {
            const center = [(a[0] + b[0]) / 2 + extrude[0] / 2, (a[1] + b[1]) / 2 + extrude[1] / 2];
            if (!seen(center)) return;
            rhomb(draw, a, b, extrude, alpha);
        }
        for (let k = 0; k < n; k += 1) {
            const hot = k === frontA;
            place(pts[k], pts[k + 1], hot ? flipA : extrudeA, hot ? 0.58 : 0.24);
            let p0 = add(pts[k], extrudeA);
            let p1 = add(pts[k + 1], extrudeA);
            for (let row = 1; row < rowsA; row += 1) {
                place(p0, p1, extrudeA, 0.16);
                p0 = add(p0, extrudeA);
                p1 = add(p1, extrudeA);
            }
        }
        for (let pair = 0; pair < pairs; pair += 1) {
            const a = pts[pair * 2];
            const b = pts[pair * 2 + 2];
            if (!b) continue;
            const hot = pair === frontB;
            place(a, b, hot ? flipB : extrudeB, hot ? 0.56 : 0.22);
            let p0 = add(a, extrudeB);
            let p1 = add(b, extrudeB);
            for (let row = 1; row < rowsB; row += 1) {
                place(p0, p1, extrudeB, 0.14);
                p0 = add(p0, extrudeB);
                p1 = add(p1, extrudeB);
            }
        }
    }

    window.miraxDStudies.set('study-190-quasi-interference', {
        id: 'study-190-quasi-interference',
        title: '190 雙域相位界',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-36-quasicrystal',
        scope: 'field',
        description: '190｜兩塊方向與尺度不同的菱格域沿曲折共稜相接，兩側前緣錯相重組邊界菱形，域內格面連續，閱讀帶之外才鋪面。',
        draw(context, frame) {
            const { narrow, primary, secondary } = layout(frame);
            const draw = makeDrawer(context);
            paintDomains(draw, primary, frame.time, narrow, false);
            if (secondary) paintDomains(draw, secondary, frame.time + 1.7, narrow, true);
            draw.flush();
        }
    });
})();
