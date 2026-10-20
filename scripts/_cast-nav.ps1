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
  public static string Title(IntPtr h) {
    var sb = new StringBuilder(512);
    GetWindowText(h, sb, 512); return sb.ToString();
  }
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
    System.Console.WriteLine("chord fg=" + Title(fg) + " tray=" + Title(tray));
  }
  public static void Key(byte vk, bool down) {
    keybd_event(vk, 0, down ? (uint)0 : (uint)2, UIntPtr.Zero);
  }
}
"@

Write-Host "=== Step 1: Win+K chord ==="
[CastClick]::Chord()
Start-Sleep -Seconds 4

$fg = [CastClick]::GetForegroundWindow()
$fgTitle = [CastClick]::Title($fg)
Write-Host "fg='$fgTitle'"

if ($fgTitle -notmatch "Quick settings") {
  Write-Host "ERROR: panel not open"
  exit 1
}

Write-Host "=== Step 2: navigate with Tab to device list ==="
for ($i = 0; $i -lt 15; $i++) {
  [CastClick]::Key(0x09, $true)
  [CastClick]::Key(0x09, $false)
  Start-Sleep -Milliseconds 100
}

Write-Host "=== Step 3: screenshot after tab ==="
$screenshot = [System.Drawing.Bitmap]::new(864, 1692)
$g = [System.Drawing.Graphics]::FromImage($screenshot)
$g.CopyFromScreen(2016, 0, 0, 0, $screenshot.Size)
$screenshot.Save("C:/Users/Leviana/Documents/workspace/mirax-touch/scripts/after-tab.png")
$screenshot.Dispose()
Write-Host "saved after-tab.png"