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
        context.strokeStyle = `rgba(220,228,232,${alpha.toFixed(3)})`;
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
                    x: top.x + 12,
                    y: top.y + Math.max(8, top.height * 0.3),
                    width: Math.min(top.width * 0.6, 900),
                    height: Math.max(52, top.height * 0.56)
                }
                : { x: 24, y: 32, width: Math.min(frame.width * 0.52, 900), height: Math.min(190, frame.height * 0.26) };
        return { narrow, bottom, stage };
    }

    function upperEdges(frame) {
        const limit = frame.width < 480 ? 220 : frame.height * 0.5;
        return edgesOf(frame).filter((edge) => {
            const my = (edge.a.y + edge.b.y) / 2;
            return my > 0 && my < limit;
        });
    }

    function baseline(stage, lift) {
        const y = stage.y + stage.height - 4 - lift;
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

    function fitDepth(edge, normal, frame, desired) {
        let depth = desired;
        const mid = mix(edge.a, edge.b, 0.5);
        for (let i = 0; i < 6; i += 1) {
            const x = mid.x + normal.x * depth;
            const y = mid.y + normal.y * depth;
            if (x > 6 && y > 6 && x < frame.width - 6 && y < frame.height - 6) return depth;
            depth *= 0.72;
        }
        return Math.max(8, depth);
    }

    function offsetEdge(edge, normal, depth) {
        return {
            a: { x: edge.a.x + normal.x * depth, y: edge.a.y + normal.y * depth },
            b: { x: edge.b.x + normal.x * depth, y: edge.b.y + normal.y * depth },
            length: edge.length,
            arm: edge.arm
        };
    }

    // Modular distance keeps the handoff moving one way across the wrap.
    function reveal(t, seam) {
        let delta = seam - t;
        if (delta < -0.5) delta += 1;
        if (delta > 0.5) delta -= 1;
        if (delta > 0.1) return 1;
        if (delta < -0.1) return 0.38;
        return 0.38 + 0.62 * ((delta + 0.1) / 0.2);
    }

    function row(context, edge, normal, depth, ranges, seam) {
        const plates = ranges.map(([from, to], index) => {
            const mid = (from + to) / 2;
            const open = reveal(mid, seam);
            const lip = depth * (0.42 + 0.58 * open);
            return {
                index,
                open,
                hinge: [at(edge, from, normal, 0), at(edge, to, normal, 0)],
                lip: [at(edge, to - 0.03, normal, lip), at(edge, from + 0.03, normal, lip)],
                inner: lip * 0.55
            };
        });
        plates.sort((a, b) => a.open - b.open);
        plates.forEach((plate) => {
            stroke(context, [...plate.hinge, ...plate.lip, plate.hinge[0]], 0.3 + 0.24 * plate.open, 1.15);
            const innerLip = [
                mix(plate.hinge[1], plate.lip[0], 0.55),
                mix(plate.hinge[0], plate.lip[1], 0.55)
            ];
            stroke(context, [mix(plate.hinge[0], plate.hinge[1], 0.12), mix(plate.hinge[0], plate.hinge[1], 0.88), innerLip[0], innerLip[1]], 0.2, 0.85);
        });
        const seamA = at(edge, seam, normal, 0);
        const seamB = at(edge, seam, normal, depth * (0.42 + 0.58 * reveal(seam, seam)));
        stroke(context, [seamA, seamB], 0.8, 1.5);
        return { lipL: at(edge, ranges[0][0], normal, depth), lipR: at(edge, ranges[ranges.length - 1][1], normal, depth) };
    }

    window.miraxDStudies.set('study-185-shield-swept-seam', {
        id: 'study-185-shield-swept-seam',
        title: '185 疊甲接縫前緣',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-75-shield-shingle',
        description: '兩條邊上的不等長折邊甲片互相疊壓，共同接縫以等速向前移交，後片露出、前片收攏；外緣延伸成與甲片共邊的場域輪廓。',
        draw(context, frame) {
            if (![frame.width, frame.height, frame.time].every(Number.isFinite) || frame.width <= 0 || frame.height <= 0) return;
            const { narrow, bottom, stage } = places(frame);
            const found = upperEdges(frame);
            const aim = { x: stage.x + 8, y: stage.y + 4 };
            const primary = found[0] || baseline(stage, 0);
            const normal = normalToward(primary, aim);
            const depth = fitDepth(primary, normal, frame, narrow ? 16 : Math.min(28, stage.height * 0.34));
            let secondary = found.find((edge) => {
                const mx = (edge.a.x + edge.b.x) / 2 - (primary.a.x + primary.b.x) / 2;
                const my = (edge.a.y + edge.b.y) / 2 - (primary.a.y + primary.b.y) / 2;
                return Math.hypot(mx, my) > 26;
            });
            if (!secondary) secondary = offsetEdge(primary, normal, narrow ? 18 : depth + 16);
            const normalB = normalToward(secondary, aim);
            const depthB = fitDepth(secondary, normalB, frame, depth * 0.82);
            const seam = wrap(frame.time / 6.4);
            const seamB = wrap(seam + 0.37);

            context.save();
            context.lineCap = 'round';
            context.lineJoin = 'round';
            stroke(context, [at(primary, 0.02, normal, 0), at(primary, 0.98, normal, 0)], 0.4, 1.15);
            stroke(context, [at(secondary, 0.04, normalB, 0), at(secondary, 0.94, normalB, 0)], 0.32, 1.05);
            row(context, secondary, normalB, depthB, [[0.04, 0.42], [0.3, 0.7], [0.58, 0.96]], seamB);
            const lips = row(context, primary, normal, depth, [[0.02, 0.3], [0.18, 0.52], [0.4, 0.74], [0.62, 0.98]], seam);
            const bandL = { x: stage.x + 3, y: stage.y + 4 };
            const bandR = { x: stage.x + stage.width - 3, y: stage.y + 4 };
            stroke(context, [lips.lipL, bandL, bandR, lips.lipR], 0.36, 1.15);
            stroke(context, [
                mix(lips.lipL, bandL, 0.2),
                { x: bandL.x, y: bandL.y + 8 },
                { x: bandR.x, y: bandR.y + 8 },
                mix(lips.lipR, bandR, 0.2)
            ], 0.22, 0.9);
            if (!narrow && bottom && bottom.height > 40) {
                const x = bottom.x + bottom.width * 0.34;
                const y = bottom.y + 18;
                const w = Math.min(380, bottom.width * 0.32);
                stroke(context, [
                    { x, y },
                    { x: x + w, y },
                    { x: x + w * 0.86, y: y + Math.min(48, bottom.height * 0.36) },
                    { x: x + 16, y: y + Math.min(40, bottom.height * 0.3) },
                    { x, y }
                ], 0.26, 1.05);
                stroke(context, [lips.lipR, { x, y }], 0.22, 0.95);
            }
            context.restore();
        }
    });
})();
