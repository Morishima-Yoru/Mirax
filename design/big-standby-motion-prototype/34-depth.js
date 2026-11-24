(() => {
    'use strict';

    const clamp = (value) => Math.max(0, Math.min(1, value));
    const smooth = (value) => {
        const progress = clamp(value);
        return progress * progress * (3 - 2 * progress);
    };

    function stroke(context, points, alpha, close = false) {
        context.strokeStyle = `rgba(228,233,230,${alpha})`;
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        if (close) context.closePath();
        context.stroke();
    }

    window.miraxDStudies.set('study-34-depth', {
        id: 'study-34-depth',
        title: '34 斜移縱深門廊',
        category: '第二批 · 對角跨區',
        scope: 'field',
        description: '梯形門框沿斜移隧道由遠而近加速掠過；游移焦點與逐層偏轉讓上下場域一起改變透視。',
        draw(context, frame) {
            const { top, bottom } = frame.field;
            const left = Math.max(0, Math.min(top.x, bottom.x));
            const right = Math.min(frame.width, Math.max(top.x + top.width, bottom.x + bottom.width));
            const width = right - left;
            const startY = top.y + top.height * 0.12;
            const endY = Math.min(frame.height, bottom.y + bottom.height * 0.88);
            const height = endY - startY;
            if (width <= 0 || height <= 0) return;
            const drift = Math.tanh(1.4 * Math.sin(frame.time * 0.34));
            const focus = {
                x: left + width * (0.50 + 0.23 * drift),
                y: startY + height * (0.50 + 0.29 * drift)
            };
            const corners = [
                { x: left + width * 0.035, y: startY },
                { x: left + width * 0.965, y: startY + top.height * 0.12 },
                { x: left + width * 0.88, y: endY },
                { x: left + width * 0.10, y: endY - bottom.height * 0.10 }
            ];
            const project = (corner, scale) => {
                const depth = 1 / (1 + scale);
                const bend = scale * (1 - scale);
                const yaw = Math.sin(frame.time * 0.43 - depth * 2.4);
                return {
                    x: focus.x + (corner.x - focus.x) * scale
                        + width * 0.15 * bend * yaw,
                    y: focus.y + (corner.y - focus.y) * scale
                        + height * 0.085 * bend * Math.sin(frame.time * 0.37 - depth * 1.8)
                };
            };
            context.lineJoin = 'miter';
            context.lineWidth = 0.9;
            corners.forEach((corner) => {
                const rail = [0.08, 0.20, 0.38, 0.62, 0.93, 1.30].map((scale) => project(corner, scale));
                stroke(context, rail, 0.14);
            });

            for (let portal = 0; portal < 12; portal += 1) {
                const phase = ((frame.time / 7.2 + portal / 12) % 1 + 1) % 1;
                // Reciprocal perspective accelerates near frames instead of scaling every layer equally.
                const distance = 0.36 + 7.5 * (1 - phase) ** 2.4;
                const scale = 0.55 / distance;
                const opacity = (0.16 + 0.18 * phase)
                    * smooth(phase / 0.12) * (1 - smooth((phase - 0.91) / 0.09));
                const points = corners.map((corner) => project(corner, scale));
                context.lineWidth = 0.8 + Math.min(1, scale) * 0.5;
                stroke(context, points, opacity, true);
                if (scale < 0.28 || scale > 1.1) continue;
                const fraction = 0.08 + 0.04 * phase;
                points.forEach((point, index) => {
                    const next = points[(index + 1) % points.length];
                    stroke(context, [point, {
                        x: point.x + (next.x - point.x) * fraction,
                        y: point.y + (next.y - point.y) * fraction
                    }], opacity * 0.65);
                });
            }
        }
    });
})();
