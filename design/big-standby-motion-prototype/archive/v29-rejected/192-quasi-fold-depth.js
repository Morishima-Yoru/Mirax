(() => {
    'use strict';

    // Near and far hex bands share edges inside each layer. Stage 2 folds hide far edges.
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

    function verts(center, radius) {
        const points = [];
        for (let k = 0; k < 6; k += 1) {
            const angle = -Math.PI / 2 + k * Math.PI / 3;
            points.push([center[0] + Math.cos(angle) * radius, center[1] + Math.sin(angle) * radius]);
        }
        return points;
    }

    function inside(poly, x, y) {
        let hit = false;
        for (let i = 0, j = poly.length - 1; i < poly.length; j = i, i += 1) {
            const yi = poly[i][1];
            const yj = poly[j][1];
            const xi = poly[i][0];
            const xj = poly[j][0];
            if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) hit = !hit;
        }
        return hit;
    }

    function spokes(draw, center, points, indexes, alpha) {
        for (const index of indexes) draw.edge(center[0], center[1], points[index][0], points[index][1], alpha);
    }

    function paintBand(draw, rect, time, narrow, depth, yBias) {
        if (rect.w < 12 || rect.h < 12) return;
        const count = narrow ? 5 : Math.max(6, Math.min(11, Math.floor(rect.w / 86)));
        const radius = Math.min(narrow ? rect.h * 0.33 : rect.h * 0.2, (rect.w * 0.78) / (count * Math.sqrt(3)));
        const y = rect.y + rect.h * yBias;
        const x0 = rect.x + (rect.w - count * radius * Math.sqrt(3)) / 2;
        const vanishX = rect.x + rect.w * 0.5;
        const vanishY = rect.y + rect.h * 0.05;
        const step = Math.floor(time * 0.97);
        const index = step % count;
        const stage = Math.floor(step / count) % 3;
        draw.edge(rect.x + rect.w * 0.08, y, vanishX, vanishY, 0.08);
        draw.edge(rect.x + rect.w * 0.92, y, vanishX, vanishY, 0.08);
        draw.edge(rect.x, rect.y + rect.h * 0.82, rect.x + rect.w, rect.y + rect.h * 0.82, 0.07);
        const project = (point) => [
            point[0] + (vanishX - point[0]) * depth,
            point[1] + (vanishY - point[1]) * depth
        ];
        for (let i = 0; i < count; i += 1) {
            const nearCenter = [x0 + (i + 0.5) * radius * Math.sqrt(3), y];
            const farCenter = project(nearCenter);
            const near = verts(nearCenter, radius);
            const far = verts(farCenter, radius * (1 - depth));
            const mode = i === index ? stage : 0;
            for (let k = 0; k < 6; k += 1) {
                const a = far[k];
                const b = far[(k + 1) % 6];
                if (mode === 2 && inside(near, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2)) continue;
                draw.edge(a[0], a[1], b[0], b[1], 0.2);
            }
            if (mode === 0) spokes(draw, farCenter, far, [0, 2, 4], 0.28);
            if (mode === 1) spokes(draw, farCenter, far, [1, 3, 5], 0.46);
            for (const k of [5, 0, 1]) draw.edge(near[k][0], near[k][1], far[k][0], far[k][1], 0.26);
            for (let k = 0; k < 6; k += 1) {
                draw.edge(near[k][0], near[k][1], near[(k + 1) % 6][0], near[(k + 1) % 6][1], 0.4);
            }
            if (mode === 0) spokes(draw, nearCenter, near, [0, 2, 4], 0.54);
            if (mode === 1) spokes(draw, nearCenter, near, [1, 3, 5], 0.6);
            if (mode === 2) {
                for (const k of [2, 3, 4]) {
                    const a = near[k];
                    const b = near[(k + 1) % 6];
                    const c = far[(k + 1) % 6];
                    const d = far[k];
                    draw.edge(a[0], a[1], b[0], b[1], 0.62);
                    draw.edge(b[0], b[1], c[0], c[1], 0.36);
                    draw.edge(c[0], c[1], d[0], d[1], 0.24);
                    draw.edge(d[0], d[1], a[0], a[1], 0.36);
                }
            }
        }
    }

    window.miraxDStudies.set('study-192-quasi-fold-depth', {
        id: 'study-192-quasi-fold-depth',
        title: '192 準晶折面縱深',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-36-quasicrystal',
        scope: 'field',
        description: '192｜橫跨場域的菱格帶含前後兩層透視截面，短稜串起對應節點；三面鄰接依序翻轉，近緣遮斷遠緣。',
        draw(context, frame) {
            const { narrow, primary, secondary } = layout(frame);
            const draw = makeDrawer(context);
            paintBand(draw, primary, frame.time, narrow, narrow ? 0.2 : 0.34, narrow ? 0.64 : 0.6);
            if (secondary) paintBand(draw, secondary, frame.time + 3.2, narrow, narrow ? 0.16 : 0.26, narrow ? 0.58 : 0.42);
            draw.flush();
        }
    });
})();
