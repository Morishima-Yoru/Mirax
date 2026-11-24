(() => {
    'use strict';

    const wrap = (value) => ((value % 1) + 1) % 1;
    const SIN60 = Math.sqrt(3) / 2;

    function finitePoint(point) {
        return !!point && Number.isFinite(point.x) && Number.isFinite(point.y);
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
        const x = region.x + Math.min(region.width * 0.5, Math.max(12, region.width - 240));
        return { x, y, lineH, font, narrow, region };
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
        context.fillText(text, x + 7, y);
        context.restore();
    }

    function bandOf(frame, column) {
        const top = column.region || (frame.field && frame.field.top);
        const rightEdge = top && Number.isFinite(top.x) ? top.x + top.width - 12 : frame.width - 12;
        const leftEdge = top && Number.isFinite(top.x) ? top.x + 12 : 14;
        const y = column.narrow ? 90 : column.y + column.lineH;
        if (column.narrow) {
            const x0 = Math.min(rightEdge - 56, Math.max(168, frame.width * 0.52));
            const x1 = Math.max(x0 + 56, rightEdge);
            return { a: { x: x0, y }, b: { x: x1, y }, length: x1 - x0, rows: 1 };
        }
        const x1 = Math.max(leftEdge + 64, column.x - 18);
        return { a: { x: leftEdge, y }, b: { x: x1, y }, length: x1 - leftEdge, rows: 2 };
    }

    function cellsOf(edge, time) {
        const len = edge.length;
        const rows = edge.rows === 1 ? 1 : 2;
        const cap = rows === 1 ? 14 : 26;
        const size = Math.max(12, Math.min(cap, len / 8));
        const count = Math.max(4, Math.min(rows === 1 ? 8 : 18, Math.floor(len / size)));
        const step = len / count;
        const ux = (edge.b.x - edge.a.x) / len;
        const uy = (edge.b.y - edge.a.y) / len;
        let vx = ux * 0.5 - uy * SIN60;
        let vy = ux * SIN60 + uy * 0.5;
        if (vy > 0) {
            vx = -vx;
            vy = -vy;
        }
        const cellU = { x: ux * step, y: uy * step };
        const cellV = { x: vx * step, y: vy * step };
        const phase = Math.floor(time / 2.9);
        const active = ((phase % count) + count) % count;
        const since = time - phase * 2.9;
        const cells = [];
        for (let index = 0; index < count; index += 1) {
            const origin = {
                x: edge.a.x + ux * step * index,
                y: edge.a.y + uy * step * index
            };
            const flip = index === active ? -1 : 1;
            let outer0 = origin;
            let outer1 = { x: origin.x + cellU.x, y: origin.y + cellU.y };
            for (let row = 0; row < rows; row += 1) {
                const p0 = outer0;
                const p1 = outer1;
                const p2 = { x: p1.x + cellV.x * flip, y: p1.y + cellV.y * flip };
                const p3 = { x: p0.x + cellV.x * flip, y: p0.y + cellV.y * flip };
                cells.push([p0, p1, p2, p3]);
                outer0 = p3;
                outer1 = p2;
            }
        }
        return {
            cells,
            active,
            phaseCount: count,
            faceCount: cells.length,
            glitch: since > 2.62
        };
    }

    function strokeCell(context, points, alpha) {
        context.strokeStyle = `rgba(232,234,230,${alpha.toFixed(3)})`;
        context.beginPath();
        context.moveTo(points[0].x, points[0].y);
        for (let index = 1; index < points.length; index += 1) context.lineTo(points[index].x, points[index].y);
        context.closePath();
        context.stroke();
    }

    window.miraxDStudies.set('study-202-glitch-phase-log', {
        id: 'study-202-glitch-phase-log',
        title: '202 準晶相位紀錄',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-36-quasicrystal',
        description: '共邊菱格帶旁首行固定 MIRAX，其下記本幀相位序、格面數與共享時間；換接前只偏移相位那一行。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            const column = columnOf(frame, 3);
            const edge = bandOf(frame, column);
            const built = cellsOf(edge, time);
            const lines = [
                'MIRAX',
                `相位序 ${built.active + 1} / ${built.phaseCount}`,
                `格面 ${built.faceCount} 片 · ${time.toFixed(2)} s`
            ];

            const regions = fieldRegions(frame);
            if (regions.length) {
                const slide = wrap(time * 0.04) * 48;
                context.save();
                context.beginPath();
                regions.forEach((region) => context.rect(region.x, region.y, region.width, region.height));
                context.clip();
                context.lineWidth = 1;
                context.strokeStyle = 'rgba(214,220,216,0.1)';
                regions.forEach((region) => {
                    for (let step = 0; step < 6; step += 1) {
                        const y = region.y + (step + 0.5) * region.height / 6 + slide * 0.15;
                        context.beginPath();
                        context.moveTo(region.x, y);
                        context.lineTo(region.x + region.width, y + 10);
                        context.stroke();
                    }
                });
                context.restore();
            }

            context.lineJoin = 'miter';
            context.lineCap = 'butt';
            context.lineWidth = 1.05;
            context.textAlign = 'left';
            context.textBaseline = 'top';
            context.font = `${column.font}px sans-serif`;
            const rows = edge.rows === 1 ? 1 : 2;
            built.cells.forEach((cell, index) => {
                const hot = Math.floor(index / rows) === built.active;
                strokeCell(context, cell, hot ? 0.78 : 0.28);
            });
            const leadCell = built.cells[built.cells.length - 1];
            const lead = leadCell[1];
            context.strokeStyle = 'rgba(236,236,232,0.55)';
            context.beginPath();
            context.moveTo(lead.x, lead.y);
            context.lineTo(column.x, column.y + column.lineH);
            context.stroke();

            lines.forEach((text, index) => {
                paintLine(context, text, column.x, column.y + index * column.lineH, column.lineH,
                    built.glitch && index === 1);
            });
        }
    });
})();
