using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

public class MultiTouchProbe {
    [DllImport("user32.dll", SetLastError = true)]
    static extern IntPtr CreateWindowEx(
        uint dwExStyle, string lpClassName, string lpWindowName, uint dwStyle,
        int x, int y, int nWidth, int nHeight, IntPtr hWndParent, IntPtr hMenu,
        IntPtr hInstance, IntPtr lpParam);

    [DllImport("user32.dll")]
    static extern bool DestroyWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    static extern bool UpdateWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    static extern IntPtr WindowFromPoint(POINT point);

    [DllImport("user32.dll")]
    static extern uint GetWindowThreadProcessId(IntPtr window, IntPtr processId);

    [DllImport("kernel32.dll")]
    static extern uint GetCurrentThreadId();

    [DllImport("user32.dll")]
    static extern bool AttachThreadInput(uint fromThread, uint toThread, bool attach);

    [DllImport("user32.dll")]
    static extern int GetSystemMetrics(int nIndex);

    [DllImport("user32.dll")]
    static extern bool SetLayeredWindowAttributes(IntPtr hwnd, uint crKey, byte bAlpha, uint dwFlags);

    [DllImport("user32.dll")]
    static extern bool RegisterTouchWindow(IntPtr hWnd, uint ulFlags);

    [DllImport("user32.dll")]
    static extern bool GetPointerType(uint pointerId, out uint pointerType);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool GetPointerInfo(uint pointerId, out POINTER_INFO pointerInfo);

