Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Chord2 {
  [DllImport("user32.dll", CharSet = CharSet.Auto)]
  public static extern IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll")]
  public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")]
  public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")]
  public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")]
  public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")]
  public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")]
  public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  public static void Do() {
    IntPtr fg = GetForegroundWindow();
    IntPtr tray = FindWindow("Shell_TrayWnd", null);
    IntPtr target = tray != IntPtr.Zero ? tray : fg;
    uint fgT = GetWindowThreadProcessId(fg, IntPtr.Zero);
    uint targetT = GetWindowThreadProcessId(target, IntPtr.Zero);
    uint me = GetCurrentThreadId();
    AttachThreadInput(me, fgT, true);
    if (targetT != me) AttachThreadInput(me, targetT, true);
    SetForegroundWindow(target);
    keybd_event(0x5B, 0, 0, UIntPtr.Zero);
    keybd_event(0x5B, 0, 0x0002, UIntPtr.Zero);
    keybd_event(0x4B, 0, 0, UIntPtr.Zero);
    keybd_event(0x4B, 0, 0x0002, UIntPtr.Zero);
    System.Console.WriteLine("chord fg=" + fg.ToString() + " tray=" + tray.ToString() + " target=" + target.ToString());
  }
}
"@
[Chord2]::Do()
Start-Sleep -Seconds 4
$screenshot = [System.Drawing.Bitmap]::new(864, 1692)
$g = [System.Drawing.Graphics]::FromImage($screenshot)
$g.CopyFromScreen(2016, 0, 0, 0, $screenshot.Size)
$screenshot.Save("C:/Users/Leviana/Documents/workspace/mirax-touch/scripts/after-chord2.png")
$screenshot.Dispose()
"screenshot saved"