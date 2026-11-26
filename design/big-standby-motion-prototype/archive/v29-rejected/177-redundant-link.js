(() => {
    'use strict';

    function traceSpan(context, points, start, end) {
        const lengths = points.slice(1).map((point, index) =>
            Math.hypot(point[0] - points[index][0], point[1] - points[index][1]));
        const total = lengths.reduce((sum, length) => sum + length, 0);
        let passed = 0;
        lengths.forEach((length, index) => {
            const first = Math.max(0, (start * total - passed) / length);
            const last = Math.min(1, (end * total - passed) / length);
            if (last > first) {
                const a = points[index];
                const b = points[index + 1];
                context.moveTo(a[0] + (b[0] - a[0]) * first, a[1] + (b[1] - a[1]) * first);
                context.lineTo(a[0] + (b[0] - a[0]) * last, a[1] + (b[1] - a[1]) * last);
            }
            passed += length;
        });
    }

    window.miraxDStudies.set('study-177-redundant-link', {
        id: 'study-177-redundant-link',
        title: '177 雙層備援鏈路',
        category: '第八批 · 科幻朋克',
        sourceId: 'study-14-penetration',
        scope: 'field',
        description: '一明一淡的前後層長折角路由穿過同一空心介面框，SIM 雙格索引直接對應兩條路線；封包只向前移動，輪替承接時僅局部路段改亮，背景與主 X 常駐。備援切換純屬模擬。',
        draw(context, frame) {
            const field = frame.field.top;
            const compact = frame.width < 600 || field.height < 135;
            const left = field.x + (compact ? 6 : field.width * 0.07);
            const width = field.width * (compact ? 0.94 : 0.84);
            const top = compact ? Math.min(64, field.y + field.height - 49)
                : field.y + field.height * 0.54;
            const height = compact ? 48 : Math.min(100, field.height * 0.35);
            const point = ([x, y]) => [left + x * width, top + y * height];
            const routes = [
                [[0, 0.83], [0.22, 0.83], [0.36, 0.38], [0.69, 0.38], [0.83, 0.08], [1, 0.08]].map(point),
                [[0.02, 0.4], [0.18, 0.4], [0.32, 0.9], [0.67, 0.9], [0.83, 0.55], [1, 0.55]].map(point)
            ];
            const step = frame.time / 3.1;
            const state = Math.floor(step) % 2;
            const progress = step % 1;
            const box = { x: left + width * 0.42, y: top + height * 0.15,
                width: width * 0.25, height: height * 0.81 };
            const arm = frame.arms[0];
            const slope = Math.abs((arm[2].x - arm[1].x) / (arm[2].y - arm[1].y));
            const cutY = compact ? 6 : 12;
            const cutX = cutY * Math.max(0.6, Math.min(1.4, slope));

            context.save();
            context.lineWidth = 1.2;
            context.strokeStyle = 'rgba(238,238,234,0.28)';
            context.beginPath();
            traceSpan(context, routes[1], 0, 1);
            context.stroke();
            context.strokeStyle = 'rgba(238,238,234,0.53)';
            context.beginPath();
            traceSpan(context, routes[0], 0, 1);
            context.stroke();

            context.strokeStyle = 'rgba(238,238,234,0.48)';
            context.beginPath();
            context.moveTo(box.x, box.y + box.height);
            context.lineTo(box.x, box.y + cutY);
            context.lineTo(box.x + cutX, box.y);
            context.lineTo(box.x + box.width, box.y);
            context.lineTo(box.x + box.width, box.y + box.height - cutY);
            context.lineTo(box.x + box.width - cutX, box.y + box.height);
            context.closePath();
            context.stroke();

            // Only a short packet changes layer; neither long route changes its base brightness.
            context.strokeStyle = 'rgba(246,246,242,0.93)';
            context.lineWidth = 2.4;
            context.beginPath();
            traceSpan(context, routes[state], Math.max(0, progress - 0.09), Math.min(1, progress + 0.045));
            context.stroke();

            const slotX = left + (compact ? 34 : 43);
            const slotY = top + 2;
            const slotWidth = compact ? 16 : 24;
            context.lineWidth = 1;
            context.strokeStyle = 'rgba(238,238,234,0.57)';
            context.beginPath();
            context.rect(slotX, slotY, slotWidth, 8);
            context.rect(slotX + slotWidth + 5, slotY, slotWidth, 8);
            // Short stems tie both indexed channels to the same route entrances.
            context.moveTo(slotX + slotWidth * 0.5, slotY + 8);
            context.lineTo(routes[0][1][0], routes[0][1][1]);
            context.moveTo(slotX + slotWidth * 1.5 + 5, slotY + 8);
            context.lineTo(routes[1][1][0], routes[1][1][1]);
            context.stroke();
            context.fillStyle = 'rgba(246,246,242,0.88)';
            context.fillRect(slotX + state * (slotWidth + 5) + 3, slotY + 2, slotWidth - 6, 4);
            context.font = compact ? '10px monospace' : '12px monospace';
            context.fillText('SIM', left, top + 10);
            context.restore();
        }
    });
})();
