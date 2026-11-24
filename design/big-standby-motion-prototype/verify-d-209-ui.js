// Run with Playwright browser_run_code_unsafe; draw on the real host, never submit reviews.
async (sourcePage) => {
    const page = await sourcePage.context().newPage();
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const results = [];
    try {
        await page.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=D&motion=study-209-cathode-extrusion&v=29');
        await page.bringToFront();
        await page.waitForFunction(() => dReviews.loaded);
        check(await page.evaluate(() => dStudies.size === 23 && dStudies.has('study-17-cathode') &&
            dStudies.has('study-118-section-extrusion') && dStudies.has('study-209-cathode-extrusion') &&
            [...dStudies.keys()].every(id => dReviews.votes[id] !== 'reject')), 'The active catalogue must retain sources, add 209 and omit explicit rejects');
        for (const [width, height] of [[390, 844], [320, 568], [568, 320], [1440, 1000], [2560, 720]]) {
            await page.setViewportSize({ width, height });
            await page.evaluate(() => { state.motion.paused = true; render(); });
            const value = await page.evaluate(() => {
                const x = document.querySelector('.x-core');
                const before = x.outerHTML + document.querySelector('.x-plane').style.transform;
                const samples = [];
                for (const time of [0, 2.4, 4.8, 7.2, 9.6]) {
                    drawDMotion(time);
                    const data = studyContext.getImageData(0, 0, studyCanvas.width, studyCanvas.height).data;
                    let ink = 0;
                    for (let offset = 3; offset < data.length; offset += 4) ink += data[offset];
                    samples.push({ time, ink });
                }
                const frame = createDStudyFrame(4.8);
                const dock = document.querySelector('#broadcast-dock').getBoundingClientRect();
                return { samples, unchangedX: before === x.outerHTML + document.querySelector('.x-plane').style.transform,
                    visible: !studyCanvas.hidden, failure: failedDStudy, fieldBottom: frame.field.bottom.y + frame.field.bottom.height,
                    dockTop: dock.top, selected: state.motion.dMode };
            });
            check(value.visible && !value.failure && value.unchangedX && value.samples.every(sample => sample.ink > 0),
                '209 must draw visible geometry without moving the original X: ' + JSON.stringify({ width, height, value }));
            check(value.fieldBottom <= value.dockTop - 10 && value.selected === 'study-209-cathode-extrusion',
                '209 must use the live dock boundary, not the hidden drawer navigation');
            results.push({ width, height, samples: value.samples.length });
        }
        await page.goto('http://127.0.0.1:8765/big-standby-prototype.html?variant=D&motion=study-174-depth-telemetry&v=29');
        check(await page.evaluate(() => state.motion.dMode === 'signal' &&
            ![...document.querySelector('#d-motion').options].some(option => option.value === 'study-174-depth-telemetry')),
            'Archived URLs must use a safe fallback, not restore the rejected renderer');
        check(errors.length === 0, 'D errors: ' + errors.join('; '));
        return { activeStudies: 23, archivedRejects: 31, source17and118: 'retained', realCanvasLayouts: results,
            originalX: 'unchanged', continuity: 'separate verify-209.cjs', physicalDevice: 'not tested' };
    } finally {
        await page.close();
    }
}
