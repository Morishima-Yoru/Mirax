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
        context.strokeStyle = `rgba(226,232,228,${alpha.toFixed(3)})`;
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

    // shortcut: offscreen X uses the y64-114 band as the hinge, upgrade when a clipped edge is on screen
    function places(frame) {
        const narrow = frame.width < 480;
        const top = regionOk(frame.field?.top) ? frame.field.top : null;
        const bottom = regionOk(frame.field?.bottom) ? frame.field.bottom : null;
        const stage = narrow
            ? { x: 18, y: 64, width: Math.max(48, frame.width - 36), height: 50 }
            : top
                ? {
                    x: top.x + 10,
                    y: top.y + Math.max(8, top.height * 0.34),
                    width: Math.min(top.width * 0.56, 860),
                    height: Math.max(48, top.height * 0.52)
                }
                : { x: 24, y: 36, width: Math.min(frame.width * 0.5, 860), height: Math.min(180, frame.height * 0.24) };
        return { narrow, bottom, stage };
    }

    function upperEdge(frame) {
        const limit = frame.width < 480 ? 220 : frame.height * 0.48;
        return edgesOf(frame).find((edge) => {
            const my = (edge.a.y + edge.b.y) / 2;
            return my > 0 && my < limit;
        }) || null;
    }

    function baseline(stage) {
        const y = stage.y + stage.height - 3;
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
        return Math.max(10, depth);
    }

    // Open front and close front both travel forward along the chain. Linear, no endpoint ease.
    function openAt(phase, index, count) {
        const slot = (index + 0.5) / count;
        let since = phase - slot;
        if (since < 0) since += 1;
        const close = since - 0.42;
        if (since < 0.14) return since / 0.14;
        if (close < 0) return 1;
        if (close < 0.14) return 1 - close / 0.14;
        return 0;
    }

    function drawLower(context, bottom, anchor) {
        if (!bottom || bottom.height < 40) return;
        const x = bottom.x + bottom.width * 0.3;
        const y = bottom.y + 16;
        const w = Math.min(340, bottom.width * 0.36);
        const h = Math.min(64, bottom.height * 0.42);
        const box = [
            { x, y },
            { x: x + w, y: y + 10 },
            { x: x + w * 0.78, y: y + h },
            { x: x + 12, y: y + h - 8 },
            { x, y }
        ];
        stroke(context, box, 0.28, 1.05);
        stroke(context, [
            mix(box[0], box[1], 0.18),
            mix(box[3], box[2], 0.22),
            mix(box[3], box[2], 0.7),
            mix(box[0], box[1], 0.72)
        ], 0.18, 0.9);
        stroke(context, [anchor, box[0]], 0.24, 1);
    }

    window.miraxDStudies.set('study-184-shield-fold-chain', {
        id: 'study-184-shield-fold-chain',
        title: '184 薄甲連鎖折面',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-76-shield-hinge',
        description: '多片不等長空心薄甲共用同一條鉸線，前一片折角帶動下一片跟進，斜撐與接縫同步變形；接合線固定，延伸通道把層次送進可見場域。',
        draw(context, frame) {
            if (![frame.width, frame.height, frame.time].every(Number.isFinite) || frame.width <= 0 || frame.height <= 0) return;
            const { narrow, bottom, stage } = places(frame);
            const hinge = upperEdge(frame) || baseline(stage);
            const aim = { x: stage.x + stage.width * 0.12, y: stage.y + 6 };
            const normal = normalToward(hinge, aim);
            const depth = fitDepth(hinge, normal, frame, narrow ? 30 : Math.min(46, stage.height * 0.72));
            const tangent = {
                x: (hinge.b.x - hinge.a.x) / hinge.length,
                y: (hinge.b.y - hinge.a.y) / hinge.length
            };
            const weights = [0.12, 0.2, 0.14, 0.3, 0.24];
            const sum = weights.reduce((total, value) => total + value, 0);
            const marks = [0.04];
            let cursor = 0.04;
            weights.forEach((weight) => {
                cursor += weight / sum * 0.92;
                marks.push(cursor);
            });
            const phase = wrap(frame.time / 5.6);
            const count = marks.length;
            const hinges = marks.map((t) => at(hinge, t, normal, 0));
            const frees = marks.map((t, index) => {
                const open = openAt(phase, index, count);
                const shear = (index % 2 === 0 ? 0.26 : -0.16) * depth * open;
                return {
                    x: hinges[index].x + normal.x * depth * (0.34 + 0.66 * open) + tangent.x * shear,
                    y: hinges[index].y + normal.y * depth * (0.34 + 0.66 * open) + tangent.y * shear,
                    open
                };
            });

            context.save();
            context.lineCap = 'round';
            context.lineJoin = 'round';
            stroke(context, [hinges[0], hinges[hinges.length - 1]], 0.46, 1.2);
            for (let i = 0; i < hinges.length - 1; i += 1) {
                const quad = [hinges[i], hinges[i + 1], frees[i + 1], frees[i], hinges[i]];
                const hot = Math.max(frees[i].open, frees[i + 1].open);
                stroke(context, quad, 0.32 + 0.22 * hot, 1.15);
                const inset = quad.slice(0, 4).map((point, index, points) => {
                    const prev = points[(index + 3) % 4];
                    const next = points[(index + 1) % 4];
                    return mix(point, mix(prev, next, 0.5), 0.34);
                });
                stroke(context, [...inset, inset[0]], 0.2 + 0.16 * hot, 0.9);
                stroke(context, [mix(hinges[i], hinges[i + 1], 0.5), mix(frees[i], frees[i + 1], 0.5)], 0.28 + 0.2 * hot, 0.95);
            }
            const lead = phase * (hinges.length - 1);
            const seam = Math.min(hinges.length - 2, Math.floor(lead));
            const tip = mix(hinges[seam + 1], frees[seam + 1], lead - seam);
            stroke(context, [hinges[seam + 1], tip], 0.78, 1.45);
            const farL = { x: stage.x + 4, y: stage.y + 5 };
            const farR = { x: stage.x + stage.width - 4, y: stage.y + 5 };
            stroke(context, [frees[0], farL, farR, frees[frees.length - 1]], 0.34, 1.1);
            stroke(context, [
                mix(frees[0], farL, 0.15),
                { x: farL.x, y: farL.y + 9 },
                { x: farR.x, y: farR.y + 9 },
                mix(frees[frees.length - 1], farR, 0.15)
            ], 0.22, 0.9);
            stroke(context, [mix(farL, farR, 0.2), frees[2] || frees[0]], 0.2, 0.85);
            if (!narrow) drawLower(context, bottom, frees[frees.length - 1]);
            context.restore();
        }
    });
})();
