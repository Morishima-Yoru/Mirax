Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

# Find the TrebleDroid row via UIAutomation and click its center
$root = [System.Windows.Automation.AutomationElement]::RootElement
$all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
$ccw = $all | Where-Object { $_.Current.Name -eq "Quick settings" } | Select-Object -First 1
if ($ccw -eq $null) { Write-Host "no ControlCenterWindow found"; exit 1 }
Write-Host "CCW: $($ccw.Current.BoundingRectangle)"

$desc = $ccw.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
$row = $desc | Where-Object { $_.Current.Name -match "TrebleDroid" } | Select-Object -First 1
if ($row -eq $null) {
  Write-Host "no TrebleDroid row yet. named descendants:"
  $desc | Where-Object { $_.Current.Name -ne "" } | Select-Object -First 20 | ForEach-Object { "  $($_.Current.ControlType):$($_.Current.Name) rect=$($_.Current.BoundingRectangle)" }
  exit 1
}
$br = $row.Current.BoundingRectangle
$cx = [int]($br.Left + $br.Width/2)
$cy = [int]($br.Top + $br.Height/2)
Write-Host ("ROW: '{0}' rect={1},{2} {3}x{4} center=({5},{6})" -f $row.Current.Name, $br.Left, $br.Top, $br.Width, $br.Height, $cx, $cy)

# Click via SetCursorPos + mouse_event
Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($cx, $cy)
Start-Sleep -Milliseconds 100
[System.Windows.Forms.Control]::Click()
Write-Host "clicked at $cx,$cy"