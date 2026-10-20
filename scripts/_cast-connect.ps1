Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Chord3 {
  [DllImport("user32.dll", CharSet = CharSet.Auto)]
  public static extern IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll")]
  public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")]
  public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")]
  public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")]
  public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")]
  public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")]
  public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  public static void Do() {
    IntPtr fg = GetForegroundWindow();
    IntPtr tray = FindWindow("Shell_TrayWnd", null);
    IntPtr target = tray != IntPtr.Zero ? tray : fg;
    uint fgT = GetWindowThreadProcessId(fg, IntPtr.Zero);
    uint targetT = GetWindowThreadProcessId(target, IntPtr.Zero);
    uint me = GetCurrentThreadId();
    AttachThreadInput(me, fgT, true);
    if (targetT != me) AttachThreadInput(me, targetT, true);
    SetForegroundWindow(target);
    keybd_event(0x5B, 0, 0, UIntPtr.Zero);
    keybd_event(0x5B, 0, 0x0002, UIntPtr.Zero);
    keybd_event(0x4B, 0, 0, UIntPtr.Zero);
    keybd_event(0x4B, 0, 0x0002, UIntPtr.Zero);
    System.Console.WriteLine("chord fg=" + fg.ToString() + " tray=" + tray.ToString() + " target=" + target.ToString());
  }
}
"@

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$deadline = $sw.ElapsedMilliseconds + 60000
$clicked = $false
while ($sw.ElapsedMilliseconds -lt $deadline -and -not $clicked) {
  Start-Sleep -Seconds 2
  $ccw = [System.Windows.Automation.AutomationElement]::FromHandle([Chord3]::GetForegroundWindow())
  if ($ccw -eq $null) { Write-Host "no fg element"; continue }
  $all = $ccw.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
  $row = $all | Where-Object { $_.Current.Name -match "TrebleDroid" } | Select-Object -First 1
  if ($row -ne $null) {
    Write-Host "FOUND row: $($row.Current.Name) rect=$($row.Current.BoundingRectangle)"
    $cp = $row.GetPattern([System.Windows.Automation.InvokePattern]::Pattern)
    if ($cp -ne $null) { $cp.Invoke(); $clicked = $true; Write-Host "INVOKED via InvokePattern" }
    else { Write-Host "no invoke pattern on row" }
  } else {
    $names = ($all | Select-Object -First 20 | ForEach-Object { $_.Current.Name }) -join " || "
    Write-Host "poll $($sw.ElapsedMilliseconds)ms: no TrebleDroid. names: $names"
  }
}
$sw.Stop()
Write-Host "clicked=$clicked"