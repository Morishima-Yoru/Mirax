(() => {
    'use strict';

    const root = document.getElementById('e-scene');
    if (!root) return;

    const upperCopy = [
        '<span class="e-code-comment">; display-only register sketch</span> R[00] &larr; <span class="e-code-word">MIRAX</span> | R[03] := 0x0C / mask=0b1010 // ',
        '<span class="e-code-address">0xD030:</span> 4D 49 52 41 58 &nbsp; 00 2F 2F 00 | <span class="e-code-word">MIRAX</span> [illustrative bytes] &middot; ',
        '<span class="e-code-marker">/*</span> no input sampled; glyphs only &lt;frame/&gt; <span class="e-code-comment">return decoration;</span> <span class="e-code-marker">*/</span> '
    ];
    const lowerCopy = '&lt;decor seq="--" flags="00"&gt; <span class="e-code-word">MIRAX</span> : [empty] &lt;/decor&gt; <span class="e-code-comment">fiction / no signal data</span> // ';
    const track = command => {
        const copy = command.repeat(8);
        const segment = `<span class="e-stream-segment"><span class="e-copy">${copy}</span></span>`;
        return `<div class="e-stream-track">${segment}${segment}</div>`;
    };
    const row = command => `<div class="e-stream-row">${track(command)}</div>`;
    const statusCopy = {
        off: 'Press the broadcast button to start receiving',
        arming: 'Starting · Not ready',
        'wifi-paused': 'Wi-Fi is off · Please turn on Wi-Fi to broadcast',
        frozen: 'Receiver unavailable · Check settings',
        waiting: 'Windows: press <span class="e-key-hint">Win + K</span> · Choose MIRAX · 客廳',
        connecting: 'Connecting · Please wait',
        failed: 'Connection failed · Press <span class="e-key-hint">Win + K</span> to retry',
        projecting: 'Connected · Receiving projection'
    };
    const statusMessages = Object.entries(statusCopy).map(([phase, message]) =>
        `<div class="e-status-message" data-status="${phase}">${track(`<span class="e-status-phrase">${message}</span>`)}</div>`
    ).join('');

    // All markup is fixed known copy; CSS selects the real stage state without another announcer or clock.
    root.setAttribute('aria-hidden', 'true');
    root.innerHTML = `
        <div class="e-channel e-diagonal e-channel--upper" aria-hidden="true">
            <div class="e-stream-window">
                ${row(upperCopy[0])}${row(upperCopy[1])}
                <div class="e-stream-row e-stream-row--status">${statusMessages}</div>
            </div>
        </div>
        <div class="e-channel e-diagonal e-channel--lower" aria-hidden="true">
            <div class="e-stream-window">
                ${row(upperCopy[2])}
                ${row(lowerCopy)}
            </div>
        </div>
    `;

    class DStreams {
        constructor(channels, random = Math.random) {
            this.random = random;
            this.active = false;
            this.skipNextUpdate = false;
            this.elapsedSeconds = 0;
            this.history = [];
            this.streams = channels.flatMap((element, index) => ['top', 'bottom'].map(edge => ({
                element, edge, name: `${index === 0 ? 'upper' : 'lower'}-${edge}`,
                shotIndex: 0, startedAtSeconds: null
            })));
            this.streams.forEach(stream => this.schedule(stream));
        }

        schedule(stream) {
            stream.gapSeconds = .12 + this.random() * .78;
            stream.durationSeconds = .95 + this.random() * .65;
            stream.elapsedSeconds = 0;
            stream.phase = 'waiting';
            stream.progress = 0;
            stream.position = 0;
            stream.alpha = 0;
            this.paint(stream);
        }

        paint(stream) {
            stream.element.style.setProperty(`--e-${stream.edge}-position`, String(stream.position));
            stream.element.style.setProperty(`--e-${stream.edge}-alpha`, String(stream.alpha));
        }

        setActive(active) {
            const next = Boolean(active);
            if (next === this.active) return;
            this.active = next;
            // The host's first resumed delta may contain hidden wall-clock time.
            this.skipNextUpdate = next;
        }

        update(deltaSeconds) {
            if (!this.active || !Number.isFinite(deltaSeconds) || deltaSeconds <= 0) return;
            if (this.skipNextUpdate) {
                this.skipNextUpdate = false;
                return;
            }
            const step = Math.min(deltaSeconds, .1);
            this.elapsedSeconds += step;
            for (const stream of this.streams) {
                stream.elapsedSeconds += step;
                if (stream.phase === 'waiting') {
                    if (stream.elapsedSeconds < stream.gapSeconds) continue;
                    stream.phase = 'shot';
                    stream.elapsedSeconds -= stream.gapSeconds;
                    stream.shotIndex += 1;
                    stream.startedAtSeconds = this.elapsedSeconds - stream.elapsedSeconds;
                    this.history.push({
                        name: stream.name, shotIndex: stream.shotIndex,
                        startedAtSeconds: stream.startedAtSeconds,
                        gapSeconds: stream.gapSeconds, durationSeconds: stream.durationSeconds
                    });
                    if (this.history.length > 32) this.history.shift();
                }
                stream.progress = Math.min(1, stream.elapsedSeconds / stream.durationSeconds);
                // Quadratic travel accelerates all the way through, never ease-out.
                stream.position = stream.progress * stream.progress;
                stream.alpha = Math.min(1, stream.progress * 12, (1 - stream.progress) * 14);
                if (stream.progress === 1) this.schedule(stream);
                else this.paint(stream);
            }
        }

        snapshot() {
            return {
                active: this.active,
                elapsedSeconds: this.elapsedSeconds,
                streams: this.streams.map(({ element, ...state }) => ({ ...state })),
                history: this.history.map(shot => ({ ...shot }))
            };
        }
    }

    window.MiraxDStreams = new DStreams([...root.querySelectorAll('.e-channel')]);
})();
