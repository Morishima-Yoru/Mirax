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
            if (length > 0.5) out.push({ a: edge.a, b: edge.b, length, index });
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

    // shortcut: narrow copy is pinned to y 68-110, move it if the header grid starts above 116.
    function columnOf(frame, lineCount) {
        const narrow = narrowOf(frame);
        const lineH = narrow ? 14 : 18;
        const font = narrow ? 12 : 16;
        const block = lineCount * lineH;
        const top = frame.field && frame.field.top;
        if (narrow) {
            let y = 68;
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

    function shiftOf(time, index) {
        const local = wrap(time / 9) * 9;
        const start = 6.15 + index * 0.55;
        return local >= start && local < start + 0.36 ? 8 : 0;
    }

    function sampleStrand(points, distance, side, time, total) {
        let remain = distance;
        for (let index = 1; index < points.length; index += 1) {
            const from = points[index - 1];
            const to = points[index];
            const seg = Math.hypot(to.x - from.x, to.y - from.y);
            if (remain <= seg || index === points.length - 1) {
                const t = seg > 0.001 ? Math.max(0, Math.min(1, remain / seg)) : 0;
                const x = from.x + (to.x - from.x) * t;
                const y = from.y + (to.y - from.y) * t;
                const len = seg || 1;
                const nx = -(to.y - from.y) / len;
                const ny = (to.x - from.x) / len;
                const along = total > 0 ? distance / total : 0;
                const wave = 6 * Math.sin(along * Math.PI * 5 - time * 0.9);
                const off = side * (4.5 + wave);
                return { x: x + nx * off, y: y + ny * off };
            }
            remain -= seg;
        }
        return points[points.length - 1];
    }

    function strokeChain(context, points, alpha, width, box) {
        context.strokeStyle = `rgba(230,234,232,${alpha.toFixed(3)})`;
        context.lineWidth = width;
        context.beginPath();
        for (let index = 1; index < points.length; index += 1) {
            outsideParts(points[index - 1].x, points[index - 1].y, points[index].x, points[index].y, box)
                .forEach((part) => {
                    context.moveTo(part[0], part[1]);
                    context.lineTo(part[2], part[3]);
                });
        }
        context.stroke();
    }

    window.miraxDStudies.set('study-203-glitch-contour-log', {
        id: 'study-203-glitch-contour-log',
        title: '203 輪廓重建記錄',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-74-cathode-weave',
        description: '交織線停在左側文字外緣，內容是 BIG STANDBY、可見邊數、共享時間與畫布尺寸；逐行錯位後回到原位。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            const edges = edgesOf(frame);
            const column = columnOf(frame, 3);
            const lines = [
                'BIG STANDBY',
                `可見邊 ${(frame.visibleEdges || []).length} 段 · ${time.toFixed(2)} s`,
                `畫布 ${Math.round(width)}×${Math.round(height)} px`
            ];
            const box = glyphBox(column, lines);
            const margin = { x: box.x - 14, y: box.y - 14, w: box.w + 28, h: box.h + 28 };
            const shifts = lines.map((text, index) => shiftOf(time, index));
            const active = shifts.findIndex((shift) => shift !== 0);
            const longest = edges.reduce((best, edge) => {
                if (!best || edge.length > best.length) return edge;
                return best;
            }, null);
            const origin = longest
                ? longest.b
                : (frame.diamond && finitePoint(frame.diamond[0]) ? frame.diamond[0] : { x: 28, y: height * 0.42 });
            const meet = facing(origin, margin, origin.y);
            const path = longest ? [longest.a, longest.b, meet] : [origin, meet];
            let total = 0;
            for (let index = 1; index < path.length; index += 1) {
                total += Math.hypot(path[index].x - path[index - 1].x, path[index].y - path[index - 1].y);
            }
            const steps = 42;
            const left = [];
            const right = [];
            for (let step = 0; step <= steps; step += 1) {
                const distance = total * step / steps;
                left.push(sampleStrand(path, distance, -1, time, total));
                right.push(sampleStrand(path, distance, 1, time, total));
            }

            context.save();
            clipHole(context, frame, box);
            const regions = fieldRegions(frame);
            if (regions.length) {
                const slide = wrap(time * 0.06) * 56;
                context.save();
                context.beginPath();
                regions.forEach((region) => context.rect(region.x, region.y, region.width, region.height));
                context.clip();
                context.strokeStyle = 'rgba(220,224,220,0.12)';
                context.lineWidth = 1;
                regions.forEach((region) => {
                    for (let step = -1; step < 8; step += 1) {
                        const x = region.x + step * 70 + slide;
                        strokeOpen(context, x, region.y + region.height, x + region.height * 0.7, region.y, box);
                    }
                });
                context.restore();
            }

            context.lineJoin = 'round';
            context.lineCap = 'round';
            context.textAlign = 'left';
            context.textBaseline = 'top';
            context.font = `${column.font}px sans-serif`;
            strokeChain(context, left, 0.62, 1.15, box);
            strokeChain(context, right, 0.4, 1.05, box);
            [0.22, 0.46, 0.7, 0.94].forEach((mark, index) => {
                const at = Math.round(mark * steps);
                const hot = active === index % 3;
                context.strokeStyle = hot ? 'rgba(248,248,244,0.92)' : 'rgba(226,230,226,0.38)';
                context.lineWidth = hot ? 1.45 : 0.95;
                strokeOpen(context, left[at].x, left[at].y, right[at].x, right[at].y, box);
            });
            const outerX = box.x + box.w + 14;
            context.strokeStyle = 'rgba(226,230,226,0.45)';
            context.lineWidth = 1;
            context.beginPath();
            context.moveTo(outerX, margin.y);
            context.lineTo(outerX, margin.y + margin.h);
            context.stroke();
            lines.forEach((text, index) => {
                const rowY = column.y + index * column.lineH + column.lineH * 0.45;
                context.strokeStyle = active === index ? 'rgba(248,248,244,0.85)' : 'rgba(226,230,226,0.4)';
                context.lineWidth = 1;
                context.beginPath();
                context.moveTo(outerX, rowY);
                context.lineTo(box.x + box.w, rowY);
                context.stroke();
            });
            context.restore();
            context.textAlign = 'left';
            context.textBaseline = 'top';
            context.font = `${column.font}px sans-serif`;
            lines.forEach((text, index) => {
                paintLine(context, text, column.x + shifts[index], column.y + index * column.lineH,
                    column.lineH, false);
            });
        }
    });
})();
