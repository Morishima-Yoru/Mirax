(() => {
    'use strict';

    function traceSpan(context, points, start = 0, end = 1) {
        const lengths = points.slice(1).map((point, index) =>
            Math.hypot(point[0] - points[index][0], point[1] - points[index][1]));
        const total = lengths.reduce((sum, length) => sum + length, 0);
        let passed = 0;
        for (let index = 0; index < lengths.length; index++) {
            const length = lengths[index];
            const first = Math.max(0, (start * total - passed) / length);
            const last = Math.min(1, (end * total - passed) / length);
            if (last > first) {
                const a = points[index];
                const b = points[index + 1];
                context.moveTo(a[0] + (b[0] - a[0]) * first, a[1] + (b[1] - a[1]) * first);
                context.lineTo(a[0] + (b[0] - a[0]) * last, a[1] + (b[1] - a[1]) * last);
            }
            passed += length;
        }
    }

    window.miraxDStudies.set('study-178-asymmetric-shell', {
        id: 'study-178-asymmetric-shell',
        title: '178 非對稱控制殼',
        category: '第八批 · 科幻朋克',
        sourceId: 'study-20-shield',
        scope: 'field',
        description: '上方長開口 L 殼與下方短切角框以兩道 X 對角稜連成非對稱控制空間，SIM 三槽嵌在同一殼角；局部輪廓逐段向前接續，三槽以不同節奏對應三段構件。小螢幕把下殼收進上方安全帶，狀態全為模擬。',
        draw(context, frame) {
            const upper = frame.field.top;
            const lower = frame.field.bottom;
            const compact = frame.width < 600 || upper.height < 135;
            const left = upper.x + (compact ? 7 : upper.width * 0.1);
            const right = upper.x + upper.width * (compact ? 0.96 : 0.87);
            const top = compact ? Math.min(64, upper.y + upper.height - 49)
                : upper.y + upper.height * 0.58;
            const leg = compact ? 39 : Math.min(85, upper.height * 0.29);
            const boxWidth = compact ? upper.width * 0.37 : upper.width * 0.28;
            const boxHeight = compact ? 24 : Math.min(70, Math.max(35, lower.height * 0.38));
            const boxX = compact ? upper.x + upper.width * 0.58 : lower.x + lower.width * 0.55;
            const boxY = compact ? top + 24 : lower.y + Math.min(17, lower.height * 0.08);
            const cut = compact ? 6 : 13;
            const arm = frame.arms[0];
            const slope = Math.abs((arm[2].x - arm[1].x) / (arm[2].y - arm[1].y));
            const diagonal = Math.max(0.6, Math.min(1.4, slope));
            const jointA = Math.max(left + (compact ? 115 : 175), boxX + cut - (boxY - top) * diagonal);
            const jointB = Math.min(right - 12, boxX + boxWidth - cut - (boxY + boxHeight - top) * diagonal);
            const upperShell = [[left, top + leg], [left, top], [right, top]];
            const lowerShell = [[boxX, boxY + boxHeight], [boxX, boxY + cut],
                [boxX + cut, boxY], [boxX + boxWidth, boxY],
                [boxX + boxWidth, boxY + boxHeight - cut],
                [boxX + boxWidth - cut, boxY + boxHeight], [boxX + boxWidth * 0.23, boxY + boxHeight]];
            const ribs = [
                [[jointA, top], [boxX + cut, boxY]],
                [[jointB, top], [boxX + boxWidth - cut, boxY + boxHeight]]
            ];
            const channels = [upperShell, ribs[0], lowerShell];
            const periods = [4.1, 5.7, 7.3];

            context.save();
            context.lineWidth = 1.15;
            context.strokeStyle = 'rgba(238,238,234,0.44)';
            context.beginPath();
            traceSpan(context, upperShell);
            traceSpan(context, lowerShell);
            context.stroke();
            context.strokeStyle = 'rgba(238,238,234,0.27)';
            context.beginPath();
            ribs.forEach(rib => traceSpan(context, rib));
            context.stroke();

            // Embedded slots report the same forward continuation on three structural runs.
            context.strokeStyle = 'rgba(246,246,242,0.87)';
            context.lineWidth = 1.9;
            context.beginPath();
            channels.forEach((channel, index) => {
                const progress = (frame.time / periods[index]) % 1;
                traceSpan(context, channel, Math.max(0, progress - 0.13), Math.min(1, progress + 0.07));
            });
            context.stroke();

            const slotWidth = compact ? 20 : 29;
            const slotX = left + (compact ? 46 : 58);
            const slotY = top + 9;
            context.lineWidth = 1;
            context.strokeStyle = 'rgba(238,238,234,0.54)';
            context.beginPath();
            for (let index = 0; index < 3; index++) {
                const x = slotX + index * (slotWidth + 5);
                context.rect(x, slotY, slotWidth, 8);
                context.moveTo(x + slotWidth * 0.5, slotY);
                context.lineTo(x + slotWidth * 0.5, top);
            }
            context.stroke();
            context.fillStyle = 'rgba(246,246,242,0.88)';
            for (let index = 0; index < 3; index++) {
                const state = Math.floor(((frame.time / periods[index]) % 1) * 3);
                context.fillRect(slotX + index * (slotWidth + 5) + 3 + state * (slotWidth - 9) * 0.5,
                    slotY + 2, 3, 4);
            }
            context.font = compact ? '10px monospace' : '12px monospace';
            context.fillText('SIM', left + 8, slotY + 8);
            context.restore();
        }
    });
})();
