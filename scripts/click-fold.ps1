# Opens Win+K and clicks the Miracast sink row wherever the cast window landed.
# Coordinates come from the row itself, mapped across the virtual desktop,
# so an extra monitor does not reuse an offset measured on the previous one.
#
# Usage:
#   .\scripts\click-fold.ps1
#   .\scripts\click-fold.ps1 -DeviceMatch "Phh-Treble"
#   .\scripts\click-fold.ps1 -DeviceMatch "Renathan.*Z\s*Fold\s*5"
param(
    [string]$DeviceMatch = "Phh-Treble|Renathan.*Z\s*Fold\s*5|Fold",
    [string]$Serial = ""
)
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class CastClick {
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern int GetSystemMetrics(int i);
  [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] inputs, int cb);
  public struct RECT { public int L, T, R, B; }
  [StructLayout(LayoutKind.Sequential)]
  public struct MOUSEINPUT {
    public int dx, dy;
    public uint mouseData, dwFlags, time;
    public UIntPtr dwExtraInfo;
  }
  [StructLayout(LayoutKind.Sequential)]
  public struct INPUT {
    public uint type;
    public MOUSEINPUT mi;
  }
  public static string Title(IntPtr h) {
    var sb = new StringBuilder(512);
    GetWindowText(h, sb, 512);
    return sb.ToString();
  }
  public static string ClassName(IntPtr h) {
    var sb = new StringBuilder(256);
    GetClassName(h, sb, 256);
    return sb.ToString();
  }
  public static void Chord() {
    // Always route Win+K through the shell tray. Cursor / browsers / IDE
    // windows often swallow the chord when they keep foreground focus.
    IntPtr fg = GetForegroundWindow();
    string title = Title(fg);
    IntPtr tray = FindWindow("Shell_TrayWnd", null);
    IntPtr target = tray != IntPtr.Zero ? tray : fg;
    Console.WriteLine("chord fg=" + title + " target=" + Title(target));
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
  public static void Click(int x, int y) {
    int vx = GetSystemMetrics(76);
    int vy = GetSystemMetrics(77);
    int vw = GetSystemMetrics(78);
    int vh = GetSystemMetrics(79);
    int ax = (int)Math.Round((x - vx) * 65535.0 / Math.Max(vw - 1, 1));
    int ay = (int)Math.Round((y - vy) * 65535.0 / Math.Max(vh - 1, 1));
    uint move = 0x0001 | 0x8000 | 0x4000;
    uint down = 0x0002 | 0x8000 | 0x4000;
    uint up = 0x0004 | 0x8000 | 0x4000;
    INPUT[] one = new INPUT[1];
    one[0].type = 0;
    one[0].mi.dx = ax;
    one[0].mi.dy = ay;
    one[0].mi.dwFlags = move;
    SendInput(1, one, Marshal.SizeOf(typeof(INPUT)));
    System.Threading.Thread.Sleep(40);
    one[0].mi.dwFlags = down;
    SendInput(1, one, Marshal.SizeOf(typeof(INPUT)));
    System.Threading.Thread.Sleep(30);
    one[0].mi.dwFlags = up;
    SendInput(1, one, Marshal.SizeOf(typeof(INPUT)));
  }
}
"@

# -4 is PER_MONITOR_AWARE_V2. Call it before any window measurement.
[void][CastClick]::SetProcessDpiAwarenessContext([IntPtr]::new(-4))
$vx = [CastClick]::GetSystemMetrics(76)
$vy = [CastClick]::GetSystemMetrics(77)
$vw = [CastClick]::GetSystemMetrics(78)
$vh = [CastClick]::GetSystemMetrics(79)
Write-Host "virtual desktop $vx,$vy ${vw}x${vh}"

Add-Type @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public class CastWindows {
  public static List<IntPtr> Hits = new List<IntPtr>();
  delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
  static bool IsCast(IntPtr h) {
    var cb = new StringBuilder(256);
    GetClassName(h, cb, 256);
    var tb = new StringBuilder(512);
    GetWindowText(h, tb, 512);
    string cls = cb.ToString();
    string title = tb.ToString();
    return cls == "ControlCenterWindow"
      || title.IndexOf("Quick settings", StringComparison.OrdinalIgnoreCase) >= 0;
  }
  public static void Scan() {
    Hits.Clear();
    EnumWindows((h, l) => {
      if (IsWindowVisible(h) && IsCast(h)) Hits.Add(h);
      return true;
    }, IntPtr.Zero);
    IntPtr fg = GetForegroundWindow();
    if (fg != IntPtr.Zero && IsCast(fg) && !Hits.Contains(fg)) Hits.Add(fg);
  }
}
"@

