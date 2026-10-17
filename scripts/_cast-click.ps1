Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Runtime.InteropServices

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class CastClick {
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
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
}
"@
[void][CastClick]::SetProcessDpiAwarenessContext([IntPtr]::new(-4))

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$deadline = $sw.ElapsedMilliseconds + 90000
$clicked = $false
$chorded = $false
while ($sw.ElapsedMilliseconds -lt $deadline -and -not $clicked) {
  Start-Sleep -Seconds 2
  $root = [System.Windows.Automation.AutomationElement]::RootElement
  $all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
  $ccw = $all | Where-Object { $_.Current.Name -eq "Quick settings" } | Select-Object -First 1
  if ($ccw -eq $null) {
    if (-not $chorded) {
      Write-Host "panel closed -> sending Win+K chord"
      [CastClick]::Chord()
      $chorded = $true
    } else {
      Write-Host "poll $($sw.ElapsedMilliseconds)ms: panel still closed"
    }
    continue
  }
  $desc = $ccw.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
  $row = $desc | Where-Object { $_.Current.Name -match "TrebleDroid" } | Select-Object -First 1
  if ($row -ne $null) {
    $br = $row.Current.BoundingRectangle
    $cx = [int]($br.Left + $br.Width/2)
    $cy = [int]($br.Top + $br.Height/2)
    Write-Host ("FOUND row: '{0}' rect={1},{2} {3}x{4} center=({5},{6})" -f $row.Current.Name, $br.Left, $br.Top, $br.Width, $br.Height, $cx, $cy)
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($cx, $cy)
    Start-Sleep -Milliseconds 200
    [System.Windows.Forms.Control]::Click()
    $clicked = $true
    Write-Host "CLICKED at $cx,$cy"
  } else {
    $names = ($desc | Where-Object { $_.Current.Name -ne "" } | Select-Object -First 8 | ForEach-Object { "$($_.Current.ControlType):$($_.Current.Name)" }) -join " || "
    Write-Host "poll $($sw.ElapsedMilliseconds)ms: no TrebleDroid. $names"
  }
}
$sw.Stop()
Write-Host "clicked=$clicked"