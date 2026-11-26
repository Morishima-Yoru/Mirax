(() => {
    'use strict';

    // Near and far frames fill the field. A thin offset of the X edge reads as the X itself.

    const INK = '232,237,240';

    function stroke(context, points, alpha, width) {
        if (points.length < 2 || alpha < 0.004) return;
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        context.lineWidth = width;
        context.strokeStyle = `rgba(${INK},${alpha.toFixed(2)})`;
        context.stroke();
    }

    function loop(context, points, alpha, width) {
        if (points.length < 3 || alpha < 0.004) return;
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        context.closePath();
        context.lineWidth = width;
        context.strokeStyle = `rgba(${INK},${alpha.toFixed(2)})`;
        context.stroke();
    }

    function mix(a, b, t) {
        return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t };
    }

    function clip(a, b, rect) {
        let t0 = 0;
        let t1 = 1;
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const checks = [
            [-dx, a.x - rect.x],
            [dx, rect.x + rect.width - a.x],
            [-dy, a.y - rect.y],
            [dy, rect.y + rect.height - a.y]
        ];
        for (const [p, q] of checks) {
            if (p === 0) {
                if (q < 0) return null;
            } else {
                const t = q / p;
                if (p < 0) {
                    if (t > t1) return null;
                    if (t > t0) t0 = t;
                } else {
                    if (t < t0) return null;
                    if (t < t1) t1 = t;
                }
            }
        }
        return {
            a: { x: a.x + dx * t0, y: a.y + dy * t0 },
            b: { x: a.x + dx * t1, y: a.y + dy * t1 }
        };
    }

    function band(frame) {
        const top = frame.field.top;
        const compact = frame.width < 720 || frame.height < 520;
        if (!compact) return { x: top.x, y: top.y, width: top.width, height: top.height, compact };
        const y = Math.max(top.y, 64);
        const bottom = Math.min(top.y + top.height, 114);
        if (bottom - y < 8) return { x: top.x, y: top.y, width: top.width, height: top.height, compact };
        return { x: top.x, y, width: top.width, height: bottom - y, compact: true };
    }

    function at(rect, px, py) {
        return { x: rect.x + rect.width * px, y: rect.y + rect.height * py };
    }

    function center(points) {
        return points.reduce((sum, point) => ({
            x: sum.x + point.x / points.length,
            y: sum.y + point.y / points.length
        }), { x: 0, y: 0 });
    }

    function inset(points, gap) {
        const origin = center(points);
        return points.map(point => {
            const dx = origin.x - point.x;
            const dy = origin.y - point.y;
            const len = Math.hypot(dx, dy) || 1;
            return { x: point.x + dx / len * gap, y: point.y + dy / len * gap };
        });
    }

    function xEdge(frame, rect) {
        const found = (frame.visibleEdges || []).flatMap(edge => {
            const clipped = clip(edge.a, edge.b, rect);
            if (!clipped) return [];
            const length = Math.hypot(clipped.b.x - clipped.a.x, clipped.b.y - clipped.a.y);
            return length > 18 ? [{ ...clipped, length }] : [];
        }).sort((left, right) => right.length - left.length);
        return found[0] || null;
    }

    function subpath(points, from, to) {
        const result = [];
        let total = 0;
        const lengths = [];
        for (let index = 1; index < points.length; index += 1) {
            const len = Math.hypot(points[index].x - points[index - 1].x, points[index].y - points[index - 1].y);
            lengths.push(len);
            total += len;
        }
        if (total < 1 || to <= from) return result;
        let passed = 0;
        for (let index = 0; index < lengths.length; index += 1) {
            const len = lengths[index];
            const a0 = passed / total;
            const a1 = (passed + len) / total;
            const start = Math.max(from, a0);
            const end = Math.min(to, a1);
            if (end > start + 0.0001) {
                const span = a1 - a0 || 1;
                const cut = (u) => ({
                    x: points[index].x + (points[index + 1].x - points[index].x) * u,
                    y: points[index].y + (points[index + 1].y - points[index].y) * u
                });
                if (!result.length) result.push(cut((start - a0) / span));
                result.push(cut((end - a0) / span));
            }
            passed += len;
        }
        return result;
    }

    function guides(context, regions, dir) {
        const len = Math.hypot(dir.x, dir.y) || 1;
        const ux = dir.x / len;
        const uy = dir.y / len;
        const px = -uy;
        const py = ux;
        regions.forEach(rect => {
            if (!rect || rect.width < 8 || rect.height < 24) return;
            const origin = { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
            const reach = rect.width + rect.height;
            for (let step = -3; step <= 3; step += 1) {
                const ox = origin.x + px * step * Math.max(28, rect.height * 0.22);
                const oy = origin.y + py * step * Math.max(28, rect.height * 0.22);
                const seg = clip(
                    { x: ox - ux * reach, y: oy - uy * reach },
                    { x: ox + ux * reach, y: oy + uy * reach },
                    rect
                );
                if (seg) stroke(context, [seg.a, seg.b], 0.13, 1.05);
            }
        });
    }

    function trace(context, path, phase, time) {
        const head = (time * 0.16 + phase) % 1;
        const tail = Math.max(0, head - 0.58);
        stroke(context, path, 0.2, 1.15);
        stroke(context, subpath(path, tail, Math.max(tail, head - 0.16)), 0.42, 1.55);
        stroke(context, subpath(path, Math.max(tail, head - 0.16), head), 0.88, 2);
    }

    window.miraxDStudies.set('study-206-depth-cathode-bridge', {
        id: 'study-206-depth-cathode-bridge',
        title: '206 縱深描線接駁',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-118-section-extrusion',
        scope: 'field',
        description: '近框與後框在場域裡分成兩層空心引伸，四條角稜連著對應角。兩條描線從Ｘ外緣的接點沿近邊、角稜、遠邊單向前進，並留下長殘段；兩框不移動。',
        draw(context, frame) {
            const rect = band(frame);
            const near = [at(rect, 0.06, 0.74), at(rect, 0.46, 0.64), at(rect, 0.40, 0.18), at(rect, 0.10, 0.30)];
            const far = [at(rect, 0.38, 0.88), at(rect, 0.92, 0.72), at(rect, 0.84, 0.24), at(rect, 0.50, 0.40)];
            const gap = rect.compact ? 4.5 : 16;
            const edge = xEdge(frame, rect);
            const pathA = [edge ? edge.a : near[0], near[0], far[0], far[3]];
            const pathB = [edge ? edge.b : near[1], near[1], far[1], far[2]];
            const depth = {
                x: center(far).x - center(near).x,
                y: center(far).y - center(near).y
            };

            context.lineCap = 'butt';
            context.lineJoin = 'round';
            guides(context, [frame.field.top, frame.field.bottom], depth);
            loop(context, inset(far, gap), 0.32, 1.15);
            loop(context, far, 0.58, 1.7);
            for (let index = 0; index < 4; index += 1) {
                stroke(context, [near[index], far[index]], 0.62, 1.45);
            }
            loop(context, inset(near, gap), 0.46, 1.2);
            loop(context, near, 0.86, 2);
            trace(context, pathA, 0, frame.time);
            trace(context, pathB, 0.47, frame.time);
        }
    });
})();