    [DllImport("user32.dll")]
    static extern int DefWindowProc(IntPtr hWnd, uint uMsg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    static extern ushort RegisterClass(ref WNDCLASS lpWndClass);

    [DllImport("user32.dll")]
    static extern bool GetMessage(out MSG lpMsg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    [DllImport("user32.dll")]
    static extern bool TranslateMessage(ref MSG lpMsg);

    [DllImport("user32.dll")]
    static extern IntPtr DispatchMessage(ref MSG lpMsg);

    [DllImport("user32.dll")]
    static extern void PostQuitMessage(int nExitCode);

    [DllImport("user32.dll")]
    static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll")]
    static extern IntPtr GetModuleHandle(string lpModuleName);

    delegate IntPtr WndProcDelegate(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    struct WNDCLASS {
        public uint style;
        public WndProcDelegate lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        public string lpszMenuName;
        public string lpszClassName;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct MSG {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public int pt_x;
        public int pt_y;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT {
        public int x;
        public int y;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINTER_INFO {
        public uint pointerType;
        public uint pointerId;
        public uint frameId;
        public uint pointerFlags;
        public IntPtr sourceDevice;
        public IntPtr hwndTarget;
        public POINT ptPixelLocation;
        public POINT ptHimetricLocation;
        public POINT ptPixelLocationRaw;
        public POINT ptHimetricLocationRaw;
        public uint dwTime;
        public uint historyCount;
        public int InputData;
        public uint dwKeyStates;
        public ulong PerformanceCount;
        public uint ButtonChangeType;
    }

    public delegate bool GetPointerInfoFunc(uint pointerId, out POINTER_INFO pointerInfo);
    public static GetPointerInfoFunc queryPointerInfo = GetPointerInfo;

    const uint WS_POPUP = 0x80000000;
    const uint WS_VISIBLE = 0x10000000;
    const uint WS_EX_TOPMOST = 0x00000008;
    const uint WS_EX_LAYERED = 0x00080000;
    const uint WS_EX_TOOLWINDOW = 0x00000080;
    const uint LWA_ALPHA = 0x00000002;

    const uint WM_DESTROY = 0x0002;
    const uint WM_CLOSE = 0x0010;
    const uint WM_TOUCH = 0x0240;
    const uint WM_POINTERUPDATE = 0x0245;
    const uint WM_POINTERDOWN = 0x0246;
    const uint WM_POINTERUP = 0x0247;
    const uint WM_POINTERCAPTURECHANGED = 0x024C;

    public const uint PT_POINTER = 1;
    public const uint PT_TOUCH = 2;
    public const uint PT_PEN = 3;
    public const uint PT_MOUSE = 4;

    public const uint POINTER_FLAG_NONE = 0x00000000;
    public const uint POINTER_FLAG_NEW = 0x00000001;
    public const uint POINTER_FLAG_INRANGE = 0x00000002;
    public const uint POINTER_FLAG_INCONTACT = 0x00000004;
    public const uint POINTER_FLAG_CANCELED = 0x00008000;
    public const uint POINTER_FLAG_DOWN = 0x00010000;
    public const uint POINTER_FLAG_UPDATE = 0x00020000;
    public const uint POINTER_FLAG_UP = 0x00040000;
    public const uint POINTER_FLAG_CAPTURECHANGED = 0x00200000;

    static int totalDowns = 0;
    static int maxConcurrent = 0;
    static HashSet<uint> activeIds = new HashSet<uint>();
    static HashSet<uint> distinctIds = new HashSet<uint>();
    static int penDowns = 0;
    static int maxPenConcurrent = 0;
    static bool touchPenConcurrent = false;
    static HashSet<uint> activePenIds = new HashSet<uint>();
    static HashSet<uint> distinctPenIds = new HashSet<uint>();

    static int confirmedMaxConcurrent = 0;
    static bool confirmedTouchPenConcurrent = false;
    static HashSet<string> loggedCandidatePairs = new HashSet<string>();
    static HashSet<uint> loggedQueryFailures = new HashSet<uint>();

    static StreamWriter logWriter = null;
    static object lockObj = new object();

    static void Log(string text) {
        string line = string.Format("[{0:HH:mm:ss.fff}] {1}", DateTime.Now, text);
        Console.WriteLine(line);
        lock (lockObj) {
            if (logWriter != null) {
                logWriter.WriteLine(line);
                logWriter.Flush();
            }
        }
    }

    static string PointerTypeName(bool recognized, uint pointerType) {
        if (!recognized) return "UNKNOWN";
        if (pointerType == PT_TOUCH) return "TOUCH";
        if (pointerType == PT_PEN) return "PEN";
        if (pointerType == PT_MOUSE) return "MOUSE";
        return "OTHER";
    }

    static void RecordPointerDown(bool recognized, uint pointerType, uint pointerId) {
        lock (lockObj) {
            if (recognized && pointerType == PT_TOUCH) {
                totalDowns++;
                activeIds.Add(pointerId);
                distinctIds.Add(pointerId);
                maxConcurrent = Math.Max(maxConcurrent, activeIds.Count);
            } else if (recognized && pointerType == PT_PEN) {
                penDowns++;
                activePenIds.Add(pointerId);
                distinctPenIds.Add(pointerId);
                maxPenConcurrent = Math.Max(maxPenConcurrent, activePenIds.Count);
            }
            if (activeIds.Count > 0 && activePenIds.Count > 0) touchPenConcurrent = true;
        }
    }

    static void RecordPointerUp(uint pointerId) {
        lock (lockObj) {
            activeIds.Remove(pointerId);
            activePenIds.Remove(pointerId);
        }
    }

    public static bool VerifyPointer(uint pointerId, uint expectedType, out POINTER_INFO info, out bool isStale) {
        isStale = false;
        if (!queryPointerInfo(pointerId, out info)) {
            int err = Marshal.GetLastWin32Error();
            if (loggedQueryFailures.Add(pointerId)) {
                Log(string.Format("GetPointerInfo failed id={0} win32Error={1}", pointerId, err));
            }
            return false;
        }
        if (info.pointerId != pointerId) {
            return false;
        }
        if (info.pointerType != expectedType) {
            return false;
        }
        if ((info.pointerFlags & POINTER_FLAG_INCONTACT) == 0 ||
            (info.pointerFlags & POINTER_FLAG_CANCELED) != 0 ||
            (info.pointerFlags & POINTER_FLAG_UP) != 0 ||
            (info.pointerFlags & POINTER_FLAG_CAPTURECHANGED) != 0) {
            isStale = true;
            return false;
        }
        return true;
    }

    public static void EvaluateCandidateConcurrency() {
        lock (lockObj) {
            List<uint> confirmedTouches = new List<uint>();
            List<POINTER_INFO> touchInfos = new List<POINTER_INFO>();
            List<uint> staleTouches = new List<uint>();

            foreach (uint id in activeIds) {
                POINTER_INFO info;
                bool isStale;
                if (VerifyPointer(id, PT_TOUCH, out info, out isStale)) {
                    confirmedTouches.Add(id);
                    touchInfos.Add(info);
                } else if (isStale) {
                    staleTouches.Add(id);
                }
            }

            foreach (uint stale in staleTouches) {
                activeIds.Remove(stale);
            }

            List<uint> confirmedPens = new List<uint>();
            List<POINTER_INFO> penInfos = new List<POINTER_INFO>();
            List<uint> stalePens = new List<uint>();

            foreach (uint id in activePenIds) {
                POINTER_INFO info;
                bool isStale;
                if (VerifyPointer(id, PT_PEN, out info, out isStale)) {
                    confirmedPens.Add(id);
                    penInfos.Add(info);
                } else if (isStale) {
                    stalePens.Add(id);
                }
            }

            foreach (uint stale in stalePens) {
                activePenIds.Remove(stale);
            }

            if (confirmedTouches.Count > confirmedMaxConcurrent) {
                confirmedMaxConcurrent = confirmedTouches.Count;
            }

            if (confirmedTouches.Count > 0 && confirmedPens.Count > 0) {
                confirmedTouchPenConcurrent = true;
            }

            if (confirmedTouches.Count >= 2) {
                for (int i = 0; i < confirmedTouches.Count; i++) {
                    for (int j = i + 1; j < confirmedTouches.Count; j++) {
                        string pairKey = string.Format("T{0}-T{1}", confirmedTouches[i], confirmedTouches[j]);
                        if (loggedCandidatePairs.Add(pairKey)) {
                            POINTER_INFO p1 = touchInfos[i];
                            POINTER_INFO p2 = touchInfos[j];
                            Log(string.Format("CONFIRMED-CONCURRENT TOUCH+TOUCH p1[id={0} type=TOUCH flags=0x{1:X8} dev=0x{2:X} pt=({3},{4})] p2[id={5} type=TOUCH flags=0x{6:X8} dev=0x{7:X} pt=({8},{9})]",
                                p1.pointerId, p1.pointerFlags, p1.sourceDevice.ToInt64(), p1.ptPixelLocation.x, p1.ptPixelLocation.y,
                                p2.pointerId, p2.pointerFlags, p2.sourceDevice.ToInt64(), p2.ptPixelLocation.x, p2.ptPixelLocation.y));
                        }
                    }
                }
            }

            if (confirmedTouches.Count > 0 && confirmedPens.Count > 0) {
                for (int i = 0; i < confirmedTouches.Count; i++) {
                    for (int j = 0; j < confirmedPens.Count; j++) {
                        string pairKey = string.Format("T{0}-P{1}", confirmedTouches[i], confirmedPens[j]);
                        if (loggedCandidatePairs.Add(pairKey)) {
                            POINTER_INFO p1 = touchInfos[i];
                            POINTER_INFO p2 = penInfos[j];
                            Log(string.Format("CONFIRMED-CONCURRENT TOUCH+PEN touch[id={0} flags=0x{1:X8} dev=0x{2:X} pt=({3},{4})] pen[id={5} flags=0x{6:X8} dev=0x{7:X} pt=({8},{9})]",
                                p1.pointerId, p1.pointerFlags, p1.sourceDevice.ToInt64(), p1.ptPixelLocation.x, p1.ptPixelLocation.y,
                                p2.pointerId, p2.pointerFlags, p2.sourceDevice.ToInt64(), p2.ptPixelLocation.x, p2.ptPixelLocation.y));
                        }
                    }
                }
            }
        }
    }

    public static void ResetState() {
        lock (lockObj) {
            totalDowns = 0;
            maxConcurrent = 0;
            activeIds.Clear();
            distinctIds.Clear();
            penDowns = 0;
            maxPenConcurrent = 0;
            touchPenConcurrent = false;
            activePenIds.Clear();
            distinctPenIds.Clear();
            confirmedMaxConcurrent = 0;
            confirmedTouchPenConcurrent = false;
            loggedCandidatePairs.Clear();
            loggedQueryFailures.Clear();
            queryPointerInfo = GetPointerInfo;
        }
    }

    static IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam) {
        if (msg == WM_POINTERDOWN) {
            uint pId = (uint)((ulong)wParam & 0xFFFF);
            uint pType = 0;
            bool ok = GetPointerType(pId, out pType);
            int x = (short)((ulong)lParam & 0xFFFF);
            int y = (short)(((ulong)lParam >> 16) & 0xFFFF);
            string typeName = PointerTypeName(ok, pType);

            RecordPointerDown(ok, pType, pId);
            Log(string.Format("POINTER-DOWN type={0} id={1} x={2} y={3} active={4} penActive={5}",
                typeName, pId, x, y, activeIds.Count, activePenIds.Count));

            if (activeIds.Count + activePenIds.Count >= 2 || (activeIds.Count > 0 && activePenIds.Count > 0)) {
                EvaluateCandidateConcurrency();
            }
            return IntPtr.Zero;
        }
        else if (msg == WM_POINTERUP) {
            uint pId = (uint)((ulong)wParam & 0xFFFF);
            uint pType = 0;
            bool ok = GetPointerType(pId, out pType);
            int x = (short)((ulong)lParam & 0xFFFF);
            int y = (short)(((ulong)lParam >> 16) & 0xFFFF);
            string typeName = PointerTypeName(ok, pType);

            RecordPointerUp(pId);
            Log(string.Format("POINTER-UP type={0} id={1} x={2} y={3} active={4} penActive={5}",
                typeName, pId, x, y, activeIds.Count, activePenIds.Count));
            return IntPtr.Zero;
        }
        else if (msg == WM_POINTERUPDATE) {
            uint pId = (uint)((ulong)wParam & 0xFFFF);
            POINTER_INFO info;
            if (queryPointerInfo(pId, out info)) {
                if ((info.pointerFlags & POINTER_FLAG_CANCELED) != 0 ||
                    (info.pointerFlags & POINTER_FLAG_INCONTACT) == 0 ||
                    (info.pointerFlags & POINTER_FLAG_UP) != 0 ||
                    (info.pointerFlags & POINTER_FLAG_CAPTURECHANGED) != 0) {
                    RecordPointerUp(pId);
                    Log(string.Format("POINTER-UPDATE inactive id={0} flags=0x{1:X8}", pId, info.pointerFlags));
                } else if (activeIds.Count + activePenIds.Count >= 2 || (activeIds.Count > 0 && activePenIds.Count > 0)) {
                    EvaluateCandidateConcurrency();
                }
            } else {
                int err = Marshal.GetLastWin32Error();
                if (loggedQueryFailures.Add(pId)) {
                    Log(string.Format("GetPointerInfo failed on update id={0} win32Error={1}", pId, err));
                }
            }
            return IntPtr.Zero;
        }
        else if (msg == WM_POINTERCAPTURECHANGED) {
            uint pId = (uint)((ulong)wParam & 0xFFFF);
            RecordPointerUp(pId);
            Log(string.Format("POINTER-CAPTURE-CHANGED id={0} active={1} penActive={2}",
                pId, activeIds.Count, activePenIds.Count));
            return IntPtr.Zero;
        }
        else if (msg == WM_TOUCH) {
            Log(string.Format("WM_TOUCH received wParam={0} lParam={1}", wParam, lParam));
            return IntPtr.Zero;
        }
        else if (msg == WM_CLOSE) {
            DestroyWindow(hWnd);
            return IntPtr.Zero;
        }
        else if (msg == WM_DESTROY) {
            PostQuitMessage(0);
            return IntPtr.Zero;
        }
        return (IntPtr)DefWindowProc(hWnd, msg, wParam, lParam);
    }

    static WndProcDelegate wndProcDelegate;

    public static bool IsInputWindowReady(IntPtr window, IntPtr foreground, IntPtr[] hits) {
        if (window == IntPtr.Zero || foreground != window || hits.Length == 0) return false;
        foreach (IntPtr hit in hits) {
            if (hit != window) return false;
        }
        return true;
    }

    static bool PrepareInputWindow(IntPtr window, int x, int y, int width, int height) {
        bool requested = SetForegroundWindow(window);
        if (!requested) {
            uint currentThread = GetCurrentThreadId();
            uint foregroundThread = GetWindowThreadProcessId(GetForegroundWindow(), IntPtr.Zero);
            bool attached = foregroundThread != 0 && foregroundThread != currentThread &&
                AttachThreadInput(currentThread, foregroundThread, true);
            if (attached) {
                bool detached;
                try { requested = SetForegroundWindow(window); }
                finally {
                    detached = AttachThreadInput(currentThread, foregroundThread, false);
                }
                if (!detached) {
                    Log("WINDOW-NOT-READY input thread detach failed");
                    return false;
                }
            }
        }
        var wait = System.Diagnostics.Stopwatch.StartNew();
        int[] percentages = new int[] { 5, 50, 95 };
        IntPtr[] hits = new IntPtr[9];
        IntPtr foreground;
        do {
            int index = 0;
            foreach (int horizontal in percentages) {
                foreach (int vertical in percentages) {
                    hits[index++] = WindowFromPoint(new POINT {
                        x = x + width * horizontal / 100,
                        y = y + height * vertical / 100
                    });
                }
            }
            foreground = GetForegroundWindow();
            if (IsInputWindowReady(window, foreground, hits)) {
                Log(string.Format("WINDOW-READY hwnd=0x{0:X} foreground=0x{1:X} hitSamples=9/9 activationRequested={2}",
                    window.ToInt64(), foreground.ToInt64(), requested));
                return true;
            }
            // The Cast flyout can outlive activation while its dismissal animates.
            Thread.Sleep(50);
        } while (wait.ElapsedMilliseconds < 3000);
        Log(string.Format("WINDOW-NOT-READY hwnd=0x{0:X} foreground=0x{1:X} activationRequested={2}",
            window.ToInt64(), foreground.ToInt64(), requested));
        return false;
    }

    public static int Main(string[] args) {
        if (args.Length > 0 && args[0] == "--selfcheck") {
            // Test 1: PointerTypeName mapping
            if (PointerTypeName(false, PT_TOUCH) != "UNKNOWN" ||
                PointerTypeName(true, PT_TOUCH) != "TOUCH" ||
                PointerTypeName(true, PT_PEN) != "PEN" ||
                PointerTypeName(true, PT_MOUSE) != "MOUSE" ||
                PointerTypeName(true, 99) != "OTHER") {
                Console.WriteLine("FAIL: PointerTypeName");
                return 1;
            }

            // Test 2: Legacy down/up tracking
            ResetState();
            RecordPointerDown(true, PT_TOUCH, 1);
            RecordPointerDown(true, PT_PEN, 2);
            if (totalDowns != 1 || penDowns != 1 || maxConcurrent != 1 ||
                maxPenConcurrent != 1 || !touchPenConcurrent) {
                Console.WriteLine("FAIL: Legacy down tracking");
                return 1;
            }
            RecordPointerUp(1);
            RecordPointerUp(2);
            if (activeIds.Count != 0 || activePenIds.Count != 0) {
                Console.WriteLine("FAIL: Legacy up tracking");
                return 1;
            }

            // Test 3: Failed query must not count as success; logs error once per id
            ResetState();
            queryPointerInfo = delegate(uint id, out POINTER_INFO info) {
                info = new POINTER_INFO();
                return false;
            };
            RecordPointerDown(true, PT_TOUCH, 10);
            RecordPointerDown(true, PT_TOUCH, 11);
            EvaluateCandidateConcurrency();
            if (maxConcurrent != 2) {
                Console.WriteLine("FAIL: legacy maxConcurrent should still be 2");
                return 1;
            }
            if (confirmedMaxConcurrent != 0) {
                Console.WriteLine("FAIL: failed query must not produce confirmedMaxConcurrent");
                return 1;
            }

            // Test 4: POINTER_FLAG_CANCELED rejected
            ResetState();
            queryPointerInfo = delegate(uint id, out POINTER_INFO info) {
                info = new POINTER_INFO();
                info.pointerId = id;
                info.pointerType = PT_TOUCH;
                info.pointerFlags = POINTER_FLAG_INCONTACT | POINTER_FLAG_CANCELED;
                return true;
            };
            RecordPointerDown(true, PT_TOUCH, 20);
            RecordPointerDown(true, PT_TOUCH, 21);
            EvaluateCandidateConcurrency();
            if (confirmedMaxConcurrent != 0) {
                Console.WriteLine("FAIL: CANCELED pointer must not be confirmed");
                return 1;
            }
            if (activeIds.Count != 0) {
                Console.WriteLine("FAIL: CANCELED pointer should be pruned from activeIds");
                return 1;
            }

            // Test 5: Non-INCONTACT pointer rejected (hovering without surface contact)
            ResetState();
            queryPointerInfo = delegate(uint id, out POINTER_INFO info) {
                info = new POINTER_INFO();
                info.pointerId = id;
                info.pointerType = PT_TOUCH;
                info.pointerFlags = POINTER_FLAG_INRANGE;
                return true;
            };
            RecordPointerDown(true, PT_TOUCH, 30);
            RecordPointerDown(true, PT_TOUCH, 31);
            EvaluateCandidateConcurrency();
            if (confirmedMaxConcurrent != 0) {
                Console.WriteLine("FAIL: Non-INCONTACT pointer must not be confirmed");
                return 1;
            }
            if (activeIds.Count != 0) {
                Console.WriteLine("FAIL: Non-INCONTACT pointer should be pruned from activeIds");
                return 1;
            }

            // Test 6: POINTER_FLAG_UP rejected even if INCONTACT flag is set
            ResetState();
            queryPointerInfo = delegate(uint id, out POINTER_INFO info) {
                info = new POINTER_INFO();
                info.pointerId = id;
                info.pointerType = PT_TOUCH;
                info.pointerFlags = POINTER_FLAG_INCONTACT | POINTER_FLAG_UP;
                return true;
            };
            RecordPointerDown(true, PT_TOUCH, 35);
            RecordPointerDown(true, PT_TOUCH, 36);
            EvaluateCandidateConcurrency();
            if (confirmedMaxConcurrent != 0) {
                Console.WriteLine("FAIL: UP flag must reject pointer");
                return 1;
            }
            if (activeIds.Count != 0) {
                Console.WriteLine("FAIL: UP flag should prune pointer from activeIds");
                return 1;
            }

            // Test 7: POINTER_FLAG_CAPTURECHANGED rejected even if INCONTACT flag is set
            ResetState();
            queryPointerInfo = delegate(uint id, out POINTER_INFO info) {
                info = new POINTER_INFO();
                info.pointerId = id;
                info.pointerType = PT_TOUCH;
                info.pointerFlags = POINTER_FLAG_INCONTACT | POINTER_FLAG_CAPTURECHANGED;
                return true;
            };
            RecordPointerDown(true, PT_TOUCH, 37);
            RecordPointerDown(true, PT_TOUCH, 38);
            EvaluateCandidateConcurrency();
            if (confirmedMaxConcurrent != 0) {
                Console.WriteLine("FAIL: CAPTURECHANGED flag must reject pointer");
                return 1;
            }
            if (activeIds.Count != 0) {
                Console.WriteLine("FAIL: CAPTURECHANGED flag should prune pointer from activeIds");
                return 1;
            }

            // Test 8: info.pointerId mismatch rejected
            ResetState();
            queryPointerInfo = delegate(uint id, out POINTER_INFO info) {
                info = new POINTER_INFO();
                info.pointerId = id + 999; // Mismatched pointerId returned by driver/OS
                info.pointerType = PT_TOUCH;
                info.pointerFlags = POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN;
                return true;
            };
            RecordPointerDown(true, PT_TOUCH, 39);
            RecordPointerDown(true, PT_TOUCH, 40);
            EvaluateCandidateConcurrency();
            if (confirmedMaxConcurrent != 0) {
                Console.WriteLine("FAIL: mismatched pointerId must not be confirmed");
                return 1;
            }

            // Test 9: One valid and one invalid candidate: confirmedMaxConcurrent must be 1, not 2
            ResetState();
            queryPointerInfo = delegate(uint id, out POINTER_INFO info) {
                info = new POINTER_INFO();
                info.pointerId = id;
                info.sourceDevice = new IntPtr(0x999);
                if (id == 45) {
                    info.pointerType = PT_TOUCH;
                    info.pointerFlags = POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN;
                } else if (id == 46) {
                    // id 46 is invalid (UP flag set)
                    info.pointerType = PT_TOUCH;
                    info.pointerFlags = POINTER_FLAG_INCONTACT | POINTER_FLAG_UP;
                }
                return true;
            };
            RecordPointerDown(true, PT_TOUCH, 45);
            RecordPointerDown(true, PT_TOUCH, 46);
            EvaluateCandidateConcurrency();
            if (maxConcurrent != 2) {
                Console.WriteLine("FAIL: legacy maxConcurrent should be 2");
                return 1;
            }
            if (confirmedMaxConcurrent != 1) {
                Console.WriteLine("FAIL: 1 valid + 1 invalid must result in confirmedMaxConcurrent=1");
                return 1;
            }
            if (!activeIds.Contains(45) || activeIds.Contains(46)) {
                Console.WriteLine("FAIL: invalid pointer 46 should be pruned while 45 is kept");
                return 1;
            }

            // Test 10: Typed touch + pen concurrency confirmed
            ResetState();
            queryPointerInfo = delegate(uint id, out POINTER_INFO info) {
                info = new POINTER_INFO();
                info.pointerId = id;
                info.sourceDevice = new IntPtr(0x1234);
                info.ptPixelLocation.x = (int)id * 10;
                info.ptPixelLocation.y = (int)id * 20;
                if (id == 50) {
                    info.pointerType = PT_TOUCH;
                    info.pointerFlags = POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN;
                } else if (id == 51) {
                    info.pointerType = PT_PEN;
                    info.pointerFlags = POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN;
                }
                return true;
            };
            RecordPointerDown(true, PT_TOUCH, 50);
            RecordPointerDown(true, PT_PEN, 51);
            EvaluateCandidateConcurrency();
            if (!touchPenConcurrent || !confirmedTouchPenConcurrent) {
                Console.WriteLine("FAIL: typed touch+pen should be confirmed concurrent");
                return 1;
            }

            // Test 11: Dual touch concurrency confirmed
            ResetState();
            queryPointerInfo = delegate(uint id, out POINTER_INFO info) {
                info = new POINTER_INFO();
                info.pointerId = id;
                info.pointerType = PT_TOUCH;
                info.pointerFlags = POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN;
                info.sourceDevice = new IntPtr(0x5678);
                info.ptPixelLocation.x = (int)id * 5;
                info.ptPixelLocation.y = (int)id * 6;
                return true;
            };
            RecordPointerDown(true, PT_TOUCH, 60);
            RecordPointerDown(true, PT_TOUCH, 61);
            EvaluateCandidateConcurrency();
            if (maxConcurrent != 2 || confirmedMaxConcurrent != 2) {
                Console.WriteLine("FAIL: dual touch should produce confirmedMaxConcurrent=2");
                return 1;
            }

            // Test 12: WM_POINTERCAPTURECHANGED cleans up active pointer
            ResetState();
            RecordPointerDown(true, PT_TOUCH, 70);
            if (activeIds.Count != 1) return 1;
            WndProc(IntPtr.Zero, WM_POINTERCAPTURECHANGED, (IntPtr)70, IntPtr.Zero);
            if (activeIds.Count != 0) {
                Console.WriteLine("FAIL: WM_POINTERCAPTURECHANGED should clear pointer from activeIds");
                return 1;
            }

            // Test 13: WM_POINTERUPDATE cancels pointer on CANCELED flag
            ResetState();
            RecordPointerDown(true, PT_TOUCH, 80);
            queryPointerInfo = delegate(uint id, out POINTER_INFO info) {
                info = new POINTER_INFO();
                info.pointerId = id;
                info.pointerType = PT_TOUCH;
                info.pointerFlags = POINTER_FLAG_CANCELED;
                return true;
            };
            WndProc(IntPtr.Zero, WM_POINTERUPDATE, (IntPtr)80, IntPtr.Zero);
            if (activeIds.Count != 0) {
                Console.WriteLine("FAIL: WM_POINTERUPDATE with CANCELED should clear pointer");
                return 1;
            }

            // Test 14: A visible window is not ready while another window owns input.
            IntPtr probeWindow = new IntPtr(1);
            IntPtr otherWindow = new IntPtr(2);
            if (!IsInputWindowReady(probeWindow, probeWindow, new IntPtr[] { probeWindow, probeWindow }) ||
                IsInputWindowReady(probeWindow, otherWindow, new IntPtr[] { probeWindow }) ||
                IsInputWindowReady(probeWindow, probeWindow, new IntPtr[] { probeWindow, otherWindow }) ||
                IsInputWindowReady(probeWindow, probeWindow, new IntPtr[0]) ||
                IsInputWindowReady(IntPtr.Zero, IntPtr.Zero, new IntPtr[] { IntPtr.Zero })) {
                Console.WriteLine("FAIL: input window readiness");
                return 1;
            }

            Console.WriteLine("SELFCHECK OK");
            return 0;
        }

        int seconds = 30;
        int screenIndex = -2;
        string logSuffix = "probe";

        if (args.Length > 0) int.TryParse(args[0], out seconds);
        if (args.Length > 1) int.TryParse(args[1], out screenIndex);
        if (args.Length > 2) logSuffix = args[2];

        string logFile = string.Format("touch-events-{0}.log", logSuffix);
        logWriter = new StreamWriter(new FileStream(logFile, FileMode.Create, FileAccess.Write, FileShare.ReadWrite));
        Log(string.Format("MultiTouchProbe started: duration={0}s screenIndex={1} log={2}", seconds, screenIndex, logFile));

        int x = 0, y = 0, w = 1920, h = 1080;
        if (screenIndex == -2) {
            x = GetSystemMetrics(76); // SM_XVIRTUALSCREEN
            y = GetSystemMetrics(77); // SM_YVIRTUALSCREEN
            w = GetSystemMetrics(78); // SM_CXVIRTUALSCREEN
            h = GetSystemMetrics(79); // SM_CYVIRTUALSCREEN
        } else {
            w = GetSystemMetrics(0); // SM_CXSCREEN
            h = GetSystemMetrics(1); // SM_CYSCREEN
        }

        Log(string.Format("Target desktop rect: x={0} y={1} w={2} h={3}", x, y, w, h));

        wndProcDelegate = new WndProcDelegate(WndProc);
        IntPtr hInst = GetModuleHandle(null);

        WNDCLASS wc = new WNDCLASS();
        wc.style = 0;
        wc.lpfnWndProc = wndProcDelegate;
        wc.cbClsExtra = 0;
        wc.cbWndExtra = 0;
        wc.hInstance = hInst;
        wc.hIcon = IntPtr.Zero;
        wc.hCursor = IntPtr.Zero;
        wc.hbrBackground = IntPtr.Zero;
        wc.lpszMenuName = null;
        wc.lpszClassName = "MultiTouchProbeWindow";

        RegisterClass(ref wc);

        IntPtr hwnd = CreateWindowEx(
            WS_EX_TOPMOST | WS_EX_LAYERED | WS_EX_TOOLWINDOW,
            "MultiTouchProbeWindow",
            "MultiTouchProbe",
            WS_POPUP | WS_VISIBLE,
            x, y, w, h,
            IntPtr.Zero, IntPtr.Zero, hInst, IntPtr.Zero
        );

        if (hwnd == IntPtr.Zero) {
            Log("Failed to create probe window!");
            return 1;
        }

        SetLayeredWindowAttributes(hwnd, 0, 1, LWA_ALPHA);
        RegisterTouchWindow(hwnd, 1);
        ShowWindow(hwnd, 1);
        UpdateWindow(hwnd);

        if (!PrepareInputWindow(hwnd, x, y, w, h)) {
            DestroyWindow(hwnd);
            return 1;
        }

        Log(string.Format("Probe window created hwnd={0}, listening for touch events...", hwnd));

        System.Threading.Timer timer = new System.Threading.Timer((state) => {
            Log(string.Format("Timer expired ({0}s), exiting probe loop...", seconds));
            PostMessage(hwnd, WM_CLOSE, IntPtr.Zero, IntPtr.Zero);
        }, null, seconds * 1000, Timeout.Infinite);

        MSG msg;
        while (GetMessage(out msg, IntPtr.Zero, 0, 0)) {
            TranslateMessage(ref msg);
            DispatchMessage(ref msg);
        }
        GC.KeepAlive(timer);
        timer.Dispose();

        DestroyWindow(hwnd);

        string summary = string.Format(
            "SUMMARY downs={0} maxConcurrent={1} distinctIds={2} penDowns={3} penMaxConcurrent={4} penDistinctIds={5} touchPenConcurrent={6} confirmedMaxConcurrent={7} confirmedTouchPenConcurrent={8}",
            totalDowns, maxConcurrent, distinctIds.Count, penDowns, maxPenConcurrent,
            distinctPenIds.Count, touchPenConcurrent, confirmedMaxConcurrent, confirmedTouchPenConcurrent);
        Log(summary);
        Console.WriteLine(summary);

        if (logWriter != null) {
            logWriter.Flush();
            logWriter.Close();
        }

        return 0;
    }
}
