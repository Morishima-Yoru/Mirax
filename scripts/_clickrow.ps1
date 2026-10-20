Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class CC {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public extern static IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint cExtraInfo, UIntPtr dwExtraInfo);
  public static string Title(IntPtr h){ var sb=new StringBuilder(512); GetWindowText(h,sb,512); return sb.ToString(); }
}
"@

$shotDir = "C:/Users/Leviana/Documents/workspace/mirax-touch/scripts"

$t = [CC]::Title([CC]::GetForegroundWindow())
Write-Host "fg title='$t'"
if ($t -notmatch "Quick settings") { Write-Host "FAIL: panel not open"; exit 1 }

# Scan y=850-1100 for device row
$tag = [int]([System.Diagnostics.Stopwatch]::GetTimestamp() % 100000)
$shot = [System.Drawing.Bitmap]::new(864, 1692)
$g = [System.Drawing.Graphics]::FromImage($shot)
$g.CopyFromScreen(2016, 0, 0, 0, $shot.Size)
$g.Dispose()
$shot.Save("$shotDir/r3-$tag.png")
$shot.Dispose()

$bmp = New-Object System.Drawing.Bitmap("$shotDir/r3-$tag.png")
$bands = @()
$inBand = $false; $start = 0
for ($y = 850; $y -lt 1100; $y++) {
  $bright = 0
  for ($x = 60; $x -lt 700; $x++) {
    $p = $bmp.GetPixel($x, $y)
    if ($p.R -gt 110 -and $p.G -gt 110 -and $p.B -gt 110) { $bright++ }
  }
  if ($bright -gt 25) { if (-not $inBand) { $inBand = $true; $start = $y } }
  else { if ($inBand) { $inBand = $false; if (($y-$start) -gt 3) { $bands += @{y1=$start; y2=$y; cy=[int](($start+$y)/2)} } } }
}
if ($inBand) { $bands += @{y1=$start; y2=1100; cy=[int](($start+1100)/2)} }
Write-Host "bands in y=850-1100: $($bands.Count)"
$bands | ForEach-Object { Write-Host ("  y={0}-{1} cy={2}" -f $_.y1, $_.y2, $_.cy) }
$bmp.Dispose()

if ($bands.Count -gt 0) {
  $cx = 2016 + 176
  $cy = 0 + $bands[0].cy
  Write-Host "clicking at ($cx, $cy)"
  [CC]::mouse_event(0x0002 -bor 0x0001, [uint]$cx, [uint]$cy, 0, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 50
  [CC]::mouse_event(0x0004, [uint]$cx, [uint]$cy, 0, [UIntPtr]::Zero)
  Start-Sleep -Seconds 3
  $t2 = [CC]::Title([CC]::GetForegroundWindow())
  Write-Host "after click fg='$t2'"
} else {
  Write-Host "no device row found"
}