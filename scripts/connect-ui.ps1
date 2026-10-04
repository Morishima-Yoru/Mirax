Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class CastKeys {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  public static string Title(IntPtr h) {
    var sb = new StringBuilder(512);
    GetWindowText(h, sb, 512);
    return sb.ToString();
  }
  public static void Chord(byte vk) {
    IntPtr fg = GetForegroundWindow();
    uint fgThread = GetWindowThreadProcessId(fg, IntPtr.Zero);
    uint me = GetCurrentThreadId();
    AttachThreadInput(me, fgThread, true);
    SetForegroundWindow(fg);
    keybd_event(0x5B, 0, 0, UIntPtr.Zero);
    keybd_event(vk, 0, 0, UIntPtr.Zero);
    System.Threading.Thread.Sleep(60);
    keybd_event(vk, 0, 2, UIntPtr.Zero);
    keybd_event(0x5B, 0, 2, UIntPtr.Zero);
    AttachThreadInput(me, fgThread, false);
  }
}
"@

[CastKeys]::keybd_event(0x1B, 0, 0, [UIntPtr]::Zero)
[CastKeys]::keybd_event(0x1B, 0, 2, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 300
function Open-Cast {
    for ($try = 1; $try -le 3; $try++) {
        [CastKeys]::Chord(0x4B)
        for ($i = 0; $i -lt 15; $i++) {
            Start-Sleep -Milliseconds 150
            $hwnd = [CastKeys]::GetForegroundWindow()
            $title = [CastKeys]::Title($hwnd)
            if ($title -match "Quick|Cast") {
                $el = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
                Write-Host "opened try $try class=$($el.Current.ClassName) title=$title"
                return $el
            }
        }
        Write-Host "try $try fg=$([CastKeys]::Title([CastKeys]::GetForegroundWindow()))"
    }
    return $null
}

$opened = Open-Cast
if (-not $opened) {
    Write-Host "could not open cast"
    exit 1
}

Add-Type -AssemblyName System.Drawing
function Save-Cast([string]$name) {
    $hwnd = [CastKeys]::GetForegroundWindow()
    $title = [CastKeys]::Title($hwnd)
    $el = $null
    if ($title -match "Quick|Cast") {
        $el = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
    } else {
        $root = [System.Windows.Automation.AutomationElement]::RootElement
        $cond = New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::ClassNameProperty, "ControlCenterWindow")
        $el = $root.FindFirst([System.Windows.Automation.TreeScope]::Subtree, $cond)
    }
    if (-not $el) {
        Write-Host "$name closed fg=$title"
        return $false
    }
    $r = $el.Current.BoundingRectangle
    Write-Host "$name rect=$($r.X),$($r.Y) $($r.Width)x$($r.Height)"
    $w = [int]$r.Width
    $h = [int]$r.Height
    if ($w -lt 20 -or $h -lt 20) { return $true }
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen([int]$r.X, [int]$r.Y, 0, 0, (New-Object System.Drawing.Size $w, $h))
    $bmp.Save("$PSScriptRoot\$name.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
    return $true
}

Start-Sleep -Seconds 2
Save-Cast "cast2" | Out-Null
Start-Sleep -Seconds 5
Save-Cast "cast7" | Out-Null
Start-Sleep -Seconds 6
Save-Cast "cast13" | Out-Null
Write-Host "captures done"
