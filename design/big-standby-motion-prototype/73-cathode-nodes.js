(() => {
    'use strict';

    const distance = (a, b) => Math.hypot(b.x - a.x, b.y - a.y);
    const mix = (a, b, t) => ({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
    const smooth = (value) => {
        const t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    };

    function arms(frame) {
        return (frame.arms || []).flatMap((points, arm) => {
            if (!points || points.length < 3 || points.some((p) =>
                !Number.isFinite(p.x) || !Number.isFinite(p.y))) return [];
            const visible = (frame.visibleEdges || []).some((edge) => edge.arm === arm
                && distance(edge.a, edge.b) > 1
                && Math.max(edge.a.x, edge.b.x) >= 0
                && Math.min(edge.a.x, edge.b.x) <= frame.width
                && Math.max(edge.a.y, edge.b.y) >= 0
                && Math.min(edge.a.y, edge.b.y) <= frame.height);
            if (!visible) return [];
            const center = points.reduce((sum, p) => ({ x: sum.x + p.x / points.length,
                y: sum.y + p.y / points.length }), { x: 0, y: 0 });
            return [{ points, center, arm }];
        });
    }

    function line(context, points, alpha, width = 1.1) {
        context.strokeStyle = `rgba(225,232,230,${alpha})`;
        context.lineWidth = width;
        context.beginPath();
        points.forEach((p, index) => {
            if (index === 0) context.moveTo(p.x, p.y);
            else context.lineTo(p.x, p.y);
        });
        context.stroke();
    }

    window.miraxDStudies.set('study-73-cathode-nodes', {
        id: 'study-73-cathode-nodes',
        sourceId: 'study-17-cathode',
        title: '73 · 17-B 轉角蓄接',
        description: '每個輪廓角形成相連的三角接點；入線縮短時出線伸長，內側分岔角隨蓄力偏轉，逐角交接而非整圈掃光。',
        category: '第四批 · 17 描邊延伸',
        draw(context, frame) {
            context.save();
            context.lineCap = 'round';
            context.lineJoin = 'round';
            for (const arm of arms(frame)) {
                const { points, center } = arm;
                line(context, [...points, points[0]], 0.11, 0.9);
                points.forEach((corner, index) => {
                    const previous = points[(index + points.length - 1) % points.length];
                    const next = points[(index + 1) % points.length];
                    const incoming = distance(previous, corner);
                    const outgoing = distance(corner, next);
                    if (Math.min(incoming, outgoing) < 3) return;
                    const clock = frame.time / 6.4 + arm.arm * 0.18 - index * 0.19;
                    const phase = ((clock % 1) + 1) % 1;
                    const receive = smooth(phase / 0.46);
                    const release = smooth((phase - 0.46) / 0.54);
                    const transfer = phase < 0.46 ? receive : 1 - release;
                    const reachIn = Math.min(incoming * 0.46, 90)
                        * (0.3 + 0.7 * transfer);
                    const reachOut = Math.min(outgoing * 0.46, 90)
                        * (1 - 0.7 * transfer);
                    const entry = mix(corner, previous, reachIn / incoming);
                    const exit = mix(corner, next, reachOut / outgoing);
                    const radius = Math.max(1, distance(corner, center));
                    const depth = Math.min(18, incoming * 0.18, outgoing * 0.18)
                        * (0.35 + 0.65 * transfer);
                    const node = mix(corner, center, depth / radius);
                    const skew = (transfer - 0.5) * 0.35;
                    const junction = mix(node, mix(entry, exit, 0.5 + skew), 0.2);
                    line(context, [entry, corner, exit], 0.31, 1.2);
                    line(context, [entry, junction, exit], 0.36);
                    line(context, [corner, junction], 0.48, 1.15);
                });
            }
            context.restore();
        }
    });
})();
