# cast-state-oct16.ps1
param(
    [switch]$EnableInput
)

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

Add-Type @'
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class CastInputState {
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr p, EnumProc cb, IntPtr l);
    [DllImport("user32.dll")] public static extern IntPtr FindWindow(string cls, string title);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern int GetSystemMetrics(int nIndex);
    public static List<IntPtr> Get(string cls) {
        var list = new List<IntPtr>();
        IntPtr p = FindWindow(cls, null);
        if (p != IntPtr.Zero) {
            list.Add(p);
            EnumChildWindows(p, (h, l) => { if (IsWindowVisible(h)) list.Add(h); return true; }, IntPtr.Zero);
        }
        return list;
    }
}
'@

$hwnds = [CastInputState]::Get("ControlCenterWindow")
$found = $false
foreach ($h in $hwnds) {
    $el = [System.Windows.Automation.AutomationElement]::FromHandle($h)
    if ($el) {
        $cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::CheckBox)
        $boxes = $el.FindAll([System.Windows.Automation.TreeScope]::Descendants, $cond)
        foreach ($box in $boxes) {
            $name = $box.Current.Name
            if ($name -match "Allow.*input|允許.*輸入|輸入|input") {
                $found = $true
                Write-Host "Found checkbox: '$name'"
                $pattern = $box.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern) -as [System.Windows.Automation.TogglePattern]
                if ($pattern) {
                    Write-Host "Current ToggleState: $($pattern.Current.ToggleState)"
                    if ($EnableInput -and $pattern.Current.ToggleState -ne [System.Windows.Automation.ToggleState]::On) {
                        $pattern.Toggle()
                        Start-Sleep -Milliseconds 400
                        Write-Host "After toggle: $($pattern.Current.ToggleState)"
                    }
                }
            }
        }
    }
}

if (-not $found) {
    Write-Host "ALLOW_NOT_FOUND"
}

# Take screenshot
$width = [CastInputState]::GetSystemMetrics(0)
$height = [CastInputState]::GetSystemMetrics(1)
$bmp = New-Object System.Drawing.Bitmap $width, $height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen(0, 0, 0, 0, [System.Drawing.Size]::new($width, $height))
$screenshotPath = Join-Path $PSScriptRoot "cast-panel.png"
$bmp.Save($screenshotPath)
$g.Dispose()
$bmp.Dispose()
Write-Host "Screenshot saved to $screenshotPath"
