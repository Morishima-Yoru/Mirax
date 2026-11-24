(() => {
    'use strict';

    function tracePath(context, points, start = 0, end = 1) {
        const lengths = points.slice(1).map((point, index) =>
            Math.hypot(point[0] - points[index][0], point[1] - points[index][1]));
        const total = lengths.reduce((sum, length) => sum + length, 0);
        let passed = 0;
        for (let index = 0; index < lengths.length; index++) {
            const length = lengths[index];
            const a = Math.max(0, (start * total - passed) / length);
            const b = Math.min(1, (end * total - passed) / length);
            if (b > a) {
                const first = points[index];
                const next = points[index + 1];
                context.moveTo(first[0] + (next[0] - first[0]) * a, first[1] + (next[1] - first[1]) * a);
                context.lineTo(first[0] + (next[0] - first[0]) * b, first[1] + (next[1] - first[1]) * b);
            }
            passed += length;
        }
    }

    window.miraxDStudies.set('study-175-relay-topology', {
        id: 'study-175-relay-topology',
        title: '175 轉接拓撲介面',
        category: '第八批 · 科幻朋克',
        sourceId: 'study-73-cathode-nodes',
        scope: 'field',
        description: 'X 角外的三個大小不等切角節點以折線串接，左側 SIM 格直接接入同一條路由；短封包依序交給三節點，格內路徑編號同步換位。僅為模擬拓撲，不表示真實連線狀態。',
        draw(context, frame) {
            const field = frame.field.top;
            const compact = frame.width < 600 || field.height < 135;
            const left = field.x + (compact ? 6 : field.width * 0.08);
            const width = field.width * (compact ? 0.94 : 0.83);
            const top = compact ? Math.min(64, field.y + field.height - 49)
                : field.y + field.height * 0.56;
            const height = compact ? 48 : Math.min(100, field.height * 0.34);
            const boxWidth = compact ? 63 : 98;
            const boxHeight = compact ? 23 : 29;
            const nodes = [
                { x: left + width * 0.34, y: top + height * 0.48, r: compact ? 12 : 23 },
                { x: left + width * 0.65, y: top + height * 0.62, r: compact ? 17 : 32 },
                { x: left + width * 0.94, y: top + height * 0.29, r: compact ? 11 : 20 }
            ];
            const boxY = top + 1;
            const routes = [
                [[left + boxWidth, boxY + boxHeight * 0.5],
                    [nodes[0].x - nodes[0].r - 8, boxY + boxHeight * 0.5],
                    [nodes[0].x - nodes[0].r, nodes[0].y]],
                [[nodes[0].x + nodes[0].r, nodes[0].y],
                    [nodes[1].x - nodes[1].r - height * 0.14, nodes[0].y],
                    [nodes[1].x - nodes[1].r, nodes[1].y]],
                [[nodes[1].x + nodes[1].r, nodes[1].y],
                    [nodes[2].x - nodes[2].r - height * 0.33, nodes[1].y],
                    [nodes[2].x - nodes[2].r, nodes[2].y]]
            ];
            const step = frame.time / 1.9;
            const state = Math.floor(step) % 3;
            const progress = step % 1;

            context.save();
            context.lineWidth = 1.2;
            context.strokeStyle = 'rgba(238,238,234,0.48)';
            context.beginPath();
            context.rect(left, boxY, boxWidth, boxHeight);
            for (const node of nodes) {
                const cut = node.r * 0.42;
                context.moveTo(node.x - node.r + cut, node.y - node.r);
                context.lineTo(node.x + node.r - cut, node.y - node.r);
                context.lineTo(node.x + node.r, node.y - node.r + cut);
                context.lineTo(node.x + node.r, node.y + node.r - cut);
                context.lineTo(node.x + node.r - cut, node.y + node.r);
                context.lineTo(node.x - node.r + cut, node.y + node.r);
                context.lineTo(node.x - node.r, node.y + node.r - cut);
                context.lineTo(node.x - node.r, node.y - node.r + cut);
                context.closePath();
            }
            routes.forEach(route => tracePath(context, route));
            context.stroke();

            // The next node is marked while the packet traverses its incoming route.
            const node = nodes[state];
            context.strokeStyle = 'rgba(246,246,242,0.87)';
            context.lineWidth = 2;
            context.beginPath();
            tracePath(context, routes[state], Math.max(0, progress - 0.24), Math.min(1, progress + 0.15));
            context.moveTo(node.x - node.r * 0.47, node.y + node.r * 0.24);
            context.lineTo(node.x, node.y - node.r * 0.24);
            context.lineTo(node.x + node.r * 0.47, node.y + node.r * 0.24);
            context.stroke();

            context.fillStyle = 'rgba(246,246,242,0.87)';
            context.font = compact ? '10px monospace' : '12px monospace';
            context.fillText(`SIM R${state + 1}`, left + 7, boxY + boxHeight * 0.67);

            // One extension follows the original X corner; the topology remains the focus.
            const corner = frame.arms[1][3];
            const last = nodes[2];
            context.lineWidth = 1;
            context.strokeStyle = 'rgba(238,238,234,0.22)';
            context.beginPath();
            context.moveTo(last.x + last.r * 0.6, last.y + last.r);
            context.lineTo(corner.x, corner.y);
            context.stroke();
            context.restore();
        }
    });
})();
