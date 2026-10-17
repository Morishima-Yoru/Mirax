Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class TapRow {
  [DllImport("user2.dll")]
  public static extern bool SetCursorPos(int X, int Y);
  [DllImport("user32.dll")]
  public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint cExtraInfo, UIntPtr dwExtraInfo);
  [DllImport("user32.dll")]
  public static extern IntPtr GetForegroundWindow();
}
"@
[TapRow]::SetCursorPos(2446, 740)
[TapRow]::mouse_event(0x0002 -bor 0x0001, 2446, 740, 0, [UIntPtr]::Zero)
Start-Sleep -Seconds 1
Add-Type -AssemblyName System.Drawing
$fg = [TapRow]::GetForegroundWindow()
"fg=$($fg.ToString())"
$screenshot = [System.Drawing.Bitmap]::new(864, 1692)
$g = [System.Drawing.Graphics]::FromImage($screenshot)
$g.CopyFromScreen(2016, 0, 0, 0, $screenshot.Size)
$screenshot.Save("C:/Users/Leviana/Documents/workspace/mirax-touch/scripts/after-tap.png")
$screenshot.Dispose()
"saved after-tap.png"