Add-Type -AssemblyName UIAutomationClient

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

$btn = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) |
    Where-Object { $_.Current.Name -eq 'Wireless display or dock' } | Select-Object -First 1

if ($btn) {
    Write-Host "Clicking Wireless display or dock!"
    $inv = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern) -as [System.Windows.Automation.InvokePattern]
    $inv.Invoke()
} else {
    Write-Host "Wireless display or dock button not found"
}
