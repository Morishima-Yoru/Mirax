(() => {
    'use strict';

    window.MiraxCOledDrift = class MiraxCOledDrift {
        constructor(random = Math.random) {
            this.random = random;
            this.x = 0;
            this.y = 0;
            this.fromX = 0;
            this.fromY = 0;
            this.elapsed = 0;
            this.pickTarget();
        }

        pickTarget() {
            this.targetX = (this.random() * 2 - 1) * 3;
            this.targetY = (this.random() * 2 - 1) * 3;
            // A full excursion takes minutes, including smooth direction changes at both ends.
            this.duration = Math.max(150, Math.hypot(this.targetX - this.x, this.targetY - this.y) * 75);
        }

        advance(deltaSeconds) {
            if (!Number.isFinite(deltaSeconds) || deltaSeconds <= 0) return { x: this.x, y: this.y };
            this.elapsed = Math.min(this.duration, this.elapsed + Math.min(deltaSeconds, .25));
            const progress = this.elapsed / this.duration;
            const blend = progress * progress * progress * (progress * (progress * 6 - 15) + 10);
            this.x = this.fromX + (this.targetX - this.fromX) * blend;
            this.y = this.fromY + (this.targetY - this.fromY) * blend;
            if (this.elapsed >= this.duration) {
                this.fromX = this.x;
                this.fromY = this.y;
                this.elapsed = 0;
                this.pickTarget();
            }
            return { x: this.x, y: this.y };
        }
    };
})();
