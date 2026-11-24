(() => {
    'use strict';

    const wrap = value => value - Math.floor(value);

    function finitePoint(point) {
        return !!point && Number.isFinite(point.x) && Number.isFinite(point.y);
    }

    function armList(frame) {
        return (frame.arms || []).map(polygon => (
            Array.isArray(polygon) && polygon.length >= 3 && polygon.every(finitePoint) ? polygon : null
        ));
    }

    function centerOf(polygon) {
        return polygon.reduce((sum, point) => ({
            x: sum.x + point.x / polygon.length,
            y: sum.y + point.y / polygon.length
        }), { x: 0, y: 0 });
    }

    function narrowOf(frame) {
        return frame.width <= 480 && frame.height > frame.width * 1.15;
    }

    // Rear shell sits in the open field. Narrow portrait keeps the anchor inside y64-114.
    function focusOf(frame) {
        const top = frame.field && frame.field.top;
        if (narrowOf(frame)) return { x: frame.width * 0.58, y: 89 };
        if (top && top.width > 8 && top.height > 8) {
            return { x: top.x + top.width * 0.62, y: top.y + top.height * 0.58 };
        }
        return { x: frame.width * 0.62, y: frame.height * 0.22 };
    }

    function insideSpan(polygon, width, height) {
        let span = 0;
        polygon.forEach((point, index) => {
            const next = polygon[(index + 1) % polygon.length];
            const x = (point.x + next.x) / 2;
            const y = (point.y + next.y) / 2;
            if (x >= 0 && y >= 0 && x <= width && y <= height) {
                span += Math.hypot(next.x - point.x, next.y - point.y);
            }
        });
        return span;
    }

    // One or two arms that actually clear the mask. Diamond and hidden arms stay out.
    function visibleArms(frame) {
        const arms = armList(frame);
        const score = new Map();
        (frame.visibleEdges || []).forEach(edge => {
            if (!edge || !Number.isInteger(edge.arm) || !arms[edge.arm]) return;
            if (!finitePoint(edge.a) || !finitePoint(edge.b)) return;
            const length = Number.isFinite(edge.length)
                ? edge.length
                : Math.hypot(edge.b.x - edge.a.x, edge.b.y - edge.a.y);
            if (length > 0) score.set(edge.arm, (score.get(edge.arm) || 0) + length);
        });
        let ranked = arms.map((polygon, index) => ({ polygon, index, span: score.get(index) || 0 }))
            .filter(item => item.polygon && item.span > 0)
            .sort((a, b) => b.span - a.span || a.index - b.index);
        if (!ranked.length) {
            ranked = arms.map((polygon, index) => ({
                polygon, index, span: polygon ? insideSpan(polygon, frame.width, frame.height) : 0
            })).filter(item => item.polygon && item.span > 0)
                .sort((a, b) => b.span - a.span || a.index - b.index);
        }
        if (!ranked.length) {
            const focus = focusOf(frame);
            const nearest = arms.map((polygon, index) => ({ polygon, index }))
                .filter(item => item.polygon)
                .sort((a, b) => {
                    const left = centerOf(a.polygon);
                    const right = centerOf(b.polygon);
                    return Math.hypot(left.x - focus.x, left.y - focus.y)
                        - Math.hypot(right.x - focus.x, right.y - focus.y);
                });
            return nearest.slice(0, 1).map(item => item.polygon);
        }
        const picked = ranked.filter(item => item.span >= 64).slice(0, 2);
        return (picked.length ? picked : ranked.slice(0, 1))
            .sort((a, b) => a.index - b.index)
            .map(item => item.polygon);
    }

    function placeShell(polygon, center, at, taper) {
        return polygon.map(point => ({
            x: at.x + (point.x - center.x) * taper,
            y: at.y + (point.y - center.y) * taper
        }));
    }

    function mix(a, b, fraction) {
        return {
            x: a.x + (b.x - a.x) * fraction,
            y: a.y + (b.y - a.y) * fraction
        };
    }

    function rearAt(center, focus, frame, slot, count) {
        if (narrowOf(frame)) {
            const spread = count === 1 ? 0.58 : (slot === 0 ? 0.34 : 0.76);
            return { x: frame.width * spread, y: 89 };
        }
        const at = {
            x: center.x + (focus.x - center.x) * 0.78,
            y: center.y + (focus.y - center.y) * 0.78
        };
        const margin = Math.min(frame.width, frame.height) * 0.06;
        return {
            x: Math.min(frame.width - margin, Math.max(margin, at.x)),
            y: Math.min(frame.height * 0.46, Math.max(margin, at.y))
        };
    }

    function strokeOpen(context, points) {
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        context.stroke();
    }

    function strokeClosed(context, points) {
        if (points.length < 3) return;
        context.beginPath();
        points.forEach((point, index) => {
            if (index === 0) context.moveTo(point.x, point.y);
            else context.lineTo(point.x, point.y);
        });
        context.closePath();
        context.stroke();
    }

    function fieldRegions(frame) {
        return [frame.field && frame.field.top, frame.field && frame.field.bottom].filter(region =>
            region && [region.x, region.y, region.width, region.height].every(Number.isFinite)
            && region.width > 0 && region.height > 0);
    }

    function contains(region, point) {
        return point.x >= region.x && point.x <= region.x + region.width
            && point.y >= region.y && point.y <= region.y + region.height;
    }

    function backdrop(context, frame, from, anchors) {
        const regions = fieldRegions(frame);
        if (!regions.length || !anchors.length) return;
        const at = anchors.reduce((sum, point) => ({
            x: sum.x + point.x / anchors.length,
            y: sum.y + point.y / anchors.length
        }), { x: 0, y: 0 });
        let dirX = at.x - from.x;
        let dirY = at.y - from.y;
        if (Math.hypot(dirX, dirY) < 8) {
            dirX = frame.width * 0.22;
            dirY = -frame.height * 0.16;
        }
        const length = Math.hypot(dirX, dirY) || 1;
        const dir = { x: dirX / length, y: dirY / length };
        const side = { x: -dir.y, y: dir.x };
        const hosts = regions.filter(region => anchors.some(point => contains(region, point)));
        const used = hosts.length ? hosts : [regions[0]];
        context.save();
        context.beginPath();
        used.forEach(region => context.rect(region.x, region.y, region.width, region.height));
        context.clip();
        context.lineWidth = 1;
        const span = Math.hypot(frame.width, frame.height);
        [-0.22, 0, 0.22].forEach((offset, index) => {
            const origin = {
                x: at.x + side.x * offset * Math.min(used[0].width, used[0].height),
                y: at.y + side.y * offset * Math.min(used[0].width, used[0].height)
            };
            context.beginPath();
            context.moveTo(origin.x - dir.x * span, origin.y - dir.y * span);
            context.lineTo(origin.x + dir.x * span, origin.y + dir.y * span);
            context.strokeStyle = index === 1 ? 'rgba(228,230,224,0.16)' : 'rgba(228,230,224,0.09)';
            context.stroke();
        });
        context.restore();
    }

    window.miraxDStudies.set('study-194-extrusion-single-layer', {
        id: 'study-194-extrusion-single-layer',
        title: '194 單層空心引伸',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-118-section-extrusion',
        description: '可見Ｘ臂外廓只後移一層，主要角以稜線收成完整空心引伸；短截面單向穿過，前後靠遮擋交代進深。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            const chosen = visibleArms(frame);
            const focus = focusOf(frame);
            if (!chosen.length) {
                backdrop(context, frame, { x: width * 0.72, y: height * 0.58 }, [focus]);
                return;
            }
            const mean = chosen.reduce((sum, polygon) => {
                const center = centerOf(polygon);
                return { x: sum.x + center.x / chosen.length, y: sum.y + center.y / chosen.length };
            }, { x: 0, y: 0 });
            const bodies = chosen.map((polygon, slot) => {
                const center = centerOf(polygon);
                const at = rearAt(center, focus, frame, slot, chosen.length);
                const back = placeShell(polygon, center, at, 0.88);
                return { polygon, back, at, depth: Math.hypot(at.x - center.x, at.y - center.y) };
            }).sort((a, b) => b.depth - a.depth);
            backdrop(context, frame, mean, bodies.map(body => body.at));

            // One ring walks the shell and wraps in one direction. No second station and no return.
            const cut = 0.14 + wrap(time * 0.084) * 0.72;
            context.lineJoin = 'miter';
            context.lineCap = 'butt';
            bodies.forEach(({ polygon, back }) => {
                context.lineWidth = 1.15;
                context.strokeStyle = 'rgba(232,234,228,0.32)';
                polygon.forEach((point, index) => {
                    strokeOpen(context, [mix(point, back[index], cut), back[index]]);
                });
                context.lineWidth = 1.45;
                context.strokeStyle = 'rgba(236,238,232,0.58)';
                strokeClosed(context, back);
            });
            bodies.forEach(({ polygon, back }) => {
                context.lineWidth = 1.2;
                context.strokeStyle = 'rgba(238,240,234,0.46)';
                polygon.forEach((point, index) => {
                    strokeOpen(context, [point, mix(point, back[index], cut)]);
                });
            });
            bodies.forEach(({ polygon, back }) => {
                context.lineWidth = 1.65;
                context.strokeStyle = 'rgba(244,244,238,0.74)';
                strokeClosed(context, polygon.map((point, index) => mix(point, back[index], cut)));
            });
        }
    });
})();