function Open-Cast {
    [CastWindows]::Scan()
    if ([CastWindows]::Hits.Count -gt 0) {
        Write-Host "cast already open"
        return
    }
    [CastClick]::Chord()
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 200
        [CastWindows]::Scan()
        if ([CastWindows]::Hits.Count -gt 0) { return }
    }
    Write-Host "Win+K chord missed; opening connectable-devices settings"
    Start-Process "explorer.exe" "ms-settings-connectabledevices:devicediscovery"
    for ($i = 0; $i -lt 50; $i++) {
        Start-Sleep -Milliseconds 200
        [CastWindows]::Scan()
        if ([CastWindows]::Hits.Count -gt 0) { return }
    }
}

function Walk-Named($el, $depth, $acc) {
    if ($null -eq $el -or $depth -gt 14) { return }
    $name = ""
    $cls = ""
    try { $name = [string]$el.Current.Name } catch {}
    try { $cls = [string]$el.Current.ClassName } catch {}
    $r = $el.Current.BoundingRectangle
    if ($name -and $r.Width -gt 8 -and $r.Height -gt 8) {
        $acc.Add([pscustomobject]@{
            Name = $name; Class = $cls; Depth = $depth
            X = [int]$r.X; Y = [int]$r.Y; W = [int]$r.Width; H = [int]$r.Height
        }) | Out-Null
    }
    $child = $null
    try { $child = [System.Windows.Automation.TreeWalker]::RawViewWalker.GetFirstChild($el) } catch {}
    while ($child) {
        Walk-Named $child ($depth + 1) $acc
        try { $child = [System.Windows.Automation.TreeWalker]::RawViewWalker.GetNextSibling($child) } catch { break }
    }
}

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq "AsTask" -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq "IAsyncOperation``1"
})[0]
function Await-WinRT($asyncOp, $resultType) {
    $method = $asTaskGeneric.MakeGenericMethod($resultType)
    $task = $method.Invoke($null, @($asyncOp))
    $task.Wait(-1) | Out-Null
    return $task.Result
}
function Find-FoldInShot($path) {
    $hits = @()
    if (-not (Test-Path $path)) { return $hits }
    $null = [Windows.Storage.StorageFile, Windows.Foundation, ContentType=WindowsRuntime]
    $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType=WindowsRuntime]
    $null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime]
    $file = Await-WinRT ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
    $stream = Await-WinRT ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $decoder = Await-WinRT ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap = Await-WinRT ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
    if (-not $engine) { Write-Host "ocr engine unavailable"; return $hits }
    $result = Await-WinRT ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
    foreach ($line in $result.Lines) {
        Write-Host "ocr line: $($line.Text)"
        if ($line.Text -notmatch $DeviceMatch) { continue }
        $x1 = [double]::MaxValue
        $y1 = [double]::MaxValue
        $x2 = 0.0
        $y2 = 0.0
        foreach ($word in $line.Words) {
            $b = $word.BoundingRect
            $wx = [double]$b.X
            $wy = [double]$b.Y
            $ww = [double]$b.Width
            $wh = [double]$b.Height
            if ($wx -lt $x1) { $x1 = $wx }
            if ($wy -lt $y1) { $y1 = $wy }
            if (($wx + $ww) -gt $x2) { $x2 = $wx + $ww }
            if (($wy + $wh) -gt $y2) { $y2 = $wy + $wh }
        }
        $rowTop = [double]$bitmap.PixelHeight * 0.62
        $rowBottom = [double]$bitmap.PixelHeight * 0.80
        if ($y1 -lt $rowTop -or $y1 -gt $rowBottom) { continue }
        $hits += [pscustomobject]@{
            Text = $line.Text
            X = [int]$x1
            Y = [int]$y1
            W = [int]($x2 - $x1)
            H = [int]($y2 - $y1)
        }
    }
    return $hits
}

