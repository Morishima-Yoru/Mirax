(() => {
    'use strict';

    const PERIOD = 4.8;
    const clamp = (value) => Math.max(0, Math.min(1, value));
    const smooth = (value) => {
        const t = clamp(value);
        return t * t * (3 - 2 * t);
    };

    function sweep(age) {
        if (age < 0.52) return 0.24 * smooth(age / 0.52);
        if (age < 0.85) return 0.24 + 0.035 * smooth((age - 0.52) / 0.33);
        if (age < 1.44) return 0.275 + 0.61 * ((age - 0.85) / 0.59) ** 2;
        return 0.885 + 0.115 * smooth((age - 1.44) / 0.41);
    }

    function contours(frame) {
        const groups = new Map();
        const source = [...frame.visibleEdges,
            ...frame.rails.map(([a, b], index) => ({ a, b, arm: `rail-${index}` }))];
        for (const edge of source) {
            const length = Math.hypot(edge.b.x - edge.a.x, edge.b.y - edge.a.y);
            if (length < 1) continue;
            if (!groups.has(edge.arm)) groups.set(edge.arm, { edges: [], length: 0 });
            const group = groups.get(edge.arm);
            group.edges.push({ a: edge.a, b: edge.b, length, start: group.length });
            group.length += length;
        }
        return [...groups.values()];
    }

    function strokeSpan(context, edge, from, to, alpha, width) {
        if (to <= from || alpha < 0.004) return;
        const start = clamp(from / edge.length);
        const end = clamp(to / edge.length);
        context.strokeStyle = `rgba(226,232,230,${alpha})`;
        context.lineWidth = width;
        context.beginPath();
        context.moveTo(edge.a.x + (edge.b.x - edge.a.x) * start,
            edge.a.y + (edge.b.y - edge.a.y) * start);
        context.lineTo(edge.a.x + (edge.b.x - edge.a.x) * end,
            edge.a.y + (edge.b.y - edge.a.y) * end);
        context.stroke();
    }

    function trace(context, contour, age) {
        const head = sweep(age) * contour.length;
        const persistence = Math.exp(-Math.max(0, age - 1.85) * 1.1);
        const decayLength = Math.max(110, contour.length * 0.65);
        for (const edge of contour.edges) {
            const exposed = Math.min(edge.length, head - edge.start);
            if (exposed <= 0) continue;
            const steps = Math.max(1, Math.ceil(exposed / 22));
            for (let i = 0; i < steps; i += 1) {
                const from = exposed * i / steps;
                const to = exposed * (i + 1) / steps;
                const behind = head - edge.start - (from + to) / 2;
                const alpha = 0.21 * Math.exp(-behind / decayLength) * persistence;
                strokeSpan(context, edge, from, to, alpha, 0.8);
            }
            // A short hard front builds the contour; the much longer tail stores its shape.
            if (age < 1.85) {
                const from = Math.max(0, head - edge.start - 27);
                strokeSpan(context, edge, from, exposed, 0.49, 1.15);
            }
        }
    }

    window.miraxDStudies.set('study-17-cathode', {
        id: 'study-17-cathode',
        title: '17 陰極射線描邊',
        category: '空間場域',
        description: '描線前鋒先蓄壓、再急掃轉角；錯開的長殘像逐段構築原始輪廓。',
        draw(context, frame) {
            context.lineCap = 'butt';
            context.lineJoin = 'miter';
            contours(frame).forEach((contour, index) => {
                for (let pass = 0; pass < 2; pass += 1) {
                    const age = ((frame.time + index * 0.67 + pass * PERIOD / 2)
                        % PERIOD + PERIOD) % PERIOD;
                    trace(context, contour, age);
                }
            });
        }
    });
})();
