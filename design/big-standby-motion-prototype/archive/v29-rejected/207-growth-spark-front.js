(() => {
    'use strict';

    // A column finishes on the shared vertex, and that same vertex starts the next bent spark.

    const INK = '229,233,237';

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

    function mix(a, b, t) {
        return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t };
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

    function vertex(originX, originY, i, j, size) {
        return {
            x: originX + i * size * 0.92 + j * size * 0.28,
            y: originY + j * size * 0.46
        };
    }

    function grown(originX, originY, i, j, size, growth) {
        const a = vertex(originX, originY, i, j, size);
        const b = vertex(originX, originY, i + 1, j, size);
        const c = vertex(originX, originY, i + 1, j + 1, size);
        const d = vertex(originX, originY, i, j + 1, size);
        return [a, mix(a, b, growth), mix(d, c, growth), d];
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

    function targetPoint(frame, rect, originX) {
        const edges = (frame.visibleEdges || []).flatMap(edge => {
            const clipped = clip(edge.a, edge.b, rect);
            if (!clipped) return [];
            const mid = { x: (clipped.a.x + clipped.b.x) / 2, y: (clipped.a.y + clipped.b.y) / 2 };
            const length = Math.hypot(clipped.b.x - clipped.a.x, clipped.b.y - clipped.a.y);
            return length > 16 && mid.x > originX ? [{ mid, length }] : [];
        }).sort((left, right) => right.length - left.length);
        return edges[0] ? edges[0].mid : { x: rect.x + rect.width * 0.9, y: rect.y + rect.height * 0.45 };
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
        for (let x = -frame.height; x < frame.width + frame.height; x += 68) {
            context.moveTo(x, frame.height);
            context.lineTo(x + frame.height * 0.22, 0);
        }
        context.lineWidth = 0.8;
        context.strokeStyle = `rgba(${INK},0.07)`;
        context.stroke();
        context.restore();
    }

    window.miraxDStudies.set('study-207-growth-spark-front', {
        id: 'study-207-growth-spark-front',
        title: '207 生長跳火前緣',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-45-growth-front',
        scope: 'field',
        description: '多向共邊菱格從一側長成帶狀，接觸點就是折角電弧的節點。前緣每到下一個接點才點燃下一段跳火，格面生長與火花路徑按同一時間交錯。',
        draw(context, frame) {
            const rect = band(frame);
            const rows = rect.compact ? 2 : 3;
            const cols = rect.compact ? 5 : Math.min(11, Math.max(7, Math.floor(rect.width / 110)));
            const size = Math.min(
                rect.compact ? 34 : 62,
                (rect.width * 0.74) / (cols * 0.92 + 0.28),
                (rect.height * 0.84) / (rows * 0.46)
            );
            const originX = rect.x + rect.width * 0.05;
            const originY = rect.y + Math.max(0, (rect.height - rows * size * 0.46) * 0.42);
            const travel = (frame.time * 0.58) % (cols + 0.2);
            const target = targetPoint(frame, rect, originX + cols * size * 0.5);

            context.lineCap = 'butt';
            context.lineJoin = 'miter';
            guides(context, frame);
            for (let i = 0; i < cols; i += 1) {
                const growth = Math.max(0, Math.min(1, travel - i));
                for (let j = 0; j < rows; j += 1) {
                    loop(context, grown(originX, originY, i, j, size, 1), 0.08, 0.75);
                    if (growth > 0.02) {
                        loop(context, grown(originX, originY, i, j, size, growth), 0.4, 1.15);
                    }
                }
            }
            for (let i = 0; i < cols; i += 1) {
                const age = travel - (i + 1);
                if (age < 0 || age > 1.55) continue;
                const start = vertex(originX, originY, i + 1, 0, size);
                const bend = vertex(originX, originY, i + 1, 1, size);
                const end = i + 1 < cols ? vertex(originX, originY, i + 2, 0, size) : target;
                const progress = Math.min(1, age / 0.7);
                const alpha = age < 0.7 ? 0.62 : 0.62 * (1 - (age - 0.7) / 0.85);
                stroke(context, partial([start, bend, end], progress * 2), Math.max(0.04, alpha), 1.35);
            }
        }
    });
})();
