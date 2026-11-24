(() => {
    'use strict';

    const wrap = value => value - Math.floor(value);

    function finitePoint(point) {
        return !!point && Number.isFinite(point.x) && Number.isFinite(point.y);
    }

    function centerOf(points) {
        return points.reduce((sum, point) => ({
            x: sum.x + point.x / points.length,
            y: sum.y + point.y / points.length
        }), { x: 0, y: 0 });
    }

    function add(point, vector, scale) {
        return { x: point.x + vector.x * scale, y: point.y + vector.y * scale };
    }

    function rotate(vector, angle) {
        const cosine = Math.cos(angle);
        const sine = Math.sin(angle);
        return {
            x: vector.x * cosine - vector.y * sine,
            y: vector.x * sine + vector.y * cosine
        };
    }

    function directionOf(frame) {
        const rail = (frame.rails || []).find(item => item && finitePoint(item[0]) && finitePoint(item[1])
            && Math.hypot(item[1].x - item[0].x, item[1].y - item[0].y) > 8);
        if (!rail) return { x: 0.48, y: -0.877 };
        const x = rail[1].x - rail[0].x;
        const y = rail[1].y - rail[0].y;
        const length = Math.hypot(x, y) || 1;
        return { x: x / length, y: y / length };
    }

    function hubOf(frame) {
        const diamond = (frame.diamond || []).filter(finitePoint);
        if (diamond.length >= 3) return centerOf(diamond);
        return { x: frame.width * 0.72, y: frame.height * 0.44 };
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
        if (t <= 0.015 || t >= 0.985 || u <= 0.015 || u >= 0.985) return null;
        return t;
    }

    function lineParam(a, b, c, d) {
        const rx = b.x - a.x;
        const ry = b.y - a.y;
        const sx = d.x - c.x;
        const sy = d.y - c.y;
        const den = rx * sy - ry * sx;
        if (Math.abs(den) < 1e-6) return null;
        const qx = c.x - a.x;
        const qy = c.y - a.y;
        return {
            t: (qx * sy - qy * sx) / den,
            u: (qx * ry - qy * rx) / den
        };
    }

    function strokeOpen(context, points) {
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        context.stroke();
    }

    function strokeCut(context, a, b, blockers) {
        const cuts = [];
        blockers.forEach(([c, d]) => {
            const t = hitT(a, b, c, d);
            if (t != null) cuts.push(t);
        });
        const gaps = cuts.map(t => [Math.max(0.01, t - 0.035), Math.min(0.99, t + 0.035)])
            .sort((left, right) => left[0] - right[0]);
        const spans = [];
        let cursor = 0;
        gaps.forEach(([start, end]) => {
            if (start > cursor) spans.push([cursor, start]);
            cursor = Math.max(cursor, end);
        });
        if (cursor < 1) spans.push([cursor, 1]);
        spans.forEach(([start, end]) => {
            if (end - start < 0.01) return;
            context.beginPath();
            context.moveTo(a.x + (b.x - a.x) * start, a.y + (b.y - a.y) * start);
            context.lineTo(a.x + (b.x - a.x) * end, a.y + (b.y - a.y) * end);
            context.stroke();
        });
    }

    function ringAt(start, end, halfW, thick, fraction) {
        const axis = { x: end.x - start.x, y: end.y - start.y };
        const length = Math.hypot(axis.x, axis.y) || 1;
        const dir = { x: axis.x / length, y: axis.y / length };
        const side = { x: -dir.y, y: dir.x };
        const skew = { x: side.x * 0.72 + dir.x * 0.22, y: side.y * 0.72 + dir.y * 0.22 };
        const skewLength = Math.hypot(skew.x, skew.y) || 1;
        const lift = { x: skew.x / skewLength, y: skew.y / skewLength };
        const center = add(start, dir, length * fraction);
        return [
            add(add(center, side, halfW), lift, thick),
            add(add(center, side, -halfW), lift, thick),
            add(add(center, side, -halfW), lift, -thick * 0.42),
            add(add(center, side, halfW), lift, -thick * 0.42)
        ];
    }

    function edgeList(points, closed) {
        const count = closed ? points.length : points.length - 1;
        const edges = [];
        for (let index = 0; index < count; index += 1) {
            edges.push([points[index], points[(index + 1) % points.length]]);
        }
        return edges;
    }

    function drawTube(context, start, end, halfW, thick, phase, wake, blockers, tone) {
        const capNear = ringAt(start, end, halfW, thick, 0.04);
        const capFar = ringAt(start, end, halfW, thick, 0.96);
        const head = ringAt(start, end, halfW, thick, phase);
        const tail = ringAt(start, end, halfW, thick, Math.max(0.04, phase - wake));
        context.lineJoin = 'miter';
        context.strokeStyle = tone.shell;
        context.lineWidth = 1.15;
        capNear.forEach((point, index) => strokeCut(context, point, capFar[index], blockers));
        edgeList(capNear, true).forEach(([a, b]) => strokeCut(context, a, b, blockers));
        edgeList(capFar, true).forEach(([a, b]) => strokeCut(context, a, b, blockers));
        context.strokeStyle = tone.wake;
        context.lineWidth = 1.05;
        head.forEach((point, index) => strokeCut(context, tail[index], point, blockers));
        edgeList(tail, true).forEach(([a, b]) => strokeCut(context, a, b, blockers));
        context.strokeStyle = tone.head;
        context.lineWidth = 1.55;
        edgeList(head, true).forEach(([a, b]) => strokeCut(context, a, b, blockers));
    }

    function fieldRegions(frame) {
        return [frame.field && frame.field.top, frame.field && frame.field.bottom].filter(region =>
            region && [region.x, region.y, region.width, region.height].every(Number.isFinite)
            && region.width > 0 && region.height > 0);
    }

    function backdrop(context, frame, dirA, dirB) {
        const regions = fieldRegions(frame);
        if (!regions.length) return;
        context.save();
        context.beginPath();
        regions.forEach(region => context.rect(region.x, region.y, region.width, region.height));
        context.clip();
        context.lineWidth = 1;
        [dirA, dirB].forEach((dir, index) => {
            const side = { x: -dir.y, y: dir.x };
            [0.22, 0.7].forEach((mark, line) => {
                regions.forEach(region => {
                    const origin = {
                        x: region.x + region.width * mark + side.x * (line === 0 ? 10 : -16),
                        y: region.y + region.height * (0.25 + line * 0.4)
                    };
                    const span = Math.max(region.width, region.height) * (0.9 + line * 0.35);
                    context.beginPath();
                    context.moveTo(origin.x - dir.x * span * 0.15, origin.y - dir.y * span * 0.15);
                    context.lineTo(origin.x + dir.x * span, origin.y + dir.y * span);
                    context.strokeStyle = index === 0 ? 'rgba(224,226,220,0.11)' : 'rgba(206,210,204,0.09)';
                    context.stroke();
                });
            });
        });
        context.restore();
    }

    window.miraxDStudies.set('study-197-extrusion-cross-section', {
        id: 'study-197-extrusion-cross-section',
        title: '197 交會稜柱切片',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-51-multi-section',
        description: '兩條斜向空心稜柱以不同角度交會於Ｘ臂周圍，截面單向通過，交會時前後互換並留下不等長殘線。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            const hub = hubOf(frame);
            const dirA = directionOf(frame);
            const dirB = rotate(dirA, 1.08);
            const unit = Math.min(width, height);
            const reach = Math.hypot(width, height) * 0.46;
            const sideA = { x: -dirA.y, y: dirA.x };
            const sideB = { x: -dirB.y, y: dirB.x };
            const narrow = width <= 480 && height > width * 1.15;
            let startA = add(add(hub, sideA, unit * 0.16), dirA, -reach);
            let endA = add(add(hub, sideA, unit * 0.16), dirA, reach * 0.92);
            let startB = add(add(hub, sideB, -unit * 0.11), dirB, -reach * 0.78);
            let endB = add(add(hub, sideB, -unit * 0.11), dirB, reach);
            if (narrow) {
                // Portrait top band is the open field; one prism stays there, the other runs toward the mark.
                startA = { x: width * 0.05, y: 106 };
                endA = { x: width * 0.94, y: 72 };
                startB = { x: width * 0.22, y: 68 };
                endB = { x: hub.x, y: Math.min(height * 0.72, hub.y + unit * 0.42) };
            }
            const halfW = Math.max(24, unit * 0.072);
            const thick = halfW * 0.82;
            backdrop(context, frame, dirA, dirB);

            const phaseA = 0.08 + wrap(time * 0.066) * 0.84;
            const phaseB = 0.08 + wrap(time * 0.112 + 0.33) * 0.84;
            const crossing = lineParam(startA, endA, startB, endB);
            const aInFront = !crossing || Math.abs(phaseA - crossing.t) <= Math.abs(phaseB - crossing.u);
            const headA = ringAt(startA, endA, halfW, thick, phaseA);
            const headB = ringAt(startB, endB, halfW, thick, phaseB);
            const capA0 = ringAt(startA, endA, halfW, thick, 0.04);
            const capA1 = ringAt(startA, endA, halfW, thick, 0.96);
            const capB0 = ringAt(startB, endB, halfW, thick, 0.04);
            const capB1 = ringAt(startB, endB, halfW, thick, 0.96);
            const blockersA = [
                ...capA0.map((point, index) => [point, capA1[index]]),
                ...edgeList(headA, true),
                ...edgeList(capA0, true),
                ...edgeList(capA1, true)
            ];
            const blockersB = [
                ...capB0.map((point, index) => [point, capB1[index]]),
                ...edgeList(headB, true),
                ...edgeList(capB0, true),
                ...edgeList(capB1, true)
            ];
            const toneA = {
                shell: 'rgba(232,234,228,0.3)',
                wake: 'rgba(236,238,232,0.22)',
                head: 'rgba(246,246,240,0.7)'
            };
            const toneB = {
                shell: 'rgba(210,214,208,0.28)',
                wake: 'rgba(210,214,208,0.2)',
                head: 'rgba(228,230,224,0.62)'
            };
            context.lineCap = 'butt';
            if (aInFront) {
                drawTube(context, startB, endB, halfW * 0.86, thick * 0.9, phaseB, 0.3, blockersA, toneB);
                drawTube(context, startA, endA, halfW, thick, phaseA, 0.12, [], toneA);
            } else {
                drawTube(context, startA, endA, halfW, thick, phaseA, 0.12, blockersB, toneA);
                drawTube(context, startB, endB, halfW * 0.86, thick * 0.9, phaseB, 0.3, [], toneB);
            }
        }
    });
})();
