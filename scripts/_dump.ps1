Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Drawing

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class CC {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L,T,R,B; }
  public static string Title(IntPtr h){ var sb=new StringBuilder(512); GetWindowText(h,sb,512); return sb.ToString(); }
}
"@
$fg = [CC]::GetForegroundWindow()
$t = [CC]::Title($fg)
Write-Host "fg=$($fg.ToString()) title='$t'"
$r = New-Object CC+RECT
[CC]::GetWindowRect($fg, [ref]$r)
Write-Host "rect=$($r.L),$($r.T) $($r.R-$r.L)x$($r.B-$r.T)"

$el = [System.Windows.Automation.AutomationElement]::FromHandle($fg)
$all = $el.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
Write-Host "descendants: $($all.Count)"
$all | ForEach-Object {
  $br = $_.Current.BoundingRectangle
  Write-Host ("  [{0}] '{1}' rect={2},{3} {4}x{5}" -f $_.Current.ControlType, $_.Current.Name, $br.Left, $br.Top, $br.Width, $br.Height)
}