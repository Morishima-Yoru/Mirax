(() => {
    'use strict';

    window.miraxDStudies.set('study-174-depth-telemetry', {
        id: 'study-174-depth-telemetry',
        title: '174 深度線框遙測',
        category: '第八批 · 科幻朋克',
        sourceId: 'study-118-section-extrusion',
        scope: 'field',
        description: '由原 X 上臂截形縮成一處空心延伸框，SIM 三格狀態欄與框體共用斜稜；斷續截面單向深入時，對應資料格同步前移。所有讀值均為模擬，不代表實際遙測。',
        draw(context, frame) {
            const field = frame.field.top;
            const compact = frame.width < 600 || field.height < 135;
            const width = Math.min(field.width - 12, compact ? 346 : 650);
            const left = field.x + (compact ? 6 : field.width * 0.19);
            const top = compact ? Math.min(64, field.y + field.height - 48)
                : field.y + field.height * 0.53;
            const depth = compact ? 22 : 31;
            const offset = compact ? 22 : 48;
            const barWidth = compact ? 89 : 125;
            const armWidth = width - barWidth - offset - 8;
            const armHeight = compact ? 27 : Math.min(73, field.height * 0.26);
            const origin = [left + barWidth, top + depth];
            const source = frame.arms[0];
            const minX = Math.min(...source.map(point => point.x));
            const minY = Math.min(...source.map(point => point.y));
            const spanX = Math.max(...source.map(point => point.x)) - minX;
            const spanY = Math.max(...source.map(point => point.y)) - minY;
            const front = source.map(point => [
                origin[0] + (point.x - minX) / spanX * armWidth,
                origin[1] + (point.y - minY) / spanY * armHeight
            ]);
            const back = front.map(([x, y]) => [x + offset, y - depth]);
            const progress = (frame.time / 5.4) % 1;
            const state = Math.floor(progress * 3);
            const section = front.map(([x, y]) => [x + offset * progress, y - depth * progress]);

            context.save();
            context.lineWidth = 1.1;
            context.strokeStyle = 'rgba(238,238,234,0.34)';
            context.beginPath();
            back.forEach(([x, y], index) => {
                if (index === 0) context.moveTo(x, y);
                else context.lineTo(x, y);
            });
            context.closePath();
            for (const index of [0, 2, 4]) {
                context.moveTo(...front[index]);
                context.lineTo(...back[index]);
            }
            // The status enclosure and extrusion share this exact depth ridge.
            context.moveTo(...back[0]);
            context.lineTo(back[0][0] - barWidth, back[0][1]);
            context.lineTo(front[0][0] - barWidth, front[0][1]);
            context.lineTo(...front[0]);
            context.stroke();

            context.strokeStyle = 'rgba(238,238,234,0.51)';
            context.beginPath();
            front.forEach(([x, y], index) => {
                if (index === 0) context.moveTo(x, y);
                else context.lineTo(x, y);
            });
            context.closePath();
            context.stroke();

            context.strokeStyle = 'rgba(246,246,242,0.88)';
            context.lineWidth = 1.65;
            context.beginPath();
            context.moveTo(...section[0]);
            context.lineTo(...section[1]);
            context.lineTo(...section[2]);
            context.moveTo(...section[3]);
            context.lineTo(...section[4]);
            context.stroke();

            const slotX = left + offset + 36;
            const slotY = top + 8;
            const slotWidth = compact ? 10 : 17;
            context.lineWidth = 1;
            context.strokeStyle = 'rgba(238,238,234,0.48)';
            context.beginPath();
            for (let index = 0; index < 3; index++) {
                context.rect(slotX + index * (slotWidth + 4), slotY, slotWidth, 7);
            }
            context.stroke();
            context.fillStyle = 'rgba(246,246,242,0.86)';
            context.fillRect(slotX + state * (slotWidth + 4) + 2, slotY + 2, slotWidth - 4, 3);
            context.font = '10px monospace';
            context.fillText('SIM', left + offset + 5, top + 14);

            // A single parallel ridge supplies depth context, not a background grid.
            const echoX = Math.min(field.x + field.width - offset - 8, left + width + (compact ? 0 : 95));
            context.lineWidth = 1;
            context.strokeStyle = 'rgba(238,238,234,0.22)';
            context.beginPath();
            context.moveTo(echoX, top + depth + armHeight);
            context.lineTo(echoX + offset, top + armHeight);
            context.stroke();
            context.restore();
        }
    });
})();
