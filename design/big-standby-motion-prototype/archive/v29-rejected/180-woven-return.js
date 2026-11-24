(() => {
    'use strict';

    const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
    const cycle = (value) => ((value % 1) + 1) % 1;

    function fields(frame) {
        const top = frame.field && frame.field.top;
        const bottom = frame.field && frame.field.bottom;
        return {
            top: top || { x: 14, y: 14, width: Math.max(40, frame.width - 28), height: Math.max(48, frame.height * 0.28) },
            bottom: bottom || { x: 14, y: frame.height * 0.62, width: Math.max(40, frame.width - 28), height: Math.max(36, frame.height * 0.18) }
        };
    }

    function narrowOf(frame) {
        return frame.width < 480 && frame.height > frame.width;
    }

    function bandY(frame, t) {
        const { top } = fields(frame);
        if (narrowOf(frame)) return 64 + 50 * clamp(t, 0, 1);
        return top.y + Math.max(top.height, 1) * (0.22 + 0.55 * clamp(t, 0, 1));
    }

    function stroke(context, points, alpha, width) {
        if (!points || points.length < 2 || !(alpha > 0.004)) return;
        const clean = [];
        for (const p of points) {
            if (!p || !Number.isFinite(p.x) || !Number.isFinite(p.y)) continue;
            const last = clean[clean.length - 1];
            if (!last || Math.hypot(p.x - last.x, p.y - last.y) > 0.25) clean.push(p);
        }
        if (clean.length < 2) return;
        context.beginPath();
        context.moveTo(clean[0].x, clean[0].y);
        for (let i = 1; i < clean.length; i += 1) context.lineTo(clean[i].x, clean[i].y);
        context.strokeStyle = `rgba(224,232,230,${clamp(alpha, 0, 1)})`;
        context.lineWidth = width;
        context.stroke();
    }

    function measure(points) {
        const seg = [];
        let total = 0;
        for (let i = 0; i < points.length - 1; i += 1) {
            const a = points[i];
            const b = points[i + 1];
            const len = Math.hypot(b.x - a.x, b.y - a.y);
            if (len < 0.2) continue;
            seg.push({ a, b, start: total, len });
            total += len;
        }
        return { seg, total, points };
    }

    function pointAt(meas, distance) {
        if (meas.total < 1) return meas.points[0];
        let d = distance % meas.total;
        if (d < 0) d += meas.total;
        for (const s of meas.seg) {
            if (d <= s.start + s.len || s === meas.seg[meas.seg.length - 1]) {
                const t = clamp((d - s.start) / s.len, 0, 1);
                return { x: s.a.x + (s.b.x - s.a.x) * t, y: s.a.y + (s.b.y - s.a.y) * t };
            }
        }
        return meas.points[0];
    }

    function subpath(meas, from, to) {
        const lo = Math.max(0, Math.min(from, to));
        const hi = Math.min(meas.total, Math.max(from, to));
        const out = [];
        if (hi - lo < 0.4) return out;
        for (const s of meas.seg) {
            const s1 = s.start + s.len;
            if (s1 <= lo || s.start >= hi) continue;
            const t0 = clamp((lo - s.start) / s.len, 0, 1);
            const t1 = clamp((hi - s.start) / s.len, 0, 1);
            const p0 = { x: s.a.x + (s.b.x - s.a.x) * t0, y: s.a.y + (s.b.y - s.a.y) * t0 };
            const p1 = { x: s.a.x + (s.b.x - s.a.x) * t1, y: s.a.y + (s.b.y - s.a.y) * t1 };
            const last = out[out.length - 1];
            if (!last || Math.hypot(last.x - p0.x, last.y - p0.y) > 0.4) out.push(p0);
            out.push(p1);
        }
        return out;
    }

    function paintRange(context, meas, from, to, alpha, width) {
        if (meas.total < 1 || !(to > from)) return;
        const len = Math.min(to - from, meas.total);
        let start = from % meas.total;
        if (start < 0) start += meas.total;
        const end = start + len;
        if (end <= meas.total) stroke(context, subpath(meas, start, end), alpha, width);
        else {
            stroke(context, subpath(meas, start, meas.total), alpha, width);
            stroke(context, subpath(meas, 0, end - meas.total), alpha, width);
        }
    }

    function loopOf(frame) {
        const { top, bottom } = fields(frame);
        const hugs = [];
        for (const arm of [0, 2, 1, 3]) {
            const edges = (frame.visibleEdges || []).filter((edge) => edge.arm === arm && edge.length > 8);
            if (!edges.length) continue;
            const edge = edges.slice().sort((a, b) => b.length - a.length)[0];
            hugs.push({
                x: edge.a.x + (edge.b.x - edge.a.x) * 0.42,
                y: edge.a.y + (edge.b.y - edge.a.y) * 0.42
            });
            hugs.push({
                x: edge.a.x + (edge.b.x - edge.a.x) * 0.78,
                y: edge.a.y + (edge.b.y - edge.a.y) * 0.78
            });
            if (hugs.length >= 4) break;
        }
        hugs.sort((a, b) => a.y - b.y || a.x - b.x);
        const points = [
            { x: top.x + top.width * 0.06, y: bandY(frame, 0.12) },
            { x: top.x + top.width * 0.4, y: bandY(frame, 0.34) },
            { x: top.x + top.width * 0.78, y: bandY(frame, 0.7) },
            ...hugs
        ];
        if (bottom.height > 24) {
            points.push({ x: bottom.x + bottom.width * 0.66, y: bottom.y + Math.min(34, bottom.height * 0.32) });
            points.push({ x: bottom.x + bottom.width * 0.16, y: bottom.y + Math.min(24, bottom.height * 0.22) });
        }
        points.push({ x: top.x + 12, y: bandY(frame, 0.88) });
        return points;
    }

    function offsetStrand(points, phase, base, amp) {
        let cx = 0;
        let cy = 0;
        for (const p of points) { cx += p.x; cy += p.y; }
        cx /= points.length;
        cy /= points.length;
        return points.map((p, index) => {
            const s = index / points.length;
            const behind = cycle(phase - s);
            const wave = behind < 0.2 ? (0.2 - behind) / 0.2 : 0;
            const amount = base + amp * wave;
            const dx = cx - p.x;
            const dy = cy - p.y;
            const len = Math.hypot(dx, dy) || 1;
            return { x: p.x + (dx / len) * amount, y: p.y + (dy / len) * amount };
        });
    }

    window.miraxDStudies.set('study-180-woven-return', {
        id: 'study-180-woven-return',
        sourceId: 'study-74-cathode-weave',
        title: '180 雙軌回路交織',
        category: '第九批 · 歷史回饋',
        description: '兩條細軌貼著 X 臂緣伸進場域，收成仍可讀的偏置閉路，斜接肋把雙軌串起。線距與亮段沿相位前移，軌身保持可見。',
        draw(context, frame) {
            context.lineCap = 'round';
            context.lineJoin = 'miter';
            for (const edge of frame.visibleEdges || []) {
                if (edge.length > 1) stroke(context, [edge.a, edge.b], 0.07, 0.9);
            }
            const ring = loopOf(frame);
            const closed = measure(ring.concat([ring[0]]));
            const count = 32;
            const samples = [];
            for (let i = 0; i < count; i += 1) samples.push(pointAt(closed, (i / count) * closed.total));
            // Spacing bulge and ribs travel with phase. The loop itself does not reverse.
            const phase = cycle(frame.time * 0.06);
            const outer = offsetStrand(samples, phase, 2.5, 5);
            const inner = offsetStrand(samples, phase, 12, 18);
            const outerMeas = measure(outer.concat([outer[0]]));
            const innerMeas = measure(inner.concat([inner[0]]));
            stroke(context, outer.concat([outer[0]]), 0.28, 1.15);
            stroke(context, inner.concat([inner[0]]), 0.2, 1.05);
            const windowLen = outerMeas.total * 0.16;
            const head = phase * outerMeas.total;
            paintRange(context, outerMeas, head - windowLen, head, 0.7, 1.45);
            const innerHead = phase * innerMeas.total;
            paintRange(context, innerMeas, innerHead - innerMeas.total * 0.16, innerHead, 0.55, 1.3);
            const ribs = 7;
            for (let i = 0; i < ribs; i += 1) {
                const s = cycle(i / ribs + phase);
                const behind = cycle(phase - s);
                const a = pointAt(outerMeas, s * outerMeas.total);
                const b = pointAt(innerMeas, cycle(s + 0.02) * innerMeas.total);
                stroke(context, [a, b], behind < 0.16 ? 0.66 : 0.24, behind < 0.16 ? 1.35 : 1);
            }
        }
    });
})();
