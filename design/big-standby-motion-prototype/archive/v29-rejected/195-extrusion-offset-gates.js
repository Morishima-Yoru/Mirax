(() => {
    'use strict';

    const wrap = value => value - Math.floor(value);

    function finitePoint(point) {
        return !!point && Number.isFinite(point.x) && Number.isFinite(point.y);
    }

    function polygonsOf(frame) {
        return [...(frame.arms || []), frame.diamond || []].filter(polygon =>
            Array.isArray(polygon) && polygon.length >= 3 && polygon.every(finitePoint));
    }

    function centerOf(polygon) {
        return polygon.reduce((sum, point) => ({
            x: sum.x + point.x / polygon.length,
            y: sum.y + point.y / polygon.length
        }), { x: 0, y: 0 });
    }

    function onStage(polygon, width, height) {
        return polygon.some(point => point.x >= -48 && point.x <= width + 48
            && point.y >= -48 && point.y <= height + 48);
    }

    function narrowOf(frame) {
        return frame.width <= 480 && frame.height > frame.width * 1.15;
    }

    function focusOf(frame) {
        const top = frame.field && frame.field.top;
        if (narrowOf(frame)) return { x: frame.width * 0.62, y: 90 };
        if (top && top.width > 8 && top.height > 8) {
            return { x: top.x + top.width * 0.58, y: top.y + top.height * 0.5 };
        }
        return { x: frame.width * 0.58, y: frame.height * 0.24 };
    }

    function placeShell(polygon, center, at, taper) {
        return polygon.map(point => ({
            x: at.x + (point.x - center.x) * taper,
            y: at.y + (point.y - center.y) * taper
        }));
    }

    function mix(a, b, fraction) {
        return {
            x: a.x + (b.x - a.x) * fraction,
            y: a.y + (b.y - a.y) * fraction
        };
    }

    function edgesOf(points) {
        return points.map((point, index) => [point, points[(index + 1) % points.length]]);
    }

    function hitT(a, b, c, d) {
        const rx = b.x - a.x;
        const ry = b.y - a.y;
        const sx = d.x - c.x;
        const sy = d.y - c.y;
        const den = rx * sy - ry * sx;
        if (Math.abs(den) < 1e-6) return null;
        const qx = c.x - a.x;
        const qy = c.y - a.y;
        const t = (qx * sy - qy * sx) / den;
        const u = (qx * ry - qy * rx) / den;
        if (t <= 0.02 || t >= 0.98 || u <= 0.02 || u >= 0.98) return null;
        return t;
    }

    function strokeClosed(context, points) {
        if (points.length < 3) return;
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        context.closePath();
        context.stroke();
    }

    function strokeCut(context, a, b, cuts) {
        const gaps = cuts.map(t => [Math.max(0.015, t - 0.04), Math.min(0.985, t + 0.04)])
            .filter(([start, end]) => end > start)
            .sort((left, right) => left[0] - right[0]);
        const spans = [];
        let cursor = 0;
        gaps.forEach(([start, end]) => {
            if (start > cursor) spans.push([cursor, start]);
            cursor = Math.max(cursor, end);
        });
        if (cursor < 1) spans.push([cursor, 1]);
        spans.forEach(([start, end]) => {
            if (end - start < 0.012) return;
            context.beginPath();
            context.moveTo(a.x + (b.x - a.x) * start, a.y + (b.y - a.y) * start);
            context.lineTo(a.x + (b.x - a.x) * end, a.y + (b.y - a.y) * end);
            context.stroke();
        });
    }

    function fieldRegions(frame) {
        return [frame.field && frame.field.top, frame.field && frame.field.bottom].filter(region =>
            region && [region.x, region.y, region.width, region.height].every(Number.isFinite)
            && region.width > 0 && region.height > 0);
    }

    function backdrop(context, frame, shift) {
        const regions = fieldRegions(frame);
        if (!regions.length) return;
        const length = Math.hypot(shift.x, shift.y) || 1;
        const dir = { x: shift.x / length, y: shift.y / length };
        const side = { x: -dir.y, y: dir.x };
        context.save();
        context.beginPath();
        regions.forEach(region => context.rect(region.x, region.y, region.width, region.height));
        context.clip();
        context.lineWidth = 1;
        regions.forEach((region, regionIndex) => {
            [0.16, 0.48, 0.8].forEach((mark, index) => {
                const origin = {
                    x: region.x + region.width * mark,
                    y: region.y + region.height * (0.3 + index * 0.18)
                };
                const span = Math.max(region.width, region.height) * (0.7 + index * 0.25);
                const bend = (index - 1) * 18;
                context.beginPath();
                context.moveTo(origin.x - side.x * bend, origin.y - side.y * bend);
                context.lineTo(origin.x + dir.x * span + side.x * bend * 0.4, origin.y + dir.y * span + side.y * bend * 0.4);
                context.strokeStyle = regionIndex === 0 ? 'rgba(226,228,222,0.13)' : 'rgba(226,228,222,0.08)';
                context.stroke();
            });
        });
        context.restore();
    }

    function gateAt(mouth, back, fraction, scale, lateral) {
        const points = mouth.map((point, index) => mix(point, back[index], fraction));
        const center = centerOf(points);
        return points.map(point => ({
            x: center.x + (point.x - center.x) * scale + lateral.x,
            y: center.y + (point.y - center.y) * scale + lateral.y
        }));
    }

    window.miraxDStudies.set('study-195-extrusion-offset-gates', {
        id: 'study-195-extrusion-offset-gates',
        title: '195 錯層開口截面',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-51-multi-section',
        description: '同一空心縱深軌上，兩個大小不同的開口錯層同向推進；只有真正交叉的邊會被遮斷。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            const polygons = polygonsOf(frame);
            const staged = polygons.filter(polygon => onStage(polygon, width, height));
            const chosen = staged.length ? staged : polygons;
            const focus = focusOf(frame);
            const mean = chosen.length ? chosen.reduce((sum, polygon) => {
                const center = centerOf(polygon);
                return { x: sum.x + center.x / chosen.length, y: sum.y + center.y / chosen.length };
            }, { x: 0, y: 0 }) : focus;
            const shift = { x: focus.x - mean.x, y: focus.y - mean.y };
            const unit = Math.min(width, height);
            const travel = Math.hypot(shift.x, shift.y) || 1;
            const side = { x: -shift.y / travel, y: shift.x / travel };
            backdrop(context, frame, shift);
            if (!chosen.length) return;

            // Same direction, unequal speed. Wrap resets forward; it does not reverse.
            const phaseA = 0.12 + wrap(time * 0.061) * 0.76;
            const phaseB = 0.12 + wrap(time * 0.097 + 0.41) * 0.76;
            const shiftA = { x: side.x * unit * 0.055, y: side.y * unit * 0.055 };
            const shiftB = { x: -side.x * unit * 0.09, y: -side.y * unit * 0.09 };
            context.lineJoin = 'miter';
            context.lineCap = 'butt';

            chosen.forEach(polygon => {
                const center = centerOf(polygon);
                const at = { x: center.x + shift.x, y: center.y + shift.y };
                const back = placeShell(polygon, center, at, 0.86);
                const auxAt = {
                    x: center.x + shift.x * 1.34,
                    y: center.y + shift.y * 1.34
                };
                const aux = placeShell(polygon, center, auxAt, 0.7);
                const gateA = gateAt(polygon, back, phaseA, 1.16, shiftA);
                const gateB = gateAt(polygon, back, phaseB, 0.6, shiftB);
                const ribs = polygon.map((point, index) => [point, back[index]]);
                const auxRibs = back.map((point, index) => [point, aux[index]]);
                const gateEdges = [
                    ...edgesOf(gateA).map(edge => ({ edge, depth: phaseA })),
                    ...edgesOf(gateB).map(edge => ({ edge, depth: phaseB }))
                ];

                const cutsFor = (a, b, depth) => {
                    const cuts = [];
                    gateEdges.forEach(item => {
                        if (item.depth >= depth - 0.012) return;
                        const t = hitT(a, b, item.edge[0], item.edge[1]);
                        if (t != null) cuts.push(t);
                    });
                    return cuts;
                };

                context.lineWidth = 1.05;
                context.strokeStyle = 'rgba(226,228,222,0.22)';
                auxRibs.forEach(([a, b]) => strokeCut(context, a, b, []));
                strokeClosed(context, aux);

                context.lineWidth = 1.15;
                context.strokeStyle = 'rgba(234,236,230,0.32)';
                ribs.forEach(([a, b]) => {
                    const cuts = [];
                    gateEdges.forEach(item => {
                        const t = hitT(a, b, item.edge[0], item.edge[1]);
                        if (t != null && t > item.depth) cuts.push(t);
                    });
                    strokeCut(context, a, b, cuts);
                });
                strokeClosed(context, back);

                const ordered = phaseA >= phaseB
                    ? [{ points: gateA, depth: phaseA, alpha: 0.4, width: 1.15 }, { points: gateB, depth: phaseB, alpha: 0.72, width: 1.4 }]
                    : [{ points: gateB, depth: phaseB, alpha: 0.4, width: 1.15 }, { points: gateA, depth: phaseA, alpha: 0.72, width: 1.4 }];
                ordered.forEach(gate => {
                    context.lineWidth = gate.width;
                    context.strokeStyle = `rgba(244,244,238,${gate.alpha})`;
                    edgesOf(gate.points).forEach(([a, b]) => {
                        const cuts = cutsFor(a, b, gate.depth);
                        ribs.forEach(([ribA, ribB]) => {
                            const t = hitT(a, b, ribA, ribB);
                            const alongRib = t == null ? null : hitT(ribA, ribB, a, b);
                            if (alongRib != null && alongRib < gate.depth - 0.02) cuts.push(t);
                        });
                        strokeCut(context, a, b, cuts);
                    });
                });
            });
        }
    });
})();
