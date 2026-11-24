(() => {
    'use strict';

    const mix = (a, b, t) => ({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
    const distance = (a, b) => Math.hypot(b.x - a.x, b.y - a.y);
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

    function pointAt(contour, position, inset = 0) {
        const along = cycle(position) * contour.length;
        const edge = contour.edges.find((item) => along <= item.start + item.size)
            || contour.edges[contour.edges.length - 1];
        const point = mix(edge.a, edge.b, (along - edge.start) / edge.size);
        const radius = Math.max(1, distance(point, contour.center));
        return mix(point, contour.center, inset / radius);
    }

    function line(context, points, alpha, width = 1.1) {
        if (points.length < 2) return;
        context.strokeStyle = `rgba(223,232,232,${alpha})`;
        context.lineWidth = width;
        context.beginPath();
        points.forEach((p, index) => {
            if (index === 0) context.moveTo(p.x, p.y);
            else context.lineTo(p.x, p.y);
        });
        context.stroke();
    }

    window.miraxDStudies.set('study-72-cathode-orbit', {
        id: 'study-72-cathode-orbit',
        sourceId: 'study-17-cathode',
        title: '72 · 17-A 雙軌繞角',
        description: '閉合細軌貼住臂緣，帶斜接肋的長弧軌車繞過轉角；內軌隨局部蓄力張開再收攏，不推移 X 本體。',
        category: '第四批 · 17 描邊延伸',
        draw(context, frame) {
            context.save();
            context.lineCap = 'round';
            context.lineJoin = 'round';
            for (const contour of contours(frame)) {
                const count = Math.max(48, Math.ceil(contour.length / 16));
                const base = Array.from({ length: count + 1 }, (_, step) =>
                    pointAt(contour, step / count, 1.5));
                line(context, base, 0.12, 0.9);
                const clock = frame.time / 7.8 + contour.arm * 0.23;
                const head = cycle(clock + 0.045 * Math.sin(clock * Math.PI * 2));
                const span = 0.23 + 0.035 * Math.sin(frame.time * 0.71 + contour.arm);
                const opening = 6 + 3 * Math.sin(frame.time * 0.83 + contour.arm * 1.4);
                const outer = [];
                const inner = [];
                for (let step = 0; step <= 32; step += 1) {
                    const t = step / 32;
                    const along = head - span + span * t;
                    const depth = 1.5 + opening * Math.sin(t * Math.PI) ** 0.8;
                    outer.push(pointAt(contour, along, 1.5));
                    inner.push(pointAt(contour, along, depth));
                }
                line(context, [...outer, ...inner.slice().reverse(), outer[0]], 0.46);
                for (const fraction of [0.28, 0.62, 0.84]) {
                    const from = head - span + span * fraction;
                    const to = from + 0.018;
                    const depth = 1.5 + opening * Math.sin(fraction * Math.PI) ** 0.8;
                    line(context, [pointAt(contour, from, 1.5), pointAt(contour, to, depth)],
                        0.25, 0.9);
                }
            }
            context.restore();
        }
    });
})();
