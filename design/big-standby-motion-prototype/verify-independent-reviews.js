// Run with Playwright browser_run_code_unsafe; every review request is mocked, never forwarded.
async (sourcePage) => {
    const page = await sourcePage.context().newPage();
    const base = 'http://127.0.0.1:8765';
    const styles = ['rail', 'frame', 'cap', 'index', 'footnote', 'bracket', 'telemetry', 'stamp', 'terminal', 'fold'];
    const legacyModes = ['off', 'signal', 'scan', 'quiet'];
    const ids = ['concept-a', 'concept-b', 'concept-e', ...styles.map(style => 'concept-c-' + style),
        ...legacyModes.map(mode => 'concept-d-' + mode), 'study-14-penetration', 'study-17-cathode', 'study-118-section-extrusion', 'study-209-cathode-extrusion'];
    const errors = [];
    const onError = error => errors.push(error.message);
    page.on('pageerror', onError);
    const check = (value, message) => { if (!value) throw Error(message); };
    const legacyB = { vote: 'reject', note: 'previous B review' };
    const legacyE = { vote: 'keep', note: 'previous separate E review' };
    const fixture = { schemaVersion: 1, votes: { 'concept-b': legacyB.vote, 'concept-e': legacyE.vote, 'concept-c': 'keep' },
        notes: { 'concept-b': legacyB.note, 'concept-e': legacyE.note, 'concept-c': 'previous C overview' } };
    for (const id of ['study-17-cathode', 'study-118-section-extrusion']) {
        fixture.votes[id] = 'keep';
        fixture.notes[id] = 'source note ' + id;
    }
    const writes = [];
    let failRailNotes = false;
    const enableTools = async () => {
        if (!(await page.locator('#settings').evaluate(dialog => dialog.open))) await page.locator('#settings-handle').click();
        await page.locator('#show-prototype-tools').check();
    };
    try {
        await page.route('**/api/d-reviews', async route => {
            const request = route.request();
            if (request.method() === 'POST') {
                const change = request.postDataJSON();
                writes.push(change);
                if (!change || typeof change !== 'object' || !ids.includes(change.id) || change.id === 'concept-b' ||
                    (Object.hasOwn(change, 'vote') && !['keep', 'reject', null].includes(change.vote)) ||
                    (Object.hasOwn(change, 'note') && (typeof change.note !== 'string' || [...change.note].length > 2000))) {
                    return route.fulfill({ status: 400, body: 'Invalid mock review change' });
                }
                if (failRailNotes && change.id === 'concept-c-rail' && Object.hasOwn(change, 'note')) {
                    return route.fulfill({ status: 503, body: 'simulated offline' });
                }
                if (Object.hasOwn(change, 'vote')) {
                    if (change.vote === null) delete fixture.votes[change.id];
                    else fixture.votes[change.id] = change.vote;
                }
                if (Object.hasOwn(change, 'note')) fixture.notes[change.id] = change.note;
            } else if (request.method() !== 'GET') {
                return route.fulfill({ status: 405, body: 'Unsupported mock review method' });
            }
            await route.fulfill({ json: fixture });
        });
        await page.setViewportSize({ width: 1440, height: 1000 });
        await page.goto(base + '/big-standby-prototype.html?variant=C&log=rail&v=33');
        await page.waitForFunction(() => dReviews.loaded);
        await enableTools();
        await page.locator('#toggle-c-review').click();
        check(await page.locator('#c-review-note').inputValue() === '', 'Old C overview must not be silently copied into Rail');
        await page.locator('#c-review-scope').selectOption('overall');
        check(await page.locator('#c-review-note').inputValue() === 'previous C overview', 'Old C overview remains accessible');
        check(await page.locator('#keep-c-review').getAttribute('aria-pressed') === 'true', 'Old overall vote remains accessible');
        await page.locator('#c-review-scope').selectOption('style');

        const saveConcept = async (id, note, choice) => {
            await page.locator('#c-review-note').fill(note);
            await page.locator(choice === 'keep' ? '#keep-c-review' : '#reject-c-review').click();
            await page.waitForFunction(({ id, note, choice }) => dNotes.saved[id] === note && dReviews.votes[id] === choice && !dNotes.saving && !dNotes.pending.size, { id, note, choice });
        };
        for (let index = 0; index < styles.length; index += 1) {
            const style = styles[index];
            await page.locator('#c-log-style').selectOption(style);
            check(await page.locator('#c-review-note').inputValue() === '', 'A new C style begins with an empty note: ' + style);
            await saveConcept('concept-c-' + style, 'private note ' + style, index % 2 ? 'reject' : 'keep');
        }
        for (let index = 0; index < styles.length; index += 1) {
            const style = styles[index];
            await page.locator('#c-log-style').selectOption(style);
            check(await page.locator('#c-review-note').inputValue() === 'private note ' + style, 'The right style owns its note: ' + style);
            const vote = index % 2 ? '#reject-c-review' : '#keep-c-review';
            check(await page.locator(vote).getAttribute('aria-pressed') === 'true', 'The right style owns its vote: ' + style);
        }
        await page.locator('#c-log-style').selectOption('rail');
        await page.locator('#c-review-note').fill('quick note rail');
        await page.locator('#c-log-style').selectOption('frame');
        await page.locator('#c-review-note').fill('quick note frame');
        await page.waitForFunction(() => dNotes.saved['concept-c-rail'] === 'quick note rail' &&
            dNotes.saved['concept-c-frame'] === 'quick note frame' && !dNotes.saving && !dNotes.pending.size);
        await page.locator('#c-log-style').selectOption('rail');
        check(await page.locator('#c-review-note').inputValue() === 'quick note rail', 'A pending note stays with its original style');

        failRailNotes = true;
        await page.locator('#c-review-note').fill('retry note rail');
        await page.waitForFunction(() => dNotes.errors.has('concept-c-rail'));
        failRailNotes = false;
        await page.locator('#c-log-style').selectOption('frame');
        check((await page.locator('#c-note-status').textContent()).includes('concept-c-rail'), 'Failed note remains visible after switching styles');
        await page.locator('#retry-c-note').click();
        await page.waitForFunction(() => dNotes.saved['concept-c-rail'] === 'retry note rail' && !dNotes.errors.size && !dNotes.saving);
        check(await page.locator('#c-review-note').inputValue() === 'quick note frame', 'Retrying Rail never overwrites Frame');
        await page.locator('#previous-variant').click();
        check(await page.locator('#c-log-style').isHidden(), 'A has no C style picker');
        await page.locator('#toggle-c-review').click();
        await saveConcept('concept-a', 'private note A', 'keep');
        check(await page.locator('#theme-select option').evaluateAll(options => !options.some(option => option.value === 'E')),
            'Merged E is not an independent review target');
        check(fixture.votes['concept-e'] === legacyE.vote && fixture.notes['concept-e'] === legacyE.note &&
            !writes.some(change => change.id === 'concept-e'), 'Merged E history must remain readable and untouched');

        await page.goto(base + '/big-standby-prototype.html?variant=D&motion=off&v=33');
        await page.waitForFunction(() => dReviews.loaded);
        await enableTools();
        for (const mode of legacyModes) {
            await page.locator('#d-motion').selectOption(mode);
            if (await page.locator('#d-review-panel').isHidden()) await page.locator('#toggle-d-review').click();
            check(await page.locator('#d-review-note').inputValue() === '', 'D mode begins empty: ' + mode);
            await page.locator('#d-review-note').fill('private note D ' + mode);
            await page.locator('#keep-d-motion').click();
            await page.waitForFunction(({ mode }) => dNotes.saved['concept-d-' + mode] === 'private note D ' + mode && dReviews.votes['concept-d-' + mode] === 'keep' && !dNotes.saving && !dNotes.pending.size, { mode });
        }
        await page.locator('#d-motion').selectOption('study-14-penetration');
        await page.locator('#d-review-note').fill('private note study 14');
        await page.locator('#reject-d-motion').click();
        await page.waitForFunction(() => dNotes.saved['study-14-penetration'] === 'private note study 14' && dReviews.votes['study-14-penetration'] === 'reject' && !dNotes.saving && !dNotes.pending.size);

        await page.locator('#d-motion').selectOption('study-209-cathode-extrusion');
        check(await page.locator('#d-review-note').inputValue() === '' &&
            await page.locator('#keep-d-motion').getAttribute('aria-pressed') === 'false',
            'The new composition must not inherit source votes or notes');
        await page.locator('#d-review-note').fill('independent 209 review');
        await page.locator('#keep-d-motion').click();
        await page.waitForFunction(() => dNotes.saved['study-209-cathode-extrusion'] === 'independent 209 review' &&
            dReviews.votes['study-209-cathode-extrusion'] === 'keep' && !dNotes.saving && !dNotes.pending.size);
        for (const id of ['study-17-cathode', 'study-118-section-extrusion']) {
            await page.locator('#d-motion').selectOption(id);
            check(await page.locator('#d-review-note').inputValue() === 'source note ' + id,
                'Composing a new study must not overwrite source reviews');
        }

        await page.goto(base + '/big-standby-prototype.html?variant=C&log=frame&v=33');
        await page.waitForFunction(() => dReviews.loaded);
        await enableTools();
        await page.locator('#toggle-c-review').click();
        check(await page.locator('#c-review-note').inputValue() === 'quick note frame', 'C note survives reload');
        check(await page.locator('#reject-c-review').getAttribute('aria-pressed') === 'true', 'C vote survives reload');
        await page.locator('#c-review-scope').selectOption('overall');
        check(await page.locator('#c-review-note').inputValue() === 'previous C overview', 'Overview survives independent writes');
        const saved = await page.evaluate(async () => (await fetch('/api/d-reviews')).json());
        for (const id of ids) check(typeof saved.notes[id] === 'string' && ['keep', 'reject'].includes(saved.votes[id]), 'Missing mocked saved review: ' + id);
        check(saved.votes['concept-b'] === legacyB.vote && saved.notes['concept-b'] === legacyB.note &&
            writes.every(change => change.id !== 'concept-b'), 'Removed B reviews remain intact without any B writes');
        check(saved.votes['concept-c'] === 'keep' && saved.notes['concept-c'] === 'previous C overview', 'Legacy C data must remain unchanged');
        check(errors.length === 0, 'Browser errors: ' + errors.join('; '));
        return { cStyles: styles.length, otherThemes: 'A; E history retained', legacyB: 'preserved', dModes: legacyModes.length,
            numberedStudy: 'study-14-penetration', composition209: 'independent of 17/118', legacyOverall: 'preserved', persistence: 'mock reload verified', switchingAndRetry: 'passed', reviewStorage: 'mock route' };
    } finally {
        page.off('pageerror', onError);
        await page.close();
    }
}
