Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class CC {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public extern static IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
  [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] inputs, int cb);
  [DllImport("user32.dll")] public static extern int GetSystemMetrics(int i);
  public static string Title(IntPtr h){ var sb=new StringBuilder(512); GetWindowText(h,sb,512); return sb.ToString(); }
  public struct RECT { public int L, T, R, B; }
  [StructLayout(LayoutKind.Sequential)]
  public struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public UIntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)]
  public struct INPUT { public uint type; public MOUSEINPUT mi; }
  public static void Chord() {
    IntPtr fg = GetForegroundWindow();
    IntPtr tray = FindWindow("Shell_TrayWnd", null);
    IntPtr target = tray != IntPtr.Zero ? tray : fg;
    uint fgThread = GetWindowThreadProcessId(fg, IntPtr.Zero);
    uint targetThread = GetWindowThreadProcessId(target, IntPtr.Zero);
    uint me = GetCurrentThreadId();
    AttachThreadInput(me, fgThread, true);
    if (targetThread != fgThread) AttachThreadInput(me, targetThread, true);
    SetForegroundWindow(target);
    keybd_event(0x5B, 0, 0, UIntPtr.Zero);
    keybd_event(0x4B, 0, 0, UIntPtr.Zero);
    System.Threading.Thread.Sleep(80);
    keybd_event(0x4B, 0, 2, UIntPtr.Zero);
    keybd_event(0x5B, 0, 2, UIntPtr.Zero);
    if (targetThread != fgThread) AttachThreadInput(me, targetThread, false);
    AttachThreadInput(me, fgThread, false);
  }
  // Proven click method from click-fold.ps1 (absolute virtual desktop coords)
  public static void Click(int x, int y) {
    int vx = GetSystemMetrics(76);
    int vy = GetSystemMetrics(77);
    int vw = GetSystemMetrics(78);
    int vh = GetSystemMetrics(79);
    int ax = (int)Math.Round((x - vx) * 65535.0 / Math.Max(vw - 1, 1));
    int ay = (int)Math.Round((y - vy) * 65535.0 / Math.Max(vh - 1, 1));
    uint move = 0x0001 | 0x8000 | 0x4000;
    uint down = 0x0002 | 0x8000 | 0x4000;
    uint up = 0x0004 | 0x8000 | 0x4000;
    INPUT[] one = new INPUT[1];
    one[0].type = 0;
    one[0].mi.dx = ax; one[0].mi.dy = ay;
    one[0].mi.dwFlags = move;
    SendInput(1, one, Marshal.SizeOf(typeof(INPUT)));
    System.Threading.Thread.Sleep(40);
    one[0].mi.dwFlags = down;
    SendInput(1, one, Marshal.SizeOf(typeof(INPUT)));
    System.Threading.Thread.Sleep(30);
    one[0].mi.dwFlags = up;
    SendInput(1, one, Marshal.SizeOf(typeof(INPUT)));
    System.Console.WriteLine("clicked " + x + "," + y + " (ax=" + ax + ", ay=" + ay + ")");
  }
}
"@
[void][CC]::SetProcessDpiAwarenessContext([IntPtr]::new(-4))

$shotDir = "C:/Users/Leviana/Documents/workspace/mirax-touch/scripts"

# Verify panel is open
$t = [CC]::Title([CC]::GetForegroundWindow())
Write-Host "fg title='$t'"
if ($t -notmatch "Quick settings") {
  Write-Host "panel closed -> opening"
  [CC]::Chord()
  for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 200
    $t2 = [CC]::Title([CC]::GetForegroundWindow())
    if ($t2 -match "Quick settings") { Write-Host "panel open after $($i*200)ms"; break }
  }
  $t = [CC]::Title([CC]::GetForegroundWindow())
}
if ($t -notmatch "Quick settings") { Write-Host "FAIL: panel not open"; exit 1 }

# Capture before
$shot = [System.Drawing.Bitmap]::new(864, 1692)
$g = [System.Drawing.Graphics]::FromImage($shot)
$g.CopyFromScreen(2016, 0, 0, 0, $shot.Size)
$g.Dispose()
$shot.Save("$shotDir/pre-click.png")
$shot.Dispose()

# Click on the title text "TrebleDroid vanilla" at y=920
$cx = 2016 + 176
$cy = 0 + 920
Write-Host "clicking title at ($cx, $cy)"
[CC]::Click($cx, $cy)
Start-Sleep -Seconds 3

$t2 = [CC]::Title([CC]::GetForegroundWindow())
Write-Host "after click fg='$t2'"

$shot2 = [System.Drawing.Bitmap]::new(864, 1692)
$g2 = [System.Drawing.Graphics]::FromImage($shot2)
$g2.CopyFromScreen(2016, 0, 0, 0, $shot2.Size)
$g2.Dispose()
$shot2.Save("$shotDir/post-click.png")
$shot2.Dispose()
Write-Host "saved post-click.png"