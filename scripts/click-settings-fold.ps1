Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class DeviceClicker {
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint x, uint y, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  public static void Click(int x, int y) {
    SetCursorPos(x, y);
    System.Threading.Thread.Sleep(80);
    mouse_event(0x02, 0, 0, 0, UIntPtr.Zero);
    System.Threading.Thread.Sleep(40);
    mouse_event(0x04, 0, 0, 0, UIntPtr.Zero);
  }
}
'@

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

if (-not $win) {
    Write-Host "Settings window not found"
    exit 1
}

# Bring Settings window to foreground
[DeviceClicker]::SetForegroundWindow([IntPtr]$win.Current.NativeWindowHandle)
Start-Sleep -Milliseconds 200

# Look for Renathan's Z Fold5 under Wireless displays & docks
$items = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
$target = $null
foreach ($it in $items) {
    if ($it.Current.Name -match "Renathan.*Z.*Fold5.*Category Cell phone") {
        $target = $it
        Write-Host "Found target element: $($it.Current.Name)"
        break
    }
}

if ($target) {
    $r = $target.Current.BoundingRectangle
    $cx = [int]($r.X + $r.Width / 2)
    $cy = [int]($r.Y + $r.Height / 2)
    Write-Host "Clicking target at $cx, $cy"
    [DeviceClicker]::Click($cx, $cy)
} else {
    Write-Host "Target not found"
}
