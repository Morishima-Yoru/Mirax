(() => {
    'use strict';

    const clamp = (value, min, max) => Math.max(min, Math.min(max, value));

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
        return { seg, total };
    }

    function subpath(meas, points, from, to) {
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
        void points;
        return out;
    }

    function edgePoint(frame, arm, which, fallback) {
        const edges = (frame.visibleEdges || []).filter((edge) => edge.arm === arm && edge.length > 1);
        if (!edges.length) return fallback;
        const edge = edges[Math.min(which, edges.length - 1)];
        const t = which % 2 === 0 ? 0.38 : 0.72;
        return { x: edge.a.x + (edge.b.x - edge.a.x) * t, y: edge.a.y + (edge.b.y - edge.a.y) * t };
    }

    function linksOf(frame) {
        const { top, bottom } = fields(frame);
        const y1 = bandY(frame, 0.16);
        const y2 = bandY(frame, 0.86);
        const xL = top.x + top.width * 0.1;
        const xM = top.x + top.width * 0.46;
        const xR = top.x + top.width * 0.76;
        const a0 = edgePoint(frame, 0, 0, { x: frame.width * 0.64, y: frame.height * 0.24 });
        const a0b = edgePoint(frame, 0, 1, { x: frame.width * 0.7, y: frame.height * 0.3 });
        const a1 = edgePoint(frame, 1, 0, { x: Math.min(frame.width - 16, frame.width * 0.84), y: frame.height * 0.26 });
        const a2 = edgePoint(frame, 2, 0, { x: frame.width * 0.66, y: frame.height * 0.62 });
        const a2b = edgePoint(frame, 2, 1, { x: frame.width * 0.58, y: frame.height * 0.74 });
        const a3 = edgePoint(frame, 3, 0, { x: Math.min(frame.width - 12, frame.width * 0.82), y: frame.height * 0.68 });
        const B = { x: xL, y: y1 };
        const C = { x: xR, y: y2 };
        const F = { x: top.x + 16, y: y2 };
        const low = { x: bottom.x + bottom.width * 0.22, y: bottom.y + Math.min(26, bottom.height * 0.3) };
        return [
            [a0, { x: xM, y: y1 }, B],
            [B, { x: (xL + xR) * 0.5, y: (y1 + y2) * 0.5 }, C],
            [C, a0b, a2],
            [a2, { x: xM * 0.55, y: (a2.y + F.y) * 0.5 }, F],
            [F, { x: top.x + top.width * 0.22, y: bandY(frame, 0.48) }, a0],
            [a1, { x: xR, y: y1 }, { x: xR - top.width * 0.08, y: (y1 + a3.y) * 0.5 }, a3],
            [a3, low, { x: F.x + top.width * 0.12, y: bottom.y + Math.min(18, bottom.height * 0.2) }, F],
            [B, { x: xL + top.width * 0.18, y: Math.min(frame.height - 8, (y2 + a2b.y) * 0.5) }, a2b]
        ];
    }

    function paintHop(context, points, age) {
        const grow = 0.62;
        const fade = 1.25;
        if (age < 0 || age > grow + fade) return;
        const meas = measure(points);
        if (meas.total < 1) return;
        const env = age < grow ? 1 : 1 - (age - grow) / fade;
        const head = age < grow ? age / grow : 1;
        stroke(context, points, 0.1 * env, 1);
        stroke(context, subpath(meas, points, 0, head * meas.total), 0.62 * env, 1.4);
    }

    window.miraxDStudies.set('study-182-spark-web', {
        id: 'study-182-spark-web',
        sourceId: 'study-58-cathode-spark',
        title: '182 跳火脈絡網',
        category: '第九批 · 歷史回饋',
        description: '上下場不等長折線與臂緣結成稀疏網，接點清楚。折角電弧沿相鄰連結接力，落點錯開，殘線短暫留下。',
        draw(context, frame) {
            context.lineCap = 'round';
            context.lineJoin = 'round';
            for (const edge of frame.visibleEdges || []) {
                if (edge.length > 1) stroke(context, [edge.a, edge.b], 0.08, 0.95);
            }
            const links = linksOf(frame);
            for (const link of links) {
                stroke(context, link, 0.09, 1);
                if (link.length >= 3) {
                    const joint = link[1];
                    const prev = link[0];
                    const dx = joint.x - prev.x;
                    const dy = joint.y - prev.y;
                    const len = Math.hypot(dx, dy) || 1;
                    stroke(context, [
                        { x: joint.x - dy / len * 8, y: joint.y + dx / len * 8 },
                        { x: joint.x + dy / len * 8, y: joint.y - dx / len * 8 }
                    ], 0.22, 1);
                }
            }
            const chainA = [0, 1, 2, 3, 4];
            const chainB = [5, 6, 7];
            const stepA = 0.7;
            const stepB = 0.86;
            const cycleA = chainA.length * stepA;
            const cycleB = chainB.length * stepB;
            // Each hop runs forward to the next joint, then fades in place.
            const timeA = frame.time % cycleA;
            const timeB = (frame.time + cycleB * 0.46) % cycleB;
            chainA.forEach((index, order) => {
                paintHop(context, links[index], timeA - order * stepA);
                paintHop(context, links[index], timeA - order * stepA + cycleA);
            });
            chainB.forEach((index, order) => {
                paintHop(context, links[index], timeB - order * stepB);
                paintHop(context, links[index], timeB - order * stepB + cycleB);
            });
        }
    });
})();
