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
        const x = region.x + Math.min(region.width * 0.5, Math.max(12, region.width - 280));
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

    function backdrop(context, frame, time) {
        const regions = fieldRegions(frame);
        if (!regions.length) return;
        const pitch = 64;
        const slide = wrap(time * 0.07) * pitch;
        context.save();
        context.beginPath();
        regions.forEach((region) => context.rect(region.x, region.y, region.width, region.height));
        context.clip();
        context.lineWidth = 1;
        context.strokeStyle = 'rgba(226,230,224,0.16)';
        regions.forEach((region) => {
            const span = region.height + region.width;
            for (let step = -1; step < 16; step += 1) {
                const x = region.x + step * pitch + slide;
                context.beginPath();
                context.moveTo(x, region.y);
                context.lineTo(x + span * 0.45, region.y + span);
                context.stroke();
            }
        });
        context.restore();
    }

    function strokeLink(context, from, to, time, glitch) {
        const parts = 4;
        const broken = glitch ? Math.floor(wrap(time * 0.37) * parts) % parts : -1;
        for (let index = 0; index < parts; index += 1) {
            const start = index / parts;
            const end = (index + 1) / parts;
            const shift = index === broken ? 5 : 0;
            context.strokeStyle = 'rgba(236,236,232,0.7)';
            context.lineWidth = 1.15;
            context.beginPath();
            context.moveTo(
                from.x + (to.x - from.x) * start,
                from.y + (to.y - from.y) * start + shift);
            context.lineTo(
                from.x + (to.x - from.x) * end,
                from.y + (to.y - from.y) * end + shift);
            context.stroke();
        }
        const head = wrap(time * 0.16);
        const tail = Math.max(0, head - 0.08);
        context.strokeStyle = 'rgba(248,248,244,0.95)';
        context.lineWidth = 1.7;
        context.beginPath();
        context.moveTo(from.x + (to.x - from.x) * tail, from.y + (to.y - from.y) * tail);
        context.lineTo(from.x + (to.x - from.x) * head, from.y + (to.y - from.y) * head);
        context.stroke();
    }

    window.miraxDStudies.set('study-199-glitch-frame-log', {
        id: 'study-199-glitch-frame-log',
        title: '199 幾何實況紀錄',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-127-hardcut-slides',
        description: '缺角框內固定 MIRAX 與 BIG STANDBY，末行合併視窗寬高、可見邊數與共享時間；只在短窗切片末行與連接線。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            const edges = edgesOf(frame);
            const column = columnOf(frame, 3);
            const phase = wrap(time / 8);
            const glitch = phase >= 0.72 && phase < 0.8;
            const lines = [
                'MIRAX',
                'BIG STANDBY',
                `${Math.round(width)}×${Math.round(height)} px · ${(frame.visibleEdges || []).length} 段 · ${time.toFixed(2)} s`
            ];
            const longest = edges.reduce((best, edge) => {
                if (!best || edge.length > best.length) return edge;
                return best;
            }, null);

            backdrop(context, frame, time);
            context.lineJoin = 'miter';
            context.lineCap = 'butt';
            context.textAlign = 'left';
            context.textBaseline = 'top';
            context.font = `${column.font}px sans-serif`;

            const boxW = column.narrow ? Math.min(220, width - 36) : 280;
            const boxH = lines.length * column.lineH + (column.narrow ? 2 : 8);
            const topInset = column.narrow ? 2 : 8;
            context.strokeStyle = 'rgba(236,236,232,0.62)';
            context.lineWidth = 1.25;
            context.beginPath();
            context.moveTo(column.x - 8, column.y + boxH);
            context.lineTo(column.x - 8, column.y - topInset);
            context.lineTo(column.x + boxW, column.y - topInset);
            context.moveTo(column.x + boxW * 0.46, column.y + boxH);
            context.lineTo(column.x + boxW, column.y + boxH);
            context.lineTo(column.x + boxW, column.y + boxH * 0.38);
            context.stroke();

            if (longest) {
                strokeLink(context, {
                    x: column.x + boxW,
                    y: column.y + boxH * 0.38
                }, {
                    x: (longest.a.x + longest.b.x) / 2,
                    y: (longest.a.y + longest.b.y) / 2
                }, time, glitch);
            }

            // Last row slices; product names stay registered.
            lines.forEach((text, index) => {
                paintLine(context, text, column.x, column.y + index * column.lineH, column.lineH,
                    glitch && index === 2);
            });
        }
    });
})();
