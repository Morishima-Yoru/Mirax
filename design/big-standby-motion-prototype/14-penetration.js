(() => {
    'use strict';

    const dot = (point, axis) => point.x * axis.x + point.y * axis.y;
    const clamp = (value, low, high) => Math.max(low, Math.min(high, value));

    function polygonPath(context, polygon) {
        context.beginPath();
        polygon.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        context.closePath();
    }

    function section(context, polygon, normal, position, opacity) {
        const cuts = [];
        polygon.forEach((a, index) => {
            const b = polygon[(index + 1) % polygon.length];
            const from = dot(a, normal);
            const difference = dot(b, normal) - from;
            if (Math.abs(difference) < 0.00001) return;
            const progress = (position - from) / difference;
            if (progress < 0 || progress > 1) return;
            cuts.push({ x: a.x + (b.x - a.x) * progress, y: a.y + (b.y - a.y) * progress });
        });
        if (cuts.length < 2) return;
        const tangent = { x: -normal.y, y: normal.x };
        cuts.sort((a, b) => dot(a, tangent) - dot(b, tangent));
        context.beginPath();
        context.moveTo(cuts[0].x, cuts[0].y);
        context.lineTo(cuts[cuts.length - 1].x, cuts[cuts.length - 1].y);
        context.lineWidth = 1.25;
        context.strokeStyle = `rgba(239,243,246,${opacity})`;
        context.stroke();
    }

    function reveal(context, polygon, normal, position, aperture, pitch) {
        const longest = polygon.map((a, index) => {
            const b = polygon[(index + 1) % polygon.length];
            return { a, b, length: Math.hypot(b.x - a.x, b.y - a.y) };
        }).sort((a, b) => b.length - a.length)[0];
        if (!longest || longest.length < 1) return;
        const tangent = {
            x: (longest.b.x - longest.a.x) / longest.length,
            y: (longest.b.y - longest.a.y) / longest.length
        };
        const across = { x: -tangent.y, y: tangent.x };
        const alongValues = polygon.map((point) => dot(point, tangent));
        const crossValues = polygon.map((point) => dot(point, across));
        const start = Math.min(...alongValues);
        const end = Math.max(...alongValues);
        const low = Math.min(...crossValues);
        const high = Math.max(...crossValues);
        const pointAt = (along, cross) => ({
            x: tangent.x * along + across.x * cross,
            y: tangent.y * along + across.y * cross
        });
        const line = (a, b) => {
            context.moveTo(a.x, a.y);
            context.lineTo(b.x, b.y);
        };
        const beamTangent = { x: -normal.y, y: normal.x };
        const span = Math.max(...polygon.map((point) => Math.hypot(point.x, point.y))) + aperture;

        context.save();
        polygonPath(context, polygon);
        context.clip();
        const corner = (offset, side) => ({
            x: normal.x * (position + offset) + beamTangent.x * side,
            y: normal.y * (position + offset) + beamTangent.y * side
        });
        polygonPath(context, [
            corner(-aperture / 2, -span), corner(aperture / 2, -span),
            corner(aperture / 2, span), corner(-aperture / 2, span)
        ]);
        context.clip();

        // Only the arm's internal truss is revealed; the oblique aperture has no fill.
        context.beginPath();
        for (const fraction of [0.2, 0.5, 0.8]) {
            const cross = low + (high - low) * fraction;
            line(pointAt(start, cross), pointAt(end, cross));
        }
        for (let along = Math.floor(start / pitch) * pitch; along <= end; along += pitch) {
            line(pointAt(along, low), pointAt(along, high));
            line(pointAt(along, low), pointAt(along + pitch, high));
        }
        context.lineWidth = 0.8;
        context.strokeStyle = 'rgba(220,229,234,0.21)';
        context.stroke();
        section(context, polygon, normal, position - aperture * 0.44, 0.13);
        section(context, polygon, normal, position, 0.48);
        section(context, polygon, normal, position + aperture * 0.44, 0.2);
        context.restore();
    }

    window.miraxDStudies.set('study-14-penetration', {
        id: 'study-14-penetration',
        title: '14 穿透幾何截面',
        category: '能量干涉',
        description: '斜向窄束加速收緊，只在Ｘ臂內顯露順形桁架與三層幾何截面，端點蓄勢後回掃。',
        draw(context, frame) {
            const edges = frame.visibleEdges.filter((edge) => edge.length > 1);
            const polygons = frame.arms.filter((polygon, arm) =>
                polygon.length >= 3 && edges.some((edge) => edge.arm === arm));
            let ranges = edges.map(({ a, b }) => [
                clamp(Math.min(a.y, b.y), 0, frame.height),
                clamp(Math.max(a.y, b.y), 0, frame.height)
            ]).filter(([start, end]) => end - start > 0.01);
            let points = edges.flatMap(({ a, b }) => [a, b]);
            if (!polygons.length) {
                for (const [a, b] of frame.rails) {
                    const length = Math.hypot(b.x - a.x, b.y - a.y);
                    if (length < 1) continue;
                    const normal = { x: -(b.y - a.y) / length * 3, y: (b.x - a.x) / length * 3 };
                    polygons.push([
                        { x: a.x + normal.x, y: a.y + normal.y },
                        { x: b.x + normal.x, y: b.y + normal.y },
                        { x: b.x - normal.x, y: b.y - normal.y },
                        { x: a.x - normal.x, y: a.y - normal.y }
                    ]);
                }
                points = polygons.flat();
                ranges = [[0, frame.height]];
            }
            if (!points.length || !ranges.length) return;
            ranges.sort((a, b) => a[0] - b[0]);
            const merged = [];
            for (const range of ranges) {
                const previous = merged[merged.length - 1];
                if (previous && range[0] <= previous[1] + 0.01) previous[1] = Math.max(previous[1], range[1]);
                else merged.push([...range]);
            }
            const normal = { x: Math.cos(-0.42), y: Math.sin(-0.42) };
            const positions = points.map((point) => dot(point, normal));
            const low = Math.min(...positions);
            const high = Math.max(...positions);
            const phase = ((frame.time % 9.6) + 9.6) % 9.6 / 9.6;
            const sweep = 0.5 - 0.5 * Math.cos(phase * Math.PI * 2);
            const tension = Math.abs(Math.sin(phase * Math.PI * 2));
            const aperture = clamp(Math.min(frame.width, frame.height) * 0.09, 26, 60) * (1 - tension * 0.46);
            const position = low + (high - low) * sweep;

            context.save();
            context.beginPath();
            for (const [start, end] of merged) context.rect(0, start, frame.width, end - start);
            context.clip();
            context.lineJoin = 'miter';
            context.lineCap = 'butt';
            const pitch = clamp(Math.min(frame.width, frame.height) * 0.055, 18, 32);
            for (const polygon of polygons) reveal(context, polygon, normal, position, aperture, pitch);
            context.restore();
        }
    });
})();
