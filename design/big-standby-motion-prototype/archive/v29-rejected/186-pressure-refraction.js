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
        context.strokeStyle = `rgba(230,226,216,${alpha.toFixed(3)})`;
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
                    y: top.y + Math.max(6, top.height * 0.28),
                    width: Math.min(top.width * 0.5, 720),
                    height: Math.max(56, top.height * 0.58)
                }
                : { x: 28, y: 30, width: Math.min(frame.width * 0.42, 720), height: Math.min(200, frame.height * 0.28) };
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
            b: { x: stage.x + stage.width * 0.62, y },
            length: Math.max(1, stage.width * 0.62 - 2),
            arm: -1
        };
    }

    function at(edge, t, normal, depth) {
        const p = mix(edge.a, edge.b, Math.max(0, Math.min(1, t)));
        return { x: p.x + normal.x * depth, y: p.y + normal.y * depth };
    }

    function normalToward(edge, target) {
        const length = edge.length || 1;
        let n = { x: -(edge.b.y - edge.a.y) / length, y: (edge.b.x - edge.a.x) / length };
        const mid = mix(edge.a, edge.b, 0.5);
        if ((target.x - mid.x) * n.x + (target.y - mid.y) * n.y < 0) n = { x: -n.x, y: -n.y };
        return n;
    }

    function farFrame(stage, edge) {
        const mx = (edge.a.x + edge.b.x) / 2;
        const placeLeft = mx > stage.x + stage.width * 0.45;
        const width = Math.max(36, stage.width * (placeLeft ? 0.46 : 0.42));
        const x = placeLeft ? stage.x + 3 : stage.x + stage.width - width - 3;
        return {
            x,
            y: stage.y + 3,
            width,
            height: Math.max(18, stage.height - 8)
        };
    }

    function bump(distance) {
        const ad = Math.abs(distance);
        if (ad > 0.45) return 0;
        return 1 - ad / 0.45;
    }

    window.miraxDStudies.set('study-186-pressure-refraction', {
        id: 'study-186-pressure-refraction',
        title: '186 護面折射鏈',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-20-shield',
        description: '兩層不等寬空心護面沿輪廓伸出，壓力只壓入當前接點，再沿斜向支稜折射到外層與下一個接點；遠場框保持連貫，不塗實心。',
        draw(context, frame) {
            if (![frame.width, frame.height, frame.time].every(Number.isFinite) || frame.width <= 0 || frame.height <= 0) return;
            const { narrow, bottom, stage } = places(frame);
            const edge = upperEdge(frame) || baseline(stage);
            const box = farFrame(stage, edge);
            const aim = { x: box.x + box.width * 0.5, y: box.y + box.height * 0.5 };
            const normal = normalToward(edge, aim);
            const inner = narrow ? 8 : 14;
            const outer = narrow ? 18 : 30;
            const joints = [0.2, 0.5, 0.8];
            const corners = [
                { x: box.x, y: box.y },
                { x: box.x, y: box.y + box.height },
                { x: box.x + box.width, y: box.y },
                { x: box.x + box.width, y: box.y + box.height }
            ];
            const front = wrap(frame.time / 4.8) * joints.length;

            context.save();
            context.lineCap = 'round';
            context.lineJoin = 'round';
            stroke(context, [at(edge, 0.06, normal, 0), at(edge, 0.94, normal, 0)], 0.42, 1.15);
            stroke(context, corners.concat(corners[0]), 0.4, 1.2);
            stroke(context, [
                { x: box.x + 7, y: box.y + 6 },
                { x: box.x + box.width - 7, y: box.y + 6 },
                { x: box.x + box.width - 9, y: box.y + box.height - 6 },
                { x: box.x + 9, y: box.y + box.height - 6 },
                { x: box.x + 7, y: box.y + 6 }
            ], 0.24, 0.9);
            stroke(context, [corners[0], { x: box.x + box.width - 9, y: box.y + box.height - 6 }], 0.16, 0.8);

            const cells = [[0.06, 0.36], [0.36, 0.64], [0.64, 0.94]];
            cells.forEach(([from, to], index) => {
                const press = bump(front - index);
                const dent = press * (narrow ? 6 : 10);
                const flare = press * (narrow ? 5 : 9);
                const innerBand = [
                    at(edge, from, normal, inner * 0.15),
                    at(edge, to, normal, inner * 0.15),
                    at(edge, to, normal, inner - dent),
                    at(edge, from, normal, inner - dent * 0.35),
                    at(edge, from, normal, inner * 0.15)
                ];
                const outerBand = [
                    at(edge, from, normal, outer * 0.62),
                    at(edge, to, normal, outer * 0.62),
                    at(edge, to, normal, outer + flare),
                    at(edge, from, normal, outer + flare * 0.25),
                    at(edge, from, normal, outer * 0.62)
                ];
                stroke(context, innerBand, 0.34 + 0.2 * press, 1.1);
                stroke(context, outerBand, 0.3 + 0.22 * press, 1.15);
                const joint = at(edge, joints[index], normal, inner - dent);
                const braceEnd = corners[index % corners.length];
                stroke(context, [joint, braceEnd], 0.28 + 0.16 * press, 1.05);
                const along = front - index;
                if (along >= 0 && along <= 1) stroke(context, [joint, mix(joint, braceEnd, along)], 0.84, 1.45);
            });
            if (!narrow && bottom && bottom.height > 40) {
                const x = bottom.x + bottom.width * 0.22;
                const y = bottom.y + 14;
                const w = Math.min(300, bottom.width * 0.28);
                const h = Math.min(56, bottom.height * 0.4);
                stroke(context, [
                    { x, y }, { x: x + w, y: y + 6 }, { x: x + w - 8, y: y + h }, { x: x + 10, y: y + h - 6 }, { x, y }
                ], 0.24, 1);
                stroke(context, [corners[1], { x, y }], 0.2, 0.9);
            }
            context.restore();
        }
    });
})();
