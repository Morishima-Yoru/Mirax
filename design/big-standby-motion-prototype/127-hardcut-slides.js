(() => {
    'use strict';

    const PERIOD = 6;

    function bottomField(frame) {
        const fb = frame.field && frame.field.bottom;
        if (fb && [fb.x, fb.y, fb.width, fb.height].every(Number.isFinite) && fb.width > 160 && fb.height > 80) return fb;
        return { x: 14, y: frame.height * 0.62, width: frame.width - 28, height: frame.height * 0.24 };
    }

    window.miraxDStudies.set('study-127-hardcut-slides', {
        id: 'study-127-hardcut-slides',
        title: '127 換板定格',
        category: '第七批 · CHAOTIC 探索',
        sourceId: null,
        description: '下方場域的投影片每六秒硬切三種構形，外框與位置不動只換內容；三格索引條同步點亮當前格，菱形右下邊刻痕同步跳格。',
        draw(context, frame) {
            // Three states, instant cuts, no interpolation.
            const state = Math.floor(frame.time / PERIOD) % 3;
            const fb = bottomField(frame);
            const BOX = Math.max(84, Math.min(148, fb.width * 0.3, fb.height * 0.62));
            const ox = fb.x + fb.width - BOX - 20;
            const oy = fb.y + 10;

            context.save();
            // Concept context: slide tray slot the panel sits in.
            context.strokeStyle = 'rgba(235,235,235,0.3)';
            context.lineWidth = 1.2;
            context.beginPath();
            context.moveTo(fb.x + 16, oy + BOX + 14);
            context.lineTo(fb.x + fb.width - 16, oy + BOX + 14);
            context.stroke();

            // Shared outer frame, identical across all three states.
            context.strokeStyle = 'rgba(235,235,235,0.85)';
            context.lineWidth = 1.8;
            context.beginPath();
            context.rect(ox, oy, BOX, BOX);
            context.stroke();

            // State content: hard cuts, panel never moves.
            context.strokeStyle = 'rgba(235,235,235,0.8)';
            context.lineWidth = 1.6;
            context.beginPath();
            if (state === 0) {
                context.moveTo(ox, oy);
                context.lineTo(ox + BOX, oy + BOX);
            } else if (state === 1) {
                context.moveTo(ox + BOX * 0.22, oy);
                context.lineTo(ox + BOX * 0.22 + BOX * 0.3, oy + BOX);
                context.moveTo(ox + BOX * 0.52, oy);
                context.lineTo(ox + BOX * 0.52 + BOX * 0.3, oy + BOX);
            } else {
                const m = BOX * 0.18;
                context.rect(ox + m, oy + m, BOX - m * 2, BOX - m * 2);
            }
            context.stroke();

            // Three-cell index strip: current cell filled, others outline.
            const gw = BOX / 3;
            const gy = oy + BOX + 22;
            for (let i = 0; i < 3; i += 1) {
                const gx = ox + i * gw;
                if (i === state) {
                    context.fillStyle = 'rgba(235,235,235,0.7)';
                    context.beginPath();
                    context.rect(gx + 2, gy, gw - 4, 10);
                    context.fill();
                } else {
                    context.strokeStyle = 'rgba(235,235,235,0.45)';
                    context.lineWidth = 1.1;
                    context.beginPath();
                    context.rect(gx + 2, gy, gw - 4, 10);
                    context.stroke();
                }
            }

            // Registration tick on the diamond lower-right edge, fixed per state.
            const dm = frame.diamond || [];
            if (dm.length === 4) {
                const r = dm[1];
                const b = dm[2];
                const t = 0.35 + state * 0.15;
                const cxp = r.x + (b.x - r.x) * t;
                const cyp = r.y + (b.y - r.y) * t;
                const ex = b.x - r.x;
                const ey = b.y - r.y;
                const el = Math.hypot(ex, ey) || 1;
                const nx = -ey / el;
                const ny = ex / el;
                context.strokeStyle = 'rgba(235,235,235,0.7)';
                context.lineWidth = 1.8;
                context.beginPath();
                context.moveTo(cxp - nx * 10, cyp - ny * 10);
                context.lineTo(cxp + nx * 10, cyp + ny * 10);
                context.stroke();
            }
            context.restore();
        }
    });
})();
