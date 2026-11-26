(() => {
    'use strict';

    const dot = (point, axis) => point.x * axis.x + point.y * axis.y;

    function clipPlane(polygon, normal, position) {
        const result = [];
        polygon.forEach((a, index) => {
            const b = polygon[(index + 1) % polygon.length];
            const from = dot(a, normal) - position;
            const to = dot(b, normal) - position;
            if (from <= 0) result.push(a);
            if ((from < 0 && to > 0) || (from > 0 && to < 0)) {
                const fraction = from / (from - to);
                result.push({ x: a.x + (b.x - a.x) * fraction, y: a.y + (b.y - a.y) * fraction });
            }
        });
        return result;
    }

    function slab(polygon, normal, position, width) {
        return clipPlane(
            clipPlane(polygon, normal, position + width / 2),
            { x: -normal.x, y: -normal.y }, -position + width / 2
        );
    }

    function outline(context, polygon, opacity, width = 0.8) {
        if (polygon.length < 3) return;
        context.beginPath();
        polygon.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        context.closePath();
        context.lineWidth = width;
        context.strokeStyle = `rgba(232,237,240,${opacity})`;
        context.stroke();
    }

    window.miraxDStudies.set('study-51-multi-section', {
        id: 'study-51-multi-section',
        title: '51 雙向交切截面',
        category: '第三批 · 穿透變體',
        description: '兩道空心截面異向推進，在Ｘ臂與菱隙內切出變形多邊形，交會處顯露第二層輪廓。',
        draw(context, frame) {
            const { width, height, time, arms, diamond, field } = frame;
            const polygons = [...arms, diamond].filter(polygon => polygon.length >= 3);
            if (!diamond.length || !polygons.length) return;
            const centre = diamond.reduce((sum, point) => ({
                x: sum.x + point.x / diamond.length,
                y: sum.y + point.y / diamond.length
            }), { x: 0, y: 0 });
            const top = field.top.y;
            const bottom = field.bottom.y + field.bottom.height;
            const scene = [
                { x: -width * 0.1, y: top - height * 0.1 },
                { x: width * 1.1, y: top - height * 0.1 },
                { x: width * 1.1, y: bottom + height * 0.1 },
                { x: -width * 0.1, y: bottom + height * 0.1 }
            ];
            const angleA = 1.27 + 0.13 * Math.sin(time * 0.19);
            const angleB = -0.54 + 0.16 * Math.sin(time * 0.23 + 0.8);
            const normalA = { x: Math.cos(angleA), y: Math.sin(angleA) };
            const normalB = { x: Math.cos(angleB), y: Math.sin(angleB) };
            const positionA = dot(centre, normalA) + (bottom - top) * 0.37 * Math.sin(time * 0.29);
            const positionB = dot(centre, normalB) + width * (0.31 * Math.sin(time * 0.23 + 1.2) - 0.12);
            const thickness = Math.max(9, Math.min(width, height) * 0.045);
            const planes = [
                { normal: normalA, position: positionA, width: thickness },
                { normal: normalB, position: positionB, width: thickness * 1.4 }
            ];

            // The unfilled tooling outlines keep both cuts legible beyond the mark.
            for (const plane of planes) {
                outline(context, slab(scene, plane.normal, plane.position, plane.width), 0.085);
                for (const polygon of polygons) {
                    outline(context, slab(polygon, plane.normal, plane.position, plane.width), 0.31, 1);
                    outline(context, slab(polygon, plane.normal, plane.position, plane.width * 0.32), 0.13);
                }
            }
            const crossing = slab(
                slab(scene, normalA, positionA, thickness),
                normalB, positionB, thickness * 1.4
            );
            outline(context, crossing, 0.22, 1);
            for (const polygon of polygons) {
                outline(context, slab(
                    slab(polygon, normalA, positionA, thickness),
                    normalB, positionB, thickness * 1.4
                ), 0.47, 1.15);
            }
        }
    });
})();
