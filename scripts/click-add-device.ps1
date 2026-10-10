Add-Type -AssemblyName UIAutomationClient

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

$btn = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) | 
    Where-Object { $_.Current.Name -match 'Add device' } | 
    Select-Object -First 1

if ($btn) { 
    Write-Host "Add device button found: $($btn.Current.Name)"
    $inv = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern) -as [System.Windows.Automation.InvokePattern]
    if ($inv) {
        Write-Host "Invoking Add device!"
        $inv.Invoke()
    }
} else { 
    Write-Host "Add device button not found" 
}
