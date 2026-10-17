Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Reopen {
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
  public static extern uint SendInput(uint n, INPUT[] inputs, int cb);
  [DllImport("user32.dll")]
  public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [StructLayout(LayoutKind.Sequential)]
  public struct KEYBDINPUT { public ushort wScan; public ushort wFlags; public uint time; public UIntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)]
  public struct INPUT { public uint type; public KEYBDINPUT ki; }
  public static void Chord() {
    IntPtr fg = GetForegroundWindow();
    IntPtr tray = FindWindow("Shell_TrayWnd", null);
    IntPtr target = tray != IntPtr.Zero ? tray : fg;
    uint fgT = GetWindowThreadProcessId(fg, IntPtr.Zero);
    uint targetT = GetWindowThreadProcessId(target, IntPtr.Zero);
    uint me = GetCurrentThreadId();
    AttachThreadInput(me, fgT, true);
    if (targetT != me) AttachThreadInput(me, targetT, true);
    INPUT[] inputs = new INPUT[4];
    for (int i = 0; i < 4; i++) inputs[i] = new INPUT();
    inputs[0].type = 1; inputs[0].ki.wFlags = 0; inputs[0].ki.wScan = 0x5B; // LWIN down
    inputs[1].type = 1; inputs[1].ki.wFlags = 2; inputs[1].ki.wScan = 0x5B; // LWIN up
    inputs[2].type = 1; inputs[2].ki.wFlags = 0; inputs[2].ki.wScan = 0x4B; // K down
    inputs[3].type = 1; inputs[3].ki.wFlags = 2; inputs[3].ki.wScan = 0x4B; // K up
    SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT)));
    System.Console.WriteLine("chord fg=" + fg.ToString() + " tray=" + tray.ToString());
  }
}
"@
[Reopen]::Chord()
Start-Sleep -Seconds 4
$screenshot = [System.Drawing.Bitmap]::new(864, 1692)
$g = [System.Drawing.Graphics]::FromImage($screenshot)
$g.CopyFromScreen(2016, 0, 0, 0, $screenshot.Size)
$screenshot.Save("C:/Users/Leviana/Documents/workspace/mirax-touch/scripts/after-reopen.png")
$screenshot.Dispose()
"screenshot saved"