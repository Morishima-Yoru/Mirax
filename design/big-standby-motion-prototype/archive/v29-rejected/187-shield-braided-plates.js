(() => {
    'use strict';

    const mix = (a, b, t) => ({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
    const wrap = (t) => ((t % 1) + 1) % 1;

    function stroke(context, pts, alpha, width) {
        if (!(alpha > 0.04) || !pts || pts.length < 2) return;
        for (const p of pts) {
            if (!p || !Number.isFinite(p.x) || !Number.isFinite(p.y)) return;
        }
        context.beginPath();
        context.moveTo(pts[0].x, pts[0].y);
        for (let i = 1; i < pts.length; i += 1) context.lineTo(pts[i].x, pts[i].y);
        context.strokeStyle = `rgba(214,224,226,${alpha.toFixed(3)})`;
        context.lineWidth = width;
        context.stroke();
    }

    function edgesOf(frame) {
        const out = [];
        for (const edge of frame.visibleEdges || []) {
            if (!edge?.a || !edge?.b) continue;
            if (![edge.a.x, edge.a.y, edge.b.x, edge.b.y].every(Number.isFinite)) continue;
            const length = Math.hypot(edge.b.x - edge.a.x, edge.b.y - edge.a.y);
            if (length < 32) continue;
            const mx = (edge.a.x + edge.b.x) / 2;
            const my = (edge.a.y + edge.b.y) / 2;
            if (mx < 0 || my < 0 || mx > frame.width || my > frame.height) continue;
            out.push({ a: edge.a, b: edge.b, length, arm: edge.arm });
        }
        out.sort((p, q) => q.length - p.length);
        return out;
    }

    function regionOk(region) {
        return !!region && [region.x, region.y, region.width, region.height].every(Number.isFinite)
            && region.width > 24 && region.height > 16;
    }

    function places(frame) {
        const narrow = frame.width < 480;
        const top = regionOk(frame.field?.top) ? frame.field.top : null;
        const bottom = regionOk(frame.field?.bottom) ? frame.field.bottom : null;
        const stage = narrow
            ? { x: 18, y: 64, width: Math.max(48, frame.width - 36), height: 50 }
            : top
                ? {
                    x: top.x + 8,
                    y: top.y + Math.max(8, top.height * 0.26),
                    width: Math.min(top.width * 0.64, 980),
                    height: Math.max(56, top.height * 0.6)
                }
                : { x: 20, y: 28, width: Math.min(frame.width * 0.56, 980), height: Math.min(200, frame.height * 0.28) };
        return { narrow, bottom, stage };
    }

    function upperEdge(frame) {
        const limit = frame.width < 480 ? 220 : frame.height * 0.5;
        return edgesOf(frame).find((edge) => {
            const my = (edge.a.y + edge.b.y) / 2;
            return my > 0 && my < limit;
        }) || null;
    }

    function baseline(stage) {
        const y = stage.y + stage.height - 4;
        return {
            a: { x: stage.x + 2, y },
            b: { x: stage.x + stage.width - 2, y },
            length: Math.max(1, stage.width - 4),
            arm: -1
        };
    }

    function at(edge, t, normal, depth) {
        const p = mix(edge.a, edge.b, t);
        return { x: p.x + normal.x * depth, y: p.y + normal.y * depth };
    }

    function normalToward(edge, target) {
        const length = edge.length || 1;
        let n = { x: -(edge.b.y - edge.a.y) / length, y: (edge.b.x - edge.a.x) / length };
        const mid = mix(edge.a, edge.b, 0.5);
        if ((target.x - mid.x) * n.x + (target.y - mid.y) * n.y < 0) n = { x: -n.x, y: -n.y };
        return n;
    }

    window.miraxDStudies.set('study-187-shield-braided-plates', {
        id: 'study-187-shield-braided-plates',
        title: '187 交織層甲',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-74-cathode-weave',
        description: '兩條不等長輪廓帶與薄甲底邊交織，接肋保持共邊；交織段的前後順序單向交換，接縫只在交接處開合，線不離開共同底邊。',
        draw(context, frame) {
            if (![frame.width, frame.height, frame.time].every(Number.isFinite) || frame.width <= 0 || frame.height <= 0) return;
            const { narrow, bottom, stage } = places(frame);
            const edge = upperEdge(frame) || baseline(stage);
            const aim = { x: stage.x + stage.width * 0.2, y: stage.y + 4 };
            const normal = normalToward(edge, aim);
            const tangent = {
                x: (edge.b.x - edge.a.x) / edge.length,
                y: (edge.b.y - edge.a.y) / edge.length
            };
            const liftA = narrow ? 11 : 20;
            const liftB = narrow ? 22 : 36;
            const shear = narrow ? 7 : 14;
            const samples = [0.02, 0.2, 0.38, 0.56, 0.74, 0.92];
            const base = samples.map((t) => at(edge, t, normal, 0));
            base.unshift({ x: stage.x + 3, y: stage.y + stage.height * 0.72 });
            base.push({ x: stage.x + stage.width - 4, y: stage.y + 6 });
            const phase = wrap(frame.time / 5.8) * (base.length - 1);
            const active = Math.min(base.length - 2, Math.floor(phase));
            const frac = phase - active;
            const gap = frac < 0.5 ? frac / 0.5 : 1 - (frac - 0.5) / 0.5;
            const sideOf = (index) => (index % 2 === 0 ? 1 : -1);

            function ribbon(index, lift, sign) {
                const swapped = index === active || index === active + 1;
                const flip = swapped ? 1 - 2 * frac : 1;
                const spread = (index === active + 1 ? gap : 0) * (narrow ? 6 : 10);
                return {
                    x: base[index].x + normal.x * (lift + spread) + tangent.x * shear * sideOf(index) * sign * flip,
                    y: base[index].y + normal.y * (lift + spread) + tangent.y * shear * sideOf(index) * sign * flip
                };
            }

            const bandA = base.map((_, index) => ribbon(index, liftA, 1));
            const bandB = base.map((_, index) => ribbon(index, liftB, -1)).slice(1);

            context.save();
            context.lineCap = 'round';
            context.lineJoin = 'round';
            stroke(context, base, 0.4, 1.15);
            base.forEach((point, index) => {
                stroke(context, [point, bandA[index]], 0.26, 0.9);
                if (index > 0) stroke(context, [point, bandB[index - 1]], 0.22, 0.85);
            });
            stroke(context, bandA, 0.38, 1.15);
            stroke(context, bandB, 0.34, 1.1);
            const front = frac < 0.5 ? bandA : bandB;
            const head = Math.min(active, front.length - 2);
            stroke(context, [front[head], front[head + 1]], 0.78, 1.45);
            stroke(context, [base[active], mix(base[active], base[active + 1], frac)], 0.62, 1.15);
            if (!narrow && bottom && bottom.height > 36) {
                const tail = {
                    x: bottom.x + bottom.width * 0.4,
                    y: bottom.y + Math.min(28, bottom.height * 0.3)
                };
                const tailB = { x: tail.x + Math.min(220, bottom.width * 0.22), y: tail.y + Math.min(36, bottom.height * 0.28) };
                stroke(context, [base[base.length - 1], tail, tailB], 0.28, 1.05);
                stroke(context, [bandA[bandA.length - 1], { x: tail.x + 10, y: tail.y - 8 }, { x: tailB.x - 8, y: tailB.y - 6 }], 0.22, 0.95);
            }
            context.restore();
        }
    });
})();
