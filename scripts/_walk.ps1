Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$ccw = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]::new(593670))
Write-Host "root: $($ccw.Current.Name) [$($ccw.Current.ControlType)] rect=$($ccw.Current.BoundingRectangle)"

function Walk($el, $depth) {
  $name = $el.Current.Name
  $ct = $el.Current.ControlType
  $br = $el.Current.BoundingRectangle
  $pad = "  " * $depth
  if ($name -ne "" -or $depth -lt 2) {
    Write-Host ("{0}[{1}] '{2}' rect={3},{4} {5}x{6}" -f $pad, $ct, $name, $br.Left, $br.Top, $br.Width, $br.Height)
  }
  try {
    $child = $el.FindFirst([System.Windows.Automation.TreeScope]::Children)
    while ($child -ne $null) {
      Walk $child ($depth + 1)
      $child = $child.FindNext([System.Windows.Automation.TreeScope]::Children)
    }
  } catch { Write-Host "$pad(err) $_" }
}
Walk $ccw 0