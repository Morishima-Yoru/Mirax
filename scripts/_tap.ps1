Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$deadline = $sw.ElapsedMilliseconds + 60000
$clicked = $false
while ($sw.ElapsedMilliseconds -lt $deadline -and -not $clicked) {
  Start-Sleep -Seconds 2
  $root = [System.Windows.Automation.AutomationElement]::RootElement
  $all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
  $ccw = $all | Where-Object { $_.Current.Name -eq "Quick settings" } | Select-Object -First 1
  if ($ccw -eq $null) {
    Write-Host "poll $($sw.ElapsedMilliseconds)ms: no Quick settings panel"
    continue
  }
  $desc = $ccw.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
  $row = $desc | Where-Object { $_.Current.Name -match "TrebleDroid" } | Select-Object -First 1
  if ($row -ne $null) {
    $br = $row.Current.BoundingRectangle
    $cx = [int]($br.Left + $br.Width/2)
    $cy = [int]($br.Top + $br.Height/2)
    Write-Host ("FOUND row: '{0}' rect={1},{2} {3}x{4} center=({5},{6})" -f $row.Current.Name, $br.Left, $br.Top, $br.Width, $br.Height, $cx, $cy)
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($cx, $cy)
    Start-Sleep -Milliseconds 150
    [System.Windows.Forms.Control]::Click()
    $clicked = $true
    Write-Host "CLICKED at $cx,$cy"
  } else {
    $names = ($desc | Where-Object { $_.Current.Name -ne "" } | Select-Object -First 8 | ForEach-Object { "$($_.Current.ControlType):$($_.Current.Name)" }) -join " || "
    Write-Host "poll $($sw.ElapsedMilliseconds)ms: no TrebleDroid. $names"
  }
}
$sw.Stop()
Write-Host "clicked=$clicked"