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
        return top.y + Math.max(top.height, 1) * (0.24 + 0.5 * clamp(t, 0, 1));
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
        if (!meas.points.length) return { x: 0, y: 0 };
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
        if (meas.total < 1 || !(to > from) || !(alpha > 0.004)) return;
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

    function paintRangeExcept(context, meas, from, to, skips, alpha, width) {
        if (meas.total < 1 || !(to > from)) return;
        let pieces = [{ a: from, b: to }];
        const k0 = Math.floor(from / meas.total) - 1;
        const k1 = Math.floor(to / meas.total) + 1;
        for (const skip of skips) {
            const next = [];
            for (const piece of pieces) {
                let local = [piece];
                for (let k = k0; k <= k1; k += 1) {
                    const ga = skip.a + k * meas.total;
                    const gb = skip.b + k * meas.total;
                    const cut = [];
                    for (const span of local) {
                        if (gb <= span.a || ga >= span.b) { cut.push(span); continue; }
                        if (ga > span.a + 0.3) cut.push({ a: span.a, b: ga });
                        if (gb < span.b - 0.3) cut.push({ a: gb, b: span.b });
                    }
                    local = cut;
                }
                next.push(...local);
            }
            pieces = next;
        }
        for (const piece of pieces) paintRange(context, meas, piece.a, piece.b, alpha, width);
    }

    function orderEdges(edges) {
        const unused = edges.slice();
        const chain = [];
        while (unused.length) {
            const first = unused.shift();
            const part = [pt(first.a), pt(first.b)];
            let guard = 0;
            let grew = true;
            while (grew && unused.length && guard < 8) {
                guard += 1;
                grew = false;
                const head = part[0];
                const tail = part[part.length - 1];
                for (let i = 0; i < unused.length; i += 1) {
                    const edge = unused[i];
                    if (near(edge.a, tail)) { part.push(pt(edge.b)); unused.splice(i, 1); grew = true; break; }
                    if (near(edge.b, tail)) { part.push(pt(edge.a)); unused.splice(i, 1); grew = true; break; }
                    if (near(edge.b, head)) { part.unshift(pt(edge.a)); unused.splice(i, 1); grew = true; break; }
                    if (near(edge.a, head)) { part.unshift(pt(edge.b)); unused.splice(i, 1); grew = true; break; }
                }
            }
            chain.push(...part);
        }
        return chain;
    }

    function armLine(frame, arm) {
        const edges = (frame.visibleEdges || []).filter((edge) => edge.arm === arm && edge.length > 1);
        return orderEdges(edges);
    }

    function quad(a, control, b, count) {
        const points = [];
        for (let i = 0; i <= count; i += 1) {
            const t = i / count;
            const u = 1 - t;
            points.push({
                x: u * u * a.x + 2 * u * t * control.x + t * t * b.x,
                y: u * u * a.y + 2 * u * t * control.y + t * t * b.y
            });
        }
        return points;
    }

    function segHit(a, b, c, d) {
        const rx = b.x - a.x;
        const ry = b.y - a.y;
        const sx = d.x - c.x;
        const sy = d.y - c.y;
        const den = rx * sy - ry * sx;
        if (Math.abs(den) < 1e-4) return null;
        const t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / den;
        const u = ((c.x - a.x) * ry - (c.y - a.y) * rx) / den;
        if (t < 0.02 || t > 0.98 || u < 0.02 || u > 0.98) return null;
        return { x: a.x + rx * t, y: a.y + ry * t, t, u };
    }

    function crossings(first, second) {
        const hits = [];
        for (const sa of first.seg) {
            for (const sb of second.seg) {
                const hit = segHit(sa.a, sa.b, sb.a, sb.b);
                if (!hit) continue;
                hits.push({
                    x: hit.x,
                    y: hit.y,
                    a: sa.start + sa.len * hit.t,
                    b: sb.start + sb.len * hit.u
                });
                if (hits.length >= 12) return hits;
            }
        }
        return hits;
    }

    function behind(head, at, total) {
        if (total < 1) return 0;
        return ((head - at) % total + total) % total;
    }

    function paintTail(context, meas, head, tail, skips, alpha, width) {
        const span = Math.min(tail, meas.total * 0.92);
        const slices = 5;
        for (let i = 0; i < slices; i += 1) {
            const from = head - span * (slices - i) / slices;
            const to = head - span * (slices - i - 1) / slices;
            paintRangeExcept(context, meas, from, to, skips, alpha * (i + 1) / slices, width);
        }
    }

    function pathsOf(frame) {
        const { top, bottom } = fields(frame);
        const y0 = bandY(frame, 0.2);
        const y1 = bandY(frame, 0.78);
        const xm = top.x + top.width * 0.42;
        const ym = (y0 + y1) * 0.5;
        const scale = Math.min(top.width * 0.22, 220);
        const innerA = { x: xm - 0.9 * scale, y: ym + 0.45 * scale };
        const innerB = { x: xm + 1.0 * scale, y: ym - 0.4 * scale };
        const outerA = { x: xm + 0.7 * scale, y: ym + 0.5 * scale };
        const outerB = { x: xm - 1.15 * scale, y: ym - 0.35 * scale };
        const inner = armLine(frame, 0).concat([
            innerA,
            innerB,
            { x: top.x + 12, y: y0 }
        ]);
        const outer = armLine(frame, 2).concat([
            outerA,
            outerB,
            { x: top.x + top.width * 0.12, y: bottom.y + Math.min(32, bottom.height * 0.28) }
        ]);
        const arc = quad(
            { x: top.x + 10, y: y0 },
            { x: top.x + top.width * 0.18, y: top.y + Math.max(top.height, 1) * 0.92 },
            { x: top.x + top.width * 0.9, y: y1 },
            12
        );
        const far = [
            { x: top.x + top.width * 0.86, y: y0 },
            { x: xm + scale * 0.15, y: ym - scale * 0.08 },
            { x: xm - scale * 0.55, y: ym + scale * 0.55 },
            { x: top.x + top.width * 0.08, y: bottom.y + Math.min(20, bottom.height * 0.2) }
        ];
        return { inner, outer, arc, far };
    }

    window.miraxDStudies.set('study-183-crossed-sweeps', {
        id: 'study-183-crossed-sweeps',
        sourceId: 'study-72-cathode-orbit',
        title: '183 交錯描寫',
        category: '第九批 · 歷史回饋',
        description: '內外兩條長描線從不同臂出發，與遠場斜弧相交。兩前鋒錯相單向繞角，交會處以細肋和缺口標出先後，殘像長度不同。',
        draw(context, frame) {
            context.lineCap = 'butt';
            context.lineJoin = 'miter';
            const built = pathsOf(frame);
            const inner = measure(built.inner);
            const outer = measure(built.outer);
            const arc = measure(built.arc);
            const far = measure(built.far);
            if (inner.total < 2 || outer.total < 2) {
                stroke(context, built.arc, 0.2, 1.1);
                return;
            }
            const headI = (frame.time * 76) % inner.total;
            const headO = (frame.time * 118 + outer.total * 0.37) % outer.total;
            const tailI = Math.max(120, inner.total * 0.4);
            const tailO = Math.max(70, outer.total * 0.16);
            const innerSkips = [];
            const outerSkips = [];
            const arcSkips = [];
            const farSkips = [];
            const marks = [];
            const relate = (measA, measB, headA, tailA, headB, tailB, skipsA, skipsB) => {
                for (const hit of crossings(measA, measB)) {
                    const ba = behind(headA, hit.a, measA.total);
                    const bb = headB == null ? Infinity : behind(headB, hit.b, measB.total);
                    const aFront = bb === Infinity ? true : ba <= bb;
                    const frontBehind = aFront ? ba : bb;
                    const window = aFront ? tailA : tailB;
                    const cover = frontBehind < window;
                    if (cover && aFront) skipsB.push({ a: hit.b - 16, b: hit.b + 16 });
                    if (cover && !aFront) skipsA.push({ a: hit.a - 16, b: hit.a + 16 });
                    marks.push({ hit, meas: aFront ? measA : measB, along: aFront ? hit.a : hit.b, hot: cover });
                }
            };
            relate(inner, outer, headI, tailI, headO, tailO, innerSkips, outerSkips);
            relate(inner, arc, headI, tailI, null, 0, innerSkips, arcSkips);
            relate(outer, arc, headO, tailO, null, 0, outerSkips, arcSkips);
            relate(inner, far, headI, tailI, null, 0, innerSkips, farSkips);
            relate(outer, far, headO, tailO, null, 0, outerSkips, farSkips);
            paintRangeExcept(context, arc, 0, arc.total, arcSkips, 0.2, 1.15);
            paintRangeExcept(context, far, 0, far.total, farSkips, 0.16, 1.05);
            paintRangeExcept(context, inner, 0, inner.total, innerSkips, 0.16, 1.05);
            paintRangeExcept(context, outer, 0, outer.total, outerSkips, 0.16, 1.05);
            // Heads only advance. The later arrival leaves the gap; the earlier one draws through.
            paintTail(context, inner, headI, tailI, innerSkips, 0.62, 1.35);
            paintTail(context, outer, headO, tailO, outerSkips, 0.5, 1.25);
            for (const mark of marks) {
                const hit = mark.hit;
                const p = { x: hit.x, y: hit.y };
                const q = pointAt(mark.meas, mark.along + 10);
                const dx = q.x - p.x;
                const dy = q.y - p.y;
                const len = Math.hypot(dx, dy) || 1;
                stroke(context, [
                    { x: p.x - dy / len * 12, y: p.y + dx / len * 12 },
                    { x: p.x + dy / len * 12, y: p.y - dx / len * 12 }
                ], mark.hot ? 0.62 : 0.22, 1.1);
            }
        }
    });
})();
