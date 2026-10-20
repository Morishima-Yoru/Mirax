Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class FG2 {
  [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern bool SetCursorPos(int X, int Y);
  [DllImport("user32.dll")] static extern bool GetCursorPos(out POINT p);
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
  public static IntPtr GetFG() { return GetForegroundWindow(); }
  public static string GetTitle(IntPtr h) {
    var sb = new StringBuilder(512);
    GetWindowText(h, sb, 512);
    return sb.ToString();
  }
  public static void SetPos(int x, int y) { SetCursorPos(x, y); }
  public static POINT GetPos() {
    POINT p;
    GetCursorPos(out p);
    return p;
  }
}
"@

$fg = [FG2]::GetFG()
Write-Host "fg='$([FG2]::GetTitle($fg))'"

# Candidate click points (virtual screen coords)
$candidates = @(
  @{name="title-center"; x=2192; y=920},
  @{name="row-center";    x=2192; y=960},
  @{name="subtitle";      x=2192; y=1000},
  @{name="icon";          x=2050; y=960}
)

foreach ($c in $candidates) {
  [FG2]::SetPos($c.x, $c.y)
  Start-Sleep -Milliseconds 100
  $p = [FG2]::GetPos()
  $rx = $p.X - 2016
  $ry = $p.Y - 0
  Write-Host ("{0}: target=({1},{2}) actual=({3},{4}) paneloffset=({5},{6})" -f $c.name, $c.x, $c.y, $p.X, $p.Y, $rx, $ry)
}

# Draw crosshair screenshot at row-center
[FG2]::SetPos(2192, 960)
Start-Sleep -Milliseconds 100
$p = [FG2]::GetPos()
$rx = $p.X - 2016; $ry = $p.Y
$shot = [System.Drawing.Bitmap]::new(864, 1692)
$g = [System.Drawing.Graphics]::FromImage($shot)
$g.CopyFromScreen(2016, 0, 0, 0, $shot.Size)
$pen = New-Object System.Drawing.Pen([System.Drawing.Color]::Red, 2)
$g.DrawRectangle($pen, $rx-20, $ry-25, 40, 50)
$g.Drawline($pen, $rx-30, $ry, $rx+30, $ry)
$g.Drawline($pen, $rx, $ry-35, $rx, $ry+35)
$g.Dispose()
$shot.Save("C:/Users/Leviana/Documents/workspace/mirax-touch/scripts/crosshair.png")
$shot.Dispose()
Write-Host "saved crosshair.png (red box = click point, panel offset $rx,$ry)"