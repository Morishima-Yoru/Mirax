Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public delegate bool EnumProc(IntPtr h, IntPtr l);
public class EnumWin3 {
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
    if (c.Contains("ControlCenter") || c.Contains("Shell") || c.Contains("Chrome") || c.Contains("WindowsTerminal") || c.Contains("ms-") || t.Length > 0)
      results.Add("h=" + h.ToString() + " class=" + c + " title='" + t + "' rect=" + r.L + "," + r.T + " " + (r.R-r.L) + "x" + (r.B-r.T));
    return true;
  }
}
"@
$del = New-Object EnumProc([EnumWin3]::Callback)
[EnumWin3]::EnumWindows($del, [IntPtr]::Zero)
[EnumWin3]::results | ForEach-Object { $_ }