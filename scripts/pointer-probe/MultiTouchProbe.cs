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
    static extern int GetSystemMetrics(int nIndex);

    [DllImport("user32.dll")]
    static extern bool SetLayeredWindowAttributes(IntPtr hwnd, uint crKey, byte bAlpha, uint dwFlags);

    [DllImport("user32.dll")]
    static extern bool RegisterTouchWindow(IntPtr hWnd, uint ulFlags);

    [DllImport("user32.dll")]
    static extern bool GetPointerType(uint pointerId, out uint pointerType);

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

    const uint PT_POINTER = 1;
    const uint PT_TOUCH = 2;
    const uint PT_PEN = 3;
    const uint PT_MOUSE = 4;

    static int totalDowns = 0;
    static int maxConcurrent = 0;
    static HashSet<uint> activeIds = new HashSet<uint>();
    static HashSet<uint> distinctIds = new HashSet<uint>();
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

    static IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam) {
        if (msg == WM_POINTERDOWN) {
            uint pId = (uint)((ulong)wParam & 0xFFFF);
            uint pType = 0;
            bool ok = GetPointerType(pId, out pType);
            int x = (short)((ulong)lParam & 0xFFFF);
            int y = (short)(((ulong)lParam >> 16) & 0xFFFF);
            string typeName = PointerTypeName(ok, pType);
            
            lock (lockObj) {
                totalDowns++;
                activeIds.Add(pId);
                distinctIds.Add(pId);
                if (activeIds.Count > maxConcurrent) {
                    maxConcurrent = activeIds.Count;
                }
            }
            Log(string.Format("POINTER-DOWN type={0} id={1} x={2} y={3} active={4}", typeName, pId, x, y, activeIds.Count));
            return IntPtr.Zero;
        }
        else if (msg == WM_POINTERUP) {
            uint pId = (uint)((ulong)wParam & 0xFFFF);
            uint pType = 0;
            bool ok = GetPointerType(pId, out pType);
            int x = (short)((ulong)lParam & 0xFFFF);
            int y = (short)(((ulong)lParam >> 16) & 0xFFFF);
            string typeName = PointerTypeName(ok, pType);
            
            lock (lockObj) {
                activeIds.Remove(pId);
            }
            Log(string.Format("POINTER-UP type={0} id={1} x={2} y={3} active={4}", typeName, pId, x, y, activeIds.Count));
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

    public static int Main(string[] args) {
        if (args.Length > 0 && args[0] == "--selfcheck") {
            if (PointerTypeName(false, PT_TOUCH) != "UNKNOWN" ||
                PointerTypeName(true, PT_TOUCH) != "TOUCH" ||
                PointerTypeName(true, PT_PEN) != "PEN") return 1;
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

        DestroyWindow(hwnd);

        string summary = string.Format("SUMMARY downs={0} maxConcurrent={1} distinctIds={2}", totalDowns, maxConcurrent, distinctIds.Count);
        Log(summary);
        Console.WriteLine(summary);

        if (logWriter != null) {
            logWriter.Flush();
            logWriter.Close();
        }

        return 0;
    }
}
