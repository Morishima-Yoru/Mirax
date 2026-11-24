// Run with Playwright browser_run_code_unsafe; observe the actual tap-to-particle projection path.
async (page) => {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const runs = [];
    for (const reducedMotion of ['reduce', 'no-preference']) {
        const browserContext = await page.context().browser().newContext({
            viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, reducedMotion
        });
        const probe = await browserContext.newPage();
        const errors = [];
        probe.on('pageerror', error => errors.push(error.message));
        try {
            await probe.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=C&log=rail&v=34');
            await probe.bringToFront();
            await probe.evaluate(() => {
                const complete = new window.MiraxCParticleAssembly(particles.length);
                complete.target = 1;
                const nativeProject = cParticleAssembly.project;
                window.onsetProbe = { clickAt: null, frames: [], nativeProject };
                document.querySelector('#broadcast-toggle').addEventListener('click', () => {
                    onsetProbe.clickAt = performance.now();
                }, { capture: true, once: true });
                cParticleAssembly.project = function (index, freeX, freeY, frame) {
                    const output = nativeProject.call(this, index, freeX, freeY, frame);
                    const elapsed = frame.time - this.startedAt;
                    if (this.target !== 1 || elapsed < 0 || elapsed > .2 || index % 5 === 0) return output;
                    const target = complete.project(index, freeX, freeY, frame);
                    const dx = target.x - freeX;
                    const dy = target.y - freeY;
                    const distanceSquared = dx * dx + dy * dy;
                    if (distanceSquared < 400) return output;
                    let sample = onsetProbe.frames.at(-1);
                    if (!sample || sample.time !== frame.time) {
                        sample = { time: frame.time, elapsed, progress: this.fraction, observedAt: performance.now(), pulls: [], motions: [] };
                        onsetProbe.frames.push(sample);
                    }
                    sample.pulls.push(((output.x - freeX) * dx + (output.y - freeY) * dy) / distanceSquared);
                    sample.motions.push(Math.hypot(output.x - freeX, output.y - freeY));
                    return output;
                };
            });
            await probe.locator('#broadcast-toggle').tap();
            await probe.waitForFunction(() => onsetProbe.frames.some(frame => frame.elapsed >= .1), null, { timeout: 5000 });
            const result = await probe.evaluate(() => {
                const median = values => values.slice().sort((a, b) => a - b)[Math.floor(values.length / 2)];
                const frames = onsetProbe.frames.filter(frame => frame.elapsed > 0);
                const first = frames[0];
                const reaction = frames.find(frame => frame.elapsed >= .1);
                const summarize = sample => ({ progress: sample.progress, elapsedMs: sample.elapsed * 1000,
                    clickLatencyMs: sample.observedAt - onsetProbe.clickAt, particles: sample.pulls.length,
                    medianPullFraction: median(sample.pulls), towardFraction: sample.pulls.filter(value => value > 0).length / sample.pulls.length,
                    medianMotionPx: median(sample.motions) });
                cParticleAssembly.project = onsetProbe.nativeProject;
                return { firstFrame: summarize(first), reaction: summarize(reaction), gatherDuration: cParticleAssembly.duration,
                    paused: state.motion.paused, hidden: state.motion.hidden };
            });
            check(result.firstFrame.clickLatencyMs < 150, 'The tap must reach a moving frame promptly: ' + JSON.stringify(result));
            check(result.reaction.particles > 1000 && result.reaction.medianPullFraction / result.reaction.progress > .9 &&
                result.reaction.medianPullFraction / result.reaction.progress < 1.1 && result.reaction.towardFraction > .9,
                'The first 100ms must visibly pull the particle population toward X, not merely twitch randomly: ' + JSON.stringify(result));
            check(result.gatherDuration === 6 && !result.paused && !result.hidden,
                'Both system preferences must preserve the six-second exponential ease-out clock and valid motion gates');
            const expected = (1 - Math.exp(-4 * result.reaction.elapsedMs / 6000)) / (1 - Math.exp(-4));
            check(Math.abs(result.reaction.progress - expected) < 1e-10, 'The live host must use normalized exponential ease-out');
            check(errors.length === 0, 'Page errors: ' + errors.join('; '));
            runs.push({ reducedMotion, ...result });
        } finally {
            await browserContext.close();
            await page.bringToFront();
        }
    }
    return { runs, physicalAndroidDevice: 'not tested' };
}
