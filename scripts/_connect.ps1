Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class CC {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint cExtraInfo, UIntPtr dwExtraInfo);
  public static string Title(IntPtr h){ var sb=new StringBuilder(512); GetWindowText(h,sb,512); return sb.ToString(); }
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
}
"@

$shotDir = "C:/Users/Leviana/Documents/workspace/mirax-touch/scripts"

# Step 0: open cast panel
Write-Host "=== opening cast panel ==="
[CC]::Chord()
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Milliseconds 200
  $t = [CC]::Title([CC]::GetForegroundWindow())
  if ($t -match "Quick settings") { Write-Host "panel open after $($i*200)ms"; break }
}

$t = [CC]::Title([CC]::GetForegroundWindow())
Write-Host "fg title='$t'"
if ($t -notmatch "Quick settings") { Write-Host "FAIL: panel not open"; exit 1 }

# Step 1: capture panel
$shot = [System.Drawing.Bitmap]::new(864, 1692)
$g = [System.Drawing.Graphics]::FromImage($shot)
$g.CopyFromScreen(2016, 0, 0, 0, $shot.Size)
$g.Dispose()
$shot.Save("$shotDir/connect-panel.png")
$shot.Dispose()
Write-Host "saved connect-panel.png"

# Step 2: click device row center (2192, 960)
$cx = 2192; $cy = 960
Write-Host "clicking at ($cx, $cy)"
[CC]::mouse_event(0x0002 -bor 0x0001, [uint]$cx, [uint]$cy, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 50
[CC]::mouse_event(0x0004, [uint]$cx, [uint]$cy, 0, [UIntPtr]::Zero)
Start-Sleep -Seconds 3

$t2 = [CC]::Title([CC]::GetForegroundWindow())
Write-Host "after click fg='$t2'"

$shot2 = [System.Drawing.Bitmap]::new(864, 1692)
$g2 = [System.Drawing.Graphics]::FromImage($shot2)
$g2.CopyFromScreen(2016, 0, 0, 0, $shot2.Size)
$g2.Dispose()
$shot2.Save("$shotDir/connect-after.png")
$shot2.Dispose()
Write-Host "saved connect-after.png"