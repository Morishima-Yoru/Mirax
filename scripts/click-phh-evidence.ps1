# Click Phh-Treble in Win+K cast UI (no Fold-only Y-band filter).
param([string]$DeviceMatch = "Phh|Treble|vanilla")
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class PhhClick {
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
  [DllImport("user32.dll")] public static extern int GetSystemMetrics(int i);
  [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] inputs, int cb);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  public struct RECT { public int L, T, R, B; }
  [StructLayout(LayoutKind.Sequential)]
  public struct MOUSEINPUT {
    public int dx, dy; public uint mouseData, dwFlags, time; public UIntPtr dwExtraInfo;
  }
  [StructLayout(LayoutKind.Sequential)]
  public struct INPUT { public uint type; public MOUSEINPUT mi; }
  public static IntPtr CastHwnd;
  public static void Chord() {
    IntPtr tray = FindWindow("Shell_TrayWnd", null);
    IntPtr fg = GetForegroundWindow();
    IntPtr target = tray != IntPtr.Zero ? tray : fg;
    uint fgThread = GetWindowThreadProcessId(fg, IntPtr.Zero);
    uint me = GetCurrentThreadId();
    AttachThreadInput(me, fgThread, true);
    SetForegroundWindow(target);
    keybd_event(0x5B, 0, 0, UIntPtr.Zero);
    keybd_event(0x4B, 0, 0, UIntPtr.Zero);
    System.Threading.Thread.Sleep(80);
    keybd_event(0x4B, 0, 2, UIntPtr.Zero);
    keybd_event(0x5B, 0, 2, UIntPtr.Zero);
    AttachThreadInput(me, fgThread, false);
  }
  public static void FindCast() {
    CastHwnd = IntPtr.Zero;
    EnumWindows((h, l) => {
      var sb = new StringBuilder(256);
      GetClassName(h, sb, 256);
      if (sb.ToString() == "ControlCenterWindow" && IsWindowVisible(h)) CastHwnd = h;
      return true;
    }, IntPtr.Zero);
  }
  public static void Click(int x, int y) {
    int vx = GetSystemMetrics(76), vy = GetSystemMetrics(77);
    int vw = GetSystemMetrics(78), vh = GetSystemMetrics(79);
    int ax = (int)Math.Round((x - vx) * 65535.0 / Math.Max(vw - 1, 1));
    int ay = (int)Math.Round((y - vy) * 65535.0 / Math.Max(vh - 1, 1));
    INPUT[] one = new INPUT[1];
    one[0].type = 0;
    one[0].mi.dx = ax; one[0].mi.dy = ay;
    one[0].mi.dwFlags = 0x0001 | 0x8000 | 0x4000;
    SendInput(1, one, Marshal.SizeOf(typeof(INPUT)));
    System.Threading.Thread.Sleep(40);
    one[0].mi.dwFlags = 0x0002 | 0x8000 | 0x4000;
    SendInput(1, one, Marshal.SizeOf(typeof(INPUT)));
    System.Threading.Thread.Sleep(30);
    one[0].mi.dwFlags = 0x0004 | 0x8000 | 0x4000;
    SendInput(1, one, Marshal.SizeOf(typeof(INPUT)));
  }
}
"@
[void][PhhClick]::SetProcessDpiAwarenessContext([IntPtr]::new(-4))
[PhhClick]::FindCast()
if ([PhhClick]::CastHwnd -eq [IntPtr]::Zero) {
    [PhhClick]::Chord()
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 200
        [PhhClick]::FindCast()
        if ([PhhClick]::CastHwnd -ne [IntPtr]::Zero) { break }
    }
}
if ([PhhClick]::CastHwnd -eq [IntPtr]::Zero) {
    Write-Host "cast did not open"
    exit 1
}
$rect = New-Object PhhClick+RECT
[void][PhhClick]::GetWindowRect([PhhClick]::CastHwnd, [ref]$rect)
$w = $rect.R - $rect.L
$h = $rect.B - $rect.T
Write-Host "cast $($rect.L),$($rect.T) ${w}x$h"
$path = Join-Path $PSScriptRoot "phh-evidence.png"
$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($rect.L, $rect.T, 0, 0, (New-Object System.Drawing.Size $w, $h))
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq "AsTask" -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq "IAsyncOperation``1"
})[0]
function Await-WinRT($op, $t) {
    $m = $asTaskGeneric.MakeGenericMethod($t)
    $task = $m.Invoke($null, @($op))
    $task.Wait(-1) | Out-Null
    return $task.Result
}
$null = [Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
$file = Await-WinRT ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
$stream = Await-WinRT ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
$decoder = Await-WinRT ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bitmap = Await-WinRT ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
$result = Await-WinRT ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
$target = $null
foreach ($line in $result.Lines) {
    Write-Host "ocr: $($line.Text)"
    if ($line.Text -notmatch $DeviceMatch) { continue }
    $x1 = [double]::MaxValue; $y1 = [double]::MaxValue; $x2 = 0.0; $y2 = 0.0
    foreach ($word in $line.Words) {
        $b = $word.BoundingRect
        if ($b.X -lt $x1) { $x1 = $b.X }
        if ($b.Y -lt $y1) { $y1 = $b.Y }
        if (($b.X + $b.Width) -gt $x2) { $x2 = $b.X + $b.Width }
        if (($b.Y + $b.Height) -gt $y2) { $y2 = $b.Y + $b.Height }
    }
    $target = [pscustomobject]@{
        Text = $line.Text
        X = [int]$x1; Y = [int]$y1
        W = [int]($x2 - $x1); H = [int]($y2 - $y1)
    }
}
if (-not $target) {
    Write-Host "device not found in OCR"
    exit 2
}
$cx = $rect.L + [int]($target.X + $target.W / 2)
$cy = $rect.T + [int]($target.Y + $target.H / 2)
Write-Host "click $($target.Text) at $cx,$cy"
[PhhClick]::Click($cx, $cy)
Start-Sleep -Seconds 10
Write-Host "clicked"
