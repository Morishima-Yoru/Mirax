(() => {
    'use strict';

    const mix = (a, b, t) => ({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
    const validPoint = (point) => point && Number.isFinite(point.x) && Number.isFinite(point.y);

    function path(context, points) {
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
    }

    function line(context, points, alpha, width = 1.05) {
        context.strokeStyle = `rgba(230,230,230,${alpha})`;
        context.lineWidth = width;
        path(context, points);
        context.stroke();
    }

    function drawUnderlay(context, frame, time) {
        const regions = [frame.field?.top, frame.field?.bottom].flatMap((region) => {
            if (!region || ![region.x, region.y, region.width, region.height].every(Number.isFinite)
                || region.width <= 0 || region.height <= 0) return [];
            const x = Math.max(0, region.x);
            const y = Math.max(0, region.y);
            const right = Math.min(frame.width, region.x + region.width);
            const bottom = Math.min(frame.height, region.y + region.height);
            return right > x && bottom > y ? [{ x, y, width: right - x, height: bottom - y }] : [];
        });
        if (!regions.length) return;
        const size = Math.max(36, Math.min(76, frame.width / 9, frame.height / 8));
        const dx = size * Math.sqrt(3) / 2;
        const dy = size / 2;
        const node = (column, row) => {
            const x = column * dx * 2 + (row & 1) * dx;
            const y = row * dy;
            const wave = Math.sin(x / size * 0.17 + y / size * 0.23 - time * 0.42);
            const relief = size * 0.16 * Math.sin(x / size * 0.11 - y / size * 0.19 + time * 0.23);
            return { x, y: y + wave * relief };
        };
        context.save();
        // Only the background uses the field clip; mark-linked relief must remain visible.
        context.beginPath();
        regions.forEach((region) => context.rect(region.x, region.y, region.width, region.height));
        context.clip();
        context.strokeStyle = 'rgba(230,230,230,0.12)';
        context.lineWidth = 0.8;
        context.beginPath();
        for (let row = -2; row <= Math.ceil(frame.height / dy) + 2; row += 1) {
            for (let column = -2; column <= Math.ceil(frame.width / (dx * 2)) + 1; column += 1) {
                const point = node(column, row);
                for (const nextColumn of [column, column + ((row & 1) ? 1 : -1)]) {
                    const next = node(nextColumn, row + 1);
                    context.moveTo(point.x, point.y);
                    context.lineTo(next.x, next.y);
                }
            }
        }
        context.stroke();
        context.restore();
    }

    function selectEdges(frame) {
        const groups = new Map();
        for (const edge of frame.visibleEdges || []) {
            if (!edge || !validPoint(edge.a) || !validPoint(edge.b)) continue;
            const length = Math.hypot(edge.b.x - edge.a.x, edge.b.y - edge.a.y);
            if (!Number.isFinite(length) || length < 48
                || Math.max(edge.a.x, edge.b.x) < 0 || Math.min(edge.a.x, edge.b.x) > frame.width
                || Math.max(edge.a.y, edge.b.y) < 0 || Math.min(edge.a.y, edge.b.y) > frame.height) continue;
            if (!groups.has(edge.arm)) groups.set(edge.arm, []);
            groups.get(edge.arm).push({ ...edge, length });
        }
        return [...groups.values()].flatMap((edges) => edges.sort((a, b) => b.length - a.length).slice(0, 2));
    }

    function normalFor(edge, frame) {
        const points = (frame.arms?.[edge.arm] || []).filter(validPoint);
        const center = points.length ? points.reduce((sum, point) => ({
            x: sum.x + point.x / points.length,
            y: sum.y + point.y / points.length
        }), { x: 0, y: 0 }) : { x: frame.width / 2, y: frame.height / 2 };
        const normal = { x: -(edge.b.y - edge.a.y) / edge.length, y: (edge.b.x - edge.a.x) / edge.length };
        if ((center.x - (edge.a.x + edge.b.x) / 2) * normal.x
            + (center.y - (edge.a.y + edge.b.y) / 2) * normal.y < 0) {
            normal.x *= -1;
            normal.y *= -1;
        }
        return normal;
    }

    function drawFace(context, points) {
        const [a, b, c, d] = points;
        context.save();
        // Erase rear braces on this canvas, never paint over the underlying double-outline X.
        context.globalCompositeOperation = 'destination-out';
        context.fillStyle = '#000';
        path(context, points);
        context.closePath();
        context.fill();
        context.restore();
        line(context, [...points, a], 0.38);
        line(context, [mix(d, a, 0.13), mix(c, b, 0.13)], 0.2, 0.85);
        line(context, [a, mix(d, c, 0.5), b], 0.22, 0.85);
    }

    window.miraxDStudies.set('study-113-relief-shingle', {
        id: 'study-113-relief-shingle',
        sourceId: 'study-75-shield-shingle',
        title: '113 A1 疊層嵌合甲列',
        category: '第六批 · 立體關係探索',
        description: '固定厚度的相連甲片沿真實 X 臂緣滑接；重疊接縫遮住再露出後層斜線，共用底網以連續菱格緩慢起伏。',
        mechanism: 'Overlapping shingled faces maintain rigid apparent thickness and swap front/back registration along true X edges; motion translates the layered junction.',
        draw(context, frame) {
            context.save();
            try {
                if (!frame || ![frame.width, frame.height].every(Number.isFinite)
                    || frame.width <= 0 || frame.height <= 0) return;
                context.lineCap = 'butt';
                context.lineJoin = 'miter';
                const time = Number.isFinite(frame.time) ? frame.time : 0;
                drawUnderlay(context, frame, time);
                selectEdges(frame).forEach((edge, index) => {
                    const normal = normalFor(edge, frame);
                    const at = (position, depth) => {
                        const point = mix(edge.a, edge.b, position);
                        return { x: point.x + normal.x * depth, y: point.y + normal.y * depth };
                    };
                    const depth = Math.min(42, Math.max(18, Math.min(frame.width, frame.height) * 0.045),
                        edge.length * 0.22);
                    const count = Math.max(1, Math.min(4, Math.ceil(edge.length / 170)));
                    line(context, [at(0.025, 0), at(0.975, 0)], 0.14, 0.85);
                    const span = 0.9 / (1 + (count - 1) * 0.77);
                    const bevel = Math.min(span * 0.14, 5 / edge.length);
                    for (let panel = 0; panel < count; panel += 1) {
                        const phase = time * 0.72 + index * 0.83 - panel * 1.35;
                        const slide = Math.min(0.025, span * 0.075) * Math.sin(phase);
                        const from = 0.05 + panel * span * 0.77 + slide;
                        const to = from + span;
                        drawFace(context, [at(from, 2.5), at(to, 2.5),
                            at(to - bevel, depth), at(from + bevel, depth)]);
                    }
                });
            } finally {
                context.restore();
            }
        }
    });
})();
