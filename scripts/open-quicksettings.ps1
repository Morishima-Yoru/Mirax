Add-Type -AssemblyName UIAutomationClient
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class QuickActionClicker {
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint x, uint y, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  public static void LeftClick(int x, int y) {
    SetCursorPos(x, y);
    System.Threading.Thread.Sleep(80);
    mouse_event(0x02, 0, 0, 0, UIntPtr.Zero);
    System.Threading.Thread.Sleep(50);
    mouse_event(0x04, 0, 0, 0, UIntPtr.Zero);
  }
}
'@

$tray = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst(
    [System.Windows.Automation.TreeScope]::Children,
    [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::ClassNameProperty, 'Shell_TrayWnd')
)

$btn = $tray.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) |
    Where-Object { $_.Current.Name -match 'Network.*Homie' } |
    Select-Object -First 1

if ($btn) {
    $r = $btn.Current.BoundingRectangle
    $cx = [int]($r.X + ($r.Width / 2))
    $cy = [int]($r.Y + ($r.Height / 2))
    Write-Host "Clicking network button at $cx, $cy"
    [QuickActionClicker]::LeftClick($cx, $cy)
} else {
    Write-Host "Network button not found in tray"
}
