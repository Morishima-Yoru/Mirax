(() => {
    'use strict';

    // Growth is a fixed window and retreat is a fixed gap. Both advance one path cell per tick, including the wrap.
    const SQRT3 = Math.sqrt(3) / 2;

    function makeDrawer(context) {
        const edges = new Map();
        return {
            edge(x1, y1, x2, y2, alpha) {
                if (!Number.isFinite(x1) || !Number.isFinite(y1) || !Number.isFinite(x2) || !Number.isFinite(y2)) return;
                const ax = Math.round(x1 * 2) / 2;
                const ay = Math.round(y1 * 2) / 2;
                const bx = Math.round(x2 * 2) / 2;
                const by = Math.round(y2 * 2) / 2;
                if (ax === bx && ay === by) return;
                const left = ax < bx || (ax === bx && ay <= by);
                const key = left ? ax + ',' + ay + '|' + bx + ',' + by : bx + ',' + by + '|' + ax + ',' + ay;
                const prev = edges.get(key);
                if (!prev || alpha > prev[4]) edges.set(key, [ax, ay, bx, by, alpha]);
            },
            flush() {
                const buckets = new Map();
                for (const item of edges.values()) {
                    const bucket = Math.max(1, Math.min(72, Math.round(item[4] * 100)));
                    let list = buckets.get(bucket);
                    if (!list) {
                        list = [];
                        buckets.set(bucket, list);
                    }
                    list.push(item);
                }
                for (const bucket of [...buckets.keys()].sort((a, b) => a - b)) {
                    context.beginPath();
                    context.lineWidth = 1;
                    context.strokeStyle = 'rgba(229,233,237,' + (bucket / 100).toFixed(2) + ')';
                    for (const item of buckets.get(bucket)) {
                        context.moveTo(item[0], item[1]);
                        context.lineTo(item[2], item[3]);
                    }
                    context.stroke();
                }
            }
        };
    }

    function layout(frame) {
        const { width, height, field } = frame;
        const top = field.top;
        const bottom = field.bottom;
        const narrow = width <= 480 && height > width;
        const primary = narrow
            ? { x: top.x + 4, y: Math.max(top.y, 64), w: Math.max(16, top.width - 8), h: Math.max(16, Math.min(top.y + top.height, 114) - Math.max(top.y, 64)) }
            : { x: top.x, y: top.y, w: top.width, h: top.height };
        const secondary = bottom && bottom.height > 24
            ? { x: bottom.x + 4, y: bottom.y + 4, w: Math.max(16, bottom.width - 8), h: Math.max(16, bottom.height - 8) }
            : null;
        return { narrow, primary, secondary };
    }

    function graphs(rect, narrow) {
        const size = narrow
            ? Math.max(16, Math.min(22, Math.min(rect.h / 2.2, rect.w / 8)))
            : Math.max(28, Math.min(64, Math.min(rect.h / 3.3, rect.w / 10)));
        const cols = Math.max(6, Math.min(18, Math.floor((rect.w - size) / size)));
        const rows = narrow ? 1 : 2;
        const mid = Math.max(2, Math.floor(cols / 2));
        const growA = [];
        const growB = [];
        const retreatA = [];
        const retreatB = [];
        for (let i = 0; i <= mid; i += 1) growA.push([i, 0]);
        for (let i = cols; i >= mid; i -= 1) growB.push([i, rows === 1 ? 0 : 2]);
        for (let i = mid; i <= cols; i += 1) retreatA.push([i, 1]);
        for (let i = mid; i >= 0; i -= 1) retreatB.push([i, 1]);
        const bone = [[mid, 0], [mid, 1], [Math.min(cols, mid + 1), 0], [Math.min(cols, mid + 1), 1]];
        if (rows > 1) bone.push([mid, 2], [Math.min(cols, mid + 1), 2]);
        return { cols, rows, size, bone, growA, growB, retreatA, retreatB };
    }

    function put(marks, i, j, kind) {
        const rank = kind === 'head' ? 3 : kind === 'tail' ? 2 : kind === 'bone' ? 1 : 0;
        const key = i + ',' + j;
        const prev = marks.get(key);
        if (!prev || rank >= prev.rank) marks.set(key, { i, j, kind, rank });
    }

    function mod(tick, count) {
        return ((tick % count) + count) % count;
    }

    function pathState(count, tick, kind) {
        const state = new Map();
        if (count < 2) return state;
        const start = mod(tick, count);
        if (kind === 'grow') {
            const span = Math.max(1, Math.min(4, count - 1));
            for (let step = 0; step < span; step += 1) {
                state.set((start + step) % count, step === span - 1 ? 'head' : 'body');
            }
            return state;
        }
        const gap = Math.max(1, Math.min(2, count - 1));
        const hidden = new Set();
        for (let step = 0; step < gap; step += 1) hidden.add((start + step) % count);
        const tail = (start + gap) % count;
        for (let index = 0; index < count; index += 1) {
            if (!hidden.has(index)) state.set(index, index === tail ? 'tail' : 'body');
        }
        return state;
    }

    function occupy(list, tick, kind, marks) {
        const state = pathState(list.length, tick, kind);
        for (const [index, cellKind] of state) {
            const cell = list[index];
            put(marks, cell[0], cell[1], cellKind);
        }
    }

    function paintBand(draw, rect, time, narrow, flip) {
        if (rect.w < 12 || rect.h < 12) return;
        const graph = graphs(rect, narrow);
        const size = graph.size;
        const vx = size;
        const wx = size * 0.5;
        const wy = (flip ? -1 : 1) * size * SQRT3;
        const originX = rect.x + size * 0.2;
        const originY = flip ? rect.y + rect.h - size * 0.55 : rect.y + size * 0.35;
        const point = (i, j) => [originX + vx * i + wx * j, originY + wy * j];
        for (let j = 0; j <= graph.rows; j += 1) {
            for (let i = 0; i < graph.cols; i += 2) {
                const a = point(i, j);
                const b = point(i + 1, j);
                const c = point(i + 1, j + 1);
                const d = point(i, j + 1);
                draw.edge(a[0], a[1], b[0], b[1], 0.07);
                draw.edge(b[0], b[1], c[0], c[1], 0.07);
                draw.edge(c[0], c[1], d[0], d[1], 0.07);
                draw.edge(d[0], d[1], a[0], a[1], 0.07);
            }
        }
        const marks = new Map();
        for (const cell of graph.bone) put(marks, cell[0], cell[1], 'bone');
        occupy(graph.growA, Math.floor(time * 1.05), 'grow', marks);
        occupy(graph.retreatA, Math.floor(time * 0.83 + 1), 'retreat', marks);
        occupy(graph.growB, Math.floor(time * 0.71 + 2), 'grow', marks);
        occupy(graph.retreatB, Math.floor(time * 0.64 + 3), 'retreat', marks);
        for (const cell of marks.values()) {
            const a = point(cell.i, cell.j);
            const b = point(cell.i + 1, cell.j);
            const c = point(cell.i + 1, cell.j + 1);
            const d = point(cell.i, cell.j + 1);
            const alpha = cell.kind === 'bone' ? 0.34 : cell.kind === 'head' ? 0.6 : 0.46;
            draw.edge(a[0], a[1], b[0], b[1], alpha);
            if (cell.kind !== 'tail') draw.edge(b[0], b[1], c[0], c[1], alpha);
            draw.edge(c[0], c[1], d[0], d[1], alpha);
            draw.edge(d[0], d[1], a[0], a[1], alpha);
            if (cell.kind === 'head') draw.edge(a[0], a[1], c[0], c[1], 0.62);
        }
    }

    window.miraxDStudies.set('study-191-growth-branching', {
        id: 'study-191-growth-branching',
        title: '191 晶格分枝生長',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-45-growth-front',
        scope: 'field',
        description: '191｜兩個不對稱種點長出共邊菱格帶，並在共享接點會合；前緣逐格接合，後緣沿另一條路徑逐格退去，骨架始終留存。',
        draw(context, frame) {
            const { narrow, primary, secondary } = layout(frame);
            const draw = makeDrawer(context);
            paintBand(draw, primary, frame.time, narrow, false);
            if (secondary) paintBand(draw, secondary, frame.time + 2.4, narrow, true);
            draw.flush();
        }
    });
})();