function Save-Window($hwnd, $path) {
    $rect = New-Object CastClick+RECT
    [void][CastClick]::GetWindowRect($hwnd, [ref]$rect)
    $w = $rect.R - $rect.L
    $h = $rect.B - $rect.T
    if ($w -lt 20 -or $h -lt 20) { return $null }
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($rect.L, $rect.T, 0, 0, (New-Object System.Drawing.Size $w, $h))
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    return $rect
}

Open-Cast
if ([CastWindows]::Hits.Count -eq 0) { Write-Host "cast did not open"; exit 1 }
Write-Host "cast windows: $([CastWindows]::Hits.Count)"
foreach ($h in [CastWindows]::Hits) {
    $rect = New-Object CastClick+RECT
    [void][CastClick]::GetWindowRect($h, [ref]$rect)
    Write-Host ("hwnd={0} class={1} title={2} rect={3},{4} {5}x{6}" -f $h, [CastClick]::ClassName($h), [CastClick]::Title($h), $rect.L, $rect.T, ($rect.R-$rect.L), ($rect.B-$rect.T))
}

Write-Host "waiting for the device list"
Start-Sleep -Seconds 6
[CastWindows]::Scan()

$target = $null
$shot = Join-Path $PSScriptRoot "after-click.png"
$shotHwnd = [IntPtr]::Zero
foreach ($h in [CastWindows]::Hits) {
    $el = [System.Windows.Automation.AutomationElement]::FromHandle($h)
    $named = New-Object System.Collections.Generic.List[object]
    Walk-Named $el 0 $named
    foreach ($row in $named) {
        Write-Host ("named d={0} '{1}' [{2}] {3},{4} {5}x{6}" -f $row.Depth, $row.Name, $row.Class, $row.X, $row.Y, $row.W, $row.H)
        if ($row.Name -match $DeviceMatch) { $target = $row; $shotHwnd = $h }
    }
    $saved = Save-Window $h $shot
    if ($saved) {
        $shotHwnd = $h
        Write-Host "saved $shot from $($saved.L),$($saved.T) $($saved.R-$saved.L)x$($saved.B-$saved.T)"
    }
    if ($target) { break }
    $ocrWords = @(Find-FoldInShot $shot)
    if ($ocrWords.Count -gt 0) {
        $word = $ocrWords[0]
        $origin = New-Object CastClick+RECT
        [void][CastClick]::GetWindowRect($h, [ref]$origin)
        $target = [pscustomobject]@{
            Name = $word.Text
            X = $origin.L + [int]$word.X
            Y = $origin.T + [int]$word.Y
            W = [int]$word.W
            H = [int]$word.H
        }
        $shotHwnd = $h
        Write-Host "ocr '$($target.Name)' at $($target.X),$($target.Y)"
        break
    }
}

if (-not $target) {
    Write-Host "could not see device matching /$DeviceMatch/ in the cast window"
    exit 1
}
$cx = [int]($target.X + ($target.W / 2))
$cy = [int]($target.Y + ($target.H / 2))
Write-Host "click '$($target.Name)' at $cx,$cy"
$adb = if ($Serial) { @("-s", $Serial) } else { @() }
& adb @adb shell "echo '--- click ---' > /data/local/tmp/miracast.log" | Out-Null
[CastClick]::Click($cx, $cy)
Start-Sleep -Seconds 1
# Refresh the picture after the click so a miss is visible.
if ($shotHwnd -ne [IntPtr]::Zero) { [void](Save-Window $shotHwnd $shot) }

Write-Host "watching for a new RTSP session"
for ($i = 0; $i -lt 18; $i++) {
    Start-Sleep -Seconds 1
    $log = & adb @adb shell "logcat -d -t 80 -s MiraxWfdBeacon:V MiraxSink:V MiraxWfdBridge:V" 2>$null
    Write-Host "---- t=$i ----"
    $log | Select-String -Pattern "click|group|RTSP|PLAY|source|listen|arm|failed|success|UP |PENDING|DOWN" | Select-Object -Last 8 | ForEach-Object { Write-Host $_ }
    if ($log -match "PLAYING|EnteredPlay|ConnectionEstablished|group down") { break }
}
