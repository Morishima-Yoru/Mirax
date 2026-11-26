(() => {
    'use strict';

    const normalize = (value) => Number.isFinite(value) ? Math.max(-1, Math.min(1, value)) : 0;

    window.MiraxCParallax = class {
        constructor(canvas, information) {
            if (!canvas || canvas.id !== 'particle-canvas' || canvas.tagName !== 'CANVAS') {
                throw new TypeError('C parallax requires #particle-canvas.');
            }
            if (!information || information.id !== 'standby-view') {
                throw new TypeError('C parallax requires #standby-view information.');
            }
            this.canvas = canvas;
            this.information = information;
            canvas.style.pointerEvents = 'none';
            canvas.style.transformOrigin = '50% 50%';
            this.reset();
        }

        setPointer(x, y) {
            this.targetX = normalize(x);
            this.targetY = normalize(y);
        }

        reset() {
            this.targetX = 0;
            this.targetY = 0;
            this.x = 0;
            this.y = 0;
            this.informationX = 0;
            this.informationY = 0;
            this.canvas.style.transform = '';
            this.information.style.removeProperty('--c-parallax-transform');
        }

        update(deltaSeconds) {
            const width = this.information.clientWidth;
            const height = this.information.clientHeight;
            if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
                this.reset();
                return;
            }
            const delta = Number.isFinite(deltaSeconds) ? Math.max(0, Math.min(.1, deltaSeconds)) : 0;
            const blend = -Math.expm1(-4 * delta);
            const informationBlend = -Math.expm1(-2 * delta);
            this.x += (this.targetX - this.x) * blend;
            this.y += (this.targetY - this.y) * blend;
            // Separate motion histories create depth without increasing particle sensitivity.
            this.informationX += (this.targetX - this.informationX) * informationBlend;
            this.informationY += (this.targetY - this.informationY) * informationBlend;
            if (this.x === 0 && this.y === 0 && this.informationX === 0 && this.informationY === 0) {
                this.canvas.style.transform = '';
                this.information.style.removeProperty('--c-parallax-transform');
                return;
            }
            const shortEdge = Math.min(width, height);
            const dx = this.x * shortEdge * .0189;
            const dy = this.y * shortEdge * .0189;
            // Layered translation preserves size everywhere, including the receding side.
            this.canvas.style.transform = `translate3d(${dx}px, ${dy}px, 0)`;
            this.information.style.setProperty('--c-parallax-transform',
                `translate3d(${this.informationX * shortEdge * .01}px, ${this.informationY * shortEdge * .01}px, 0)`);
        }
    };
})();
