(() => {
    'use strict';

    // Crossing tracks and a horizontal channel are different directions. The window only moves forward.

    const INK = '223,232,231';

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

    function mix(a, b, t) {
        return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t };
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

    function weave(rect) {
        const nodesA = [];
        const nodesB = [];
        for (let index = 0; index < 4; index += 1) {
            const x = rect.x + rect.width * (0.06 + 0.88 * (index / 3));
            const high = rect.y + rect.height * 0.18;
            const low = rect.y + rect.height * 0.82;
            const up = index % 2 === 0;
            nodesA.push({ x, y: up ? high : low });
            nodesB.push({ x, y: up ? low : high });
        }
        return { nodesA, nodesB };
    }

    function at(nodes, position) {
        const span = nodes.length - 1;
        const clamped = Math.max(0, Math.min(span, position));
        const index = Math.min(span - 1, Math.floor(clamped));
        return mix(nodes[index], nodes[index + 1], clamped - index);
    }

    function spanPoints(nodes, start, end) {
        const marks = [start];
        for (let index = Math.floor(start) + 1; index < end; index += 1) marks.push(index);
        if (end - start > 0.001) marks.push(end);
        const points = [];
        marks.forEach(position => {
            const point = at(nodes, position);
            const prev = points[points.length - 1];
            if (!prev || Math.hypot(point.x - prev.x, point.y - prev.y) > 0.4) points.push(point);
        });
        return points;
    }

    function windowOf(time, speed, length) {
        const span = 3;
        const cycle = ((time * speed) % 1 + 1) % 1;
        const end = length + cycle * (span - length);
        return { start: end - length, end };
    }

    function pull(points, origin, amount) {
        return points.map(point => mix(point, origin, amount));
    }

    function originOf(sideA, sideB) {
        const points = [sideA[0], sideA[sideA.length - 1], sideB[0], sideB[sideB.length - 1]];
        return points.reduce((sum, point) => ({
            x: sum.x + point.x / points.length,
            y: sum.y + point.y / points.length
        }), { x: 0, y: 0 });
    }

    function drawOpen(context, sideA, sideB, lead, alpha, width) {
        const origin = originOf(sideA, sideB);
        stroke(context, pull(sideA, origin, 0.32), alpha * 0.55, width * 0.75);
        stroke(context, pull(sideB, origin, 0.32), alpha * 0.55, width * 0.75);
        stroke(context, pull(lead, origin, 0.32), alpha * 0.7, width * 0.8);
        stroke(context, sideA, alpha, width);
        stroke(context, sideB, alpha, width);
        stroke(context, lead, Math.min(0.95, alpha + 0.18), width + 0.45);
    }

    function drawWeave(context, rect, time) {
        const { nodesA, nodesB } = weave(rect);
        const cross = windowOf(time, 0.27, 1.45);
        const channel = windowOf(time + 1.7, 0.15, 1.05);
        const crossA = spanPoints(nodesA, cross.start, cross.end);
        const crossB = spanPoints(nodesB, cross.start, cross.end);
        const crossLead = [at(nodesA, cross.end), at(nodesB, cross.end)];
        const channelA = [at(nodesA, channel.start), at(nodesB, channel.end)];
        const channelB = [at(nodesB, channel.start), at(nodesA, channel.end)];
        const channelLead = [at(nodesA, channel.end), at(nodesB, channel.end)];

        for (let index = 0; index < nodesA.length; index += 1) {
            stroke(context, [nodesA[index], nodesB[index]], 0.22, 1.15);
        }
        stroke(context, nodesA, 0.5, 1.75);
        stroke(context, nodesB, 0.5, 1.75);
        drawOpen(context, channelA, channelB, channelLead, 0.58, 1.55);
        drawOpen(context, crossA, crossB, crossLead, 0.84, 2);
    }

    window.miraxDStudies.set('study-208-section-weave-depth', {
        id: 'study-208-section-weave-depth',
        title: '208 交織截面縱深',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-51-multi-section',
        scope: 'field',
        description: '雙軌在場域裡交叉。斜向開口截面沿著軌線交錯，另一條開口截面改走橫向通道，兩者共用軌上的節點並以不同速度單向換接。斜向截面後畫，蓋住交會。',
        draw(context, frame) {
            const rect = band(frame);
            const bottom = frame.field.bottom;
            context.lineCap = 'butt';
            context.lineJoin = 'round';
            drawWeave(context, rect, frame.time);
            if (!rect.compact && bottom && bottom.height > 48) drawWeave(context, bottom, frame.time);
        }
    });
})();
