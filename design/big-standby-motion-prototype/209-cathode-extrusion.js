(() => {
    'use strict';

    const PERIOD = 9.6;
    const smooth = (value) => {
        const t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    };
    const envelope = (phase) => Math.sin(Math.PI * phase) ** 2;
    const phaseAt = (time, layer) => ((time / PERIOD + layer * 0.5) % 1 + 1) % 1;
    const validPoint = (point) => point && Number.isFinite(point.x) && Number.isFinite(point.y);

    function contours(frame) {
        const groups = new Map();
        const source = [...(frame.visibleEdges || [])];
        (frame.rails || []).forEach((rail, index) => {
            if (Array.isArray(rail)) {
                source.push({ a: rail[0], b: rail[1], arm: `rail-${index}`, rail: true });
            }
        });
        for (const edge of source) {
            if (!edge || !validPoint(edge.a) || !validPoint(edge.b)) continue;
            const length = Math.hypot(edge.b.x - edge.a.x, edge.b.y - edge.a.y);
            if (!Number.isFinite(length) || length < 0.001) continue;
            if (!groups.has(edge.arm)) groups.set(edge.arm, { edges: [], length: 0, rail: Boolean(edge.rail) });
            const group = groups.get(edge.arm);
            group.edges.push({ a: edge.a, b: edge.b, length, start: group.length });
            group.length += length;
        }
        return [...groups.values()];
    }

    function stroke(context, a, b, alpha, width) {
        if (alpha <= 0) return;
        context.strokeStyle = `rgba(226,232,230,${alpha})`;
        context.lineWidth = width;
        context.beginPath();
        context.moveTo(a.x, a.y);
        context.lineTo(b.x, b.y);
        context.stroke();
    }

    function trace(context, groups, phase, dx, dy, strength) {
        const opacity = envelope(phase) * strength;
        if (opacity <= 0) return;
        for (const contour of groups) {
            const head = smooth(phase) * contour.length;
            const feather = Math.max(12, Math.min(28, contour.length * 0.04));
            const persistence = Math.max(100, contour.length * 0.65);
            for (const edge of contour.edges) {
                // Fixed cells fade continuously; advancing the head never adds a hard segment.
                const steps = Math.max(1, Math.ceil(edge.length / 18));
                for (let index = 0; index < steps; index += 1) {
                    const from = index / steps;
                    const to = (index + 1) / steps;
                    const distance = head - edge.start - edge.length * (from + to) / 2;
                    const behind = (distance + Math.hypot(distance, feather)) / 2;
                    const tail = smooth((distance + feather) / (2 * feather))
                        * Math.exp(-behind / persistence);
                    const front = Math.exp(-((distance / feather) ** 2));
                    const alpha = opacity * (0.13 * tail + 0.3 * front);
                    stroke(context, {
                        x: edge.a.x + (edge.b.x - edge.a.x) * from + dx,
                        y: edge.a.y + (edge.b.y - edge.a.y) * from + dy
                    }, {
                        x: edge.a.x + (edge.b.x - edge.a.x) * to + dx,
                        y: edge.a.y + (edge.b.y - edge.a.y) * to + dy
                    }, alpha, strength === 1 ? 1.05 : 0.85);
                }
            }
        }
    }

    function section(context, polygons, dx, dy, opacity) {
        if (opacity <= 0 || !polygons.length) return;
        context.beginPath();
        for (const polygon of polygons) {
            polygon.forEach((point, index) => {
                if (index === 0) context.moveTo(point.x + dx, point.y + dy);
                else context.lineTo(point.x + dx, point.y + dy);
            });
            context.closePath();
        }
        context.lineWidth = 0.78;
        context.strokeStyle = `rgba(226,232,230,${0.075 * opacity})`;
        context.stroke();

        // One real corner per polygon suggests a prism without rebuilding a wire cage.
        context.beginPath();
        for (const polygon of polygons) {
            context.moveTo(polygon[0].x, polygon[0].y);
            context.lineTo(polygon[0].x + dx, polygon[0].y + dy);
        }
        context.lineWidth = 0.7;
        context.strokeStyle = `rgba(226,232,230,${0.055 * opacity})`;
        context.stroke();
    }

    window.miraxDStudies.set('study-209-cathode-extrusion', {
        id: 'study-209-cathode-extrusion',
        title: '209 描邊引伸框 · 17＋118',
        category: '合成提案',
        sourceIds: ['study-17-cathode', 'study-118-section-extrusion'],
        description: '17 的亮前緣與長殘像沿原Ｘ可見邊及導軌推進，接入 118 真實臂角與菱形頂點構成的兩層空心引伸框；截面只平移、不縮放，錯相淡入淡出連續交接，無背景網格，原Ｘ保持原位與完整輪廓。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            const groups = contours(frame);
            const sectionGroups = groups.filter(group => !group.rail);
            const polygons = [...(frame.arms || []), frame.diamond || []].filter(polygon => {
                if (!Array.isArray(polygon) || polygon.length < 3 || !polygon.every(validPoint)) return false;
                const area = polygon.reduce((sum, point, index) => {
                    const next = polygon[(index + 1) % polygon.length];
                    return sum + point.x * next.y - point.y * next.x;
                }, 0);
                return Number.isFinite(area) && Math.abs(area) > 0.001;
            });
            if (!groups.length && !polygons.length) return;

            const rail = groups.find(group => group.rail)?.edges[0];
            const direction = rail ? {
                x: (rail.b.x - rail.a.x) / rail.length,
                y: (rail.b.y - rail.a.y) / rail.length
            } : { x: Math.SQRT1_2, y: -Math.SQRT1_2 };
            const depth = Math.min(width, height) * 0.11;
            context.save();
            try {
                context.lineCap = 'butt';
                context.lineJoin = 'miter';
                for (let layer = 0; layer < 2; layer += 1) {
                    const phase = phaseAt(time, layer);
                    const opacity = envelope(phase);
                    const distance = depth * smooth(phase);
                    const dx = direction.x * distance;
                    const dy = direction.y * distance;
                    section(context, polygons, dx, dy, opacity);
                    trace(context, sectionGroups, phase, dx, dy, 0.32);
                    trace(context, groups, phase, 0, 0, 1);
                }
            } finally {
                context.restore();
            }
        }
    });
})();
