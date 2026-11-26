(() => {
    'use strict';

    const PERIOD = 5.6;
    const TOOTH_PITCH = 11;
    const clamp = (value) => Math.max(0, Math.min(1, value));
    const smooth = (value) => {
        const progress = clamp(value);
        return progress * progress * (3 - 2 * progress);
    };

    function alignment(time) {
        const phase = ((time % PERIOD) + PERIOD) % PERIOD;
        if (phase < 2.35) return { offset: 13 * (phase / 2.35) ** 2, mismatch: 2.2, lock: 0 };
        if (phase < 3) {
            const lock = smooth((phase - 2.35) / 0.65);
            return { offset: 13 - lock * 2, mismatch: 2.2 * (1 - lock), lock };
        }
        if (phase < 3.55) {
            const elapsed = phase - 3;
            const recoil = 1.5 * Math.exp(-10 * elapsed) * Math.sin(18 * elapsed)
                * (1 - smooth(elapsed / 0.55));
            return { offset: 11 + recoil, mismatch: 0, lock: 1 };
        }
        const release = smooth((phase - 3.55) / 2.05);
        return { offset: 11 * (1 - release), mismatch: 2.2 * release, lock: 1 - release };
    }

    function drawVernier(context, pair, time, opacity) {
        const lengths = pair.map(([a, b]) => Math.hypot(b.x - a.x, b.y - a.y));
        if (lengths.some((length) => !Number.isFinite(length) || length < 50)) return;
        const length = Math.min(...lengths) * 0.86;
        const { offset, mismatch, lock } = alignment(time);
        const pitch = TOOTH_PITCH * 5 + mismatch;
        const at = (distance) => pair.map(([a, b]) => {
            const progress = 0.07 + clamp(distance / length) * 0.86;
            return { x: a.x + (b.x - a.x) * progress, y: a.y + (b.y - a.y) * progress };
        });
        const tooth = (a, b, reach) => {
            const gap = Math.hypot(b.x - a.x, b.y - a.y);
            const scale = gap > 0.1 ? Math.min(reach, gap * 0.38) / gap : 0;
            return { x: a.x + (b.x - a.x) * scale, y: a.y + (b.y - a.y) * scale };
        };
        const line = (a, b) => {
            context.moveTo(a.x, a.y);
            context.lineTo(b.x, b.y);
        };
        const [first] = at(0);
        const [last] = at(length);
        context.lineWidth = 0.65;
        context.strokeStyle = `rgba(166,174,169,${opacity * 0.32})`;
        context.beginPath();
        line(first, last);
        context.stroke();

        for (let distance = 0, index = 0; distance <= length; distance += TOOTH_PITCH, index += 1) {
            const [a, b] = at(distance);
            const nearest = Math.round((distance - offset) / pitch) * pitch + offset;
            const error = Math.abs(distance - nearest);
            const engagement = Math.exp(-((error / 2.35) ** 2));
            context.lineWidth = engagement > 0.65 ? 1.25 : 0.8;
            context.strokeStyle = `rgba(224,237,226,${opacity * (0.22 + engagement * 0.57)})`;
            context.beginPath();
            line(a, tooth(a, b, index % 5 === 0 ? 9 : 5));
            context.stroke();
        }

        const firstIndex = Math.floor(-offset / pitch) - 1;
        const lastIndex = Math.ceil((length - offset) / pitch);
        for (let index = firstIndex; index <= lastIndex; index += 1) {
            const distance = index * pitch + offset;
            if (distance + pitch * 0.69 < 0 || distance > length) continue;
            const [a, b] = at(distance);
            const [, end] = at(distance + pitch * 0.69);
            const tip = tooth(b, a, 9);
            const nearestTooth = Math.round(distance / TOOTH_PITCH) * TOOTH_PITCH;
            const error = Math.abs(distance - nearestTooth);
            const engagement = Math.exp(-((error / 2.35) ** 2));
            context.lineWidth = 1.3;
            context.strokeStyle = `rgba(227,239,230,${opacity * (0.42 + engagement * 0.33)})`;
            context.beginPath();
            line(b, end);
            if (distance >= 0) line(b, tip);
            context.stroke();
            if (distance < 0 || nearestTooth < 0 || nearestTooth > length) continue;
            // The travelling near-match becomes a physical cross-bank latch, not a moving dash.
            const [toothBase, otherBank] = at(nearestTooth);
            const toothTip = tooth(toothBase, otherBank, 7);
            context.lineWidth = 0.8;
            context.strokeStyle = `rgba(231,246,230,${opacity * engagement * (0.3 + lock * 0.35)})`;
            context.beginPath();
            context.moveTo(toothTip.x, toothTip.y);
            context.lineTo((toothTip.x + tip.x) / 2, (toothTip.y + tip.y) / 2 - error * 0.6);
            context.lineTo(tip.x, tip.y);
            context.stroke();
        }
    }

    window.miraxDStudies.set('study-09-vernier', {
        id: 'study-09-vernier',
        title: '09 虛實線段咬合',
        category: '幾何張力',
        description: '細齒尺對抗長行程滑塊，游標節距差形成移動拍點；壓縮到整倍齒距時逐排扣合，再解除定位。',
        draw(context, frame) {
            context.lineCap = 'butt';
            context.lineJoin = 'miter';
            if (frame.rails.length >= 2) {
                const pair = frame.rails.slice(0, 2).map(([a, b]) => a.y >= b.y ? [a, b] : [b, a]);
                drawVernier(context, pair, frame.time, 1);
            }
            const usedArms = new Set();
            let field = 0;
            for (const { a, b, length, arm } of [...frame.visibleEdges].sort((a, b) => b.length - a.length)) {
                if (length < 100 || usedArms.has(arm)) continue;
                usedArms.add(arm);
                const normal = { x: -(b.y - a.y) / length, y: (b.x - a.x) / length };
                const inward = (frame.width / 2 - (a.x + b.x) / 2) * normal.x
                    + (frame.height / 2 - (a.y + b.y) / 2) * normal.y;
                const gap = inward < 0 ? -15 : 15;
                const echo = [a, b].map((point) => ({ x: point.x + normal.x * gap, y: point.y + normal.y * gap }));
                drawVernier(context, [[a, b], echo], frame.time + 1.4 + field * 1.7, 0.72);
                field += 1;
                if (field === 2) break;
            }
        }
    });
})();
