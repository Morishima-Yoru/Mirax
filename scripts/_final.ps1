Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class CC {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  public static string Title(IntPtr h){ var sb=new StringBuilder(512); GetWindowText(h,sb,512); return sb.ToString(); }
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
  }
}
"@

$shotDir = "C:/Users/Leviana/Documents/workspace/mirax-touch/scripts"

# ---- Step 0: open cast panel ----
Write-Host "=== opening cast panel ==="
[CC]::Chord()
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Milliseconds 200
  $t = [CC]::Title([CC]::GetForegroundWindow())
  if ($t -match "Quick settings") { Write-Host "panel open after $($i*200)ms"; break }
}

$t = [CC]::Title([CC]::GetForegroundWindow())
Write-Host "fg title='$t'"
if ($t -notmatch "Quick settings") { Write-Host "FAIL: panel not open"; exit 1 }

# ---- Step 1: capture panel immediately ----
$shot = [System.Drawing.Bitmap]::new(864, 1692)
$g = [System.Drawing.Graphics]::FromImage($shot)
$g.CopyFromScreen(2016, 0, 0, 0, $shot.Size)
$shot.Save("$shotDir/e2e-panel.png")
$g.Dispose()
$shot.Dispose()
Write-Host "saved e2e-panel.png"

# ---- Step 2: scan for device row ----
$bmp = New-Object System.Drawing.Bitmap("$shotDir/e2e-panel.png")
$bands = @()
$inBand = $false; $start = 0
for ($y = 0; $y -lt $bmp.Height; $y++) {
  $bright = 0
  for ($x = 60; $x -lt 700; $x++) {
    $p = $bmp.GetPixel($x, $y)
    if ($p.R -gt 110 -and $p.G -gt 110 -and $p.B -gt 110) { $bright++ }
  }
  if ($bright -gt 25) { if (-not $inBand) { $inBand = $true; $start = $y } }
  else { if ($inBand) { $inBand = $false; if (($y-$start) -gt 3) { $bands += @{y1=$start; y2=$y; cy=[int](($start+$y)/2)} } } }
}
if ($inBand) { $bands += @{y1=$start; y2=$bmp.Height; cy=[int](($start+$bmp.Height)/2)} }
$bands | ForEach-Object {
  $mX = 99999; $MX = -1
  for ($y = $_.y1; $y -lt $_.y2; $y++) {
    for ($x = 0; $x -lt $bmp.Width; $x++) {
      $p = $bmp.GetPixel($x, $y)
      if ($p.R -gt 110 -and $p.G -gt 110 -and $p.B -gt 110) { if ($x -lt $mX) {$mX=$x}; if ($x -gt $MX) {$MX=$x} }
    }
  }
  Write-Host ("BAND y={0}-{1} cy={2} x={3}-{4} cx={5}" -f $_.y1, $_.y2, $_.cy, $mX, $MX, [int](($mX+$MX)/2))
}

# ---- Step 3: click the row (cy=817, cx=176 from measurements) ----
$cx = 2016 + 176
$cy = 0 + 817
Write-Host "clicking at ($cx, $cy)"

Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($cx, $cy)
Start-Sleep -Milliseconds 200
[System.Windows.Forms.Control]::Click()
Start-Sleep -Seconds 3

$t2 = [CC]::Title([CC]::GetForegroundWindow())
Write-Host "after click fg='$t2'"

$shot2 = [System.Drawing.Bitmap]::new(864, 1692)
$g2 = [System.Drawing.Graphics]::FromImage($shot2)
$g2.CopyFromScreen(2016, 0, 0, 0, $shot2.Size)
$shot2.Save("$shotDir/e2e-after.png")
$g2.Dispose()
$shot2.Dispose()
Write-Host "saved e2e-after.png"
$bmp.Dispose()