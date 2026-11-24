(() => {
    'use strict';

    window.MiraxCGyroscope = class MiraxCGyroscope {
        constructor({ onInput, onReset, onStatus }) {
            this.onInput = onInput;
            this.onReset = onReset;
            this.onStatus = onStatus;
            this.enabled = false;
            this.requesting = false;
            this.listening = false;
            this.status = 'idle';
            this._active = false;
            this._granted = false;
            this._generation = 0;
            this._destroyed = false;
            this._previous = null;
            this._sampleTimer = null;
            this._idleTimer = null;
            this._orientationTarget = null;
            this._onSample = event => this._handleSample(event);
            this._onScreenChange = () => this.resetMotion();
        }

        async enable() {
            if (this._destroyed || this.enabled) return;
            if (!window.isSecureContext) {
                this._stop('insecure');
                return;
            }
            const OrientationEvent = window.DeviceOrientationEvent;
            if (!OrientationEvent) {
                this._stop('unsupported');
                return;
            }
            this.enabled = true;
            const generation = ++this._generation;
            if (!this._granted) {
                if (typeof OrientationEvent.requestPermission === 'function') {
                    this.requesting = true;
                    this._setStatus('requesting');
                    try {
                        // Call before the first await to retain the click's transient activation.
                        const permission = await OrientationEvent.requestPermission();
                        if (generation !== this._generation) return;
                        this.requesting = false;
                        if (permission !== 'granted') {
                            console.warn('C gyroscope permission was not granted:', permission);
                            this._stop('denied');
                            return;
                        }
                    } catch (error) {
                        console.warn('C gyroscope permission request failed:', error);
                        if (generation !== this._generation) return;
                        this._stop('error');
                        return;
                    }
                }
                this._granted = true;
            }
            if (this._active) this._startListening();
            else this._setStatus('suspended');
        }

        disable() {
            if (!this._destroyed) this._stop('idle');
        }

        setActive(active) {
            if (this._destroyed) return;
            this._active = active;
            if (!active) {
                this._stopListening();
                if (this.enabled && this._granted) this._setStatus('suspended');
            } else if (this.enabled && this._granted) {
                this._startListening();
            }
        }

        resetMotion() {
            if (this._destroyed || !this.listening) return;
            this._previous = null;
            this._clearSampleTimer();
            this._clearIdleTimer();
            this._sampleTimer = window.setTimeout(() => {
                this._sampleTimer = null;
                this._stop('unavailable');
            }, 3000);
            this.onReset();
            this._setStatus('waiting');
        }

        destroy() {
            if (this._destroyed) return;
            this._destroyed = true;
            this._stop('idle');
        }

        _setStatus(status) {
            this.status = status;
            this.onStatus();
        }

        _clearSampleTimer() {
            if (this._sampleTimer !== null) window.clearTimeout(this._sampleTimer);
            this._sampleTimer = null;
        }

        _clearIdleTimer() {
            if (this._idleTimer !== null) window.clearTimeout(this._idleTimer);
            this._idleTimer = null;
        }

        _stop(status) {
            this._generation += 1;
            this.enabled = false;
            this.requesting = false;
            this._stopListening();
            this._setStatus(status);
        }

        _stopListening() {
            window.removeEventListener('deviceorientation', this._onSample);
            if (this._orientationTarget) {
                this._orientationTarget.removeEventListener('change', this._onScreenChange);
                this._orientationTarget = null;
            } else {
                window.removeEventListener('orientationchange', this._onScreenChange);
            }
            this.listening = false;
            this._previous = null;
            this._clearSampleTimer();
            this._clearIdleTimer();
            this.onReset();
        }

        _startListening() {
            if (this.listening || this._destroyed || !this.enabled || !this._active) return;
            this.listening = true;
            window.addEventListener('deviceorientation', this._onSample);
            const orientation = window.screen?.orientation;
            if (typeof orientation?.addEventListener === 'function' &&
                typeof orientation.removeEventListener === 'function') {
                this._orientationTarget = orientation;
                orientation.addEventListener('change', this._onScreenChange);
            } else {
                window.addEventListener('orientationchange', this._onScreenChange);
            }
            this.resetMotion();
        }

        _handleSample({ alpha, beta, gamma }) {
            if (!this.listening || !this.enabled || !this._active) return;
            if (!Number.isFinite(alpha) || alpha < 0 || alpha >= 360 ||
                !Number.isFinite(beta) || beta < -180 || beta > 180 ||
                !Number.isFinite(gamma) || gamma < -90 || gamma > 90) return;
            const time = window.performance.now();
            if (!Number.isFinite(time)) return;
            const previous = this._previous;
            const elapsed = previous ? time - previous.time : 0;
            if (previous && elapsed <= 0) return;
            const radians = Math.PI / 180;
            const ca = Math.cos(alpha * radians);
            const sa = Math.sin(alpha * radians);
            const cb = Math.cos(beta * radians);
            const sb = Math.sin(beta * radians);
            const cg = Math.cos(gamma * radians);
            const sg = Math.sin(gamma * radians);
            // https://www.w3.org/TR/orientation-event/#device-orientation-model
            // R = Rz * Rx * Ry, with Rz=[ca,-sa,0;sa,ca,0;0,0,1],
            // Rx=[1,0,0;0,cb,-sb;0,sb,cb], Ry=[cg,0,sg;0,1,0;-sg,0,cg].
            const normal = [ca * sg + sa * sb * cg, sa * sg - ca * sb * cg, cb * cg];
            this._previous = { time, matrix: [
                [ca * cg - sa * sb * sg, sa * cg + ca * sb * sg, -cb * sg],
                [-sa * cb, ca * cb, sb],
                normal
            ] };
            this._clearSampleTimer();
            this._clearIdleTimer();
            this._idleTimer = window.setTimeout(() => {
                this._idleTimer = null;
                this.onInput(0, 0);
            }, 150);
            if (!previous || elapsed > 250) {
                if (!previous) this._setStatus('active');
                this.onInput(0, 0);
                return;
            }
            // Stored columns give the third column of R_previous^T * R_current.
            const [nx, ny, nz] = previous.matrix.map(column =>
                column[0] * normal[0] + column[1] * normal[1] + column[2] * normal[2]);
            const normalize = angle => Math.abs(angle) < 1e-12 ? 0 :
                Math.max(-1, Math.min(1, angle / radians / (elapsed / 1000) / 90));
            const x = normalize(Math.atan2(nx, nz));
            const y = normalize(-Math.atan2(ny, nz));
            const screenAngle = window.screen?.orientation?.angle;
            const angle = Number.isFinite(screenAngle) ? screenAngle : window.orientation;
            switch (Number.isFinite(angle) ? (angle % 360 + 360) % 360 : 0) {
                case 90: this.onInput(y, -x); break;
                case 180: this.onInput(-x, -y); break;
                case 270: this.onInput(-y, x); break;
                default: this.onInput(x, y);
            }
        }
    };
})();
