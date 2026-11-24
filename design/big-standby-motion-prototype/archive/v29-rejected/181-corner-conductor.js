(() => {
    'use strict';

    const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
    const pt = (p) => ({ x: p.x, y: p.y });
    const near = (a, b) => Math.hypot(a.x - b.x, a.y - b.y) < 0.6;
    const mix = (a, b, t) => ({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
    const dist = (a, b) => Math.hypot(b.x - a.x, b.y - a.y);

    function fields(frame) {
        const top = frame.field && frame.field.top;
        const bottom = frame.field && frame.field.bottom;
        return {
            top: top || { x: 14, y: 14, width: Math.max(40, frame.width - 28), height: Math.max(48, frame.height * 0.28) },
            bottom: bottom || { x: 14, y: frame.height * 0.62, width: Math.max(40, frame.width - 28), height: Math.max(36, frame.height * 0.18) }
        };
    }

    function narrowOf(frame) {
        return frame.width < 480 && frame.height > frame.width;
    }

    function bandY(frame, t) {
        const { top } = fields(frame);
        if (narrowOf(frame)) return 64 + 50 * clamp(t, 0, 1);
        return top.y + Math.max(top.height, 1) * (0.2 + 0.58 * clamp(t, 0, 1));
    }

    function stroke(context, points, alpha, width) {
        if (!points || points.length < 2 || !(alpha > 0.004)) return;
        const clean = [];
        for (const p of points) {
            if (!p || !Number.isFinite(p.x) || !Number.isFinite(p.y)) continue;
            const last = clean[clean.length - 1];
            if (!last || Math.hypot(p.x - last.x, p.y - last.y) > 0.25) clean.push(p);
        }
        if (clean.length < 2) return;
        context.beginPath();
        context.moveTo(clean[0].x, clean[0].y);
        for (let i = 1; i < clean.length; i += 1) context.lineTo(clean[i].x, clean[i].y);
        context.strokeStyle = `rgba(255,255,255,${clamp(alpha, 0, 1)})`;
        context.lineWidth = width;
        context.stroke();
    }

    function orderEdges(edges) {
        const unused = edges.slice();
        const chains = [];
        while (unused.length) {
            const first = unused.shift();
            const chain = [pt(first.a), pt(first.b)];
            let guard = 0;
            let grew = true;
            while (grew && unused.length && guard < 8) {
                guard += 1;
                grew = false;
                const head = chain[0];
                const tail = chain[chain.length - 1];
                for (let i = 0; i < unused.length; i += 1) {
                    const edge = unused[i];
                    if (near(edge.a, tail)) { chain.push(pt(edge.b)); unused.splice(i, 1); grew = true; break; }
                    if (near(edge.b, tail)) { chain.push(pt(edge.a)); unused.splice(i, 1); grew = true; break; }
                    if (near(edge.b, head)) { chain.unshift(pt(edge.a)); unused.splice(i, 1); grew = true; break; }
                    if (near(edge.a, head)) { chain.unshift(pt(edge.b)); unused.splice(i, 1); grew = true; break; }
                }
            }
            if (chain.length > 1) chains.push(chain);
        }
        return chains;
    }

    function nodesOf(frame) {
        const groups = [[], [], [], []];
        for (const edge of frame.visibleEdges || []) {
            if (edge && edge.length > 1 && edge.arm >= 0 && edge.arm < 4) groups[edge.arm].push(edge);
        }
        const list = [];
        groups.forEach((edges, arm) => {
            for (const chain of orderEdges(edges)) {
                for (let i = 1; i < chain.length - 1; i += 1) {
                    const a = chain[i - 1];
                    const p = chain[i];
                    const b = chain[i + 1];
                    const l1 = dist(a, p);
                    const l2 = dist(p, b);
                    if (l1 < 6 || l2 < 6) continue;
                    const dot = clamp(((p.x - a.x) * (b.x - p.x) + (p.y - a.y) * (b.y - p.y)) / (l1 * l2), -1, 1);
                    if (Math.acos(dot) < 0.34) continue;
                    list.push({ p, prev: a, next: b, arm });
                }
            }
        });
        list.sort((a, b) => a.p.y - b.p.y || a.p.x - b.p.x || a.arm - b.arm);
        const picked = [];
        for (const node of list) {
            if (picked.some((item) => dist(item.p, node.p) < 30)) continue;
            picked.push(node);
            if (picked.length === 4) break;
        }
        return picked;
    }

    function anchors(frame, node, index) {
        const { top, bottom } = fields(frame);
        const narrow = narrowOf(frame);
        const long = {
            x: clamp(top.x + top.width * (0.08 + (index % 4) * 0.2), 8, frame.width - 8),
            y: clamp(narrow ? 64 + (index % 4) * 14 : bandY(frame, 0.15 + (index % 4) * 0.18), 8, frame.height - 8)
        };
        const short = {
            x: clamp(node.p.x + (index % 2 ? -1 : 1) * Math.min(frame.width * 0.14, 110), 8, frame.width - 8),
            y: clamp(narrow ? 108 - (index % 3) * 10 : node.p.y - Math.min(64, top.height * 0.28), 8, frame.height - 8)
        };
        const low = {
            x: clamp(bottom.x + bottom.width * (0.14 + (index % 3) * 0.22), 8, frame.width - 8),
            y: clamp(bottom.y + Math.min(30, bottom.height * 0.35), 8, frame.height - 8)
        };
        return { long, short, low };
    }

    function along(from, to, length) {
        const span = dist(from, to);
        if (span < 1) return pt(from);
        return mix(from, to, clamp(length / span, 0, 1));
    }

    function symbolCenter(frame) {
        const diamond = frame.diamond || [];
        if (!diamond.length) return { x: frame.width * 0.7, y: frame.height * 0.45 };
        let x = 0;
        let y = 0;
        for (const point of diamond) { x += point.x; y += point.y; }
        return { x: x / diamond.length, y: y / diamond.length };
    }

    function fieldAim(frame, point) {
        const { top, bottom } = fields(frame);
        const upper = { x: top.x + top.width * 0.28, y: bandY(frame, 0.45) };
        const lower = { x: bottom.x + bottom.width * 0.28, y: bottom.y + Math.min(36, bottom.height * 0.35) };
        const up = Math.hypot(point.x - upper.x, point.y - upper.y);
        const down = Math.hypot(point.x - lower.x, point.y - lower.y);
        return up <= down ? upper : lower;
    }

    function nodeFigure(frame, node, pose) {
        const center = symbolCenter(frame);
        const aim = fieldAim(frame, node.p);
        let ox = aim.x - node.p.x;
        let oy = aim.y - node.p.y;
        const away = (node.p.x - center.x) * ox + (node.p.y - center.y) * oy;
        if (away < 0) { ox = -ox; oy = -oy; }
        const foreX = node.next.x - node.p.x;
        const foreY = node.next.y - node.p.y;
        ox = ox * (1 - pose * 0.45) + foreX * pose * 0.45;
        oy = oy * (1 - pose * 0.45) + foreY * pose * 0.45;
        const span = Math.hypot(ox, oy) || 1;
        const opening = Math.max(32, Math.min(frame.width * 0.055, 68)) * (0.75 + 0.7 * pose);
        const apex = { x: node.p.x + ox / span * opening, y: node.p.y + oy / span * opening };
        const scale = Math.max(0.72, Math.min(frame.width / 900, 1.15));
        const inPt = along(node.p, node.prev, (26 + 84 * (1 - pose)) * scale);
        const outPt = along(node.p, node.next, (24 + 96 * pose) * scale);
        return { apex, inPt, outPt };
    }

    window.miraxDStudies.set('study-181-corner-conductor', {
        id: 'study-181-corner-conductor',
        sourceId: 'study-73-cathode-nodes',
        title: '181 轉角導體',
        category: '第九批 · 歷史回饋',
        description: '可見轉角形成相連的三角形節點，長短斜軌把節點接進上下場。輸入邊收束、輸出邊延伸，開口隨接點輪替，輪廓不斷開。',
        draw(context, frame) {
            context.lineCap = 'butt';
            context.lineJoin = 'miter';
            const groups = [[], [], [], []];
            for (const edge of frame.visibleEdges || []) {
                if (edge && edge.length > 1 && edge.arm >= 0 && edge.arm < 4) groups[edge.arm].push(edge);
            }
            for (const edges of groups) {
                for (const chain of orderEdges(edges)) stroke(context, chain, 0.18, 1.05);
            }
            let nodes = nodesOf(frame);
            if (nodes.length < 2) {
                const { top } = fields(frame);
                const p = { x: top.x + top.width * 0.62, y: bandY(frame, 0.55) };
                nodes = nodes.concat([{
                    p,
                    prev: { x: p.x - 48, y: p.y + 18 },
                    next: { x: p.x + 18, y: p.y + 42 },
                    arm: 0
                }]);
            }
            const n = nodes.length;
            const period = 2.15;
            const turn = Math.floor(frame.time / period);
            const active = turn % n;
            const u = frame.time / period - turn;
            const figures = nodes.map((node, index) => {
                const fires = turn < index ? 0 : Math.floor((turn - index) / n) + 1;
                const live = index === active;
                const pose = live ? u : (fires > 0 ? 1 : 0);
                return nodeFigure(frame, node, pose);
            });
            const { top, bottom } = fields(frame);
            const topApex = [];
            const lowApex = [];
            figures.forEach((figure, index) => {
                if (nodes[index].p.y >= bottom.y - 2) lowApex.push(figure.apex);
                else topApex.push(figure.apex);
            });
            if (topApex.length > 1) stroke(context, topApex, 0.5, 1.25);
            if (lowApex.length > 1) stroke(context, lowApex, 0.42, 1.15);
            nodes.forEach((node, index) => {
                const figure = figures[index];
                const live = index === active;
                const box = node.p.y >= bottom.y - 2 ? bottom : top;
                const far = {
                    x: clamp(box.x + box.width * (0.1 + (index % 3) * 0.26), box.x + 8, box.x + box.width - 8),
                    y: clamp(node.p.y < bottom.y ? bandY(frame, 0.18 + (index % 3) * 0.28) : box.y + Math.min(24 + index * 12, box.height * 0.55), box.y + 8, box.y + box.height - 8)
                };
                const near = {
                    x: clamp(node.p.x + (index % 2 ? -1 : 1) * Math.min(box.width * 0.18, 120), box.x + 8, box.x + box.width - 8),
                    y: clamp(node.p.y + (node.p.y < bottom.y ? -1 : 1) * Math.min(42, box.height * 0.28), box.y + 8, box.y + box.height - 8)
                };
                const apex = {
                    x: clamp(figure.apex.x, box.x + 8, box.x + box.width - 8),
                    y: clamp(figure.apex.y, box.y + 8, box.y + box.height - 8)
                };
                stroke(context, [apex, far], live ? 0.95 : 0.62, live ? 1.6 : 1.3);
                stroke(context, [node.p, near], live ? 0.8 : 0.48, 1.15);
                stroke(context, [figure.inPt, node.p, apex, figure.outPt, figure.inPt], live ? 1 : 0.74, live ? 1.75 : 1.4);
            });
        }
    });
})();
