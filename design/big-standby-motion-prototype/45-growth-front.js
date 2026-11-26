(() => {
    'use strict';

    const SQRT_3 = Math.sqrt(3);
    const smooth = (value) => {
        const t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    };

    window.miraxDStudies.set('study-45-growth-front', {
        id: 'study-45-growth-front',
        title: '45 游種鋪面生長',
        category: '第三批 · 準晶變體',
        scope: 'field',
        description: '45｜游移種點向外催生菱形，前緣逐片接合、後緣逐片退去；下一輪以另一種三菱接法補入，改寫局部鄰接。',
        draw(context, frame) {
            const { width, height, time, field } = frame;
            const size = Math.max(22, Math.min(64, width / 9, height / 8));
            const left = Math.min(field.top.x, field.bottom.x) - size * 2;
            const right = Math.max(field.top.x + field.top.width,
                field.bottom.x + field.bottom.width) + size * 2;
            const top = Math.min(field.top.y, field.bottom.y);
            const bottom = Math.max(field.top.y + field.top.height,
                field.bottom.y + field.bottom.height);
            const originX = width / 2;
            const originY = (top + bottom) / 2;
            const columnStep = size * 1.5;
            const rowStep = size * SQRT_3;
            const firstColumn = Math.floor((left - originX) / columnStep) - 1;
            const lastColumn = Math.ceil((right - originX) / columnStep) + 1;
            const firstRow = Math.floor((top - originY - size * 2) / rowStep) - 1;
            const lastRow = Math.ceil((bottom - originY + size * 2) / rowStep) + 1;
            const speed = Math.max(width, height) * 0.105;
            const period = 14;
            const newest = Math.floor(time / period);
            const seedX = width * (0.5 + 0.18 * Math.sin(time * 0.12));
            const seedY = originY + (bottom - top) * 0.19 * Math.sin(time * 0.09 + 0.8);
            const corners = Array.from({ length: 6 }, (_, index) => ({
                x: size * Math.cos(index * Math.PI / 3),
                y: size * Math.sin(index * Math.PI / 3)
            }));

            context.lineWidth = 0.8;
            context.lineJoin = 'miter';
            for (let generation = newest - 2; generation <= newest; generation += 1) {
                const emission = generation * period;
                const parity = ((generation % 2) + 2) % 2;
                for (let column = firstColumn; column <= lastColumn; column += 1) {
                    const x = originX + column * columnStep;
                    const stagger = ((column % 2) + 2) % 2;
                    for (let row = firstRow; row <= lastRow; row += 1) {
                        const y = originY + (row + stagger / 2) * rowStep;
                        const age = time - emission - Math.hypot(x - seedX, y - seedY) / speed;
                        if (age <= 0 || age >= 12.2) continue;
                        for (let face = 0; face < 3; face += 1) {
                            const localAge = age - face * 0.28;
                            const birth = smooth(localAge / 1.25);
                            const retirement = 1 - smooth((localAge - 9.6) / 2);
                            const scale = birth * retirement;
                            if (scale <= 0) continue;
                            const start = face * 2 + parity;
                            const activity = 4 * scale * (1 - scale);
                            context.strokeStyle = `rgba(225, 230, 235, ${scale * (0.12 + activity * 0.09)})`;
                            // Cohorts alternate the two three-rhomb partitions, not just a reveal mask.
                            context.beginPath();
                            context.moveTo(x, y);
                            for (let vertex = 0; vertex < 3; vertex += 1) {
                                const corner = corners[(start + vertex) % 6];
                                context.lineTo(x + corner.x * scale, y + corner.y * scale);
                            }
                            context.closePath();
                            context.stroke();
                        }
                    }
                }
            }
        }
    });
})();
