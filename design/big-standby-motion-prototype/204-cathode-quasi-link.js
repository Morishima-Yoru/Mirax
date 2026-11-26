(() => {
    'use strict';

    // Shared rhomb edge is the cathode joint. The boundary must arrive there before the trace grows.

    const INK = '226,232,230';

    function stroke(context, points, alpha, width) {
        if (points.length < 2 || alpha < 0.004) return;
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        context.lineWidth = width;
        context.strokeStyle = `rgba(${INK},${alpha.toFixed(2)})`;
        context.stroke();
    }

    function loop(context, points, alpha, width) {
        if (points.length < 3 || alpha < 0.004) return;
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        context.closePath();
        context.lineWidth = width;
        context.strokeStyle = `rgba(${INK},${alpha.toFixed(2)})`;
        context.stroke();
    }

    function clip(a, b, rect) {
        let t0 = 0;
        let t1 = 1;
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const checks = [
            [-dx, a.x - rect.x],
            [dx, rect.x + rect.width - a.x],
            [-dy, a.y - rect.y],
            [dy, rect.y + rect.height - a.y]
        ];
        for (const [p, q] of checks) {
            if (p === 0) {
                if (q < 0) return null;
            } else {
                const t = q / p;
                if (p < 0) {
                    if (t > t1) return null;
                    if (t > t0) t0 = t;
                } else {
                    if (t < t0) return null;
                    if (t < t1) t1 = t;
                }
            }
        }
        return {
            a: { x: a.x + dx * t0, y: a.y + dy * t0 },
            b: { x: a.x + dx * t1, y: a.y + dy * t1 }
        };
    }

    function band(frame) {
        const top = frame.field.top;
        const compact = frame.width < 720 || frame.height < 520;
        if (!compact) return { x: top.x, y: top.y, width: top.width, height: top.height, compact };
        const y = Math.max(top.y, 64);
        const bottom = Math.min(top.y + top.height, 114);
        if (bottom - y < 8) return { x: top.x, y: top.y, width: top.width, height: top.height, compact };
        return { x: top.x, y, width: top.width, height: bottom - y, compact: true };
    }

    function fieldEdges(frame, rect) {
        return (frame.visibleEdges || []).flatMap(edge => {
            const clipped = clip(edge.a, edge.b, rect);
            if (!clipped) return [];
            const length = Math.hypot(clipped.b.x - clipped.a.x, clipped.b.y - clipped.a.y);
            if (length < 16) return [];
            return [{ a: clipped.a, b: clipped.b, length }];
        }).sort((left, right) => right.length - left.length);
    }

    function face(originX, originY, index, width, height) {
        const x = originX + index * width;
        const shear = height * 0.55;
        return [
            { x, y: originY + height },
            { x: x + shear, y: originY },
            { x: x + width + shear, y: originY },
            { x: x + width, y: originY + height }
        ];
    }

    function partial(points, amount) {
        const count = Math.max(0, Math.min(points.length - 1, amount));
        const whole = Math.floor(count);
        const result = points.slice(0, whole + 1);
        const fraction = count - whole;
        if (fraction > 0.001 && whole + 1 < points.length) {
            const a = points[whole];
            const b = points[whole + 1];
            result.push({
                x: a.x + (b.x - a.x) * fraction,
                y: a.y + (b.y - a.y) * fraction
            });
        }
        return result;
    }

    function route(start, end) {
        return [
            start,
            { x: start.x + (end.x - start.x) * 0.34, y: start.y + (end.y - start.y) * 0.34 },
            { x: start.x + (end.x - start.x) * 0.67, y: start.y + (end.y - start.y) * 0.67 },
            end
        ];
    }

    function guides(context, frame) {
        const regions = [frame.field.top, frame.field.bottom].filter(region =>
            region && region.width > 8 && region.height > 24);
        if (!regions.length) return;
        context.save();
        context.beginPath();
        regions.forEach(region => context.rect(region.x, region.y, region.width, region.height));
        context.clip();
        context.beginPath();
        for (let x = -frame.height; x < frame.width + frame.height; x += 72) {
            context.moveTo(x, 0);
            context.lineTo(x + frame.height * 0.42, frame.height);
        }
        context.lineWidth = 0.8;
        context.strokeStyle = `rgba(${INK},0.07)`;
        context.stroke();
        context.restore();
    }

    window.miraxDStudies.set('study-204-cathode-quasi-link', {
        id: 'study-204-cathode-quasi-link',
        title: '204 準晶陰極接續',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-36-quasicrystal',
        scope: 'field',
        description: '上方準晶菱格沿共邊排成一列，右端那條共邊就是兩條陰極描線的接點。相位邊界單向掃過每一條共邊，抵達接點後才把描線推進到下一個節點；菱格一直留在場上。',
        draw(context, frame) {
            const rect = band(frame);
            const cells = rect.compact ? 6 : Math.min(12, Math.max(8, Math.floor(rect.width / 120)));
            const height = Math.min(rect.compact ? 22 : 52, rect.height * 0.62);
            const shear = height * 0.55;
            const cellWidth = Math.min(
                rect.compact ? 42 : 90,
                Math.max(12, (rect.width * 0.66 - shear) / cells)
            );
            const originX = rect.x + rect.width * (rect.compact ? 0.04 : 0.06);
            const originY = rect.y + (rect.height - height) * 0.5;
            const travel = (frame.time * 0.46) % cells;
            const edge = Math.floor(travel);
            const fraction = travel - edge;
            const lap = Math.floor(frame.time * 0.46 / cells);
            const arriving = edge === cells - 1;
            const amount = (lap % 3) + (arriving ? fraction : 0);
            const joint = face(originX, originY, cells - 1, cellWidth, height);
            const jointTop = joint[2];
            const jointBottom = joint[3];
            const edges = fieldEdges(frame, rect);
            const endA = edges[0]
                ? { x: (edges[0].a.x + edges[0].b.x) / 2, y: (edges[0].a.y + edges[0].b.y) / 2 }
                : { x: rect.x + rect.width * 0.9, y: rect.y + rect.height * 0.22 };
            const endB = edges[1]
                ? { x: (edges[1].a.x + edges[1].b.x) / 2, y: (edges[1].a.y + edges[1].b.y) / 2 }
                : { x: rect.x + rect.width * 0.84, y: rect.y + rect.height * 0.78 };
            const pathA = route(jointTop, endA);
            const pathB = route(jointBottom, endB);

            context.lineCap = 'butt';
            context.lineJoin = 'miter';
            guides(context, frame);
            for (let index = 0; index < cells; index += 1) {
                const active = index === edge;
                loop(context, face(originX, originY, index, cellWidth, height), active ? 0.42 : 0.16, active ? 1.25 : 0.9);
                const side = face(originX, originY, index, cellWidth, height);
                const shared = index === cells - 1;
                const boundary = index === edge;
                stroke(context, [side[3], side[2]], shared ? 0.48 : boundary ? 0.22 + fraction * 0.4 : 0.1, shared || boundary ? 1.45 : 0.8);
            }
            stroke(context, pathA, 0.12, 0.9);
            stroke(context, pathB, 0.12, 0.9);
            stroke(context, partial(pathA, amount), 0.58, 1.35);
            stroke(context, partial(pathB, amount), 0.5, 1.2);
        }
    });
})();
