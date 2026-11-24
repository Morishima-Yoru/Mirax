(() => {
    'use strict';

    const wrap = (value) => ((value % 1) + 1) % 1;
    const mix = (a, b, t) => ({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });

    function finitePoint(point) {
        return !!point && Number.isFinite(point.x) && Number.isFinite(point.y);
    }

    function diamondOf(frame) {
        const diamond = frame.diamond || [];
        if (diamond.length < 4 || !diamond.every(finitePoint)) return null;
        return diamond.slice(0, 4);
    }

    function centerOf(points) {
        return points.reduce((sum, point) => ({
            x: sum.x + point.x / points.length,
            y: sum.y + point.y / points.length
        }), { x: 0, y: 0 });
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
        const y = Math.max(region.y + 6, region.y + region.height - block - 10);
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
        context.rect(x - 8, y + half, 460, Math.max(4, lineH - half));
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

    function fitShell(points, at, maxSpan) {
        const center = centerOf(points);
        let radius = 1;
        points.forEach((point) => {
            radius = Math.max(radius, Math.hypot(point.x - center.x, point.y - center.y));
        });
        const scale = maxSpan / radius;
        return points.map((point) => ({
            x: at.x + (point.x - center.x) * scale,
            y: at.y + (point.y - center.y) * scale
        }));
    }

    function strokeClosed(context, points) {
        context.beginPath();
        context.moveTo(points[0].x, points[0].y);
        for (let index = 1; index < points.length; index += 1) context.lineTo(points[index].x, points[index].y);
        context.closePath();
        context.stroke();
    }

    function pairText(points) {
        const names = ['頂', '右', '底', '左'];
        const body = (index) => {
            const point = points[index];
            return `${names[index]} ${Math.round(point.x)},${Math.round(point.y)}`;
        };
        return [`${body(0)}  ${body(1)} px`, `${body(2)}  ${body(3)} px`];
    }

    window.miraxDStudies.set('study-201-glitch-section-readout', {
        id: 'study-201-glitch-section-readout',
        title: '201 截面座標錯位',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-118-section-extrusion',
        description: '左側列出 BIG STANDBY、視窗尺寸與菱形頂點；空心框在文字右緣之外連往菱形，數字行與框的第一邊短暫錯列。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            const diamond = diamondOf(frame);
            const column = columnOf(frame, 3);
            const phase = wrap(time / 8.6);
            const glitch = phase >= 0.68 && phase < 0.76;
            const lines = diamond
                ? [`BIG STANDBY ${Math.round(width)}×${Math.round(height)} px`, ...pairText(diamond)]
                : [
                    `BIG STANDBY ${Math.round(width)}×${Math.round(height)} px`,
                    '菱形頂點 0',
                    `共享 ${time.toFixed(2)} s`
                ];
            const box = glyphBox(column, lines);
            const shellAt = column.narrow
                ? { x: Math.max(width * 0.74, box.x + box.w + 36), y: 89 }
                : { x: box.x + box.w + 64, y: box.y + box.h / 2 };
            const shell = diamond ? fitShell(diamond, shellAt, column.narrow ? 16 : 48) : null;

            context.save();
            clipHole(context, frame, box);
            const regions = fieldRegions(frame);
            if (regions.length && shell) {
                const center = centerOf(diamond);
                const dx = shellAt.x - center.x;
                const dy = shellAt.y - center.y;
                const span = Math.hypot(dx, dy) || 1;
                context.save();
                context.beginPath();
                regions.forEach((region) => context.rect(region.x, region.y, region.width, region.height));
                context.clip();
                context.lineWidth = 1;
                context.strokeStyle = 'rgba(226,230,224,0.14)';
                regions.forEach((region) => {
                    [0.16, 0.38, 0.62, 0.84].forEach((mark) => {
                        const origin = {
                            x: region.x + region.width * mark,
                            y: region.y + region.height * (0.2 + (mark > 0.5 ? 0.35 : 0))
                        };
                        strokeOpen(context, origin.x, origin.y, origin.x + dx / span * region.width, origin.y + dy / span * region.height, box);
                    });
                });
                context.restore();
            }

            context.lineJoin = 'miter';
            context.lineCap = 'butt';
            context.textAlign = 'left';
            context.textBaseline = 'top';
            context.font = `${column.font}px sans-serif`;

            if (diamond && shell) {
                const travel = wrap(time * 0.07);
                context.strokeStyle = 'rgba(232,234,228,0.32)';
                context.lineWidth = 1;
                diamond.forEach((point, index) => {
                    context.beginPath();
                    context.moveTo(point.x, point.y);
                    context.lineTo(shell[index].x, shell[index].y);
                    context.stroke();
                });
                context.strokeStyle = 'rgba(244,244,238,0.45)';
                strokeClosed(context, diamond);
                context.strokeStyle = 'rgba(244,244,238,0.88)';
                context.lineWidth = 1.35;
                shell.forEach((point, index) => {
                    const next = shell[(index + 1) % shell.length];
                    if (glitch && index === 0) {
                        const mid = mix(point, next, 0.5);
                        context.beginPath();
                        context.moveTo(point.x, point.y);
                        context.lineTo(mid.x, mid.y);
                        context.moveTo(mid.x + 6, mid.y - 4);
                        context.lineTo(next.x + 6, next.y - 4);
                        context.stroke();
                        return;
                    }
                    context.beginPath();
                    context.moveTo(point.x, point.y);
                    context.lineTo(next.x, next.y);
                    context.stroke();
                });
                const section = diamond.map((point, index) => mix(point, shell[index], travel));
                context.strokeStyle = 'rgba(250,250,246,0.92)';
                context.lineWidth = 1.5;
                strokeClosed(context, section);
                context.strokeStyle = 'rgba(236,236,232,0.55)';
                context.lineWidth = 1;
                const stop = facing(shell[0], box, shell[0].y);
                context.beginPath();
                context.moveTo(shell[0].x, shell[0].y);
                context.lineTo(stop.x, stop.y);
                context.stroke();
            }

            context.restore();
            context.textAlign = 'left';
            context.textBaseline = 'top';
            context.font = `${column.font}px sans-serif`;
            lines.forEach((text, index) => {
                paintLine(context, text, column.x, column.y + index * column.lineH, column.lineH,
                    glitch && index === 1);
            });
        }
    });
})();
