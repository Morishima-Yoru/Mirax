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

    function line(context, points, alpha, width = 1.1) {
        context.strokeStyle = `rgba(220,230,232,${alpha})`;
        context.lineWidth = width;
        context.beginPath();
        points.forEach((p, index) => {
            if (index === 0) context.moveTo(p.x, p.y);
            else context.lineTo(p.x, p.y);
        });
        context.stroke();
    }

    window.miraxDStudies.set('study-76-shield-hinge', {
        id: 'study-76-shield-hinge',
        sourceId: 'study-20-shield',
        title: '76 · 20-B 共軸折甲',
        description: '臂緣是固定鉸鏈，前後兩片相連薄甲錯相翻折；自由邊投影與斜撐隨角度改變，接點不離開輪廓。',
        category: '第四批 · 20 護場延伸',
        draw(context, frame) {
            context.save();
            context.lineCap = 'round';
            context.lineJoin = 'round';
            fields(frame).forEach((edge, index) => {
                const normal = inward(edge, frame);
                const tangent = { x: (edge.b.x - edge.a.x) / edge.length,
                    y: (edge.b.y - edge.a.y) / edge.length };
                const depthLimit = Math.min(24, edge.length * 0.1);
                line(context, [mix(edge.a, edge.b, 0.06), mix(edge.a, edge.b, 0.94)], 0.16);
                for (let panel = 0; panel < 2; panel += 1) {
                    const phase = frame.time * 0.81 + index * 0.67 - panel * 1.7;
                    const opening = 0.5 + 0.5 * Math.sin(phase + 0.28 * Math.sin(phase * 0.63));
                    const angle = 0.2 + opening * 1.17;
                    const depth = depthLimit * Math.cos(angle);
                    const shear = (panel === 0 ? 1 : -1) * depthLimit * 0.38 * Math.sin(angle);
                    const a = mix(edge.a, edge.b, 0.06 + panel * 0.44);
                    const b = mix(edge.a, edge.b, 0.5 + panel * 0.44);
                    const free = (p) => ({ x: p.x + normal.x * depth + tangent.x * shear,
                        y: p.y + normal.y * depth + tangent.y * shear });
                    const c = free(b);
                    const d = free(a);
                    line(context, [a, b, c, d, a], 0.37);
                    line(context, [panel === 0 ? a : b, mix(d, c, 0.5)], 0.25, 0.95);
                    line(context, [mix(a, b, 0.5), mix(d, c, 0.5)], 0.2, 0.9);
                }
            });
            context.restore();
        }
    });
})();
