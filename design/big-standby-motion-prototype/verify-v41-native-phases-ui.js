// Run with Playwright browser_run_code_unsafe; the supplied page is never changed.
async (sourcePage) => {
    const browser = sourcePage.context().browser();
    if (!browser) throw Error('V41 requires an isolated browser context');
    const base = 'http://127.0.0.1:8765';
    const started = Date.now();
    const sizes = [[320, 568], [390, 844], [480, 375], [568, 320], [667, 375], [896, 592], [1440, 1000], [2560, 720]];
    const failures = [];
    const errors = [];
    const blockedWrites = [];
    const results = [];
    const check = (ok, message) => { if (!ok) throw Error(message); };
    const run = async (name, action) => {
        try { results.push({ name, evidence: await action() }); }
        catch (error) { failures.push({ name, message: String(error.message || error) }); }
    };
    const context = await browser.newContext({
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 1,
        reducedMotion: 'no-preference',
        serviceWorkers: 'block'
    });
    let probe;
    let beforeHash;
    let afterHash;
    const reviewHash = async () => {
        const response = await context.request.get(base + '/big-standby-motion-prototype/review-votes.json');
        if (response.status() === 404) return null;
        check(response.ok(), 'Protected review fixture check failed: HTTP ' + response.status());
        const bytes = Array.from(await response.body());
        return probe.evaluate(async raw => [...new Uint8Array(await crypto.subtle.digest('SHA-256', new Uint8Array(raw)))].map(byte => byte.toString(16).padStart(2, '0')).join(''), bytes);
    };
    try {
        context.setDefaultTimeout(3000);
        context.setDefaultNavigationTimeout(6000);
        await context.route('**/*', async route => {
            const request = route.request();
            const parsed = new URL(request.url());
            const isLoopback = parsed.origin === base || parsed.protocol === 'data:' || parsed.protocol === 'blob:';
            if (!isLoopback) {
                errors.push('Blocked non-loopback request: ' + request.url());
                await route.abort();
            } else if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
                blockedWrites.push({ method: request.method(), url: request.url() });
                await route.fulfill({ status: 405, contentType: 'application/json', body: '{"error":"read-only verification"}' });
            } else if (parsed.pathname === '/api/d-reviews') {
                await route.fulfill({ status: 200, contentType: 'application/json', body: '{"schemaVersion":1,"votes":{},"notes":{}}' });
            } else await route.continue();
        });
        probe = await context.newPage();
        probe.on('pageerror', error => errors.push(error.message));
        probe.on('console', message => { if (['error', 'assert'].includes(message.type())) errors.push(message.text()); });
        await probe.goto(base + '/big-standby-prototype.html?v=41');
        await probe.bringToFront();
        beforeHash = await reviewHash();

        await run('cold URL parameters initialize exact native phase without manual clicks and without errors', async () => {
            const coldTests = [
                { query: 'phase=ARMING', expectedPhase: 'arming', expectedBroadcast: true, expectedNative: 'ARMING' },
                { query: 'phase=WIFI_PAUSED', expectedPhase: 'wifi-paused', expectedBroadcast: true, expectedNative: 'WIFI_PAUSED' },
                { query: 'phase=FROZEN', expectedPhase: 'frozen', expectedBroadcast: false, expectedNative: 'FROZEN' },
                { query: 'phase=READY', expectedPhase: 'off', expectedBroadcast: false, expectedNative: 'READY' },
                { query: 'phase=ADVERTISING', expectedPhase: 'waiting', expectedBroadcast: true, expectedNative: 'ADVERTISING' },
                { query: 'phase=CONNECTING', expectedPhase: 'connecting', expectedBroadcast: true, expectedNative: 'CONNECTING' },
                { query: 'phase=CONNECTED', expectedPhase: 'projecting', expectedBroadcast: true, expectedNative: 'CONNECTED' },
                { query: 'nativePhase=ARMING', expectedPhase: 'arming', expectedBroadcast: true, expectedNative: 'ARMING' },
                { query: 'phase=arming', expectedPhase: 'arming', expectedBroadcast: true, expectedNative: 'ARMING' },
                { query: 'phase=wifi-paused', expectedPhase: 'wifi-paused', expectedBroadcast: true, expectedNative: 'WIFI_PAUSED' },
                { query: 'phase=frozen', expectedPhase: 'frozen', expectedBroadcast: false, expectedNative: 'FROZEN' },
                { query: 'phase=invalid_garbage', expectedPhase: 'off', expectedBroadcast: false, expectedNative: 'READY' },
                { query: 'phase=%3Cscript%3E', expectedPhase: 'off', expectedBroadcast: false, expectedNative: 'READY' }
            ];
            const evidence = [];
            for (const item of coldTests) {
                await probe.goto(`${base}/big-standby-prototype.html?${item.query}&v=41`);
                const sample = await probe.evaluate(() => ({
                    phase: state.phase,
                    broadcast: state.broadcast,
                    stagePhase: document.querySelector('#stage').dataset.phase,
                    stageNativePhase: document.querySelector('#stage').dataset.nativePhase,
                    nativeSelect: document.querySelector('#native-phase').value,
                    broadcastDisabled: document.querySelector('#broadcast-toggle').disabled,
                    broadcastChecked: document.querySelector('#broadcast-toggle').getAttribute('aria-checked'),
                    broadcastRole: document.querySelector('#broadcast-toggle').getAttribute('role'),
                    isProjecting: !document.querySelector('#projection').hidden,
                    isStandbyHidden: document.querySelector('#standby-view').hidden
                }));
                check(sample.phase === item.expectedPhase, `${item.query}: state.phase mismatch: ${sample.phase}`);
                check(sample.broadcast === item.expectedBroadcast, `${item.query}: broadcast mismatch: ${sample.broadcast}`);
                check(sample.stagePhase === item.expectedPhase, `${item.query}: stage dataset mismatch: ${sample.stagePhase}`);
                check(sample.stageNativePhase === item.expectedNative, `${item.query}: stage native dataset mismatch`);
                if (item.expectedPhase === 'frozen') {
                    check(!sample.broadcastDisabled && sample.broadcastRole === 'button' && sample.broadcastChecked === null, 'Cold FROZEN broadcast control is an enabled info button');
                }
                if (item.expectedPhase === 'projecting') {
                    check(sample.isProjecting && sample.isStandbyHidden, 'Cold CONNECTED must show projection and hide standby-view');
                }
                evidence.push({ query: item.query, sample });
            }
            return evidence;
        });

        await run('broadcast ON enters ARMING, observable before explicit ack, >400ms stable without fake ack', async () => {
            await probe.goto(`${base}/big-standby-prototype.html?variant=A&v=41`);
            await probe.evaluate(() => {
                selectVariant('A');
                state.home = 'big';
                state.phase = 'off';
                state.broadcast = false;
                render();
            });
            const before = await probe.evaluate(() => ({ phase: state.phase, broadcast: state.broadcast }));
            check(before.phase === 'off' && !before.broadcast, 'Precondition is OFF');

            // Click broadcast toggle
            await probe.locator('#broadcast-toggle').click();
            // Immediate check (first frame)
            const immediate = await probe.evaluate(() => ({
                phase: state.phase,
                broadcast: state.broadcast,
                ariaChecked: document.querySelector('#broadcast-toggle').getAttribute('aria-checked'),
                buttonLabel: document.querySelector('#broadcast-label').textContent
            }));
            check(immediate.phase === 'arming' && immediate.broadcast, 'First frame on ON is ARMING');
            check(immediate.ariaChecked === 'true', 'Toggle is aria-checked=true');
            check(immediate.buttonLabel === '停止廣播', 'Toggle label is 停止廣播');

            // Wait for fade copy to settle
            await probe.evaluate(async () => {
                const ids = ['status-title', 'status-eyebrow', 'connection-hint'];
                const frame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                for (let pass = 0; pass < 20; pass++) {
                    const running = [];
                    for (const id of ids) {
                        const change = mainCopyChanges.get(document.getElementById(id));
                        if (change && change.animation && change.animation.playState !== 'finished') {
                            running.push(change.animation.finished.catch(() => {}));
                        }
                    }
                    if (running.length) await Promise.all(running);
                    await frame();
                    if (!ids.some(id => mainCopyChanges.has(document.getElementById(id)))) break;
                }
            });

            const settledArming = await probe.evaluate(() => ({
                title: document.querySelector('#status-title').textContent,
                eyebrow: document.querySelector('#status-eyebrow').textContent,
                hint: document.querySelector('#connection-hint').textContent
            }));
            check(settledArming.title === '啟動中', 'Settled ARMING title');
            check(settledArming.eyebrow.includes('STARTING'), 'Settled ARMING eyebrow');
            check(!settledArming.hint.includes('Win + K') && !settledArming.hint.includes('Win+K'), 'ARMING must not invite Win + K');

            // Confirm no auto-ack timer exists: wait 450ms and verify state is STILL arming
            await probe.waitForTimeout(450);
            const stillArming = await probe.evaluate(() => state.phase);
            check(stillArming === 'arming', 'ARMING must remain stable (>400ms) without fake auto-ack');

            // Explicit confirmation
            await probe.evaluate(() => confirmServiceReady());
            await probe.evaluate(async () => {
                const ids = ['status-title', 'status-eyebrow', 'connection-hint'];
                const frame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                for (let pass = 0; pass < 20; pass++) {
                    const running = [];
                    for (const id of ids) {
                        const change = mainCopyChanges.get(document.getElementById(id));
                        if (change && change.animation && change.animation.playState !== 'finished') {
                            running.push(change.animation.finished.catch(() => {}));
                        }
                    }
                    if (running.length) await Promise.all(running);
                    await frame();
                    if (!ids.some(id => mainCopyChanges.has(document.getElementById(id)))) break;
                }
            });

            const waitingState = await probe.evaluate(() => ({
                phase: state.phase,
                title: document.querySelector('#status-title').textContent,
                hint: document.querySelector('#connection-hint').textContent
            }));
            check(waitingState.phase === 'waiting', 'Confirmation moves to waiting');
            check(waitingState.hint.includes('Win + K') || waitingState.hint.includes('Win+K'), 'Waiting invites Win + K');

            return { immediate, settledArming, waitingState };
        });

        await run('7 native phases + legacy failed across A, C, D: title, eyebrow, C word, D track, and hint safety', async () => {
            const allPhases = ['off', 'arming', 'waiting', 'connecting', 'projecting', 'wifi-paused', 'frozen', 'failed'];
            const evidence = [];
            for (const variant of ['A', 'C', 'D']) {
                await probe.evaluate(v => { selectVariant(v); render(); }, variant);
                for (const phase of allPhases) {
                    const sample = await probe.evaluate(async p => {
                        if (p === 'off') { previewNativePhase('READY'); }
                        else if (p === 'arming') { previewNativePhase('ARMING'); }
                        else if (p === 'waiting') { previewNativePhase('ADVERTISING'); }
                        else if (p === 'connecting') { previewNativePhase('CONNECTING'); }
                        else if (p === 'projecting') { previewNativePhase('CONNECTED'); }
                        else if (p === 'wifi-paused') { previewNativePhase('WIFI_PAUSED'); }
                        else if (p === 'frozen') { previewNativePhase('FROZEN'); }
                        else if (p === 'failed') {
                            previewNativePhase('ADVERTISING');
                            setPhase('connecting');
                            setPhase('failed');
                        }
                        render();

                        const frame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));

                        // Settle copy animation
                        const ids = ['status-title', 'status-eyebrow', 'connection-hint'];
                        for (let pass = 0; pass < 20; pass++) {
                            const running = [];
                            for (const id of ids) {
                                const change = mainCopyChanges.get(document.getElementById(id));
                                if (change && change.animation && change.animation.playState !== 'finished') {
                                    running.push(change.animation.finished.catch(() => {}));
                                }
                            }
                            if (running.length) await Promise.all(running);
                            await frame();
                            if (!ids.some(id => mainCopyChanges.has(document.getElementById(id)))) break;
                        }

                        // Settle view transition for projecting or CSS transition for D
                        if (p === 'projecting') {
                            if (typeof viewExit !== 'undefined' && viewExit) await viewExit.finished.catch(() => {});
                            if (typeof viewEntry !== 'undefined' && viewEntry) await viewEntry.finished.catch(() => {});
                            const anims = [...document.querySelectorAll('#standby-view, #projection')].flatMap(el => el.getAnimations());
                            if (anims.length) await Promise.all(anims.map(a => a.finished.catch(() => {})));
                            await frame();
                        } else if (state.variant === 'D') {
                            const activeMsg = document.querySelector(`.e-status-message[data-status="${p}"]`);
                            if (activeMsg) {
                                const anims = activeMsg.getAnimations();
                                if (anims.length) await Promise.all(anims.map(a => a.finished.catch(() => {})));
                                for (let pass = 0; pass < 20; pass++) {
                                    if (parseFloat(getComputedStyle(activeMsg).opacity) >= 0.95) break;
                                    await frame();
                                }
                            }
                        }

                        const title = document.querySelector('#status-title')?.textContent;
                        const eyebrow = document.querySelector('#status-eyebrow')?.textContent;
                        const hint = document.querySelector('#connection-hint')?.textContent;
                        const statusDetail = document.querySelector('#status-detail')?.textContent ?? '';
                        const cStatusWord = document.querySelector('#c-status-word')?.textContent;
                        const toggle = document.querySelector('#broadcast-toggle');
                        const isProjecting = !document.querySelector('#projection').hidden;
                        const isStandbyHidden = document.querySelector('#standby-view').hidden;

                        const isActuallyVisible = (el) => {
                            if (!el) return false;
                            if (el.closest('[hidden], [inert]')) return false;
                            const style = getComputedStyle(el);
                            if (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity) < 0.2) return false;
                            const rects = el.getClientRects();
                            if (!rects.length || rects[0].width === 0 || rects[0].height === 0) return false;
                            const parentRow = el.closest('.e-stream-row--status');
                            if (parentRow) {
                                const rowStyle = getComputedStyle(parentRow);
                                if (rowStyle.display === 'none' || rowStyle.visibility === 'hidden' || parseFloat(rowStyle.opacity) < 0.2) return false;
                            }
                            return true;
                        };

                        const activeDTrack = document.querySelector(`.e-status-message[data-status="${p}"] .e-stream-track`);
                        let activeDTrackMoved = false;
                        if (activeDTrack && p !== 'projecting') {
                            const t1 = getComputedStyle(activeDTrack).transform;
                            await frame();
                            await frame();
                            const t2 = getComputedStyle(activeDTrack).transform;
                            activeDTrackMoved = (t1 !== t2) || (getComputedStyle(activeDTrack).animationPlayState === 'running');
                        }

                        const allDTracks = [...document.querySelectorAll('.e-stream-row--status .e-status-message')].map(node => ({
                            status: node.dataset.status,
                            opacity: Number(getComputedStyle(node).opacity),
                            visibility: getComputedStyle(node).visibility,
                            actuallyVisible: isActuallyVisible(node),
                            playState: getComputedStyle(node.querySelector('.e-stream-track')).animationPlayState
                        }));

                        const visibleDCount = allDTracks.filter(item => item.actuallyVisible).length;
                        const activeDItem = allDTracks.find(item => item.status === p);
                        const inactiveDPausedAndHidden = allDTracks
                            .filter(item => item.status !== p)
                            .every(item => item.playState === 'paused' && !item.actuallyVisible && item.opacity < 0.2);

                        return {
                            phase: state.phase,
                            broadcast: state.broadcast,
                            title,
                            eyebrow,
                            hint,
                            statusDetail,
                            cStatusWord,
                            isProjecting,
                            isStandbyHidden,
                            toggleDisabled: toggle.disabled,
                            toggleRole: toggle.getAttribute('role'),
                            toggleChecked: toggle.getAttribute('aria-checked'),
                            activeDTrackPlayState: activeDTrack ? getComputedStyle(activeDTrack).animationPlayState : null,
                            activeDTrackMoved,
                            visibleDCount,
                            activeDItem,
                            inactiveDPausedAndHidden,
                            allDTracks
                        };
                    }, phase);

                    // Assertions per phase
                    if (['waiting', 'failed'].includes(phase)) {
                        if (variant !== 'D') {
                            check(sample.hint.includes('Win') && sample.hint.includes('K'), `${variant}/${phase}: ready phase must invite Win + K`);
                        }
                    } else {
                        check(!sample.hint.includes('Win + K') && !sample.hint.includes('Win+K'), `${variant}/${phase}: unready phase must NOT invite Win + K`);
                    }

                    if (phase === 'frozen') {
                        check(!sample.toggleDisabled, 'FROZEN info button stays enabled');
                        check(sample.toggleRole === 'button' && sample.toggleChecked === null, 'FROZEN control is an info button');
                        if (variant !== 'C') check(sample.title === '接收不可用', 'FROZEN title');
                        if (variant === 'C') check(sample.hint.includes('Receiver unavailable') && !sample.hint.includes('Check settings'), 'FROZEN C hint');
                        if (variant === 'A') check(sample.hint.includes('接收不可用') && !sample.hint.includes('Check settings'), 'FROZEN A summary');
                    }
                    if (phase === 'wifi-paused') {
                        check(sample.broadcast, 'WIFI_PAUSED retains broadcast intent');
                        if (variant !== 'C') check(sample.title === 'Wi-Fi 已關閉', 'WIFI_PAUSED title');
                        if (variant === 'C') check(sample.hint.includes('Wi-Fi is off') && !sample.hint.includes('Please turn on') && !sample.hint.includes('Broadcast intent retained'), 'WIFI C hint');
                        if (variant === 'A') check(sample.hint.includes('Wi-Fi 已關閉 · 請開啟 Wi-Fi 以廣播'), 'WIFI A detail');
                    }
                    if (phase === 'arming') {
                        check(sample.broadcast, 'ARMING broadcast intent is true');
                        if (variant !== 'C') check(sample.title === '啟動中', 'ARMING title');
                        if (variant === 'A') check(sample.hint === '', 'A ARMING hint blank');
                        if (variant === 'C') check(sample.statusDetail === '接收啟動中', 'C ARMING detail exact');
                    }
                    if (phase === 'connecting') {
                        if (variant === 'A') {
                            check(sample.title === '正在連接', 'A connecting title stays');
                            check(sample.hint === '', 'A connecting hint blank');
                        }
                        if (variant === 'C') {
                            check(sample.title === '正在連接', 'C connecting title stays');
                            check(sample.hint.includes('Connecting · Please wait'), 'C connecting hint stays');
                            check(sample.statusDetail === '模擬發送端已選取此裝置。', 'C connecting detail stays shared');
                        }
                    }
                    if (phase === 'projecting') {
                        check(sample.isProjecting, `${variant}/projecting: projection section must be visible`);
                        check(sample.isStandbyHidden, `${variant}/projecting: standby view must be hidden`);
                    }

                    if (variant === 'C') {
                        const expectedCWord = {
                            off: 'DISCONN', arming: 'ARMING', waiting: 'STANDBY',
                            connecting: 'ESTABLISHING', projecting: 'CONNECTED',
                            'wifi-paused': 'WAIT_WIFI', frozen: 'UNAVAILABLE', failed: 'ERROR'
                        }[phase];
                        check(sample.cStatusWord === expectedCWord, `C status word mismatch for ${phase}: ${sample.cStatusWord} !== ${expectedCWord}`);
                    }

                    if (variant === 'D') {
                        if (phase === 'projecting') {
                            check(sample.visibleDCount === 0, 'D projecting must have 0 visible ambient status messages');
                        } else {
                            check(sample.visibleDCount === 1, `D status row must have exactly 1 visible message for ${phase}, got ${sample.visibleDCount}`);
                            check(sample.activeDItem && sample.activeDItem.opacity >= 0.95 && sample.activeDItem.actuallyVisible, `D status message for ${phase} must be visible with opacity >= 0.95`);
                            check(sample.activeDTrackPlayState === 'running', `D active status track for ${phase} must be running, got ${sample.activeDTrackPlayState}`);
                            check(sample.activeDTrackMoved, `D active status track for ${phase} must advance transform`);
                            check(sample.inactiveDPausedAndHidden, `D inactive status tracks for ${phase} must be paused and hidden`);
                        }
                    }

                    evidence.push({ variant, phase, sample });
                }
            }
            return { totalPhaseChecks: evidence.length };
        });

        await run('live focus transition: FROZEN keeps the enabled info button focused; motion stays active', async () => {
            await probe.goto(`${base}/big-standby-prototype.html?variant=C&phase=READY&v=41`);
            await probe.bringToFront();
            const focusResult = await probe.evaluate(() => {
                // Focus the enabled broadcast toggle
                const toggle = document.querySelector('#broadcast-toggle');
                toggle.focus();
                const initialFocusedId = document.activeElement.id;

                // Live transition to FROZEN
                previewNativePhase('FROZEN');
                render();

                const finalFocusedId = document.activeElement.id;
                const toggleDisabled = toggle.disabled;
                const toggleRole = toggle.getAttribute('role');
                const toggleChecked = toggle.getAttribute('aria-checked');
                const shiftActive = oledShiftActive();
                const particlesActive = shouldAnimateParticles();

                return {
                    initialFocusedId,
                    finalFocusedId,
                    toggleDisabled,
                    toggleRole,
                    toggleChecked,
                    shiftActive,
                    particlesActive
                };
            });
            check(focusResult.initialFocusedId === 'broadcast-toggle', 'Precondition: broadcast-toggle was focused');
            check(!focusResult.toggleDisabled && focusResult.toggleRole === 'button' && focusResult.toggleChecked === null, 'FROZEN keeps an enabled info button');
            check(focusResult.finalFocusedId === 'broadcast-toggle', 'Focus stays on the enabled info button');
            check(focusResult.shiftActive && focusResult.particlesActive, 'FROZEN retains background motion, never freezes rendering');
            return focusResult;
        });

        await run('interactive UI phase picker and ready confirmation wiring through prototype tools drawer', async () => {
            await probe.goto(`${base}/big-standby-prototype.html?variant=A&v=41`);
            await probe.bringToFront();

            // Open settings drawer
            await probe.locator('#settings-handle').click();
            await probe.locator('#show-prototype-tools').check();

            // Select ARMING via native-phase selector
            await probe.locator('#native-phase').selectOption('ARMING');
            const armingUI = await probe.evaluate(() => ({
                phase: state.phase,
                broadcast: state.broadcast,
                simConnectDisabled: document.querySelector('#simulate-connect').disabled,
                confirmReadyDisabled: document.querySelector('#confirm-service-ready').disabled
            }));
            check(armingUI.phase === 'arming' && armingUI.broadcast, 'Selecting ARMING updates state');
            check(armingUI.simConnectDisabled, 'simulate-connect is disabled during ARMING');
            check(!armingUI.confirmReadyDisabled, 'confirm-service-ready is enabled during ARMING');

            // Click confirm-service-ready
            await probe.locator('#confirm-service-ready').click();
            const readyUI = await probe.evaluate(() => ({
                phase: state.phase,
                broadcast: state.broadcast,
                simConnectDisabled: document.querySelector('#simulate-connect').disabled,
                nativeSelectValue: document.querySelector('#native-phase').value
            }));
            check(readyUI.phase === 'waiting', 'confirm-service-ready moves to waiting');
            check(!readyUI.simConnectDisabled, 'simulate-connect is enabled after confirmation');
            check(readyUI.nativeSelectValue === 'ADVERTISING', 'native-phase select reflects ADVERTISING');

            // Select WIFI_PAUSED: simulate-connect must be blocked
            await probe.locator('#native-phase').selectOption('WIFI_PAUSED');
            const wifiPausedUI = await probe.evaluate(() => ({
                phase: state.phase,
                broadcast: state.broadcast,
                simConnectDisabled: document.querySelector('#simulate-connect').disabled
            }));
            check(wifiPausedUI.phase === 'wifi-paused' && wifiPausedUI.broadcast, 'WIFI_PAUSED retains broadcast');
            check(wifiPausedUI.simConnectDisabled, 'simulate-connect is disabled in WIFI_PAUSED');

            // Select FROZEN: simulate-connect blocked, broadcast toggle disabled
            await probe.locator('#native-phase').selectOption('FROZEN');
            const frozenUI = await probe.evaluate(() => ({
                phase: state.phase,
                simConnectDisabled: document.querySelector('#simulate-connect').disabled,
                broadcastDisabled: document.querySelector('#broadcast-toggle').disabled,
                broadcastRole: document.querySelector('#broadcast-toggle').getAttribute('role'),
                broadcastChecked: document.querySelector('#broadcast-toggle').getAttribute('aria-checked')
            }));
            check(frozenUI.phase === 'frozen', 'FROZEN state');
            check(frozenUI.simConnectDisabled, 'simulate-connect disabled in FROZEN');
            check(!frozenUI.broadcastDisabled && frozenUI.broadcastRole === 'button' && frozenUI.broadcastChecked === null, 'FROZEN broadcast control is an enabled info button');

            await probe.locator('#close-settings').click();
            return { armingUI, readyUI, wifiPausedUI, frozenUI };
        });

        await run('entering ARMING, WIFI_PAUSED, or FROZEN cancels failure glitch and clears 7.5s recovery anchor', async () => {
            const targets = [
                { native: 'ARMING', phase: 'arming' },
                { native: 'WIFI_PAUSED', phase: 'wifi-paused' },
                { native: 'FROZEN', phase: 'frozen' }
            ];
            const evidence = [];
            for (const item of targets) {
                const target = item.native;
                // Post-anchor case: wait for real glitch to finish and set anchor
                await probe.evaluate(() => {
                    selectVariant('C');
                    previewNativePhase('ADVERTISING');
                    setPhase('connecting');
                    setPhase('failed');
                });
                // Await glitch finish so cFailureStartedAt is set
                await probe.waitForFunction(() => cGlitch?.playState === 'finished' && cFailureStartedAt !== null);

                const postResult = await probe.evaluate(t => {
                    const anchorBefore = cFailureStartedAt;
                    previewNativePhase(t);
                    const anchorAfter = cFailureStartedAt;
                    const glitchState = cGlitch?.playState;

                    // Advance particleTime by 12 seconds
                    particleTime += 12000;
                    return { target: t, anchorBefore, anchorAfter, glitchState, phase: state.phase };
                }, target);

                // Wait 2 native animation frames
                await probe.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
                const settledPhase = await probe.evaluate(() => state.phase);
                check(postResult.anchorBefore !== null, `${target}: Precondition anchor was set`);
                check(postResult.anchorAfter === null, `${target}: Entering new phase clears failure anchor`);
                check(settledPhase === item.phase, `${target}: 7.5s recovery timer cannot jump state back to waiting`);

                // Pre-anchor case: fresh glitch, switch before glitch finishes
                const preResult = await probe.evaluate(t => {
                    selectVariant('C');
                    previewNativePhase('ADVERTISING');
                    setPhase('connecting');
                    setPhase('failed');
                    const oldGlitch = cGlitch;
                    const oldFinish = oldGlitch?.onfinish;

                    // Switch immediately while glitch is running
                    previewNativePhase(t);
                    const glitchCancelled = oldGlitch.playState !== 'running' || oldGlitch.cancelled;

                    // If old onfinish callback fires, it must not set anchor or affect state
                    if (oldFinish) oldFinish.call(oldGlitch, { currentTarget: oldGlitch });
                    particleTime += 12000;
                    return { target: t, glitchCancelled, anchor: cFailureStartedAt, phase: state.phase };
                }, target);

                await probe.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
                const settledPre = await probe.evaluate(() => state.phase);
                check(preResult.anchor === null, `${target}: Pre-anchor switch leaves anchor null`);
                check(settledPre === item.phase, `${target}: Late glitch finish cannot resurrect recovery`);

                evidence.push({ target, postResult, preResult });
            }
            return evidence;
        });

        await run('full 8-size RWD verification for ARMING, WIFI_PAUSED, FROZEN across A, C, D and CONNECTED projection', async () => {
            const newPhases = ['arming', 'wifi-paused', 'frozen'];
            const evidence = [];
            for (const [width, height] of sizes) {
                await probe.setViewportSize({ width, height });
                for (const phase of newPhases) {
                    for (const variant of ['A', 'C', 'D']) {
                        const sample = await probe.evaluate(async ({ v, p }) => {
                            selectVariant(v);
                            previewNativePhase(nativePhaseFor(p));
                            render();
                            resizeCanvas();

                            const frame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));

                            // Wait for copy fade to settle
                            const ids = ['status-title', 'status-eyebrow', 'connection-hint'];
                            for (let pass = 0; pass < 20; pass++) {
                                const running = [];
                                for (const id of ids) {
                                    const change = mainCopyChanges.get(document.getElementById(id));
                                    if (change && change.animation && change.animation.playState !== 'finished') {
                                        running.push(change.animation.finished.catch(() => {}));
                                    }
                                }
                                if (running.length) await Promise.all(running);
                                await frame();
                                if (!ids.some(id => mainCopyChanges.has(document.getElementById(id)))) break;
                            }

                            if (v === 'D') {
                                const activeMsg = document.querySelector(`.e-status-message[data-status="${p}"]`);
                                if (activeMsg) {
                                    const anims = activeMsg.getAnimations();
                                    if (anims.length) await Promise.all(anims.map(a => a.finished.catch(() => {})));
                                    for (let pass = 0; pass < 20; pass++) {
                                        if (parseFloat(getComputedStyle(activeMsg).opacity) >= 0.95) break;
                                        await frame();
                                    }
                                }
                            }

                            const stageRect = document.querySelector('#stage').getBoundingClientRect();
                            if (v === 'C') {
                                const word = document.querySelector('#c-status-word');
                                const clock = document.querySelector('#c-standby-time');
                                const hintTitle = document.querySelector('.stage.v-C .connection-title');
                                const hintDesc = document.querySelector('.stage.v-C .connection-description');
                                const wordRect = word.getBoundingClientRect();
                                const clockRect = clock.getBoundingClientRect();
                                const hintTitleRect = hintTitle.getBoundingClientRect();
                                const hintDescRect = hintDesc.getBoundingClientRect();
                                const wordFont = parseFloat(getComputedStyle(word).fontSize);
                                const clockFont = parseFloat(getComputedStyle(clock).fontSize);

                                // Check non-overlap between left connection hint and right status plate
                                const plateRect = document.querySelector('.c-standby-plate').getBoundingClientRect();
                                const hintContainer = document.querySelector('.stage.v-C .connection-hint').getBoundingClientRect();
                                const noPlateHintOverlap = hintContainer.right <= plateRect.left + 5;

                                return {
                                    variant: v,
                                    phase: p,
                                    wordText: word.textContent,
                                    wordWidth: wordRect.width,
                                    wordFont,
                                    clockWidth: clockRect.width,
                                    clockFont,
                                    hintDescText: hintDesc.textContent,
                                    fitsViewport: wordRect.right <= stageRect.right + 2 && wordRect.left >= stageRect.left - 2 &&
                                        hintContainer.left >= stageRect.left - 2,
                                    noPlateHintOverlap
                                };
                            } else {
                                const title = document.querySelector('#status-title');
                                const eyebrow = document.querySelector('#status-eyebrow');
                                const titleRect = title.getBoundingClientRect();
                                const eyebrowRect = eyebrow.getBoundingClientRect();
                                const titleFont = parseFloat(getComputedStyle(title).fontSize);
                                const fitsViewport = titleRect.left >= stageRect.left - 2 && titleRect.right <= stageRect.right + 2 &&
                                    eyebrowRect.left >= stageRect.left - 2 && eyebrowRect.right <= stageRect.right + 2;

                                let dTrackOk = true;
                                if (v === 'D') {
                                    const activeTrack = document.querySelector(`.e-status-message[data-status="${p}"] .e-stream-track`);
                                    dTrackOk = activeTrack && getComputedStyle(activeTrack).animationPlayState === 'running';
                                }

                                return {
                                    variant: v,
                                    phase: p,
                                    titleText: title.textContent,
                                    titleWidth: titleRect.width,
                                    titleFont,
                                    fitsViewport,
                                    dTrackOk
                                };
                            }
                        }, { v: variant, p: phase });

                        check(sample.fitsViewport, `${variant}/${phase} at ${width}x${height} overflows stage viewport`);
                        if (variant === 'C') {
                            check(sample.wordWidth > 15, `C status word not visible for ${phase} at ${width}x${height}`);
                            check(sample.wordFont >= 12, `C status word font downscaled: ${sample.wordFont}`);
                        } else {
                            check(sample.titleWidth > 15, `${variant} title not visible for ${phase} at ${width}x${height}`);
                            check(sample.titleFont >= 20, `${variant} title font downscaled: ${sample.titleFont}`);
                            if (variant === 'D') {
                                check(sample.dTrackOk, `D status track for ${phase} at ${width}x${height} must be running`);
                            }
                        }
                        evidence.push({ width, height, ...sample });
                    }
                }

                // Check CONNECTED projection at this viewport
                const projectionCheck = await probe.evaluate(async () => {
                    previewNativePhase('CONNECTED');
                    render();
                    if (typeof viewExit !== 'undefined' && viewExit) await viewExit.finished.catch(() => {});
                    if (typeof viewEntry !== 'undefined' && viewEntry) await viewEntry.finished.catch(() => {});
                    const anims = [...document.querySelectorAll('#standby-view, #projection')].flatMap(el => el.getAnimations());
                    if (anims.length) await Promise.all(anims.map(a => a.finished.catch(() => {})));
                    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));

                    const proj = document.querySelector('#projection');
                    const standby = document.querySelector('#standby-view');
                    const disconnectBtn = document.querySelector('#disconnect-projection');
                    const rect = proj.getBoundingClientRect();
                    return {
                        projVisible: !proj.hidden,
                        standbyHidden: standby.hidden,
                        btnVisible: !disconnectBtn.disabled && disconnectBtn.getBoundingClientRect().width > 20,
                        projWidth: rect.width,
                        projHeight: rect.height
                    };
                });
                check(projectionCheck.projVisible && projectionCheck.standbyHidden, `Projection section must be visible and standby hidden at ${width}x${height}`);
                check(projectionCheck.btnVisible, `Disconnect projection button visible at ${width}x${height}`);
            }
            return { totalRwdCases: evidence.length };
        });

        await run('FROZEN receiver details: info button, dialog focus, warning colors, bounds', async () => {
            const counts = { variants: 0, intents: 0, sizes: 0, settings: 0 };
            const settle = () => probe.evaluate(async () => {
                const ids = ['status-title', 'status-eyebrow', 'connection-hint'];
                for (let pass = 0; pass < 20; pass += 1) {
                    const running = [];
                    for (const id of ids) {
                        const change = mainCopyChanges.get(document.getElementById(id));
                        if (change && change.animation && change.animation.playState !== 'finished') running.push(change.animation.finished.catch(() => {}));
                    }
                    for (const item of [viewEntry, viewExit, drawerAnimation]) {
                        if (item && item.playState !== 'finished') running.push(item.finished.catch(() => {}));
                    }
                    if (running.length) await Promise.all(running);
                    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                    const copyPending = ids.some(id => mainCopyChanges.has(document.getElementById(id)));
                    const motionPending = [viewEntry, viewExit, drawerAnimation].some(item => item && item.playState !== 'finished');
                    if (!copyPending && !motionPending) break;
                }
            });
            for (const variant of ['A', 'C', 'D']) {
                await probe.goto(`${base}/big-standby-prototype.html?variant=${variant}&phase=READY&v=41`);
                await probe.bringToFront();
                if (variant === 'A') {
                    const copy = await probe.evaluate(() => {
                        const dialog = document.querySelector('#receiver-details');
                        return { text: dialog.textContent, buttons: [...dialog.querySelectorAll('button')].map(button => button.textContent.trim()) };
                    });
                    check(copy.text.includes('接收服務不可用，尚無可用的接收服務或必要權限。'), 'details explanation');
                    check(copy.text.includes('請確認接收服務已啟用並具備必要權限。'), 'details next step');
                    check(copy.text.includes('原型說明，不代表已檢測此裝置。'), 'details disclaimer');
                    check(copy.buttons.length === 1 && copy.buttons[0] === '關閉', 'details close only');
                }
                const warning = await probe.evaluate(async (variantName) => {
                    const frame = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                    previewNativePhase('ADVERTISING');
                    setPhase('connecting');
                    setPhase('failed');
                    await frame();
                    const failed = {
                        symbol: getComputedStyle(document.querySelector('.broadcast-symbol-warning')).color,
                        light: getComputedStyle(document.querySelector('.c-status-light')).backgroundColor,
                        row: getComputedStyle(document.querySelector('#e-scene .e-stream-row--status')).color
                    };
                    previewNativePhase('FROZEN');
                    await frame();
                    const toggle = document.querySelector('#broadcast-toggle');
                    return {
                        failed,
                        frozen: {
                            symbol: getComputedStyle(document.querySelector('.broadcast-symbol-warning')).color,
                            light: getComputedStyle(document.querySelector('.c-status-light')).backgroundColor,
                            row: getComputedStyle(document.querySelector('#e-scene .e-stream-row--status')).color,
                            glyph: toggle.dataset.symbol,
                            disabled: toggle.disabled,
                            opacity: getComputedStyle(toggle).opacity,
                            role: toggle.getAttribute('role'),
                            checked: toggle.getAttribute('aria-checked'),
                            timer: cFailureStartedAt,
                            phrase: document.querySelector('.e-status-message[data-status="frozen"] .e-status-phrase').textContent,
                            wifi: document.querySelector('.e-status-message[data-status="wifi-paused"] .e-status-phrase').textContent,
                            motion: variantName === 'D' ? document.querySelector('#stage').classList.contains('e-motion-active') : variantName === 'A' ? oledShiftActive() : shouldAnimateParticles()
                        }
                    };
                }, variant);
                if (variant !== 'D') check(warning.frozen.symbol === warning.failed.symbol, `${variant} warning glyph matches FAILED`);
                if (variant === 'C') check(warning.frozen.light === warning.failed.light, 'C frozen status light matches FAILED');
                if (variant === 'D') {
                    check(warning.frozen.row === warning.failed.row && warning.frozen.row.replace(/\s/g, '').includes('255,107,107'), 'D frozen status row stays #ff6b6b');
                    check(warning.frozen.phrase === 'Receiver unavailable · Check settings', 'D frozen marquee unchanged');
                    check(warning.frozen.wifi === 'Wi-Fi is off · Please turn on Wi-Fi to broadcast', 'D wifi marquee');
                }
                check(warning.frozen.glyph === 'warning' && !warning.frozen.disabled && (warning.frozen.opacity === '1' || warning.frozen.opacity === '1.0'), `${variant} info button enabled`);
                check(warning.frozen.role === 'button' && warning.frozen.checked === null, `${variant} info role`);
                check(warning.frozen.timer === null && warning.frozen.motion, `${variant} no legacy timer and ambient continues`);
                counts.variants += 1;
                for (const intent of [false, true]) {
                    await probe.evaluate(async (want) => {
                        previewNativePhase(want ? 'ADVERTISING' : 'READY');
                        previewNativePhase('FROZEN');
                        const ids = ['status-title', 'status-eyebrow', 'connection-hint'];
                        for (let pass = 0; pass < 20; pass += 1) {
                            const running = [];
                            for (const id of ids) {
                                const change = mainCopyChanges.get(document.getElementById(id));
                                if (change && change.animation && change.animation.playState !== 'finished') running.push(change.animation.finished.catch(() => {}));
                            }
                            if (viewEntry && viewEntry.playState !== 'finished') running.push(viewEntry.finished.catch(() => {}));
                            if (viewExit && viewExit.playState !== 'finished') running.push(viewExit.finished.catch(() => {}));
                            if (running.length) await Promise.all(running);
                            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                            if (!ids.some(id => mainCopyChanges.has(document.getElementById(id)))) break;
                        }
                    }, intent);
                    const before = await probe.evaluate(() => ({ phase: state.phase, broadcast: state.broadcast }));
                    await probe.locator('#broadcast-toggle').click();
                    const opened = await probe.evaluate(() => ({
                        open: document.querySelector('#receiver-details').open,
                        phase: state.phase,
                        broadcast: state.broadcast,
                        sim: document.querySelector('#simulate-connect').disabled,
                        settingsOpen: document.querySelector('#settings').open
                    }));
                    check(before.phase === 'frozen' && before.broadcast === intent && opened.open && opened.phase === 'frozen' && opened.broadcast === intent && opened.sim && !opened.settingsOpen, `${variant} intent ${intent} opens details without mutation`);
                    await probe.locator('#close-receiver-details').click();
                    await settle();
                    const closed = await probe.evaluate(() => ({ open: document.querySelector('#receiver-details').open, focus: document.activeElement.id, phase: state.phase, broadcast: state.broadcast }));
                    check(!closed.open && closed.focus === 'broadcast-toggle' && closed.phase === 'frozen' && closed.broadcast === intent, `${variant} close restores broadcast-toggle`);
                    await probe.locator('#broadcast-toggle').click();
                    await probe.keyboard.press('Escape');
                    await settle();
                    const escaped = await probe.evaluate(() => ({ open: document.querySelector('#receiver-details').open, settings: document.querySelector('#settings').open, focus: document.activeElement.id, phase: state.phase, broadcast: state.broadcast }));
                    check(!escaped.open && !escaped.settings && escaped.focus === 'broadcast-toggle' && escaped.phase === 'frozen' && escaped.broadcast === intent, `${variant} Escape closes details only`);
                    counts.intents += 1;
                }
                await probe.locator('#broadcast-toggle').click();
                const restored = await probe.evaluate(async () => {
                    const wasOpen = document.querySelector('#receiver-details').open;
                    const broadcast = state.broadcast;
                    previewNativePhase('READY');
                    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                    const toggle = document.querySelector('#broadcast-toggle');
                    return { wasOpen, broadcast, stillOpen: document.querySelector('#receiver-details').open, role: toggle.getAttribute('role'), checked: toggle.getAttribute('aria-checked'), popup: toggle.getAttribute('aria-haspopup'), phase: state.phase };
                });
                check(restored.wasOpen && restored.broadcast === true && !restored.stillOpen && restored.role === 'switch' && restored.checked === 'false' && restored.popup === null && restored.phase === 'off', `${variant} leaving FROZEN restores the switch`);
            }
            for (const [width, height] of sizes) {
                await probe.setViewportSize({ width, height });
                await probe.evaluate(() => { selectVariant('A'); previewNativePhase('FROZEN'); resizeCanvas(); });
                await probe.locator('#broadcast-toggle').click();
                const box = await probe.evaluate(() => {
                    const dialog = document.querySelector('#receiver-details');
                    const button = document.querySelector('#close-receiver-details');
                    const dialogBox = dialog.getBoundingClientRect();
                    const buttonBox = button.getBoundingClientRect();
                    return { open: dialog.open, left: dialogBox.left, top: dialogBox.top, right: dialogBox.right, bottom: dialogBox.bottom, width: dialogBox.width, height: dialogBox.height, buttonWidth: buttonBox.width, buttonHeight: buttonBox.height, vw: window.innerWidth, vh: window.innerHeight };
                });
                check(box.open && box.left >= -1 && box.top >= -1 && box.right <= box.vw + 1 && box.bottom <= box.vh + 1 && box.width <= 420.5 && box.height <= box.vh - 16 && box.buttonWidth >= 44 && box.buttonHeight >= 44, `dialog bounds ${width}x${height}`);
                await probe.locator('#close-receiver-details').click();
                counts.sizes += 1;
            }
            await probe.setViewportSize({ width: 390, height: 844 });
            await probe.goto(`${base}/big-standby-prototype.html?variant=A&phase=READY&v=41`);
            await probe.locator('#settings-handle').click();
            await settle();
            await probe.locator('#show-prototype-tools').check();
            await probe.locator('#home-simple').check();
            await settle();
            await probe.locator('#native-phase').selectOption('FROZEN');
            await settle();
            const dockHidden = await probe.evaluate(() => document.querySelector('#broadcast-dock').hidden);
            check(dockHidden, 'simple home hides the broadcast dock');
            await probe.locator('#settings-broadcast-toggle').click();
            const stacked = await probe.evaluate(() => {
                const info = document.querySelector('#settings-broadcast-toggle');
                return { settings: document.querySelector('#settings').open, details: document.querySelector('#receiver-details').open, phase: state.phase, broadcast: state.broadcast, role: info.getAttribute('role'), label: info.textContent };
            });
            check(!stacked.settings && stacked.details && stacked.phase === 'frozen' && stacked.broadcast === false && stacked.role === 'button' && stacked.label === '查看接收不可用詳情', 'simple settings opens details without stacking');
            await probe.locator('#close-receiver-details').click();
            await settle();
            const back = await probe.evaluate(() => document.activeElement.id);
            check(back === 'settings-handle', 'simple close restores settings-handle');
            counts.settings = 1;
            return counts;
        });

        await run('Wi-Fi release, C/D warning switch, A connecting hint blank', async () => {
            const frame = () => probe.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            const settleCopy = () => probe.evaluate(async () => {
                const ids = ['status-title', 'status-eyebrow', 'connection-hint'];
                for (let pass = 0; pass < 20; pass += 1) {
                    const running = [];
                    for (const id of ids) {
                        const change = mainCopyChanges.get(document.getElementById(id));
                        if (change && change.animation && change.animation.playState !== 'finished') running.push(change.animation.finished.catch(() => {}));
                    }
                    if (running.length) await Promise.all(running);
                    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                    if (!ids.some(id => mainCopyChanges.has(document.getElementById(id)))) break;
                }
            });
            await probe.goto(`${base}/big-standby-prototype.html?variant=A&phase=CONNECTING&v=41`);
            await probe.bringToFront();
            await settleCopy();
            const connectingA = await probe.evaluate(() => ({
                title: document.querySelector('#status-title').textContent,
                hint: document.querySelector('#connection-hint').textContent,
                hidden: getComputedStyle(document.querySelector('#connection-hint')).display
            }));
            check(connectingA.title === '正在連接' && connectingA.hint === '' && connectingA.hidden === 'none', 'A connecting hint is blank and not reserved');
            await probe.evaluate(() => { previewNativePhase('ARMING'); });
            await settleCopy();
            const armingA = await probe.evaluate(() => ({
                hint: document.querySelector('#connection-hint').textContent,
                display: getComputedStyle(document.querySelector('#connection-hint')).display
            }));
            check(armingA.hint === '' && armingA.display === 'none', 'A ARMING hint blank and display none after fade');
            await probe.evaluate(() => { selectVariant('C'); previewNativePhase('CONNECTING'); });
            await settleCopy();
            const connectingC = await probe.evaluate(() => ({
                title: document.querySelector('#status-title').textContent,
                hint: document.querySelector('#connection-hint').textContent,
                detail: document.querySelector('#status-detail').textContent
            }));
            check(connectingC.title === '正在連接' && connectingC.hint.includes('Connecting · Please wait') && connectingC.detail === '模擬發送端已選取此裝置。', 'C connecting copy stays');

            await probe.goto(`${base}/big-standby-prototype.html?variant=C&phase=WIFI_PAUSED&v=41`);
            await frame();
            const cold = await probe.evaluate(() => {
                const toggle = document.querySelector('#broadcast-toggle');
                return {
                    phase: state.phase,
                    broadcast: state.broadcast,
                    target: cParticleAssembly.target,
                    fraction: cParticleAssembly.fraction,
                    symbol: toggle.dataset.symbol,
                    role: toggle.getAttribute('role'),
                    checked: toggle.getAttribute('aria-checked'),
                    label: toggle.getAttribute('aria-label'),
                    settings: document.querySelector('#settings-broadcast-toggle').textContent,
                    timer: cFailureStartedAt,
                    color: getComputedStyle(document.querySelector('.broadcast-symbol-warning')).color
                };
            });
            check(cold.phase === 'wifi-paused' && cold.broadcast === true && cold.target === 0 && cold.fraction === 0, 'cold C Wi-Fi target 0');
            check(cold.symbol === 'warning' && cold.role === 'switch' && cold.checked === 'true' && cold.label === 'Wi-Fi 已關閉，停止廣播', 'C Wi-Fi warning switch');
            check(cold.settings === '停止廣播' && cold.timer === null && cold.color.replace(/\s/g, '').includes('255,87,87'), 'C Wi-Fi keeps stop intent, red glyph, no failure timer');

            const moveUntil = async (ready) => {
                let sample = null;
                for (let i = 0; i < 20; i += 1) {
                    await frame();
                    sample = await probe.evaluate(() => ({ target: cParticleAssembly.target, fraction: cParticleAssembly.fraction, broadcast: state.broadcast, phase: state.phase }));
                    if (ready(sample)) break;
                }
                return sample;
            };
            await probe.evaluate(() => { previewNativePhase('ADVERTISING'); });
            const gathered = await moveUntil(sample => sample.target === 1 && sample.fraction > 0.01);
            check(gathered.phase === 'waiting' && gathered.broadcast && gathered.target === 1 && gathered.fraction > 0.01, 'advertising gathers on the owned clock');
            await probe.evaluate(() => { previewNativePhase('WIFI_PAUSED'); });
            const released = await moveUntil(sample => sample.target === 0 && sample.fraction < gathered.fraction);
            check(released.phase === 'wifi-paused' && released.broadcast && released.target === 0 && released.fraction < gathered.fraction && released.fraction > 0, 'Wi-Fi releases from the current progress');
            await probe.evaluate(() => { previewNativePhase('ADVERTISING'); });
            const reversed = await moveUntil(sample => sample.target === 1 && sample.fraction > released.fraction);
            check(reversed.broadcast && reversed.target === 1 && reversed.fraction > released.fraction, 'leaving Wi-Fi reassembles without a jump');
            await probe.evaluate(() => { previewNativePhase('WIFI_PAUSED'); });
            await frame();
            await probe.locator('#broadcast-toggle').click();
            const stopped = await probe.evaluate(() => {
                const toggle = document.querySelector('#broadcast-toggle');
                return { phase: state.phase, broadcast: state.broadcast, symbol: toggle.dataset.symbol, role: toggle.getAttribute('role'), checked: toggle.getAttribute('aria-checked') };
            });
            check(stopped.phase === 'off' && stopped.broadcast === false && stopped.symbol === 'play' && stopped.role === 'switch' && stopped.checked === 'false', 'Wi-Fi click stops to ready');

            await probe.evaluate(() => { selectVariant('D'); previewNativePhase('WIFI_PAUSED'); });
            await frame();
            const wifiD = await probe.evaluate(() => {
                const toggle = document.querySelector('#broadcast-toggle');
                return { symbol: toggle.dataset.symbol, role: toggle.getAttribute('role'), checked: toggle.getAttribute('aria-checked'), label: toggle.getAttribute('aria-label'), color: getComputedStyle(document.querySelector('.broadcast-symbol-warning')).color, phase: state.phase, broadcast: state.broadcast };
            });
            check(wifiD.phase === 'wifi-paused' && wifiD.broadcast && wifiD.symbol === 'warning' && wifiD.role === 'switch' && wifiD.checked === 'true' && wifiD.label === 'Wi-Fi 已關閉，停止廣播' && wifiD.color.replace(/\s/g, '').includes('255,87,87'), 'D Wi-Fi warning switch');
            await probe.evaluate(() => { selectVariant('A'); previewNativePhase('WIFI_PAUSED'); });
            await frame();
            const wifiA = await probe.evaluate(() => {
                const toggle = document.querySelector('#broadcast-toggle');
                return { symbol: toggle.dataset.symbol, role: toggle.getAttribute('role'), checked: toggle.getAttribute('aria-checked'), label: toggle.getAttribute('aria-label'), broadcast: state.broadcast };
            });
            check(wifiA.broadcast && wifiA.symbol === 'stop' && wifiA.role === 'switch' && wifiA.checked === 'true' && wifiA.label === '停止廣播', 'A Wi-Fi stays the stop switch');
            return { copy: 2, cold: 1, gather: 1, release: 1, reverse: 1, click: 1, themes: 3 };
        });

        await run('A celestial 3.6s rise survives standby burnY', async () => {
            const viewports = [[667, 375], [2560, 720], [390, 844]];
            let rises = 0;
            for (const [width, height] of viewports) {
                await probe.setViewportSize({ width, height });
                await probe.goto(`${base}/big-standby-prototype.html?variant=A&phase=READY&v=41`);
                await probe.bringToFront();
                const primed = await probe.evaluate(() => {
                    particleTime = 1700;
                    applyAmbientTransforms();
                    resizeCanvas();
                    render();
                    const burn = document.querySelector('#standby-view').style.transform.match(/translate3d\(([-\d.]+)px, ([-\d.]+)px/);
                    return {
                        burnY: burn ? Number(burn[2]) : 0,
                        transform: document.querySelector('.a-celestial').getAttribute('transform'),
                        paused: document.querySelector('#stage').classList.contains('motion-stopped'),
                        phase: state.phase,
                        broadcast: state.broadcast
                    };
                });
                check(!primed.paused && primed.phase === 'off' && primed.broadcast === false && Math.abs(primed.burnY) > 0.05, `${width}x${height} burnY is active before the rise`);
                const sample = async () => {
                    const before = await probe.evaluate(() => document.querySelector('.a-celestial').getAttribute('transform'));
                    await probe.locator('#broadcast-toggle').click();
                    const after = await probe.evaluate(async () => {
                        const sun = document.querySelector('.a-sun-orbit');
                        const moon = document.querySelector('.a-moon-orbit');
                        const frames = [];
                        for (let i = 0; i < 24; i += 1) {
                            await new Promise(resolve => requestAnimationFrame(resolve));
                            frames.push({
                                sun: Number(getComputedStyle(sun).opacity),
                                moon: Number(getComputedStyle(moon).opacity),
                                sunAnims: sun.getAnimations().length,
                                moonAnims: moon.getAnimations().length
                            });
                        }
                        return {
                            transform: document.querySelector('.a-celestial').getAttribute('transform'),
                            phase: state.phase,
                            broadcast: state.broadcast,
                            frames
                        };
                    });
                    return { before, ...after };
                };
                const up = await sample();
                check(up.before === primed.transform && up.transform === primed.transform, `${width}x${height} rise keeps the celestial transform`);
                check(up.phase === 'arming' && up.broadcast === true, `${width}x${height} OFF to ARMING`);
                check(up.frames.some(frame => frame.sun > 0.0001 && frame.sun < 0.9999 && frame.moon > 0.0001 && frame.moon < 0.9999 && frame.sunAnims >= 1 && frame.moonAnims >= 1 && frame.sunAnims + frame.moonAnims >= 2), `${width}x${height} rise stays in transition`);
                const down = await sample();
                check(down.before === primed.transform && down.transform === primed.transform && down.phase === 'off' && down.broadcast === false, `${width}x${height} set returns ready without a celestial reset`);
                check(down.frames.some(frame => frame.sun > 0.0001 && frame.sun < 0.9999 && frame.moon > 0.0001 && frame.moon < 0.9999 && frame.sunAnims >= 1 && frame.moonAnims >= 1), `${width}x${height} set stays in transition`);
                rises += 1;
            }
            return { rises };
        });

        await run('C theme status word never wraps across all 8 viewports and all 7 phases with zero overlap', async () => {
            const cPhases = ['off', 'arming', 'waiting', 'connecting', 'wifi-paused', 'frozen', 'failed'];
            const evidence = [];
            for (const [width, height] of sizes) {
                await probe.setViewportSize({ width, height });
                for (const phase of cPhases) {
                    const sample = await probe.evaluate(async (p) => {
                        selectVariant('C');
                        previewNativePhase(nativePhaseFor(p));
                        render();
                        resizeCanvas();

                        const frame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                        const ids = ['status-title', 'status-eyebrow', 'connection-hint'];
                        for (let pass = 0; pass < 20; pass++) {
                            const running = [];
                            for (const id of ids) {
                                const change = mainCopyChanges.get(document.getElementById(id));
                                if (change && change.animation && change.animation.playState !== 'finished') {
                                    running.push(change.animation.finished.catch(() => {}));
                                }
                            }
                            if (running.length) await Promise.all(running);
                            await frame();
                            if (!ids.some(id => mainCopyChanges.has(document.getElementById(id)))) break;
                        }

                        const word = document.querySelector('#c-status-word');
                        const plate = document.querySelector('.c-standby-plate');
                        const hint = document.querySelector('.stage.v-C .connection-hint');
                        const stageRect = document.querySelector('#stage').getBoundingClientRect();
                        const wordRect = word.getBoundingClientRect();
                        const plateRect = plate.getBoundingClientRect();
                        const hintRect = hint.getBoundingClientRect();
                        const wordFont = parseFloat(getComputedStyle(word).fontSize);

                        const range = document.createRange();
                        range.selectNodeContents(word);
                        const lines = range.getClientRects().length;

                        const noOverlap = hintRect.right <= plateRect.left + 1;
                        const contained = plateRect.right <= stageRect.right + 2 && hintRect.left >= stageRect.left - 2;

                        return {
                            phase: p,
                            word: word.textContent,
                            lines,
                            wordFont,
                            noOverlap,
                            contained,
                            layout: stage.dataset.cFooterLayout
                        };
                    }, phase);

                    check(sample.lines === 1, `C status word "${sample.word}" wrapped to ${sample.lines} lines for ${phase} at ${width}x${height}`);
                    check(sample.wordFont === 20, `C status word font size ${sample.wordFont} != 20 for ${phase} at ${width}x${height}`);
                    check(sample.noOverlap, `Overlap between hint and plate for C/${phase} at ${width}x${height}`);
                    check(sample.contained, `Content out of viewport bounds for C/${phase} at ${width}x${height}`);
                    evidence.push({ width, height, ...sample });
                }
            }
            return evidence;
        });

        await run('C particle state samples: URL initialization, selector sync, distinct coordinates, and frozen stasis on motion paused', async () => {
            const modes = ['auto', 'cloud', 'x-mark', 'arming-vortex', 'connecting-tunnel', 'wifi-ripple', 'frozen-lattice'];
            const evidence = [];

            for (const mode of modes) {
                await probe.goto(`${base}/big-standby-prototype.html?variant=C&particle=${mode}&v=41`);
                await probe.waitForFunction(() => canvasWidth === stage.clientWidth && canvasHeight === stage.clientHeight);

                const sample = await probe.evaluate((m) => {
                    const modeVal = state.cParticleMode;
                    const gyroSelect = document.querySelector('#c-particle-mode')?.value;
                    const pickerSelect = document.querySelector('#c-particle-picker')?.value;

                    // Sample coordinates for non-ambient particle #3
                    const frame = { width: canvasWidth, height: canvasHeight, time: 2.0 };
                    const posA = cParticleSampleProject(m, 3, 400, 300, frame);
                    const posB = cParticleSampleProject(m, 3, 400, 300, frame);
                    const posLater = cParticleSampleProject(m, 3, 400, 300, { ...frame, time: 3.5 });

                    let autoAdvisedA = null;
                    let autoAdvisedLater = null;
                    let autoFrozen = null;
                    if (m === 'auto') {
                        previewNativePhase('ADVERTISING');
                        cParticleAssembly.setBroadcast(true, 0);
                        autoAdvisedA = cParticleSampleProject(m, 3, 400, 300, frame);
                        autoAdvisedLater = cParticleSampleProject(m, 3, 400, 300, { ...frame, time: 3.5 });
                        // FROZEN must adopt proposal 06 (Locked Stasis Lattice) under auto.
                        previewNativePhase('FROZEN');
                        autoFrozen = {
                            sample: cParticleSampleProject(m, 3, 400, 300, frame),
                            lattice: cParticleSampleProject('frozen-lattice', 3, 400, 300, frame)
                        };
                        previewNativePhase('ADVERTISING');
                    }

                    return {
                        mode: m,
                        modeVal,
                        gyroSelect,
                        pickerSelect,
                        posA,
                        posB,
                        posLater,
                        autoAdvisedA,
                        autoAdvisedLater,
                        autoFrozen
                    };
                }, mode);

                check(sample.modeVal === mode, `state.cParticleMode must be ${mode}, got ${sample.modeVal}`);
                check(sample.gyroSelect === mode, `#c-particle-mode must be ${mode}, got ${sample.gyroSelect}`);
                check(sample.pickerSelect === mode, `#c-particle-picker must be ${mode}, got ${sample.pickerSelect}`);
                check(sample.posA.x === sample.posB.x && sample.posA.y === sample.posB.y, `${mode} must be deterministic at same timestamp`);

                if (mode === 'auto') {
                    // In READY (broadcast === false), auto returns free-flight input
                    check(sample.posA.x === 400 && sample.posA.y === 300, 'auto in READY must return free-flight coordinates');
                    // In ADVERTISING (broadcast === true), auto assembles into X-mark and advances along contour
                    check(sample.autoAdvisedA.x !== 400 || sample.autoAdvisedA.y !== 300, 'auto in ADVERTISING must assemble into X-mark');
                    check(sample.autoAdvisedA.x !== sample.autoAdvisedLater.x || sample.autoAdvisedA.y !== sample.autoAdvisedLater.y, 'auto in ADVERTISING must advance along X contour with time');
                    // FROZEN (receiver unavailable) adopts proposal 06 frozen-lattice coordinates
                    check(sample.autoFrozen.sample.x === sample.autoFrozen.lattice.x && sample.autoFrozen.sample.y === sample.autoFrozen.lattice.y, 'auto in FROZEN must match frozen-lattice coordinates');
                    check(sample.autoFrozen.sample.x !== 400 || sample.autoFrozen.sample.y !== 300, 'auto in FROZEN must leave the free-flight position');
                } else if (mode === 'cloud') {
                    // Cloud mode stays in free-flight
                    check(sample.posA.x === 400 && sample.posA.y === 300, 'cloud mode must retain free-flight coordinates');
                } else {
                    // Explicit animated formation presets advance with time
                    check(sample.posA.x !== sample.posLater.x || sample.posA.y !== sample.posLater.y, `${mode} must advance with time`);
                }
                evidence.push(sample);
            }

            // Verify that all 6 explicit sample modes produce mutually distinct coordinates at time: 2.0
            const explicitModes = ['cloud', 'x-mark', 'arming-vortex', 'connecting-tunnel', 'wifi-ripple', 'frozen-lattice'];
            const explicitCoordinates = new Set(
                evidence
                    .filter(item => explicitModes.includes(item.mode))
                    .map(item => `${item.posA.x.toFixed(2)},${item.posA.y.toFixed(2)}`)
            );
            check(explicitCoordinates.size === explicitModes.length, `All 6 explicit sample modes must produce mutually distinct coordinates, got ${explicitCoordinates.size} unique of ${explicitModes.length}`);

            // Verify stasis when state.motion.paused is true
            await probe.evaluate(() => {
                state.motion.paused = true;
                syncMotion();
            });
            const t1 = await probe.evaluate(() => particleTime);
            await probe.waitForTimeout(200);
            const t2 = await probe.evaluate(() => particleTime);
            check(t1 === t2, `particleTime must freeze when motion paused: ${t1} vs ${t2}`);

            return evidence;
        });

        await run('custom broadcast name: URL init, cross-theme sync, live typing, escaping, and default restore', async () => {
            const evidence = [];
            const defaultName = 'MIRAX · 客廳';
            const named = 'MIRAX%20%C2%B7%20%E6%9B%B8%E6%88%BF';
            const settle = () => probe.evaluate(async () => {
                const frame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                for (let pass = 0; pass < 20; pass++) {
                    const running = [...mainCopyChanges.values()].filter(change => change.animation && change.animation.playState !== 'finished').map(change => change.animation.finished.catch(() => {}));
                    if (running.length) await Promise.all(running);
                    await frame();
                    if (!mainCopyChanges.size) break;
                }
            });

            // Default name across A (waiting), C (waiting), D (waiting).
            for (const variant of ['A', 'C', 'D']) {
                await probe.goto(`${base}/big-standby-prototype.html?variant=${variant}&phase=ADVERTISING&v=41`);
                await settle();
                const sample = await probe.evaluate(() => ({
                    deviceName: state.deviceName,
                    devices: [...document.querySelectorAll('.device-name')].map(node => node.textContent),
                    cTitle: document.querySelector('#connection-hint .connection-title')?.textContent ?? null,
                    aHint: document.querySelector('#connection-hint')?.textContent ?? null,
                    input: document.querySelector('#device-name-input').value
                }));
                check(sample.deviceName === defaultName, `${variant}: default state.deviceName must be ${defaultName}, got ${sample.deviceName}`);
                check(sample.devices.length > 0 && sample.devices.every(name => name === defaultName), `${variant}: default .device-name copy mismatch: ${JSON.stringify(sample.devices)}`);
                check(sample.input === defaultName, `${variant}: settings input must show the default name`);
                if (variant === 'A') check(sample.aHint.includes('「' + defaultName + '」'), `A: default hint must quote the default name, got ${sample.aHint}`);
                if (variant === 'C') check(sample.cTitle === defaultName, `C: default .connection-title must be ${defaultName}, got ${sample.cTitle}`);
                evidence.push({ variant, sample });
            }

            // Custom name from the URL, across A, C, D.
            const customName = 'MIRAX · 書房';
            for (const variant of ['A', 'C', 'D']) {
                await probe.goto(`${base}/big-standby-prototype.html?variant=${variant}&phase=ADVERTISING&name=${named}&v=41`);
                await settle();
                const sample = await probe.evaluate(() => ({
                    deviceName: state.deviceName,
                    devices: [...document.querySelectorAll('.device-name')].map(node => node.textContent),
                    cTitle: document.querySelector('#connection-hint .connection-title')?.textContent ?? null,
                    aReceiver: document.querySelector('#connection-hint .receiver-name')?.textContent ?? null,
                    aHint: document.querySelector('#connection-hint')?.textContent ?? null,
                    dWaiting: [...document.querySelectorAll('.e-status-message[data-status=\"waiting\"] .e-status-phrase')].map(node => node.textContent).filter(Boolean),
                    input: document.querySelector('#device-name-input').value
                }));
                check(sample.deviceName === customName, `${variant}: URL name must init state.deviceName to ${customName}, got ${sample.deviceName}`);
                check(sample.devices.length > 0 && sample.devices.every(name => name === customName), `${variant}: custom .device-name copy mismatch: ${JSON.stringify(sample.devices)}`);
                check(sample.input === customName, `${variant}: settings input must mirror the URL name`);
                if (variant === 'A') {
                    check(sample.aReceiver === '「' + customName + '」', `A: receiver span must quote ${customName}, got ${sample.aReceiver}`);
                    check(sample.aHint.includes(customName), `A: hint copy must contain ${customName}`);
                }
                if (variant === 'C') check(sample.cTitle === customName, `C: .connection-title must be ${customName}, got ${sample.cTitle}`);
                if (variant === 'D') {
                    check(sample.dWaiting.length > 0, 'D: waiting status phrases must exist');
                    check(sample.dWaiting.every(copy => copy.includes(customName)), `D: waiting status must contain ${customName}, got ${JSON.stringify(sample.dWaiting.slice(0, 2))}`);
                }
                evidence.push({ variant, named: customName, sample });
            }

            // Interactive typing updates every surface immediately.
            await probe.goto(`${base}/big-standby-prototype.html?variant=A&phase=ADVERTISING&v=41`);
            await settle();
            await probe.locator('#settings-handle').click();
            await probe.locator('#device-name-input').fill('會議室大螢幕');
            await settle();
            const typed = await probe.evaluate(() => ({
                deviceName: state.deviceName,
                focused: document.activeElement === document.querySelector('#device-name-input'),
                devices: [...document.querySelectorAll('.device-name')].map(node => node.textContent),
                aReceiver: document.querySelector('#connection-hint .receiver-name')?.textContent ?? null,
                input: document.querySelector('#device-name-input').value
            }));
            check(typed.deviceName === '會議室大螢幕', `typing must set state.deviceName, got ${typed.deviceName}`);
            check(typed.focused, 'typing must keep focus in the name input');
            check(typed.input === '會議室大螢幕', 'typing must not be overwritten by render sync');
            check(typed.devices.every(name => name === '會議室大螢幕'), `typed .device-name copy mismatch: ${JSON.stringify(typed.devices)}`);
            check(typed.aReceiver === '「會議室大螢幕」', `typed A receiver must update, got ${typed.aReceiver}`);
            evidence.push({ typed });

            // Markup in the name stays inert.
            await probe.locator('#device-name-input').fill('');
            await probe.locator('#device-name-input').type('<b>Test</b>', { delay: 5 });
            await settle();
            const escaped = await probe.evaluate(() => ({
                deviceName: state.deviceName,
                aReceiverChildren: document.querySelector('#connection-hint .receiver-name')?.children.length ?? -1,
                aReceiverText: document.querySelector('#connection-hint .receiver-name')?.textContent ?? null,
                boldElements: document.querySelectorAll('#connection-hint b').length,
                deviceTexts: [...document.querySelectorAll('.device-name')].map(node => ({ text: node.textContent, bold: node.querySelectorAll('b').length }))
            }));
            check(escaped.deviceName === '<b>Test</b>', `markup name must be stored verbatim, got ${escaped.deviceName}`);
            check(escaped.aReceiverChildren === 0 && escaped.aReceiverText === '「<b>Test</b>」', `markup must be escaped in A hint, got children=${escaped.aReceiverChildren} text=${escaped.aReceiverText}`);
            check(escaped.boldElements === 0, 'markup name must not inject elements into the hint');
            check(escaped.deviceTexts.every(item => item.bold === 0 && item.text === '<b>Test</b>'), `markup name must stay text in .device-name: ${JSON.stringify(escaped.deviceTexts)}`);
            evidence.push({ escaped });

            // Clearing the input restores the default name.
            await probe.locator('#device-name-input').fill('');
            await probe.locator('#device-name-input').blur();
            await settle();
            const restored = await probe.evaluate(() => ({
                deviceName: state.deviceName,
                input: document.querySelector('#device-name-input').value,
                devices: [...document.querySelectorAll('.device-name')].map(node => node.textContent),
                aReceiver: document.querySelector('#connection-hint .receiver-name')?.textContent ?? null
            }));
            check(restored.deviceName === defaultName, `blur on blank input must restore ${defaultName}, got ${restored.deviceName}`);
            check(restored.input === defaultName, 'blank blur must write the default back into the input');
            check(restored.devices.every(name => name === defaultName), `restore .device-name mismatch: ${JSON.stringify(restored.devices)}`);
            check(restored.aReceiver === '「' + defaultName + '」', `restore A receiver mismatch: ${restored.aReceiver}`);
            evidence.push({ restored });

            // Broadcast name persists across reloads until reset to the default.
            const persistedName = '臥室電視機';
            await probe.locator('#device-name-input').fill(persistedName);
            await settle();
            const stored = await probe.evaluate(() => ({
                deviceName: state.deviceName,
                stored: localStorage.getItem('mirax_broadcast_name')
            }));
            check(stored.deviceName === persistedName, `typed name must reach state before persistence, got ${stored.deviceName}`);
            check(stored.stored === persistedName, `typed name must persist to localStorage, got ${stored.stored}`);

            await probe.goto(base + '/big-standby-prototype.html?v=41');
            await settle();
            const reloaded = await probe.evaluate(() => ({
                deviceName: state.deviceName,
                input: document.querySelector('#device-name-input').value,
                devices: [...document.querySelectorAll('.device-name')].map(node => node.textContent),
                stored: localStorage.getItem('mirax_broadcast_name')
            }));
            check(reloaded.deviceName === persistedName, `reload must restore stored name, got ${reloaded.deviceName}`);
            check(reloaded.input === persistedName, `reload must mirror stored name in the input, got ${reloaded.input}`);
            check(reloaded.devices.length > 0 && reloaded.devices.every(name => name === persistedName), `reload .device-name copy mismatch: ${JSON.stringify(reloaded.devices)}`);
            check(reloaded.stored === persistedName, `reload must keep the stored entry, got ${reloaded.stored}`);
            evidence.push({ reloaded });

            await probe.locator('#settings-handle').click();
            await probe.locator('#device-name-input').fill('');
            await probe.locator('#device-name-input').blur();
            await settle();
            const cleared = await probe.evaluate(() => ({
                deviceName: state.deviceName,
                stored: localStorage.getItem('mirax_broadcast_name'),
                devices: [...document.querySelectorAll('.device-name')].map(node => node.textContent)
            }));
            check(cleared.deviceName === defaultName, `blank blur must restore ${defaultName}, got ${cleared.deviceName}`);
            check(cleared.stored === null, `blank blur must clear the stored name, got ${cleared.stored}`);
            check(cleared.devices.every(name => name === defaultName), `cleared .device-name mismatch: ${JSON.stringify(cleared.devices)}`);

            await probe.goto(base + '/big-standby-prototype.html?v=41');
            await settle();
            const coldReload = await probe.evaluate(() => ({
                deviceName: state.deviceName,
                input: document.querySelector('#device-name-input').value,
                stored: localStorage.getItem('mirax_broadcast_name'),
                devices: [...document.querySelectorAll('.device-name')].map(node => node.textContent)
            }));
            check(coldReload.deviceName === defaultName, `cleared reload must default to ${defaultName}, got ${coldReload.deviceName}`);
            check(coldReload.input === defaultName, 'cleared reload must show the default in the input');
            check(coldReload.stored === null, `cleared reload must leave storage empty, got ${coldReload.stored}`);
            check(coldReload.devices.every(name => name === defaultName), `cleared reload .device-name mismatch: ${JSON.stringify(coldReload.devices)}`);
            evidence.push({ cleared, coldReload });

            // Portrait containment for the user-reported long name: A must clip inside the viewport, D must stack above the dock.
            const longName = "Renathan's Fold 5555555555555555555";
            const longQuery = encodeURIComponent(longName);
            for (const [width, height] of [[390, 844], [412, 915]]) {
                await probe.setViewportSize({ width, height });
                for (const variant of ['A', 'D']) {
                    await probe.goto(`${base}/big-standby-prototype.html?variant=${variant}&phase=ADVERTISING&name=${longQuery}&v=41`);
                    await settle();
                    const measured = await probe.evaluate(() => {
                        const nameNode = document.querySelector('.device-name');
                        const deviceNode = document.querySelector('.device');
                        const dockNode = document.querySelector('#broadcast-dock');
                        const name = nameNode.getBoundingClientRect();
                        const device = deviceNode.getBoundingClientRect();
                        const dock = dockNode.getBoundingClientRect();
                        const range = document.createRange();
                        range.selectNodeContents(nameNode);
                        return {
                            text: nameNode.textContent,
                            nameLines: new Set([...range.getClientRects()].filter(rect => rect.width > 0).map(rect => Math.round(rect.top))).size,
                            name: { left: name.left, right: name.right },
                            device: { left: device.left, right: device.right, top: device.top, bottom: device.bottom },
                            dock: { left: dock.left, right: dock.right, top: dock.top, bottom: dock.bottom, hidden: dockNode.hidden },
                            innerWidth,
                            scrollWidth: document.documentElement.scrollWidth
                        };
                    });
                    const where = `${variant} ${width}x${height}`;
                    check(measured.text === longName, `${where}: long name copy mismatch: ${measured.text}`);
                    check(measured.nameLines === 1, `${where}: .device-name wrapped to ${measured.nameLines} lines`);
                    check(measured.scrollWidth <= measured.innerWidth, `${where}: horizontal overflow ${measured.scrollWidth} > ${measured.innerWidth}`);
                    if (variant === 'A') {
                        check(measured.name.left >= 0 && measured.name.right <= measured.innerWidth, `A ${width}x${height}: .device-name escapes the viewport (${measured.name.left}..${measured.name.right} of ${measured.innerWidth})`);
                    } else {
                        check(measured.device.left >= 0 && measured.device.right <= measured.innerWidth, `D ${width}x${height}: .device escapes the viewport (${measured.device.left}..${measured.device.right} of ${measured.innerWidth})`);
                        check(!measured.dock.hidden, `D ${width}x${height}: dock must be visible for the stacking check`);
                        check(measured.device.bottom <= measured.dock.top, `D ${width}x${height}: .device bottom ${measured.device.bottom} is not above dock top ${measured.dock.top}`);
                        const overlap = measured.device.bottom > measured.dock.top && measured.device.right > measured.dock.left && measured.device.left < measured.dock.right;
                        check(overlap === false, `D ${width}x${height}: .device overlaps the dock`);
                    }
                    evidence.push({ longName, variant, width, height, measured });
                }
            }
            return evidence;
        });
        afterHash = await reviewHash();
        check(beforeHash === afterHash, 'Protected reviews/notes state changed');
    } catch (error) {
        failures.push({ name: 'setup/audit', message: String(error.message || error) });
    } finally {
        await context.close();
    }
    return {
        ok: !failures.length && !errors.length && !blockedWrites.length,
        elapsedMs: Date.now() - started,
        results,
        failures,
        errors,
        blockedWrites,
        beforeHash,
        afterHash,
        ownedContexts: 1,
        closedContexts: 1
    };
}
