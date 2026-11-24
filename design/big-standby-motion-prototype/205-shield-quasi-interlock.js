(() => {
    'use strict';

    // One seam hands the shared outer edge to the next plate. Only that rhomb tip crosses the edge.

    const INK = '219,229,231';

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

    function longest(frame, rect) {
        const found = (frame.visibleEdges || []).flatMap(edge => {
            const clipped = clip(edge.a, edge.b, rect);
            if (!clipped) return [];
            const length = Math.hypot(clipped.b.x - clipped.a.x, clipped.b.y - clipped.a.y);
            return length > 16 ? [{ ...clipped, length }] : [];
        }).sort((left, right) => right.length - left.length);
        return found[0] || null;
    }

    function chordOf(edge, rect) {
        const dx = edge.b.x - edge.a.x;
        const dy = edge.b.y - edge.a.y;
        const len = Math.hypot(dx, dy) || 1;
        const mid = { x: (edge.a.x + edge.b.x) / 2, y: (edge.a.y + edge.b.y) / 2 };
        const far = rect.width + rect.height;
        return clip(
            { x: mid.x - dx / len * far, y: mid.y - dy / len * far },
            { x: mid.x + dx / len * far, y: mid.y + dy / len * far },
            rect
        );
    }

    function baseline(frame, rect) {
        const edge = longest(frame, rect);
        const fallback = {
            a: { x: rect.x + rect.width * 0.06, y: rect.y + rect.height * 0.62 },
            b: { x: rect.x + rect.width * 0.94, y: rect.y + rect.height * 0.62 }
        };
        if (!edge) return { line: fallback, real: null };
        const chord = chordOf(edge, rect);
        if (!chord) return { line: fallback, real: edge };
        const length = Math.hypot(chord.b.x - chord.a.x, chord.b.y - chord.a.y);
        if (length < rect.width * 0.42) return { line: fallback, real: edge };
        return { line: chord, real: edge };
    }

    function inward(a, b, rect) {
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const len = Math.hypot(dx, dy) || 1;
        let nx = -dy / len;
        let ny = dx / len;
        const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        const center = { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
        if ((center.x - mid.x) * nx + (center.y - mid.y) * ny < 0) {
            nx *= -1;
            ny *= -1;
        }
        return { x: nx, y: ny };
    }

    function travel(point, dir, rect) {
        const limits = [];
        if (Math.abs(dir.x) > 1e-6) {
            limits.push(dir.x > 0
                ? (rect.x + rect.width - 2 - point.x) / dir.x
                : (rect.x + 2 - point.x) / dir.x);
        }
        if (Math.abs(dir.y) > 1e-6) {
            limits.push(dir.y > 0
                ? (rect.y + rect.height - 2 - point.y) / dir.y
                : (rect.y + 2 - point.y) / dir.y);
        }
        if (!limits.length) return 0;
        return Math.max(0, Math.min(...limits));
    }

    function reflect(point, a, b) {
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const len2 = dx * dx + dy * dy || 1;
        const t = ((point.x - a.x) * dx + (point.y - a.y) * dy) / len2;
        const px = a.x + dx * t;
        const py = a.y + dy * t;
        return { x: px * 2 - point.x, y: py * 2 - point.y };
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
        for (let y = 0; y < frame.height; y += 48) {
            context.moveTo(0, y);
            context.lineTo(frame.width, y + frame.height * 0.04);
        }
        context.lineWidth = 0.8;
        context.strokeStyle = `rgba(${INK},0.07)`;
        context.stroke();
        context.restore();
    }

    window.miraxDStudies.set('study-205-shield-quasi-interlock', {
        id: 'study-205-shield-quasi-interlock',
        title: '205 菱格薄甲咬合',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-75-shield-shingle',
        scope: 'field',
        description: '空心薄甲沿同一條邊逐片疊接，每片外緣同時是外側菱格的底邊。接縫一次交給下一片，並把那一片的自由角翻過共邊；其餘格面停在原位。',
        draw(context, frame) {
            const rect = band(frame);
            const { line, real } = baseline(frame, rect);
            const count = rect.compact ? 4 : 5;
            const normal = inward(line.a, line.b, rect);
            const midLine = mix(line.a, line.b, 0.5);
            const spaceIn = Math.min(travel(line.a, normal, rect), travel(line.b, normal, rect), travel(midLine, normal, rect));
            const spaceOut = Math.min(
                travel(line.a, { x: -normal.x, y: -normal.y }, rect),
                travel(line.b, { x: -normal.x, y: -normal.y }, rect)
            );
            const depth = Math.min(rect.compact ? 12 : 28, spaceIn * 0.72);
            const rise = Math.min(rect.compact ? 14 : 36, spaceOut * 0.72);
            const cycle = frame.time / 1.55;
            const step = Math.floor(cycle);
            const local = cycle - step;
            const active = step % count;

            context.lineCap = 'butt';
            context.lineJoin = 'miter';
            guides(context, frame);
            stroke(context, [line.a, line.b], 0.14, 0.9);
            if (real) stroke(context, [real.a, real.b], 0.4, 1.5);

            for (let index = 0; index < count; index += 1) {
                const span = 0.82 / count;
                const t0 = 0.08 + index * span * 0.78;
                const t1 = Math.min(0.96, t0 + span);
                const outerA = mix(line.a, line.b, t0);
                const outerB = mix(line.a, line.b, t1);
                const innerA = { x: outerA.x + normal.x * depth, y: outerA.y + normal.y * depth };
                const innerB = { x: outerB.x + normal.x * depth, y: outerB.y + normal.y * depth };
                const plateMid = mix(outerA, outerB, 0.5);
                const rest = { x: plateMid.x - normal.x * rise, y: plateMid.y - normal.y * rise };
                const flipped = reflect(rest, outerA, outerB);
                const pose = index === active ? local : 0;
                const tip = mix(rest, flipped, pose);
                loop(context, [outerA, outerB, innerB, innerA], index === active ? 0.46 : 0.2, 1.05);
                if (rise > 2) {
                    loop(context, [outerA, outerB, tip], index === active ? 0.62 : 0.24, index === active ? 1.4 : 0.95);
                }
                stroke(context, [outerA, outerB], index === active ? 0.7 : 0.28, index === active ? 1.55 : 1);
            }

            for (let extra = 0; extra < 2; extra += 1) {
                const t0 = 0.9 + extra * 0.04;
                const t1 = Math.min(0.99, t0 + 0.035);
                if (t1 <= t0) continue;
                const outerA = mix(line.a, line.b, t0);
                const outerB = mix(line.a, line.b, t1);
                const plateMid = mix(outerA, outerB, 0.5);
                const rest = { x: plateMid.x - normal.x * rise, y: plateMid.y - normal.y * rise };
                if (rise > 2) loop(context, [outerA, outerB, rest], 0.12, 0.8);
            }
        }
    });
})();
