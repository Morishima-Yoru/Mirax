Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class EnumWin {
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
    RECT r;
    GetWindowRect(h, out r);
    string t = sb.ToString();
    if (t.Length > 0 || cb.ToString().Contains("ControlCenter") || cb.ToString().Contains("Shell"))
      results.Add($"h={h} class={cb} title='{t}' rect={r.L},{r.T} {r.R-r.L}x{r.B-r.T}");
    return true;
  }
}
"@
$cb = New-Object EnumWin+EnumProc([EnumWin]::Callback)
[EnumWin]::EnumWindows($cb, [IntPtr]::Zero)
[EnumWin]::results | ForEach-Object { $_ }