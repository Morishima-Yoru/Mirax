Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class CastClick {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] inputs, int cb);
  [DllImport("user32.dll")] public static extern int GetSystemMetrics(int i);
  public static string Title(IntPtr h) {
    var sb = new StringBuilder(512);
    GetWindowText(h, sb, 512); return sb.ToString();
  }
  public struct RECT { public int L, T, R, B; }
  [StructLayout(LayoutKind.Sequential)]
  public struct MOUSEINPUT {
    public int dx, dy;
    public uint mouseData, dwFlags, time;
    public UIntPtr dwExtraInfo;
  }
  [StructLayout(LayoutKind.Sequential)]
  public struct INPUT {
    public uint type;
    public MOUSEINPUT mi;
  }
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

$shotDir = "C:/Users/Leviana/Documents/workspace/mirax-touch/scripts"

# Capture before
$fg = [CastClick]::GetForegroundWindow()
$fgTitle = [CastClick]::Title($fg)
Write-Host "BEFORE: fg='$fgTitle'"
$shot = [System.Drawing.Bitmap]::new(864, 1692)
$g = [System.Drawing.Graphics]::FromImage($shot)
$g.CopyFromScreen(2016, 0, 0, 0, $shot.Size)
$shot.Save("$shotDir/before-click.png")
$shot.Dispose()

if ($fgTitle -notmatch "Quick settings") {
  Write-Host "ERROR: panel not open"
  exit 1
}

# Row center: title y=806-828, subtitle y=916-932, x=70-283
# Whole row vertical center = ~869, horizontal center = ~176
$cx = 2016 + 176
$cy = 0 + 869
Write-Host "clicking row center at ($cx, $cy)"
[CastClick]::Click($cx, $cy)

Start-Sleep -Seconds 3

# Capture after
$fg2 = [CastClick]::GetForegroundWindow()
$fg2Title = [CastClick]::Title($fg2)
Write-Host "AFTER: fg='$fg2Title'"
$shot2 = [System.Drawing.Bitmap]::new(864, 1692)
$g2 = [System.Drawing.Graphics]::FromImage($shot2)
$g2.CopyFromScreen(2016, 0, 0, 0, $shot2.Size)
$shot2.Save("$shotDir/after-click2.png")
$shot2.Dispose()