Add-Type -AssemblyName UIAutomationClient

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

# Look for all groups with 'Remove this device'
$items = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
foreach ($it in $items) {
    if ($it.Current.Name -match "Renathan.*Z.*Fold5.*Category Cell phone") {
        Write-Host "Found Fold entry: $($it.Current.Name)"
        $removeBtn = $it.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) |
            Where-Object { $_.Current.Name -eq 'Remove' } | Select-Object -First 1
        if ($removeBtn) {
            Write-Host "Invoking Remove!"
            $inv = $removeBtn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern) -as [System.Windows.Automation.InvokePattern]
            $inv.Invoke()
            Start-Sleep -Seconds 1
            # Confirm if dialog appears
            $yesBtn = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) |
                Where-Object { $_.Current.Name -eq 'Yes' } | Select-Object -First 1
            if ($yesBtn) {
                Write-Host "Confirming Remove with Yes!"
                $yinv = $yesBtn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern) -as [System.Windows.Automation.InvokePattern]
                $yinv.Invoke()
            }
        }
    }
}
