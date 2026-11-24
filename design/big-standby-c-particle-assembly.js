(() => {
    'use strict';

    // Source: ic_mirax_mark.xml; the diamond stays hollow, not a fifth filled arm.
    const markContours = [
        [[26.1, 23], [31.9, 23], [51.8, 42.9], [43.9, 50.8], [26.1, 32.8]],
        [[76, 23.1], [81.9, 23.1], [81.9, 32.8], [64.2, 50.5], [56.3, 43]],
        [[26.1, 73.6], [44, 55.7], [51.7, 63.5], [32, 83.3], [26.1, 83.3]],
        [[64, 55.7], [82, 73.6], [82, 83.4], [76.1, 83.4], [56.2, 63.4]],
        [[54, 47.5], [60.2, 53.4], [54, 59.4], [47.8, 53.4]]
    ];
    const clamp = (value) => Math.max(0, Math.min(1, value));
    const ease = (value) => clamp(value * value * value * (value * (value * 6 - 15) + 10));
    // Normalize the exponential decay to complete exactly at the finite endpoint.
    const exponentialOut = (value) => -Math.expm1(-4 * clamp(value)) / -Math.expm1(-4);
    const hash = (index) => {
        const value = Math.sin(index * 127.1 + 311.7) * 43758.5453;
        return value - Math.floor(value);
    };
    const contours = markContours.map((vertices) => {
        const center = vertices.reduce((sum, point) => [sum[0] + point[0], sum[1] + point[1]], [0, 0]);
        let perimeter = 0;
        const edges = vertices.map((point, index) => {
            const next = vertices[(index + 1) % vertices.length];
            const dx = next[0] - point[0];
            const dy = next[1] - point[1];
            const length = Math.hypot(dx, dy);
            const edge = { x: point[0], y: point[1], dx, dy, length, start: perimeter };
            perimeter += length;
            return edge;
        });
        return { edges, perimeter, cx: center[0] / vertices.length, cy: center[1] / vertices.length };
    });

    function createTarget(index, contourIndex) {
        return {
            contour: contours[contourIndex],
            start: (index * .618033988749895 + .13) % 1,
            inset: contourIndex === 4 ? -.02 - hash(index + 7) * .06 : .025 + hash(index + 7) * .34,
            speed: (.031 + hash(index + 79) * .018) * (index % 2 ? 1 : -1),
            phase: hash(index + 23) * Math.PI * 2,
            lag: (hash(index + 41) - .5) * 1.4,
            bend: (hash(index + 61) - .5) * .36
        };
    }

    window.MiraxCParticleAssembly = class MiraxCParticleAssembly {
        constructor(count) {
            let arm = 0;
            this.targets = Array.from({ length: count }, (_, index) => createTarget(index, index % 10 === 9 ? 4 : arm++ % 4));
            this.target = 0;
            this.from = 0;
            this.startedAt = 0;
            this.duration = 0;
            this.width = -1;
            this.height = -1;
            this.frameTime = NaN;
            this.fraction = 0;
        }

        setBroadcast(active, time) {
            const target = active ? 1 : 0;
            if (target === this.target) return;
            const now = Number.isFinite(time) ? time : this.startedAt;
            this.from = this.progress(now);
            this.target = target;
            this.startedAt = now;
            this.duration = Math.abs(target - this.from) * (active ? 6 : 8);
            this.frameTime = NaN;
        }

        progress(time) {
            if (!this.duration) return this.target;
            const now = Number.isFinite(time) ? time : this.startedAt;
            const elapsed = clamp((now - this.startedAt) / this.duration);
            // Start gathering fast and keep decelerating; leave release timing and easing unchanged.
            return this.from + (this.target - this.from) * (this.target === 1 ? exponentialOut(elapsed) : ease(elapsed));
        }

        layout(width, height) {
            this.width = width;
            this.height = height;
            this.size = Math.min(width * .82, height * .74, Math.max(0, height - 160));
            this.cx = width * .5;
            this.cy = height * .5;
            this.drift = Math.min(3, this.size * .012);
        }

        project(index, freeX, freeY, frame) {
            const time = Number.isFinite(frame.time) ? frame.time : this.startedAt;
            if (time !== this.frameTime) {
                this.frameTime = time;
                this.fraction = this.progress(time);
            }
            const fraction = this.fraction;
            const target = this.targets[index];
            // One fifth stays in the original free-flowing cloud, even after assembly.
            if (fraction === 0 || !target || index % 5 === 0) return { x: freeX, y: freeY };
            const width = Math.max(0, Number.isFinite(frame.width) ? frame.width : 0);
            const height = Math.max(0, Number.isFinite(frame.height) ? frame.height : 0);
            if (width !== this.width || height !== this.height) this.layout(width, height);
            const contour = target.contour;
            const distance = (((target.start + time * target.speed) % 1 + 1) % 1) * contour.perimeter;
            const edge = contour.edges.find((candidate) => distance < candidate.start + candidate.length) || contour.edges[0];
            const position = (distance - edge.start) / edge.length;
            const pathX = edge.x + edge.dx * position;
            const pathY = edge.y + edge.dy * position;
            const swayX = Math.sin(time * .93 + target.phase) * this.drift;
            const swayY = Math.sin(time * .75 + target.phase * 1.7) * this.drift * .7;
            // Particles circulate around the real contours instead of settling on fixed dots.
            const x = this.cx + (pathX + (contour.cx - pathX) * target.inset - 54.05) / 60.4 * this.size + swayX;
            const y = this.cy + (pathY + (contour.cy - pathY) * target.inset - 53.2) / 60.4 * this.size + swayY;
            const dx = x - freeX;
            const dy = y - freeY;
            const local = fraction + target.lag * fraction * (1 - fraction);
            const arc = 4 * local * (1 - local) * target.bend;
            return {
                x: freeX + dx * local - dy * arc,
                y: freeY + dy * local + dx * arc
            };
        }
    };
})();
