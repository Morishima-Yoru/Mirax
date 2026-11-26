(() => {
    'use strict';

    const clamp = (value) => Math.max(0, Math.min(1, value));
    const smooth = (value) => { const t = clamp(value); return t * t * (3 - 2 * t); };
    const distance = (a, b) => Math.hypot(b.x - a.x, b.y - a.y);
    const mix = (a, b, t) => ({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });

    function edgeNodes(frame, index, centre, reverse) {
        const vertices = frame.arms[index];
        if (!vertices || !vertices.length) return [];
        const inner = vertices.reduce((a, b) => distance(a, centre) < distance(b, centre) ? a : b);
        const edges = frame.visibleEdges.filter((edge) => edge.arm === index
            && distance(edge.a, edge.b) > 0.01
            && Math.max(edge.a.x, edge.b.x) >= 0 && Math.min(edge.a.x, edge.b.x) <= frame.width
            && Math.max(edge.a.y, edge.b.y) >= 0 && Math.min(edge.a.y, edge.b.y) <= frame.height)
            .sort((a, b) => distance(b.a, b.b) - distance(a.a, a.b));
        if (!edges.length) return [];
        const sides = [edges[0], edges[1] || edges[0]].map((edge) =>
            distance(edge.a, inner) < distance(edge.b, inner) ? [edge.a, edge.b] : [edge.b, edge.a]);
        const nodes = [0.18, 0.32, 0.49, 0.65, 0.83]
            .map((fraction, step) => mix(...sides[step % 2], fraction));
        return reverse ? nodes.reverse() : nodes;
    }

    function paint(context, a, b, brightness) {
        const length = distance(a, b);
        if (length < 0.01) return;
        const steps = Math.max(1, Math.ceil(length / 14));
        for (let step = 0; step < steps; step += 1) {
            const start = mix(a, b, step / steps);
            const end = mix(a, b, (step + 1) / steps);
            context.strokeStyle = `rgba(229,232,230,${brightness(mix(a, b, (step + 0.5) / steps), (step + 0.5) / steps)})`;
            context.lineWidth = 0.85;
            context.beginPath();
            context.moveTo(start.x, start.y);
            context.lineTo(end.x, end.y);
            context.stroke();
        }
    }

    function hop(context, a, b, age) {
        const bend = { x: a.x + (b.x - a.x) * 0.56, y: a.y };
        const first = distance(a, bend);
        const second = distance(bend, b);
        const total = first + second;
        if (total < 0.01) return;
        const envelope = smooth(age / 0.22) * (1 - smooth((age - 1) / 1.55));
        const head = smooth(age / 1.08);
        const brightness = (position) => {
            const behind = head - position;
            const front = Math.exp(-(((position - head) / 0.12) ** 2));
            const tail = behind >= 0 ? 0.2 * Math.exp(-behind / 0.26) : 0;
            return 0.006 + 0.27 * envelope * (0.8 * front + tail);
        };
        paint(context, a, bend, (point, fraction) => brightness(first * fraction / total));
        paint(context, bend, b, (point, fraction) => brightness((first + second * fraction) / total));
    }

    window.miraxDStudies.set('study-58-cathode-spark', {
        id: 'study-58-cathode-spark',
        title: '58 陰極跳火接力',
        category: '第三批 · 陰極變體',
        description: '短折角電弧從一條臂邊跳到另一條，前一跳的落點引發下一跳；接力鏈繞過上下場域而緩慢遷移。',
        draw(context, frame) {
            const centre = frame.diamond.reduce((sum, point) =>
                ({ x: sum.x + point.x / frame.diamond.length, y: sum.y + point.y / frame.diamond.length }),
            { x: 0, y: 0 });
            const at = (field, x, y) => ({ x: field.x + field.width * x, y: field.y + field.height * y });
            const top = frame.field.top;
            const bottom = frame.field.bottom;
            const nodes = [
                ...edgeNodes(frame, 0, centre, false),
                at(top, 0.46, 0.19), at(top, 0.25, 0.32), at(top, 0.05, 0.54),
                at(top, 0.26, 0.78), at(top, 0.5, 0.83),
                ...edgeNodes(frame, 1, centre, true),
                ...edgeNodes(frame, 3, centre, false),
                at(bottom, 0.58, 0.8), at(bottom, 0.3, 0.74), at(bottom, 0.06, 0.51),
                at(bottom, 0.29, 0.23), at(bottom, 0.5, 0.16),
                ...edgeNodes(frame, 2, centre, true)
            ];
            const count = nodes.length;
            const clock = frame.time / 1.12;
            const landings = [];
            context.lineCap = 'butt';
            context.lineJoin = 'miter';
            for (let index = 0; index < count; index += 1) {
                const age = ((clock - index) % count + count) % count;
                if (age > 2.55) continue;
                const next = nodes[(index + 1) % count];
                // A successor starts while the previous landing still retains charge.
                hop(context, nodes[index], next, age);
                const energy = smooth((age - 0.45) / 0.6) * (1 - smooth((age - 1.1) / 1.45));
                landings.push({ point: next, energy });
            }
            const reach = Math.max(24, Math.min(frame.width, frame.height) * 0.085);
            for (const edge of frame.visibleEdges) {
                if (!frame.arms[edge.arm] || distance(edge.a, edge.b) < 0.01
                    || Math.max(edge.a.x, edge.b.x) < 0 || Math.min(edge.a.x, edge.b.x) > frame.width
                    || Math.max(edge.a.y, edge.b.y) < 0 || Math.min(edge.a.y, edge.b.y) > frame.height) continue;
                paint(context, edge.a, edge.b, (point) => 0.008 + landings.reduce((sum, landing) =>
                    sum + 0.13 * landing.energy * Math.exp(-((distance(point, landing.point) / reach) ** 2)), 0));
            }
        }
    });
})();
