(() => {
    'use strict';

    window.miraxDStudies.set('study-176-lattice-diagnostics', {
        id: 'study-176-lattice-diagnostics',
        title: '176 晶格診斷介面',
        category: '第八批 · 科幻朋克',
        sourceId: 'study-36-quasicrystal',
        scope: 'field',
        description: '六格共邊菱格保留穩定輪廓，單一邊缺口依序接回；折線把晶格接到 SIM 六格紀錄欄，當前格與回補邊同步移位。另一端僅保留兩條延伸稜，診斷內容全為模擬。',
        draw(context, frame) {
            const field = frame.field.top;
            const compact = frame.width < 600 || field.height < 135;
            const registration = Math.max(0.08, Math.min(0.2, frame.diamond[3].x / frame.width - 0.58));
            const left = field.x + (compact ? 7 : field.width * registration);
            const top = compact ? Math.min(65, field.y + field.height - 47)
                : field.y + field.height * 0.53;
            const dx = compact ? Math.min(20, field.width * 0.065) : Math.min(47, field.width * 0.038);
            const dy = compact ? 9 : Math.min(22, field.height * 0.08);
            const origin = [left + 2 * dx, top];
            const point = (column, row) => [origin[0] + (column - row) * dx, origin[1] + (column + row) * dy];
            const edges = [];
            // Integer lattice coordinates deduplicate shared edges without drawing over them.
            for (let column = 0; column < 3; column++) {
                for (let row = 0; row < 2; row++) {
                    const corners = [[column, row], [column + 1, row], [column + 1, row + 1], [column, row + 1]];
                    for (let side = 0; side < 4; side++) {
                        const a = corners[side];
                        const b = corners[(side + 1) % 4];
                        const key = [a.join(','), b.join(',')].sort().join(':');
                        if (!edges.some(edge => edge.key === key)) edges.push({ key, a: point(...a), b: point(...b) });
                    }
                }
            }
            const step = frame.time / 1.8;
            const state = Math.floor(step) % 6;
            const progress = step % 1;
            const repair = edges[[0, 3, 6, 10, 13, 16][state]];
            const interpolate = fraction => [
                repair.a[0] + (repair.b[0] - repair.a[0]) * fraction,
                repair.a[1] + (repair.b[1] - repair.a[1]) * fraction
            ];
            const logX = compact ? field.x + field.width * 0.45 : left + 5 * dx + field.width * 0.09;
            const logY = top + dy * 1.1;
            const logWidth = compact ? Math.min(112, field.width * 0.37) : 174;
            const logHeight = compact ? 27 : 37;
            const pitch = (logWidth - 16) / 6;
            const slotY = logY + logHeight - 10;
            const bus = [origin[0] + 3 * dx, top + 3 * dy];

            context.save();
            context.lineWidth = 1.15;
            context.strokeStyle = 'rgba(238,238,234,0.45)';
            context.beginPath();
            for (const edge of edges) {
                if (edge === repair) continue;
                context.moveTo(...edge.a);
                context.lineTo(...edge.b);
            }
            context.moveTo(...repair.a);
            context.lineTo(...interpolate(0.18));
            context.moveTo(...interpolate(0.82));
            context.lineTo(...repair.b);
            context.stroke();

            // The repaired span grows only forward; the rest of the six-cell patch stays fixed.
            context.lineWidth = 2;
            context.strokeStyle = 'rgba(246,246,242,0.9)';
            context.beginPath();
            context.moveTo(...interpolate(0.18));
            context.lineTo(...interpolate(0.2 + progress * 0.62));
            context.stroke();

            context.lineWidth = 1.1;
            context.strokeStyle = 'rgba(238,238,234,0.48)';
            context.beginPath();
            context.moveTo(...bus);
            context.lineTo(logX - dy, logY + logHeight * 0.65);
            context.lineTo(logX, logY + logHeight * 0.65);
            context.rect(logX, logY, logWidth, logHeight);
            for (let index = 0; index < 6; index++) {
                context.rect(logX + 8 + index * pitch, slotY, pitch - 4, 6);
            }
            context.stroke();
            context.fillStyle = 'rgba(246,246,242,0.88)';
            context.fillRect(logX + 10 + state * pitch, slotY + 2, pitch - 8, 2);
            context.font = compact ? '10px monospace' : '12px monospace';
            context.fillText(`SIM ${state + 1}/6`, logX + 8, logY + (compact ? 11 : 15));

            // The two remote ridges retain the lattice direction without repeating its cells.
            const farX = compact ? field.x + field.width - 30 : field.x + field.width * 0.86;
            context.strokeStyle = 'rgba(238,238,234,0.27)';
            context.beginPath();
            for (let index = 0; index < 2; index++) {
                context.moveTo(farX + index * 12, top + dy * 2);
                context.lineTo(farX + dx * 0.7 + index * 12, top + dy * 2.7);
            }
            context.stroke();
            context.restore();
        }
    });
})();
