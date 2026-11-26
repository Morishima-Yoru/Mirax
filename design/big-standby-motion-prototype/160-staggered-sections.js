(() => {
    'use strict';

    const mix = (a, b, t) => ({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
    const clamp = (value, low, high) => Math.max(low, Math.min(high, value));
    const line = (context, a, b) => {
        context.moveTo(a.x, a.y);
        context.lineTo(b.x, b.y);
    };

    function section(center, width, height) {
        const cut = Math.min(width, height) * 0.22;
        return [[-width / 2 + cut, -height / 2], [width / 2 - cut, -height / 2],
            [width / 2, -height / 2 + cut], [width / 2, height / 2 - cut],
            [width / 2 - cut, height / 2], [-width / 2 + cut, height / 2],
            [-width / 2, height / 2 - cut], [-width / 2, -height / 2 + cut]]
            .map(([x, y]) => ({ x: center.x + x, y: center.y + y }));
    }

    function path(context, points, open = false) {
        context.moveTo(points[0].x, points[0].y);
        for (let index = 1; index < points.length; index++) {
            if (open && index === 6) context.moveTo(points[index].x, points[index].y);
            else context.lineTo(points[index].x, points[index].y);
        }
        context.lineTo(points[0].x, points[0].y);
    }

    window.miraxDStudies.set('study-160-staggered-sections', {
        id: 'study-160-staggered-sections',
        title: '160 錯層截面艙',
        category: '第八批 · 科幻朋克',
        sourceId: 'study-51-multi-section',
        scope: 'field',
        description: '一大一小的開口截面沿同一深度錯時前推，兩條長稜連出空艙；前框只遮斷後框的交叉邊，不填滿艙內，窄屏保留橫向深度與前後交替。',
        draw(context, frame) {
            if (![frame.width, frame.height, frame.time].every(Number.isFinite)
                || frame.width <= 0 || frame.height <= 0) return;
            const compact = frame.width < 720 || frame.height < 520 || frame.width < frame.height;
            const region = compact ? frame.field.top : frame.field.bottom;
            const edges = frame.visibleEdges.filter(edge => edge.length > 25
                && Math.abs(edge.b.y - edge.a.y) > edge.length * 0.2);
            const edge = edges.filter(item => item.arm === (compact ? 0 : 2))
                .sort((a, b) => b.length - a.length)[0] || edges[0];
            const reference = edge ? mix(edge.a, edge.b, 0.55)
                : { x: region.x + region.width * 0.8, y: region.y + region.height * 0.5 };
            const height = compact ? 32 : Math.min(140, region.height * 0.58);
            const width = compact ? 52 : height * 1.7;
            const origin = compact ? { x: region.x + region.width * 0.84,
                y: Math.min(87, region.y + region.height * 0.53) }
                : { x: clamp(reference.x, region.x + region.width * 0.4, region.x + region.width * 0.86),
                    y: clamp(reference.y, region.y + height * 0.65, region.y + region.height - height * 1.1) };
            const depth = { x: -Math.min(compact ? region.width * 0.65 : region.width * 0.31, compact ? 230 : 380),
                y: compact ? 4 : Math.min(58, region.height * 0.22) };
            const sections = [1, 0.69].map((scale, index) => {
                const progress = (frame.time * 0.072 + 0.14 + index * 0.5) % 1;
                const center = { x: origin.x + depth.x * progress, y: origin.y + depth.y * progress };
                return { progress, center, width: width * scale, height: height * scale,
                    points: section(center, width * scale, height * scale) };
            });
            const [near, far] = [...sections].sort((a, b) => a.progress - b.progress);

            context.lineWidth = 1.25;
            context.strokeStyle = 'rgba(238,238,233,0.48)';
            context.beginPath();
            line(context, sections[0].points[0], sections[1].points[0]);
            line(context, sections[0].points[5], sections[1].points[5]);
            const tail = { x: origin.x + depth.x - width * 0.35, y: origin.y + depth.y };
            line(context, tail, { x: tail.x - 28, y: tail.y });
            line(context, { x: tail.x - 28, y: tail.y }, { x: tail.x - 36, y: tail.y + 8 });
            if (edge) {
                const target = compact ? mix(edge.a, edge.b,
                    clamp((origin.y - edge.a.y) / (edge.b.y - edge.a.y), 0, 1)) : reference;
                line(context, { x: origin.x + width * 0.5, y: origin.y }, target);
            }
            context.stroke();

            // Subtract only a narrow rim, never an opaque face or the hollow interior.
            context.save();
            context.beginPath();
            context.rect(0, 0, frame.width, frame.height);
            path(context, section(near.center, near.width + 4, near.height + 4));
            path(context, section(near.center, near.width - 4, near.height - 4));
            context.clip('evenodd');
            context.strokeStyle = 'rgba(237,237,232,0.58)';
            context.beginPath();
            path(context, far.points, true);
            context.stroke();
            context.restore();

            context.strokeStyle = 'rgba(243,243,237,0.76)';
            context.lineWidth = 1.45;
            context.beginPath();
            path(context, near.points, true);
            context.stroke();
            context.strokeStyle = 'rgba(249,249,243,0.9)';
            context.beginPath();
            line(context, near.points[1], near.points[2]);
            line(context, far.points[6], far.points[7]);
            context.stroke();
        }
    });
})();
