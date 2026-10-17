Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class W32 {
  [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  public static string Title() {
    var sb = new StringBuilder(512);
    GetWindowText(GetForegroundWindow(), sb, 512);
    return sb.ToString();
  }
  public static void SendKey(byte vk) {
    keybd_event(vk, 0, 0, UIntPtr.Zero);
    keybd_event(vk, 0, 2, UIntPtr.Zero);
  }
}
"@
Write-Host "before fg='$([W32]::Title())'"
[W32]::SendKey(0x1B)
Start-Sleep -Seconds 2
Write-Host "after Esc fg='$([W32]::Title())'"