Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

Write-Host "Scanning for discovered devices for 15s..."
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 1
    $items = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
    foreach ($it in $items) {
        $n = $it.Current.Name
        if ($n -match "Fold|Treble|vanilla|Phh|Mirax|Pixel") {
            Write-Host "Found discovered target: $n | $($it.Current.ControlType.ProgrammaticName)"
            $inv = $it.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern) -as [System.Windows.Automation.InvokePattern]
            if ($inv) {
                Write-Host "Invoking target connection!"
                $inv.Invoke()
                exit 0
            }
        }
    }
}
Write-Host "Scan completed."
