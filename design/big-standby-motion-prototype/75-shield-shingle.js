(() => {
    'use strict';

    const distance = (a, b) => Math.hypot(b.x - a.x, b.y - a.y);
    const mix = (a, b, t) => ({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });

    function fields(frame) {
        const groups = new Map();
        for (const edge of frame.visibleEdges || []) {
            if (![edge.a.x, edge.a.y, edge.b.x, edge.b.y].every(Number.isFinite)) continue;
            const length = distance(edge.a, edge.b);
            if (length < 40 || Math.max(edge.a.x, edge.b.x) < 0
                || Math.min(edge.a.x, edge.b.x) > frame.width
                || Math.max(edge.a.y, edge.b.y) < 0
                || Math.min(edge.a.y, edge.b.y) > frame.height) continue;
            if (!groups.has(edge.arm)) groups.set(edge.arm, []);
            groups.get(edge.arm).push({ ...edge, length });
        }
        return [...groups.values()].flatMap((edges) => edges
            .sort((a, b) => b.length - a.length).slice(0, 2));
    }

    function inward(edge, frame) {
        const polygon = (frame.arms || [])[edge.arm] || [];
        const center = polygon.length ? polygon.reduce((sum, p) => ({
            x: sum.x + p.x / polygon.length, y: sum.y + p.y / polygon.length
        }), { x: 0, y: 0 }) : { x: frame.width / 2, y: frame.height / 2 };
        const normal = { x: -(edge.b.y - edge.a.y) / edge.length,
            y: (edge.b.x - edge.a.x) / edge.length };
        if ((center.x - (edge.a.x + edge.b.x) / 2) * normal.x
            + (center.y - (edge.a.y + edge.b.y) / 2) * normal.y < 0) {
            normal.x *= -1;
            normal.y *= -1;
        }
        return normal;
    }

    function point(edge, t, normal, inset = 0) {
        const p = mix(edge.a, edge.b, t);
        return { x: p.x + normal.x * inset, y: p.y + normal.y * inset };
    }

    function line(context, points, alpha, width = 1.1) {
        context.strokeStyle = `rgba(219,229,231,${alpha})`;
        context.lineWidth = width;
        context.beginPath();
        points.forEach((p, index) => {
            if (index === 0) context.moveTo(p.x, p.y);
            else context.lineTo(p.x, p.y);
        });
        context.stroke();
    }

    window.miraxDStudies.set('study-75-shield-shingle', {
        id: 'study-75-shield-shingle',
        sourceId: 'study-20-shield',
        title: '75 · 20-A 疊甲滑接',
        description: '三片薄輪廓甲沿同一條臂緣錯相滑接，重疊縫與內側折邊逐段伸縮；共同邊軌保持連接，不移動實心字形。',
        category: '第四批 · 20 護場延伸',
        draw(context, frame) {
            context.save();
            context.lineCap = 'round';
            context.lineJoin = 'round';
            fields(frame).forEach((edge, index) => {
                const normal = inward(edge, frame);
                const at = (t, depth = 0) => point(edge, t, normal, depth);
                line(context, [at(0.02), at(0.98)], 0.13, 0.9);
                for (let plate = 0; plate < 3; plate += 1) {
                    const phase = frame.time * 0.74 + index * 0.82 - plate * 1.2;
                    const slide = 0.04 * Math.sin(phase)
                        + 0.012 * Math.sin(phase * 1.71 + plate);
                    const center = 0.18 + plate * 0.32 + slide;
                    const half = 0.205 + 0.018 * Math.sin(phase + 0.8);
                    const from = Math.max(0.02, center - half);
                    const to = Math.min(0.98, center + half);
                    const depth = Math.min(17, edge.length * 0.075)
                        * (0.62 + 0.25 * Math.sin(phase - 0.4));
                    const lip = 0.035 + 0.012 * Math.sin(phase + 1.1);
                    const rim = [at(from), at(to), at(to - lip, depth),
                        at(from + lip, depth), at(from)];
                    line(context, rim, 0.3 + plate * 0.035);
                    line(context, [at(from + lip, depth), at(from + lip * 2, depth * 0.52),
                        at(from + lip * 2.6)], 0.23, 0.9);
                }
            });
            context.restore();
        }
    });
})();
