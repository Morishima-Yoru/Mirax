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
        context.strokeStyle = `rgba(232,232,226,${alpha.toFixed(3)})`;
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
                    x: top.x + 10,
                    y: top.y + Math.max(8, top.height * 0.24),
                    width: Math.min(top.width * 0.62, 940),
                    height: Math.max(60, top.height * 0.62)
                }
                : { x: 22, y: 28, width: Math.min(frame.width * 0.54, 940), height: Math.min(210, frame.height * 0.3) };
        return { narrow, bottom, stage };
    }

    function upperEdges(frame) {
        const limit = frame.width < 480 ? 220 : frame.height * 0.5;
        return edgesOf(frame).filter((edge) => {
            const my = (edge.a.y + edge.b.y) / 2;
            return my > 0 && my < limit;
        });
    }

    function baseline(stage, from, to, yLift) {
        const y = stage.y + stage.height - 3 - yLift;
        return {
            a: { x: stage.x + stage.width * from, y },
            b: { x: stage.x + stage.width * to, y },
            length: Math.max(1, stage.width * (to - from)),
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
            if (x > 4 && y > 4 && x < frame.width - 4 && y < frame.height - 4) return depth;
            depth *= 0.7;
        }
        return Math.max(8, depth);
    }

    function sectionOf(stage, edge) {
        const mx = (edge.a.x + edge.b.x) / 2;
        const onLeft = mx > stage.x + stage.width * 0.5;
        const width = Math.max(40, stage.width * 0.4);
        const x = onLeft ? stage.x + 4 : stage.x + stage.width - width - 4;
        return {
            x,
            y: stage.y + 3,
            width,
            height: Math.max(20, stage.height - 7)
        };
    }

    function quad(box, inset) {
        return [
            { x: box.x + inset, y: box.y + inset },
            { x: box.x + box.width - inset, y: box.y + inset * 0.6 },
            { x: box.x + box.width - inset * 1.2, y: box.y + box.height - inset },
            { x: box.x + inset * 1.4, y: box.y + box.height - inset * 0.8 }
        ];
    }

    window.miraxDStudies.set('study-188-shield-depth-bridge', {
        id: 'study-188-shield-depth-bridge',
        title: '188 護甲深度橋',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-118-section-extrusion',
        description: '兩段臂緣短薄甲以雙層空心斜稜橋接到遠側截面，接縫沿橋面單向交接，遠側輪廓同步前進；空心體積位置固定，不堆疊花飾。',
        draw(context, frame) {
            if (![frame.width, frame.height, frame.time].every(Number.isFinite) || frame.width <= 0 || frame.height <= 0) return;
            const { narrow, bottom, stage } = places(frame);
            const found = upperEdges(frame);
            const primary = found[0] || baseline(stage, 0.02, 0.4, 0);
            const box = sectionOf(stage, primary);
            const aim = { x: box.x + box.width * 0.5, y: box.y + box.height * 0.45 };
            let secondary = found.find((edge) => Math.hypot(
                (edge.a.x + edge.b.x) / 2 - (primary.a.x + primary.b.x) / 2,
                (edge.a.y + edge.b.y) / 2 - (primary.a.y + primary.b.y) / 2
            ) > 30);
            if (!secondary) secondary = baseline(stage, 0.58, 0.96, narrow ? 0 : 8);
            const normalA = normalToward(primary, aim);
            const normalB = normalToward(secondary, aim);
            const depthA = fitDepth(primary, normalA, frame, narrow ? 16 : 26);
            const depthB = fitDepth(secondary, normalB, frame, narrow ? 14 : 22);
            const near = quad(box, 0);
            const far = quad(box, narrow ? 6 : 10);
            const cluster = (edge, normal, depth, cuts) => cuts.map((t) => ({
                hinge: at(edge, t, normal, 0),
                free: at(edge, t, normal, depth)
            }));
            const left = cluster(primary, normalA, depthA, [0.12, 0.34, 0.56]);
            const right = cluster(secondary, normalB, depthB, [0.18, 0.46, 0.74]);
            const bridge = [
                left[left.length - 1].free,
                near[0],
                near[3],
                right[0].free
            ];
            const bridgeFar = [
                mix(left[left.length - 1].hinge, left[left.length - 1].free, 0.45),
                far[0],
                far[3],
                mix(right[0].hinge, right[0].free, 0.45)
            ];
            const route = [
                ...left.map((point) => point.free),
                near[0],
                near[1],
                near[2],
                near[3],
                ...right.map((point) => point.free)
            ];
            const travel = wrap(frame.time / 6.6) * (route.length - 1);
            const index = Math.min(route.length - 2, Math.floor(travel));
            const local = travel - index;

            context.save();
            context.lineCap = 'round';
            context.lineJoin = 'round';
            stroke(context, [at(primary, 0.08, normalA, 0), at(primary, 0.62, normalA, 0)], 0.4, 1.15);
            stroke(context, [at(secondary, 0.12, normalB, 0), at(secondary, 0.82, normalB, 0)], 0.34, 1.05);
            left.forEach((point, plate) => {
                const next = left[plate + 1] || point;
                stroke(context, [point.hinge, next.hinge, next.free, point.free, point.hinge], 0.32, 1.05);
                stroke(context, [point.hinge, point.free], 0.24, 0.9);
            });
            right.forEach((point, plate) => {
                const next = right[plate + 1] || point;
                stroke(context, [point.hinge, next.hinge, next.free, point.free, point.hinge], 0.3, 1.05);
            });
            stroke(context, bridge.concat(bridge[0]), 0.36, 1.15);
            stroke(context, bridgeFar.concat(bridgeFar[0]), 0.24, 0.95);
            stroke(context, [bridge[1], bridgeFar[1]], 0.28, 1);
            stroke(context, [bridge[2], bridgeFar[2]], 0.28, 1);
            stroke(context, near.concat(near[0]), 0.46, 1.25);
            stroke(context, far.concat(far[0]), 0.3, 1);
            stroke(context, [near[0], far[0]], 0.26, 0.9);
            stroke(context, [near[3], far[3]], 0.22, 0.85);
            stroke(context, [route[index], mix(route[index], route[index + 1], local)], 0.84, 1.55);
            if (index > 0) stroke(context, [route[index - 1], route[index]], 0.5, 1.15);
            if (!narrow && bottom && bottom.height > 40) {
                const x = bottom.x + bottom.width * 0.36;
                const y = bottom.y + 16;
                const w = Math.min(260, bottom.width * 0.24);
                const h = Math.min(48, bottom.height * 0.36);
                stroke(context, [
                    { x, y }, { x: x + w, y: y + 4 }, { x: x + w * 0.8, y: y + h }, { x: x + 8, y: y + h - 4 }, { x, y }
                ], 0.24, 1);
                stroke(context, [near[2], { x: x + w, y: y + 4 }], 0.2, 0.9);
            }
            context.restore();
        }
    });
})();
