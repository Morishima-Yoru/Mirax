Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class ME {
  [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint cExtraInfo, UIntPtr dwExtraInfo);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("System.Drawing.dll")] public static extern bool GetCursorPos(out POINT p);
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
  public static string Title(IntPtr h){ var sb=new StringBuilder(512); GetWindowText(h,sb,512); return sb.ToString(); }
}
"@
$fg = [ME]::GetForegroundWindow()
Write-Host "fg='$([ME]::Title($fg))'"

$cx = 2192; $cy = 960
[System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($cx, $cy)
Start-Sleep -Milliseconds 200
[ME]::mouse_event(0x0002 -bor 0x0001, [uint]$cx, [uint]$cy, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 50
[ME]::mouse_event(0x0004, [uint]$cx, [uint]$cy, 0, [UIntPtr]::Zero)
Start-Sleep -Seconds 3
Write-Host "after click fg='$([ME]::Title([ME]::GetForegroundWindow()))'"