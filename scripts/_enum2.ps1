Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class EnumWin2 {
  [DllImport("user32.dll")]
  public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")]
  public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")]
  public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")]
  public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")]
  public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [StructLayout(LayoutKind.Sequential)]
  public struct RECT { public int L, T, R, B; }
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  public static System.Collections.Generic.List<string> results = new System.Collections.Generic.List<string>();
  public static bool Callback(IntPtr h, IntPtr l) {
    if (!IsWindowVisible(h)) return true;
    var sb = new StringBuilder(512);
    GetWindowText(h, sb, 512);
    var cb = new StringBuilder(256);
    GetClassName(h, cb, 256);
    RECT r; GetWindowRect(h, out r);
    string t = sb.ToString();
    string c = cb.ToString();
    if (c.Contains("ControlCenter") || c.Contains("Shell") || c.Contains("Chrome") || c.Contains("WindowsTerminal") || t.Length > 0)
      results.Add("h=" + h.ToString() + " class=" + c + " title='" + t + "' rect=" + r.L + "," + r.T + " " + (r.R-r.L) + "x" + (r.B-r.T));
    return true;
  }
}
"@
$del = New-Object EnumWin2+EnumProc([EnumWin2]::Callback)
[EnumWin2]::EnumWindows($del, [IntPtr]::Zero)
[EnumWin2]::results | ForEach-Object { $_ }