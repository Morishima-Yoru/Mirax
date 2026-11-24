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

    function narrowOf(frame) {
        return frame.width <= 480 && frame.height > frame.width * 1.15;
    }

    function focusAt(frame, xBias, yBias) {
        const top = frame.field && frame.field.top;
        if (narrowOf(frame)) return { x: frame.width * xBias, y: 64 + 50 * yBias };
        if (top && top.width > 8 && top.height > 8) {
            return { x: top.x + top.width * xBias, y: top.y + top.height * yBias };
        }
        return { x: frame.width * xBias, y: frame.height * (0.12 + 0.2 * yBias) };
    }

    function placeShell(polygon, center, at, taper) {
        return polygon.map(point => ({
            x: at.x + (point.x - center.x) * taper,
            y: at.y + (point.y - center.y) * taper
        }));
    }

    function mixPoly(mouth, back, fraction) {
        return mouth.map((point, index) => ({
            x: point.x + (back[index].x - point.x) * fraction,
            y: point.y + (back[index].y - point.y) * fraction
        }));
    }

    function strokeOpen(context, points) {
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        context.stroke();
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

    function longestEdge(frame) {
        let best = null;
        (frame.visibleEdges || []).forEach(edge => {
            if (!finitePoint(edge.a) || !finitePoint(edge.b)) return;
            if (!best || edge.length > best.length) best = edge;
        });
        if (best) return best;
        (frame.arms || []).forEach((polygon, arm) => {
            if (!Array.isArray(polygon)) return;
            polygon.forEach((point, index) => {
                const next = polygon[(index + 1) % polygon.length];
                if (!finitePoint(point) || !finitePoint(next)) return;
                const length = Math.hypot(next.x - point.x, next.y - point.y);
                if (!best || length > best.length) best = { a: point, b: next, length, arm };
            });
        });
        return best;
    }

    function fieldRegions(frame) {
        return [frame.field && frame.field.top, frame.field && frame.field.bottom].filter(region =>
            region && [region.x, region.y, region.width, region.height].every(Number.isFinite)
            && region.width > 0 && region.height > 0);
    }

    function backdrop(context, frame, origins) {
        const regions = fieldRegions(frame);
        if (!regions.length) return;
        context.save();
        context.beginPath();
        regions.forEach(region => context.rect(region.x, region.y, region.width, region.height));
        context.clip();
        context.lineWidth = 1;
        origins.forEach((item, index) => {
            const length = Math.hypot(item.dir.x, item.dir.y) || 1;
            const dir = { x: item.dir.x / length, y: item.dir.y / length };
            const side = { x: -dir.y, y: dir.x };
            [-0.16, 0, 0.2].forEach((offset, line) => {
                const span = Math.hypot(frame.width, frame.height) * (0.34 + line * 0.16);
                const origin = {
                    x: item.from.x + side.x * offset * Math.min(frame.width, frame.height),
                    y: item.from.y + side.y * offset * Math.min(frame.width, frame.height)
                };
                context.beginPath();
                context.moveTo(origin.x, origin.y);
                context.lineTo(origin.x + dir.x * span, origin.y + dir.y * span);
                context.strokeStyle = index === 0 ? 'rgba(228,230,224,0.12)' : 'rgba(206,210,204,0.1)';
                context.stroke();
            });
        });
        context.restore();
    }

    function drawFan(context, mouth, back, phase, trail) {
        mouth.forEach((point, index) => strokeOpen(context, [point, back[index]]));
        strokeClosed(context, back);
        const head = mixPoly(mouth, back, phase);
        const tail = mixPoly(mouth, back, Math.max(0.05, phase - trail));
        context.strokeStyle = 'rgba(244,244,238,0.28)';
        context.lineWidth = 1.05;
        strokeClosed(context, tail);
        head.forEach((point, index) => strokeOpen(context, [tail[index], point]));
        context.strokeStyle = 'rgba(244,244,238,0.66)';
        context.lineWidth = 1.5;
        strokeClosed(context, head);
    }

    window.miraxDStudies.set('study-196-extrusion-fan', {
        id: 'study-196-extrusion-fan',
        title: '196 分歧截面扇',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-118-section-extrusion',
        description: '一段Ｘ邊緣分向兩個後方消失點，兩組空心截面以不同節奏由近到遠接續，共用起點保持不動。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            const edge = longestEdge(frame);
            const arms = frame.arms || [];
            const mouth = edge && Array.isArray(arms[edge.arm]) && arms[edge.arm].length >= 3
                && arms[edge.arm].every(finitePoint)
                ? arms[edge.arm]
                : (polygonsOf(frame)[0] || null);
            const focusA = focusAt(frame, 0.24, 0.28);
            const focusB = focusAt(frame, 0.78, 0.82);
            const center = mouth ? centerOf(mouth) : { x: width * 0.7, y: height * 0.45 };
            backdrop(context, frame, [
                { from: center, dir: { x: focusA.x - center.x, y: focusA.y - center.y } },
                { from: center, dir: { x: focusB.x - center.x, y: focusB.y - center.y } }
            ]);
            if (!mouth) return;

            const backA = placeShell(mouth, center, focusA, 0.9);
            const backB = placeShell(mouth, center, focusB, 0.66);
            // Independent forward cycles. Shared mouth is not part of either phase.
            const phaseA = 0.16 + wrap(time * 0.072) * 0.74;
            const phaseB = 0.16 + wrap(time * 0.128 + 0.37) * 0.74;
            context.lineJoin = 'miter';
            context.lineCap = 'butt';
            context.lineWidth = 1.15;
            context.strokeStyle = 'rgba(236,238,232,0.34)';
            strokeClosed(context, mouth);
            if (edge) {
                context.lineWidth = 1.4;
                context.strokeStyle = 'rgba(244,244,238,0.5)';
                strokeOpen(context, [edge.a, edge.b]);
            }
            context.strokeStyle = 'rgba(232,234,228,0.3)';
            context.lineWidth = 1.1;
            drawFan(context, mouth, backA, phaseA, 0.1);
            context.strokeStyle = 'rgba(214,218,212,0.28)';
            context.lineWidth = 1.1;
            drawFan(context, mouth, backB, phaseB, 0.2);
        }
    });
})();
