(() => {
    'use strict';

    const clamp = (value) => Math.max(0, Math.min(1, value));
    const smooth = (value) => {
        const t = clamp(value);
        return t * t * (3 - 2 * t);
    };
    const pointOn = (edge, t, normal, inset = 0) => ({
        x: edge.a.x + (edge.b.x - edge.a.x) * t + normal.x * inset,
        y: edge.a.y + (edge.b.y - edge.a.y) * t + normal.y * inset
    });

    function fields(frame) {
        const source = [...frame.visibleEdges,
            ...frame.rails.map(([a, b], index) => ({ a, b, arm: `rail-${index}` }))];
        const groups = new Map();
        for (const edge of source) {
            const length = Math.hypot(edge.b.x - edge.a.x, edge.b.y - edge.a.y);
            if (length < 24) continue;
            if (!groups.has(edge.arm)) groups.set(edge.arm, { edges: [], length: 0 });
            const group = groups.get(edge.arm);
            group.edges.push({ a: edge.a, b: edge.b, arm: edge.arm, length, start: group.length });
            group.length += length;
        }
        return [...groups.values()];
    }

    function inward(edge, frame) {
        const polygon = frame.arms[edge.arm];
        let center = { x: frame.width / 2, y: frame.height / 2 };
        if (polygon && polygon.length) {
            center = polygon.reduce((sum, point) => ({ x: sum.x + point.x / polygon.length,
                y: sum.y + point.y / polygon.length }), { x: 0, y: 0 });
        }
        const normal = { x: -(edge.b.y - edge.a.y) / edge.length,
            y: (edge.b.x - edge.a.x) / edge.length };
        const dot = (center.x - (edge.a.x + edge.b.x) / 2) * normal.x
            + (center.y - (edge.a.y + edge.b.y) / 2) * normal.y;
        if (dot < 0) return { x: -normal.x, y: -normal.y };
        return normal;
    }

    function stroke(context, points, alpha, width, close = false) {
        if (alpha < 0.004) return;
        context.strokeStyle = `rgba(224,231,227,${alpha})`;
        context.lineWidth = width;
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        if (close) context.closePath();
        context.stroke();
    }

    function facet(context, edge, normal, from, to, age) {
        const span = to - from;
        const depth = Math.min(7, edge.length * span * 0.12);
        // Pressure deforms, holds the hinge locked, then releases through an inner refraction.
        const load = age < 0.36 ? smooth(age / 0.36)
            : age < 0.82 ? 1 : 1 - smooth((age - 0.82) / 0.88);
        const inset = depth * 0.85 * load;
        const a = pointOn(edge, from, normal);
        const b = pointOn(edge, to, normal);
        const middle = pointOn(edge, (from + to) / 2, normal, inset);
        const innerA = pointOn(edge, from + span * 0.22, normal, depth + inset * 0.35);
        const innerB = pointOn(edge, to - span * 0.22, normal, depth + inset * 0.35);
        stroke(context, [a, middle, b, innerB, innerA], 0.055 + 0.25 * load,
            0.65 + 0.3 * load, true);

        const lock = smooth((age - 0.25) / 0.18) * (1 - smooth((age - 0.82) / 0.35));
        stroke(context, [innerA, innerB], lock * 0.25, 1);
        if (age > 0.72 && age < 1.65) {
            const refract = (age - 0.72) / 0.93;
            const drift = depth + 4 * smooth(refract);
            stroke(context, [pointOn(edge, from + span * 0.22, normal, drift),
                pointOn(edge, (from + to) / 2, normal, drift + 2),
                pointOn(edge, to - span * 0.22, normal, drift)],
            0.23 * Math.sin(refract * Math.PI), 0.75);
        }
    }

    window.miraxDStudies.set('study-20-shield', {
        id: 'study-20-shield',
        title: '20 靜態防護場',
        category: '空間場域',
        description: '沿輪廓傳遞的壓力依序壓入護面、鎖住鉸點，再折射至內層；底圖始終固定。',
        draw(context, frame) {
            context.lineCap = 'butt';
            context.lineJoin = 'miter';
            fields(frame).forEach((field, index) => {
                const period = Math.max(3.8, field.length / 175 + 0.8);
                for (const edge of field.edges) {
                    const normal = inward(edge, frame);
                    const count = Math.max(1, Math.ceil(edge.length / 78));
                    for (let i = 0; i < count; i += 1) {
                        const position = edge.start + edge.length * (i + 0.5) / count;
                        const age = ((frame.time + index * 0.83 - position / 175)
                            % period + period) % period;
                        const gap = Math.min(0.015, 1 / edge.length);
                        facet(context, edge, normal, i / count + gap,
                            (i + 1) / count - gap, age);
                    }
                }
            });
        }
    });
})();
