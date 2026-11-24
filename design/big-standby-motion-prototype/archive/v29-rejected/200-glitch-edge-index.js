(() => {
    'use strict';

    const wrap = (value) => ((value % 1) + 1) % 1;

    function finitePoint(point) {
        return !!point && Number.isFinite(point.x) && Number.isFinite(point.y);
    }

    function edgesOf(frame) {
        const out = [];
        (frame.visibleEdges || []).forEach((edge, index) => {
            if (!finitePoint(edge && edge.a) || !finitePoint(edge && edge.b)) return;
            const length = Number.isFinite(edge.length) && edge.length > 0.5
                ? edge.length
                : Math.hypot(edge.b.x - edge.a.x, edge.b.y - edge.a.y);
            if (length > 0.5) out.push({ a: edge.a, b: edge.b, length, index, arm: edge.arm });
        });
        return out;
    }

    function fieldRegions(frame) {
        return [frame.field && frame.field.top, frame.field && frame.field.bottom].filter((region) =>
            !!region && [region.x, region.y, region.width, region.height].every(Number.isFinite)
            && region.width > 8 && region.height > 8);
    }

    function narrowOf(frame) {
        return frame.width <= 480 && frame.height > frame.width * 1.15;
    }

    // shortcut: narrow copy is pinned to y 68-110, or y 64-112 for four lines; move it if the header grid starts above 116.
    function columnOf(frame, lineCount) {
        const narrow = narrowOf(frame);
        const tight = narrow && lineCount > 3;
        const lineH = tight ? 12 : narrow ? 14 : 18;
        const font = narrow ? 12 : 16;
        const block = lineCount * lineH;
        const top = frame.field && frame.field.top;
        if (narrow) {
            let y = tight ? 64 : 68;
            if (top && Number.isFinite(top.y) && Number.isFinite(top.height) && top.y + top.height < y + block) {
                y = Math.max(top.y + 2, top.y + top.height - block - 2);
            }
            return { x: 18, y, lineH, font, narrow };
        }
        const region = top && top.width > 8 && top.height > 8 ? top
            : { x: 14, y: 14, width: frame.width - 28, height: frame.height * 0.3 };
        const y = Math.max(region.y + 4, Math.min(
            region.y + region.height - block - 12,
            region.y + region.height - block - 2));
        const x = region.x + 16;
        return { x, y, lineH, font, narrow };
    }

    function paintLine(context, text, x, y, lineH, sliced) {
        context.fillStyle = 'rgba(241,241,236,0.95)';
        context.fillText(text, x, y);
        if (!sliced) return;
        const half = Math.max(3, lineH * 0.42);
        context.save();
        context.beginPath();
        context.rect(x - 8, y + half, 420, Math.max(4, lineH - half));
        context.clip();
        context.fillText(text, x + 8, y);
        context.restore();
    }


    function textWidth(text, font) {
        let width = 0;
        for (let index = 0; index < text.length; index += 1) {
            const code = text.charCodeAt(index);
            if (code <= 32) width += font * 0.42;
            else if (code < 128) width += font * (code >= 65 && code <= 90 ? 0.8 : 0.64);
            else width += font * 1.06;
        }
        return width;
    }

    // Overestimate glyph width so strokes stop clear of the copy.
    function glyphBox(column, lines) {
        const width = lines.reduce((max, text) => Math.max(max, textWidth(text, column.font)), 0);
        return { x: column.x, y: column.y, w: width + 14, h: lines.length * column.lineH };
    }

    function facing(from, box, yPrefer) {
        const y = Math.min(box.y + box.h, Math.max(box.y, yPrefer));
        const x = Math.min(box.x + box.w, Math.max(box.x, from.x));
        if (from.x >= box.x + box.w) return { x: box.x + box.w, y };
        if (from.x <= box.x) return { x: box.x, y };
        if (from.y >= box.y + box.h) return { x, y: box.y + box.h };
        return { x, y: box.y };
    }

    function outsideParts(x0, y0, x1, y1, box) {
        const dx = x1 - x0;
        const dy = y1 - y0;
        const ts = [0, 1];
        const pushT = (t, onEdge) => {
            if (t > 0 && t < 1 && onEdge) ts.push(t);
        };
        if (dx !== 0) {
            let t = (box.x - x0) / dx;
            pushT(t, y0 + dy * t >= box.y && y0 + dy * t <= box.y + box.h);
            t = (box.x + box.w - x0) / dx;
            pushT(t, y0 + dy * t >= box.y && y0 + dy * t <= box.y + box.h);
        }
        if (dy !== 0) {
            let t = (box.y - y0) / dy;
            pushT(t, x0 + dx * t >= box.x && x0 + dx * t <= box.x + box.w);
            t = (box.y + box.h - y0) / dy;
            pushT(t, x0 + dx * t >= box.x && x0 + dx * t <= box.x + box.w);
        }
        ts.sort((left, right) => left - right);
        const parts = [];
        for (let index = 0; index < ts.length - 1; index += 1) {
            const t0 = ts[index];
            const t1 = ts[index + 1];
            const mx = x0 + dx * ((t0 + t1) / 2);
            const my = y0 + dy * ((t0 + t1) / 2);
            if (mx > box.x && mx < box.x + box.w && my > box.y && my < box.y + box.h) continue;
            parts.push([x0 + dx * t0, y0 + dy * t0, x0 + dx * t1, y0 + dy * t1]);
        }
        return parts;
    }

    function strokeOpen(context, x0, y0, x1, y1, box) {
        outsideParts(x0, y0, x1, y1, box).forEach((part) => {
            context.beginPath();
            context.moveTo(part[0], part[1]);
            context.lineTo(part[2], part[3]);
            context.stroke();
        });
    }
    function clipHole(context, frame, box) {
        context.beginPath();
        context.rect(-4, -4, frame.width + 8, frame.height + 8);
        context.rect(box.x - 4, box.y - 3, box.w + 12, box.h + 10);
        context.clip('evenodd');
    }

    function diamondCenter(frame) {
        const diamond = (frame.diamond || []).filter(finitePoint);
        if (!diamond.length) return { x: frame.width * 0.5, y: frame.height * 0.5 };
        return diamond.reduce((sum, point) => ({
            x: sum.x + point.x / diamond.length,
            y: sum.y + point.y / diamond.length
        }), { x: 0, y: 0 });
    }

    function outward(edge, center) {
        const mx = (edge.a.x + edge.b.x) / 2;
        const my = (edge.a.y + edge.b.y) / 2;
        const len = edge.length || 1;
        let nx = -(edge.b.y - edge.a.y) / len;
        let ny = (edge.b.x - edge.a.x) / len;
        if (nx * (mx - center.x) + ny * (my - center.y) < 0) {
            nx = -nx;
            ny = -ny;
        }
        return { x: mx, y: my, nx, ny };
    }

    function backdrop(context, frame, time, box) {
        const regions = fieldRegions(frame);
        if (!regions.length) return;
        const pitch = 78;
        const slide = wrap(time * 0.05) * pitch;
        context.save();
        context.beginPath();
        regions.forEach((region) => context.rect(region.x, region.y, region.width, region.height));
        context.clip();
        context.lineWidth = 1;
        context.strokeStyle = 'rgba(220,226,224,0.12)';
        regions.forEach((region) => {
            for (let step = -1; step < 14; step += 1) {
                const y = region.y + step * pitch + slide;
                strokeOpen(context, region.x, y, region.x + region.width, y + region.width * 0.18, box);
            }
        });
        context.restore();
    }

    function traceEdge(context, edge, time, hot) {
        const steps = Math.max(4, Math.ceil(edge.length / 18));
        const head = wrap(time * 0.14 + edge.index * 0.17);
        for (let step = 0; step < steps; step += 1) {
            const t0 = step / steps;
            const t1 = (step + 1) / steps;
            let behind = head - (t0 + t1) / 2;
            if (behind < 0) behind += 1;
            const alpha = (hot ? 0.28 : 0.14) + (hot ? 0.62 : 0.4) * Math.exp(-behind * 5.5);
            context.strokeStyle = `rgba(230,234,232,${alpha.toFixed(3)})`;
            context.lineWidth = hot && behind < 0.08 ? 1.75 : 0.9;
            context.beginPath();
            context.moveTo(
                edge.a.x + (edge.b.x - edge.a.x) * t0,
                edge.a.y + (edge.b.y - edge.a.y) * t0);
            context.lineTo(
                edge.a.x + (edge.b.x - edge.a.x) * t1,
                edge.a.y + (edge.b.y - edge.a.y) * t1);
            context.stroke();
        }
    }

    window.miraxDStudies.set('study-200-glitch-edge-index', {
        id: 'study-200-glitch-edge-index',
        title: '200 輪廓索引漂移',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-17-cathode',
        description: '左側 MIRAX 與其下的邊索引留在空場，亮線停在文字外緣；陰極前鋒沿輪廓單向接續，同一時刻只切片一個索引行。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            const edges = edgesOf(frame);
            const ranked = edges.slice().sort((left, right) => right.length - left.length || left.index - right.index);
            const shown = ranked.slice(0, 3);
            const lines = ['MIRAX', ...shown.map((edge) => `邊${edge.index} ${edge.length.toFixed(1)} px`)];
            if (lines.length < 3) lines.push(`共 ${edges.length} 段`);
            if (lines.length < 3) lines.push(`共享 ${time.toFixed(2)} s`);
            const column = columnOf(frame, lines.length);
            const box = glyphBox(column, lines);
            const period = 7.6;
            const local = wrap(time / period) * period;
            const span = period / Math.max(1, shown.length);
            const slot = shown.length ? Math.min(shown.length - 1, Math.floor(local / span)) : 0;
            const within = local - slot * span;
            const glitch = shown.length > 0 && within >= span - 0.42;
            const center = diamondCenter(frame);

            context.save();
            clipHole(context, frame, box);
            backdrop(context, frame, time, box);
            context.lineJoin = 'miter';
            context.lineCap = 'butt';
            context.textAlign = 'left';
            context.textBaseline = 'top';
            context.font = `${column.font}px sans-serif`;

            edges.forEach((edge) => {
                traceEdge(context, edge, time, shown[slot] && edge.index === shown[slot].index);
            });

            shown.forEach((edge, index) => {
                const rib = outward(edge, center);
                const reach = column.narrow ? 26 : 70;
                context.strokeStyle = index === slot ? 'rgba(244,244,240,0.78)' : 'rgba(226,230,226,0.4)';
                context.lineWidth = index === slot ? 1.35 : 1;
                context.beginPath();
                context.moveTo(rib.x, rib.y);
                context.lineTo(rib.x + rib.nx * reach, rib.y + rib.ny * reach);
                if (index === slot) {
                    const rowY = column.y + (index + 1) * column.lineH + column.lineH * 0.45;
                    const stop = facing(rib, box, rowY);
                    context.moveTo(rib.x, rib.y);
                    context.lineTo(stop.x, stop.y);
                }
                context.stroke();
            });

            context.restore();
            context.textAlign = 'left';
            context.textBaseline = 'top';
            context.font = `${column.font}px sans-serif`;
            lines.forEach((text, index) => {
                paintLine(context, text, column.x, column.y + index * column.lineH, column.lineH,
                    glitch && index === slot + 1);
            });
        }
    });
})();
