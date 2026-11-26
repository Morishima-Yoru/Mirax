(() => {
    'use strict';

    const ALPHA = (Math.sqrt(5) - 1) / 2;
    const BETA = Math.sqrt(2) - 1;
    const COS_30 = Math.sqrt(3) / 2;
    const smooth = (value) => {
        const t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    };

    function face(context, points, alpha) {
        context.strokeStyle = `rgba(229, 233, 237, ${alpha})`;
        context.beginPath();
        context.moveTo(points[0].x, points[0].y);
        for (let index = 1; index < points.length; index += 1) {
            context.lineTo(points[index].x, points[index].y);
        }
        context.closePath();
        context.stroke();
    }

    window.miraxDStudies.set('study-36-quasicrystal', {
        id: 'study-36-quasicrystal',
        title: '36 準晶相位翻轉',
        category: '第二批 · 全幅場域',
        scope: 'field',
        description: '36｜上下全幅菱形鋪面沿兩道相位前緣局部換位，三片菱形輪流抬升、收合，改變相鄰拓撲。',
        draw(context, frame) {
            const { width, height, time, field } = frame;
            const size = Math.max(12, Math.min(46, width / 12, height / 14));
            const originX = width / 2;
            const originY = height / 2;
            const project = (i, j, elevation) => ({
                x: originX + (i - j) * size * COS_30,
                y: originY + (i + j) * size / 2 - elevation * size
            });
            const minX = Math.min(field.top.x, field.bottom.x) - size * 2;
            const maxX = Math.max(field.top.x + field.top.width,
                field.bottom.x + field.bottom.width) + size * 2;
            const minY = Math.min(field.top.y, field.bottom.y) - size * 2;
            const maxY = Math.max(field.top.y + field.top.height,
                field.bottom.y + field.bottom.height) + size * 2;
            let minI = Infinity;
            let maxI = -Infinity;
            let minJ = Infinity;
            let maxJ = -Infinity;
            for (const x of [minX, maxX]) {
                for (const y of [minY, maxY]) {
                    const difference = (x - originX) / (size * COS_30);
                    const j = ((y - originY) / size - (0.5 + ALPHA) * difference)
                        / (1 + ALPHA + BETA);
                    const i = j + difference;
                    minI = Math.min(minI, Math.floor(i) - 3);
                    maxI = Math.max(maxI, Math.ceil(i) + 3);
                    minJ = Math.min(minJ, Math.floor(j) - 3);
                    maxJ = Math.max(maxJ, Math.ceil(j) + 3);
                }
            }
            const columns = maxI - minI + 1;
            const rows = maxJ - minJ + 1;
            const elevations = [];
            const activity = [];
            for (let column = 0; column <= columns; column += 1) {
                elevations.push(new Float64Array(rows + 1));
                activity.push(new Float64Array(rows + 1));
                for (let row = 0; row <= rows; row += 1) {
                    const i = minI + column;
                    const j = minJ + row;
                    const base = -ALPHA * i - BETA * j;
                    const point = project(i, j, base);
                    const u = point.x / width;
                    const v = point.y / height;
                    const phason = 0.48 * Math.sin(time * 0.64 - u * 5.1 + v * 2.7)
                        + 0.16 * Math.sin(time * 0.39 + u * 3.3 - v * 4.6);
                    const level = base + phason;
                    const integer = Math.floor(level);
                    const flip = smooth((level - integer - 0.38) / 0.24);
                    elevations[column][row] = integer + flip;
                    activity[column][row] = 4 * flip * (1 - flip);
                }
            }
            context.lineWidth = 0.8;
            context.lineJoin = 'miter';
            // A stepped cubic surface projects to a coherent lozenge tiling; cube flips change adjacency.
            for (let column = 0; column < columns; column += 1) {
                for (let row = 0; row < rows; row += 1) {
                    const i = minI + column;
                    const j = minJ + row;
                    const z = elevations[column][row];
                    const nextI = elevations[column + 1][row];
                    const nextJ = elevations[column][row + 1];
                    const energy = activity[column][row];
                    face(context, [project(i, j, z), project(i + 1, j, z),
                        project(i + 1, j + 1, z), project(i, j + 1, z)], 0.10 + energy * 0.20);
                    if (Math.abs(z - nextI) > 0.001) {
                        face(context, [project(i + 1, j, z), project(i + 1, j + 1, z),
                            project(i + 1, j + 1, nextI), project(i + 1, j, nextI)],
                        0.08 + Math.max(energy, activity[column + 1][row]) * 0.19);
                    }
                    if (Math.abs(z - nextJ) > 0.001) {
                        face(context, [project(i, j + 1, z), project(i + 1, j + 1, z),
                            project(i + 1, j + 1, nextJ), project(i, j + 1, nextJ)],
                        0.08 + Math.max(energy, activity[column][row + 1]) * 0.19);
                    }
                }
            }
        }
    });
})();
