(() => {
    'use strict';

    const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
    const pt = (p) => ({ x: p.x, y: p.y });
    const near = (a, b) => Math.hypot(a.x - b.x, a.y - b.y) < 0.6;

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
        return top.y + Math.max(top.height, 1) * (0.24 + 0.52 * clamp(t, 0, 1));
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
        context.strokeStyle = `rgba(255,255,255,${clamp(alpha, 0, 1)})`;
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
        if (meas.total < 1 || !(to > from) || !(alpha > 0.004)) return;
        const len = Math.min(to - from, meas.total);
        if (len >= meas.total - 0.4) {
            stroke(context, meas.points, alpha, width);
            return;
        }
        let start = from % meas.total;
        if (start < 0) start += meas.total;
        const end = start + len;
        if (end <= meas.total) stroke(context, subpath(meas, start, end), alpha, width);
        else {
            stroke(context, subpath(meas, start, meas.total), alpha, width);
            stroke(context, subpath(meas, 0, end - meas.total), alpha, width);
        }
    }

    function orderEdges(edges) {
        const unused = edges.slice();
        const chains = [];
        while (unused.length) {
            const first = unused.shift();
            const chain = [pt(first.a), pt(first.b)];
            let guard = 0;
            let grew = true;
            while (grew && unused.length && guard < 8) {
                guard += 1;
                grew = false;
                const head = chain[0];
                const tail = chain[chain.length - 1];
                for (let i = 0; i < unused.length; i += 1) {
                    const edge = unused[i];
                    if (near(edge.a, tail)) { chain.push(pt(edge.b)); unused.splice(i, 1); grew = true; break; }
                    if (near(edge.b, tail)) { chain.push(pt(edge.a)); unused.splice(i, 1); grew = true; break; }
                    if (near(edge.b, head)) { chain.unshift(pt(edge.a)); unused.splice(i, 1); grew = true; break; }
                    if (near(edge.a, head)) { chain.unshift(pt(edge.b)); unused.splice(i, 1); grew = true; break; }
                }
            }
            if (chain.length > 1) chains.push(chain);
        }
        return chains;
    }

    function chainsOf(frame) {
        const groups = [[], [], [], []];
        for (const edge of frame.visibleEdges || []) {
            if (edge && edge.length > 1 && edge.arm >= 0 && edge.arm < 4) groups[edge.arm].push(edge);
        }
        return groups.flatMap(orderEdges).filter((chain) => chain.length > 1);
    }

    function boxFor(frame, y) {
        const { top, bottom } = fields(frame);
        return y >= bottom.y - 2 ? bottom : top;
    }

    function clampBox(box, x, y) {
        return {
            x: clamp(x, box.x + 8, box.x + Math.max(20, box.width) - 8),
            y: clamp(y, box.y + 8, box.y + Math.max(20, box.height) - 8)
        };
    }

    function forkLine(frame, corner, variant) {
        const box = boxFor(frame, corner.y);
        const narrowTop = narrowOf(frame) && box.y < frame.height * 0.45;
        const yA = narrowTop ? bandY(frame, variant ? 0.86 : 0.1) : box.y + box.height * (variant ? 0.8 : 0.16);
        const yB = narrowTop ? bandY(frame, variant ? 0.16 : 0.74) : box.y + box.height * (variant ? 0.24 : 0.68);
        const yC = narrowTop ? bandY(frame, 0.46) : box.y + box.height * 0.48;
        return [
            corner,
            clampBox(box, corner.x + box.width * (variant ? -0.08 : 0.07), (corner.y + yC) * 0.5),
            clampBox(box, box.x + box.width * (variant ? 0.2 : 0.8), yA),
            clampBox(box, box.x + box.width * (variant ? 0.58 : 0.34), yC),
            clampBox(box, box.x + box.width * (variant ? 0.4 : 0.12), yB)
        ];
    }

    function cornersOf(points) {
        const list = [];
        for (let i = 1; i < points.length - 1; i += 1) {
            const a = points[i - 1];
            const p = points[i];
            const b = points[i + 1];
            const v1x = p.x - a.x;
            const v1y = p.y - a.y;
            const v2x = b.x - p.x;
            const v2y = b.y - p.y;
            const l1 = Math.hypot(v1x, v1y);
            const l2 = Math.hypot(v2x, v2y);
            if (l1 < 6 || l2 < 6 || p.x < 48) continue;
            const dot = clamp((v1x * v2x + v1y * v2y) / (l1 * l2), -1, 1);
            const turn = Math.acos(dot);
            if (turn < 0.38) continue;
            if (p.x < 0 || p.x > 10000 || p.y < 0) continue;
            list.push({ p, index: i, turn });
        }
        list.sort((a, b) => b.turn - a.turn || a.p.y - b.p.y || a.p.x - b.p.x);
        const picked = [];
        for (const corner of list) {
            if (picked.some((item) => Math.hypot(item.p.x - corner.p.x, item.p.y - corner.p.y) < 28)) continue;
            picked.push(corner);
            if (picked.length === 2) break;
        }
        picked.sort((a, b) => a.p.y - b.p.y || a.p.x - b.p.x);
        return picked;
    }

    function project(meas, point) {
        let best = 0;
        let gap = Infinity;
        let at = point;
        for (const s of meas.seg) {
            const t = clamp(((point.x - s.a.x) * (s.b.x - s.a.x) + (point.y - s.a.y) * (s.b.y - s.a.y)) / (s.len * s.len), 0, 1);
            const q = { x: s.a.x + (s.b.x - s.a.x) * t, y: s.a.y + (s.b.y - s.a.y) * t };
            const dist = Math.hypot(point.x - q.x, point.y - q.y);
            if (dist < gap) {
                gap = dist;
                best = s.start + s.len * t;
                at = q;
            }
        }
        return { along: best, at };
    }

    function upperBranch(frame, corner) {
        const { top } = fields(frame);
        const x0 = clamp(corner.x - top.width * 0.06, top.x + 8, top.x + top.width - 8);
        return [
            corner,
            { x: x0, y: (corner.y + bandY(frame, 0.15)) * 0.5 },
            { x: top.x + top.width * 0.74, y: bandY(frame, 0.08) },
            { x: top.x + top.width * 0.42, y: bandY(frame, 0.5) },
            { x: top.x + top.width * 0.1, y: bandY(frame, 0.92) }
        ];
    }

    function upperSide(frame, corner) {
        const { top } = fields(frame);
        return [
            corner,
            { x: top.x + top.width * 0.18, y: clamp((corner.y + top.y) * 0.5, top.y + 8, top.y + top.height - 8) },
            { x: top.x + 18, y: bandY(frame, 0.62) },
            { x: top.x + top.width * 0.3, y: top.y + Math.max(top.height * 0.82, 20) }
        ];
    }

    function lowerBranch(frame, corner) {
        const { top, bottom } = fields(frame);
        const yLift = bandY(frame, 0.78);
        return [
            corner,
            { x: top.x + top.width * 0.24, y: clamp(corner.y, 8, frame.height - 8) },
            { x: top.x + 18, y: yLift },
            { x: top.x + top.width * 0.16, y: bottom.y + Math.min(28, bottom.height * 0.28) },
            { x: top.x + top.width * 0.48, y: bottom.y + Math.min(46, bottom.height * 0.5) }
        ];
    }

    function lowerSide(frame, corner) {
        const { top, bottom } = fields(frame);
        return [
            corner,
            { x: clamp(corner.x + top.width * 0.08, 8, frame.width - 8), y: clamp(corner.y + 36, 8, frame.height - 8) },
            { x: top.x + top.width * 0.58, y: bottom.y + Math.min(22, bottom.height * 0.22) }
        ];
    }

    function paintBranch(context, points, age, grow, fade) {
        const meas = measure(points);
        stroke(context, points, 0.62, 1.45);
        if (meas.total < 2 || age < 0 || age > fade) return;
        let from = 0;
        let to = 1;
        let alpha = 1;
        if (age < grow) {
            to = age / grow;
        } else {
            const u = (age - grow) / Math.max(0.001, fade - grow);
            from = u;
            alpha = 1 - u;
        }
        stroke(context, subpath(meas, from * meas.total, to * meas.total), alpha, 1.85);
    }

    window.miraxDStudies.set('study-179-cathode-branches', {
        id: 'study-179-cathode-branches',
        sourceId: 'study-17-cathode',
        title: '179 陰極分岔續寫',
        category: '第九批 · 歷史回饋',
        description: '沿可見外緣留連續描線與長殘像。兩個轉角各自接出上場與下場折線，前鋒到點後分向兩條支路，單向長出再由根端退出。',
        draw(context, frame) {
            context.lineCap = 'butt';
            context.lineJoin = 'miter';
            const chains = chainsOf(frame);
            const speed = 130;
            const pool = [];
            for (const points of chains) {
                const meas = measure(points);
                stroke(context, points, 0.3, 1.25);
                if (meas.total < 2) continue;
                // Constant speed along the visible outer edge. The tail does not reverse.
                const head = (frame.time * speed) % meas.total;
                const tail = Math.max(200, meas.total * 0.74);
                const slices = 5;
                for (let i = 0; i < slices; i += 1) {
                    const span = Math.min(tail, meas.total * 0.92);
                    const from = head - span * (slices - i) / slices;
                    const to = head - span * (slices - i - 1) / slices;
                    paintRange(context, meas, from, to, 0.45 + 0.55 * (i + 1) / slices, 1.45 + 0.4 * (i + 1) / slices);
                }
                for (const corner of cornersOf(points)) {
                    const station = project(meas, corner.p);
                    const age = ((head - station.along) % meas.total + meas.total) % meas.total / speed;
                    pool.push({ corner, age, period: meas.total / speed, at: station.at });
                }
            }
            const { top, bottom } = fields(frame);
            const upper = pool.filter((item) => item.corner.p.y <= top.y + top.height + 2)
                .sort((a, b) => b.corner.turn - a.corner.turn);
            const lower = pool.filter((item) => item.corner.p.y >= bottom.y - 2)
                .sort((a, b) => b.corner.turn - a.corner.turn);
            const chosen = [];
            if (upper[0]) chosen.push(upper[0]);
            if (lower[0]) chosen.push(lower[0]);
            for (const item of pool) {
                if (chosen.length >= 2 || chosen.includes(item)) continue;
                chosen.push(item);
            }
            if (!chosen.length && chains[0]) {
                const mid = chains[0][Math.floor(chains[0].length / 2)];
                chosen.push({ corner: { p: mid, turn: 1 }, age: frame.time % 3.2, period: 6, at: mid });
            }
            chosen.slice(0, 2).forEach((item, index) => {
                const fade = Math.min(3.6, item.period * 0.55);
                const grow = Math.min(1.05, fade * 0.38);
                [0, 1].forEach((variant) => {
                    const branch = forkLine(frame, item.corner.p, variant);
                    const line = [item.at, item.corner.p].concat(branch.slice(1));
                    paintBranch(context, line, item.age - variant * 0.24 - index * 0.1, grow, fade * (variant ? 0.84 : 1));
                });
            });
        }

    });
})();
