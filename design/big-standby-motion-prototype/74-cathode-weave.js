(() => {
    'use strict';

    const distance = (a, b) => Math.hypot(b.x - a.x, b.y - a.y);
    const mix = (a, b, t) => ({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
    const cycle = (value) => ((value % 1) + 1) % 1;

    function contours(frame) {
        return (frame.arms || []).flatMap((vertices, arm) => {
            if (!vertices || vertices.length < 3 || vertices.some((p) =>
                !Number.isFinite(p.x) || !Number.isFinite(p.y))) return [];
            const visible = (frame.visibleEdges || []).some((edge) => edge.arm === arm
                && distance(edge.a, edge.b) > 1
                && Math.max(edge.a.x, edge.b.x) >= 0
                && Math.min(edge.a.x, edge.b.x) <= frame.width
                && Math.max(edge.a.y, edge.b.y) >= 0
                && Math.min(edge.a.y, edge.b.y) <= frame.height);
            if (!visible) return [];
            const center = vertices.reduce((sum, p) => ({ x: sum.x + p.x / vertices.length,
                y: sum.y + p.y / vertices.length }), { x: 0, y: 0 });
            let length = 0;
            const edges = vertices.flatMap((a, index) => {
                const b = vertices[(index + 1) % vertices.length];
                const size = distance(a, b);
                if (size < 1) return [];
                const edge = { a, b, size, start: length };
                length += size;
                return [edge];
            });
            return length > 4 ? [{ edges, length, center, arm }] : [];
        });
    }

    function pointAt(contour, position, inset) {
        const along = cycle(position) * contour.length;
        const edge = contour.edges.find((item) => along <= item.start + item.size)
            || contour.edges[contour.edges.length - 1];
        const point = mix(edge.a, edge.b, (along - edge.start) / edge.size);
        return mix(point, contour.center, inset / Math.max(1, distance(point, contour.center)));
    }

    function line(context, points, alpha, width = 1.05) {
        context.strokeStyle = `rgba(223,232,231,${alpha})`;
        context.lineWidth = width;
        context.beginPath();
        points.forEach((p, index) => {
            if (index === 0) context.moveTo(p.x, p.y);
            else context.lineTo(p.x, p.y);
        });
        context.stroke();
    }

    window.miraxDStudies.set('study-74-cathode-weave', {
        id: 'study-74-cathode-weave',
        sourceId: 'study-17-cathode',
        title: '74 · 17-C 輪廓交織',
        description: '兩條連續細線沿臂緣交織，局部線距與交叉節點錯相遷移；短接肋始終連著雙線，沒有散落火花或整圈明暗呼吸。',
        category: '第四批 · 17 描邊延伸',
        draw(context, frame) {
            context.save();
            context.lineCap = 'round';
            context.lineJoin = 'round';
            for (const contour of contours(frame)) {
                const phase = frame.time * 0.9 + 0.38 * Math.sin(frame.time * 0.57)
                    + contour.arm * 1.7;
                const separation = (s) => 4.3 * Math.sin(s * Math.PI * 6 - phase
                    + 0.34 * Math.sin(s * Math.PI * 2 + frame.time * 0.49));
                const strand = (s, side) => pointAt(contour, s, 5 + side * separation(s));
                const count = Math.max(90, Math.ceil(contour.length / 9));
                for (const side of [-1, 1]) {
                    const points = Array.from({ length: count + 1 }, (_, step) =>
                        strand(step / count, side));
                    line(context, points, side === -1 ? 0.25 : 0.34);
                }
                for (const anchor of [0.12, 0.38, 0.64, 0.88]) {
                    line(context, [strand(anchor, -1), strand(anchor + 0.012, 1)], 0.3, 0.9);
                }
                const focus = cycle(frame.time / 9.4 + contour.arm * 0.21
                    + 0.025 * Math.sin(frame.time * 0.76));
                const joined = [];
                for (let step = 0; step <= 22; step += 1) {
                    joined.push(strand(focus - 0.1 + step / 110, 1));
                }
                line(context, joined, 0.48, 1.15);
            }
            context.restore();
        }
    });
})();
