(() => {
    'use strict';

    const mix = (a, b, fraction) => ({
        x: a.x + (b.x - a.x) * fraction,
        y: a.y + (b.y - a.y) * fraction
    });

    function fieldClip(context, frame) {
        const regions = [frame.field?.top, frame.field?.bottom].filter(region => region
            && [region.x, region.y, region.width, region.height].every(Number.isFinite)
            && region.width > 0 && region.height > 0);
        if (!regions.length) return false;
        context.beginPath();
        for (const region of regions) context.rect(region.x, region.y, region.width, region.height);
        context.clip();
        return true;
    }

    function underlay(context, frame) {
        context.save();
        if (fieldClip(context, frame)) {
            const pitch = Math.max(36, Math.min(72, Math.min(frame.width, frame.height) * 0.1));
            const phase = Math.sin(frame.time * 0.17) * pitch * 0.12;
            context.beginPath();
            for (let x = -frame.height; x < frame.width + frame.height; x += pitch) {
                context.moveTo(x + phase, 0);
                context.lineTo(x + phase + frame.height * 0.6, frame.height);
                context.moveTo(x - phase, 0);
                context.lineTo(x - phase - frame.height * 0.6, frame.height);
            }
            context.lineWidth = 0.75;
            context.strokeStyle = 'rgba(240,240,240,0.055)';
            context.stroke();
        }
        context.restore();
    }

    function polygonCenter(polygon) {
        return polygon.reduce((sum, point) => ({
            x: sum.x + point.x / polygon.length,
            y: sum.y + point.y / polygon.length
        }), { x: 0, y: 0 });
    }

    function segment(context, a, b) {
        if (Math.hypot(b.x - a.x, b.y - a.y) < 0.001) return;
        context.moveTo(a.x, a.y);
        context.lineTo(b.x, b.y);
    }

    window.miraxDStudies.set('study-118-section-extrusion', {
        id: 'study-118-section-extrusion',
        title: '118 C2 假透視引伸框',
        category: '第六批 · 立體關係探索',
        sourceId: 'study-14-penetration',
        description: '沿Ｘ臂外廓向斜後方延伸多層等角截面框，邊角以稜線連綴成空心稜柱體積。',
        draw(context, frame) {
            const { width, height, time } = frame;
            if (![width, height, time].every(Number.isFinite) || width <= 0 || height <= 0) return;
            underlay(context, frame);

            const polygons = [...(frame.arms || []), frame.diamond || []].filter(polygon =>
                polygon.length >= 3 && polygon.every(point => Number.isFinite(point.x) && Number.isFinite(point.y)));
            if (!polygons.length) return;

            const unit = Math.min(width, height);
            const rails = (frame.rails || []).filter(([a, b]) =>
                [a.x, a.y, b.x, b.y].every(Number.isFinite) && Math.hypot(b.x - a.x, b.y - a.y) > 10);
            const baseAngle = rails.length
                ? Math.atan2(rails[0][1].y - rails[0][0].y, rails[0][1].x - rails[0][0].x)
                : -0.785;
            const angle = baseAngle + 0.04 * Math.sin(time * 0.22);
            const dir = { x: Math.cos(angle), y: Math.sin(angle) };
            const depthMax = unit * (0.13 + 0.02 * Math.sin(time * 0.26));

            // Continuous longitudinal corner edges anchored to real X arm vertices
            context.beginPath();
            for (const polygon of polygons) {
                const center = polygonCenter(polygon);
                for (const v of polygon) {
                    const vend = {
                        x: center.x + (v.x - center.x) * 0.85 + dir.x * depthMax,
                        y: center.y + (v.y - center.y) * 0.85 + dir.y * depthMax
                    };
                    segment(context, v, vend);
                }
            }
            context.lineWidth = 0.8;
            context.strokeStyle = 'rgba(240,240,240,0.22)';
            context.stroke();

            // Registered sectional frames along the extrusion axis
            const frameCount = 4;
            for (let k = 0; k < frameCount; k++) {
                const progress = (k / frameCount + time * 0.075) % 1;
                const depth = progress * depthMax;
                const taper = 1 - 0.15 * progress;
                const opacity = Math.sin(progress * Math.PI) * 0.38;
                if (opacity < 0.01) continue;

                context.beginPath();
                for (const polygon of polygons) {
                    const center = polygonCenter(polygon);
                    polygon.forEach((v, idx) => {
                        const px = center.x + (v.x - center.x) * taper + dir.x * depth;
                        const py = center.y + (v.y - center.y) * taper + dir.y * depth;
                        if (idx === 0) context.moveTo(px, py);
                        else context.lineTo(px, py);
                    });
                    context.closePath();
                }
                context.lineWidth = 0.85;
                context.strokeStyle = `rgba(240,240,240,${opacity})`;
                context.stroke();
            }

            // Outer projection guide lines extending into the background field
            context.save();
            if (fieldClip(context, frame)) {
                context.beginPath();
                for (const polygon of polygons) {
                    const center = polygonCenter(polygon);
                    for (const v of polygon) {
                        const dCenter = Math.hypot(v.x - center.x, v.y - center.y);
                        if (dCenter < unit * 0.08) continue;
                        const vstart = {
                            x: center.x + (v.x - center.x) * 0.85 + dir.x * depthMax,
                            y: center.y + (v.y - center.y) * 0.85 + dir.y * depthMax
                        };
                        const vext = {
                            x: center.x + (v.x - center.x) * 0.72 + dir.x * depthMax * 1.85,
                            y: center.y + (v.y - center.y) * 0.72 + dir.y * depthMax * 1.85
                        };
                        segment(context, vstart, vext);
                    }
                }
                context.lineWidth = 0.65;
                context.strokeStyle = 'rgba(240,240,240,0.09)';
                context.stroke();
            }
            context.restore();
        }
    });
})();
