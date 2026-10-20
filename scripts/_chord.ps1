Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Chord {
  [DllImport("user32.dll")] static extern IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
  [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  public static void Do(){
    IntPtr fg = GetForegroundWindow();
    IntPtr tray = FindWindow("Shell_TrayWnd", null);
    IntPtr target = tray != IntPtr.Zero ? tray : fg;
    uint fgT = GetWindowThreadProcessId(fg, IntPtr.Zero);
    uint targetT = GetWindowThreadProcessId(target, IntPtr.Zero);
    uint me = GetCurrentThreadId();
    AttachThreadInput(me, fgT, true);
    if (targetT != me) AttachThreadInput(me, targetT, true);
    keybd_event(0x5B, 0, 0, UIntPtr.Zero); // VK_LWIN down
    keybd_event(0x5B, 0, 0x0002, UIntPtr.Zero); // up
    keybd_event(0x4B, 0, 0, UIntPtr.Zero); // VK_K down
    keybd_event(0x4B, 0, 0x0002, UIntPtr.Zero); // up
    System.Console.WriteLine("chord done fg=" + fg.ToString() + " target=" + target.ToString());
  }
}
"@; [Chord]::Do(); Start-Sleep -Seconds 3