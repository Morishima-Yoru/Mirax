(() => {
    'use strict';

    const wrap = value => value - Math.floor(value);

    function finitePoint(point) {
        return !!point && Number.isFinite(point.x) && Number.isFinite(point.y);
    }

    function polygonsOf(frame) {
        return (frame.arms || []).filter(polygon =>
            Array.isArray(polygon) && polygon.length >= 3 && polygon.every(finitePoint));
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

    function focusAt(frame, xBias, yBias) {
        const top = frame.field && frame.field.top;
        if (narrowOf(frame)) return { x: frame.width * xBias, y: 64 + 50 * yBias };
        if (top && top.width > 8 && top.height > 8) {
            return { x: top.x + top.width * xBias, y: top.y + top.height * yBias };
        }
        return { x: frame.width * xBias, y: frame.height * (0.14 + 0.18 * yBias) };
    }

    function placeShell(polygon, center, at, taper) {
        return polygon.map(point => ({
            x: at.x + (point.x - center.x) * taper,
            y: at.y + (point.y - center.y) * taper
        }));
    }

    function lengthOf(points, closed) {
        const count = closed ? points.length : points.length - 1;
        let total = 0;
        for (let index = 0; index < count; index += 1) {
            const a = points[index];
            const b = points[(index + 1) % points.length];
            total += Math.hypot(b.x - a.x, b.y - a.y);
        }
        return total;
    }

    function strokeGap(context, points, closed, gapFrom, gapTo) {
        const count = closed ? points.length : points.length - 1;
        let cursor = 0;
        for (let index = 0; index < count; index += 1) {
            const a = points[index];
            const b = points[(index + 1) % points.length];
            const len = Math.hypot(b.x - a.x, b.y - a.y);
            if (len < 0.001) {
                cursor += len;
                continue;
            }
            const start = cursor;
            const end = cursor + len;
            const ranges = [];
            if (gapTo <= start || gapFrom >= end) ranges.push([0, 1]);
            else {
                if (gapFrom > start) ranges.push([0, (gapFrom - start) / len]);
                if (gapTo < end) ranges.push([(gapTo - start) / len, 1]);
            }
            ranges.forEach(([u, v]) => {
                if (v - u < 0.004) return;
                context.beginPath();
                context.moveTo(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u);
                context.lineTo(a.x + (b.x - a.x) * v, a.y + (b.y - a.y) * v);
                context.stroke();
            });
            cursor = end;
        }
    }

    function pointAlong(points, closed, distance) {
        const count = closed ? points.length : points.length - 1;
        let cursor = 0;
        for (let index = 0; index < count; index += 1) {
            const a = points[index];
            const b = points[(index + 1) % points.length];
            const len = Math.hypot(b.x - a.x, b.y - a.y);
            if (len > 0 && cursor + len >= distance) {
                const u = (distance - cursor) / len;
                return {
                    x: a.x + (b.x - a.x) * u,
                    y: a.y + (b.y - a.y) * u,
                    tx: (b.x - a.x) / len,
                    ty: (b.y - a.y) / len
                };
            }
            cursor += len;
        }
        return null;
    }

    function fieldRegions(frame) {
        return [frame.field && frame.field.top, frame.field && frame.field.bottom].filter(region =>
            region && [region.x, region.y, region.width, region.height].every(Number.isFinite)
            && region.width > 0 && region.height > 0);
    }

    function backdrop(context, frame, dir) {
        const regions = fieldRegions(frame);
        if (!regions.length) return;
        const length = Math.hypot(dir.x, dir.y) || 1;
        const axis = { x: dir.x / length, y: dir.y / length };
        const side = { x: -axis.y, y: axis.x };
        const steps = [0, 26, 62, 112, 178, 268];
        context.save();
        context.beginPath();
        regions.forEach(region => context.rect(region.x, region.y, region.width, region.height));
        context.clip();
        context.lineWidth = 1;
        regions.forEach(region => {
            const anchor = {
                x: region.x + region.width * 0.62,
                y: region.y + region.height * 0.55
            };
            steps.forEach((step, index) => {
                const origin = {
                    x: anchor.x + side.x * step,
                    y: anchor.y + side.y * step
                };
                const span = Math.max(region.width, region.height) * (0.55 + index * 0.12);
                context.beginPath();
                context.moveTo(origin.x - axis.x * span * 0.35, origin.y - axis.y * span * 0.35);
                context.lineTo(origin.x + axis.x * span, origin.y + axis.y * span);
                context.strokeStyle = index < 3 ? 'rgba(228,230,224,0.13)' : 'rgba(228,230,224,0.07)';
                context.stroke();
            });
        });
        context.restore();
    }

    window.miraxDStudies.set('study-198-extrusion-stepped-shell', {
        id: 'study-198-extrusion-stepped-shell',
        title: '198 層階稜框',
        category: '第九批 · 歷史回饋',
        sourceId: 'study-118-section-extrusion',
        description: 'Ｘ一側連出兩層尺度不同的開口輪廓，少數折階稜固定註冊；短缺口由近層依序移到遠層。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            const arms = polygonsOf(frame);
            const arm = arms.slice().sort((left, right) => {
                const a = centerOf(left);
                const b = centerOf(right);
                return (b.x / width + b.y / height) - (a.x / width + a.y / height);
            })[0];
            const farAt = focusAt(frame, 0.72, 0.34);
            const nearAt = focusAt(frame, 0.46, 0.78);
            const center = arm ? centerOf(arm) : { x: width * 0.74, y: height * 0.62 };
            backdrop(context, frame, { x: farAt.x - center.x, y: farAt.y - center.y });
            if (!arm) return;

            const unit = Math.min(width, height);
            const near = placeShell(arm, center, nearAt, 1.06);
            const far = placeShell(arm, center, farAt, 0.62);
            const travel = { x: farAt.x - nearAt.x, y: farAt.y - nearAt.y };
            const travelLen = Math.hypot(travel.x, travel.y) || 1;
            const side = { x: -travel.y / travelLen, y: travel.x / travelLen };
            const kink = Math.max(16, unit * 0.04);
            const indexes = [0, 2, arm.length - 1];
            const steps = indexes.map(index => {
                const a = near[index];
                const b = far[index];
                return [
                    a,
                    {
                        x: a.x + (b.x - a.x) * 0.36 + side.x * kink,
                        y: a.y + (b.y - a.y) * 0.36 + side.y * kink
                    },
                    {
                        x: a.x + (b.x - a.x) * 0.7 - side.x * kink * 0.65,
                        y: a.y + (b.y - a.y) * 0.7 - side.y * kink * 0.65
                    },
                    b
                ];
            });
            const pieces = [
                { points: near, closed: true },
                ...steps.map(points => ({ points, closed: false })),
                { points: far, closed: true }
            ];
            const totals = pieces.map(piece => lengthOf(piece.points, piece.closed));
            const sum = totals.reduce((total, value) => total + value, 0) || 1;
            // Notch walks near shell, then each fold, then the far shell. Shells stay put.
            let cursor = wrap(time * 0.078) * sum;
            let active = pieces.length - 1;
            for (let index = 0; index < totals.length; index += 1) {
                if (cursor <= totals[index]) {
                    active = index;
                    break;
                }
                cursor -= totals[index];
            }
            context.lineJoin = 'miter';
            context.lineCap = 'butt';
            context.lineWidth = 1.15;
            context.strokeStyle = 'rgba(236,238,232,0.24)';
            indexes.forEach(index => {
                context.beginPath();
                context.moveTo(arm[index].x, arm[index].y);
                context.lineTo(near[index].x, near[index].y);
                context.stroke();
            });

            pieces.forEach((piece, index) => {
                const total = totals[index];
                const gap = index === active ? Math.min(total * 0.22, Math.max(14, unit * 0.03)) : 0;
                const gapFrom = index === active ? cursor - gap * 0.5 : 0;
                const gapTo = index === active ? cursor + gap * 0.5 : 0;
                context.lineWidth = index === 0 ? 1.45 : index === pieces.length - 1 ? 1.25 : 1.15;
                context.strokeStyle = index === pieces.length - 1
                    ? 'rgba(244,244,238,0.58)'
                    : index === 0 ? 'rgba(236,238,232,0.42)' : 'rgba(226,228,222,0.34)';
                strokeGap(context, piece.points, piece.closed, gapFrom, gapTo);
                if (index === active) {
                    const cap = pointAlong(piece.points, piece.closed, Math.max(0, Math.min(total, gapTo)));
                    if (cap) {
                        context.strokeStyle = 'rgba(248,248,242,0.8)';
                        context.lineWidth = 1.6;
                        context.beginPath();
                        context.moveTo(cap.x, cap.y);
                        context.lineTo(cap.x + cap.tx * 18, cap.y + cap.ty * 18);
                        context.stroke();
                    }
                }
            });
        }
    });
})();
