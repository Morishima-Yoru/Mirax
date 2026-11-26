(() => {
    'use strict';

    // Two diagonal fronts rewire 3-rhomb hexes. Stripe cells stay subdivided.
    const HEX_W = Math.sqrt(3);

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

    function chords(draw, rect, angle) {
        const ux = Math.cos(angle);
        const uy = Math.sin(angle);
        const px = -uy;
        const py = ux;
        for (const t of [0.28, 0.72]) {
            const ox = rect.x + rect.w * 0.5 + px * rect.w * (t - 0.5);
            const oy = rect.y + rect.h * 0.5 + py * rect.h * (t - 0.5);
            draw.edge(ox - ux * rect.w, oy - uy * rect.h, ox + ux * rect.w, oy + uy * rect.h, 0.08);
        }
    }

    function subdivide(draw, a, b, d, c, alpha) {
        const mid = (p, q) => [(p[0] + q[0]) / 2, (p[1] + q[1]) / 2];
        const ab = mid(a, b);
        const bd = mid(b, d);
        const dc = mid(d, c);
        const ca = mid(c, a);
        const rc = [(a[0] + b[0] + d[0] + c[0]) / 4, (a[1] + b[1] + d[1] + c[1]) / 4];
        const segs = [[a, ab], [ab, b], [b, bd], [bd, d], [d, dc], [dc, c], [c, ca], [ca, a], [ab, rc], [bd, rc], [dc, rc], [ca, rc]];
        for (const [p, q] of segs) draw.edge(p[0], p[1], q[0], q[1], alpha);
    }

    function emitHex(draw, cx, cy, radius, mode) {
        const verts = [];
        for (let k = 0; k < 6; k += 1) {
            const angle = -Math.PI / 2 + k * Math.PI / 3;
            verts.push([cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius]);
        }
        for (let k = 0; k < 6; k += 1) {
            draw.edge(verts[k][0], verts[k][1], verts[(k + 1) % 6][0], verts[(k + 1) % 6][1], 0.2);
        }
        if (mode >= 2) {
            const groups = mode === 3 ? [1, 3, 5] : [0, 2, 4];
            for (const k of groups) {
                subdivide(draw, verts[k], verts[(k + 1) % 6], verts[(k + 2) % 6], [cx, cy], 0.4);
            }
            return;
        }
        const spokes = mode === 1 ? [1, 3, 5] : [0, 2, 4];
        for (const k of spokes) draw.edge(cx, cy, verts[k][0], verts[k][1], 0.52);
    }

    function paintField(draw, rect, time, radius, speedA, speedB, slope) {
        if (rect.w < 12 || rect.h < 12) return;
        chords(draw, rect, slope);
        const hexW = HEX_W * radius;
        const hexH = 1.5 * radius;
        const q1 = Math.ceil(rect.w / hexW) + 1;
        const r1 = Math.ceil(rect.h / hexH) + 1;
        const period = q1 + r1 + 4;
        const frontA = (time * speedA) % period;
        const frontB = (time * speedB + period * 0.37) % period;
        for (let r = -1; r <= r1; r += 1) {
            for (let q = -1; q <= q1; q += 1) {
                const cx = rect.x + hexW * (q + ((r & 1) ? 0.5 : 0)) + radius;
                const cy = rect.y + hexH * r + radius;
                if (cx < rect.x - radius || cx > rect.x + rect.w + radius || cy < rect.y - radius * 0.25 || cy > rect.y + rect.h + radius * 0.25) continue;
                const s1 = q + 1 + (r + 1);
                const s2 = (q + 1) * slope + (r1 - r);
                const passA = (frontA - s1 + period * 8) % period;
                const passB = (frontB - s2 + period * 8) % period;
                const stripe = ((q + r * 2) % 5 + 5) % 5 === 0;
                let mode = stripe ? 2 : 0;
                if (passA < 1.15) mode = stripe || passB < 1.15 ? 3 : 1;
                else if (passB < 1.15) mode = 2;
                emitHex(draw, cx, cy, radius, mode);
            }
        }
    }

    window.miraxDStudies.set('study-189-quasi-phase-stream', {
        id: 'study-189-quasi-phase-stream',
        title: '189 準晶相位流',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-36-quasicrystal',
        scope: 'field',
        description: '189｜上下場連續共邊菱格以不同尺度鋪過可見區，兩道斜向相位前緣只改寫沿途三菱面的鄰接與細分，其餘格面保持穩定。',
        draw(context, frame) {
            const { narrow, primary, secondary } = layout(frame);
            const draw = makeDrawer(context);
            const radius = narrow
                ? Math.max(16, Math.min(22, primary.h * 0.42))
                : Math.max(26, Math.min(42, Math.min(primary.w / 14, primary.h / 5)));
            paintField(draw, primary, frame.time, radius, 0.92, 0.57, 0.62);
            if (secondary) {
                const lower = narrow
                    ? Math.max(18, Math.min(28, secondary.h * 0.28))
                    : Math.max(30, Math.min(48, radius * 1.28));
                paintField(draw, secondary, frame.time, lower, 0.48, 0.81, -0.85);
            }
            draw.flush();
        }
    });
})();
