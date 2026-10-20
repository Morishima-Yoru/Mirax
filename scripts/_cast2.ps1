Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class CastChord {
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
    System.Console.WriteLine("chord fg=" + fg.ToString() + " tray=" + tray.ToString() + " target='" + Title(target) + "'");
  }
}
"@

Write-Host "=== sending Win+K chord ==="
[CastChord]::Chord()

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$deadline = $sw.ElapsedMilliseconds + 60000
$clicked = $false
while ($sw.ElapsedMilliseconds -lt $deadline -and -not $clicked) {
  Start-Sleep -Seconds 2
  $root = [System.Windows.Automation.AutomationElement]::RootElement
  $all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
  $row = $all | Where-Object { $_.Current.Name -match "TrebleDroid" } | Select-Object -First 1
  if ($row -ne $null) {
    $br = $row.Current.BoundingRectangle
    Write-Host ("FOUND row: '{0}' rect={1},{2} {3}x{4}" -f $row.Current.Name, $br.Left, $br.Top, $br.Width, $br.Height)
    $cp = $row.GetPattern([System.Windows.Automation.InvokePattern]::Pattern)
    if ($cp -ne $null) {
      $cp.Invoke(); $clicked = $true; Write-Host "INVOKED via InvokePattern"
    } else {
      Write-Host "no invoke pattern"
    }
  } else {
    $names = ($all | Where-Object { $_.Current.Name -ne "" } | Select-Object -First 8 | ForEach-Object { "$($_.Current.ControlType):$($_.Current.Name)" }) -join " || "
    Write-Host "poll $($sw.ElapsedMilliseconds)ms: no TrebleDroid. $names"
  }
}
$sw.Stop()
Write-Host "clicked=$clicked"