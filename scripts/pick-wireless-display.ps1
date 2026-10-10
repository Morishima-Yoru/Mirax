Add-Type -AssemblyName UIAutomationClient

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

$items = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
foreach ($it in $items) {
    if ($it.Current.Name -match "Wireless display|Bluetooth|Everything else") {
        Write-Host "Found: $($it.Current.Name) | $($it.Current.ControlType.ProgrammaticName)"
        $inv = $it.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern) -as [System.Windows.Automation.InvokePattern]
        if ($inv) {
            Write-Host "Invoking $($it.Current.Name)..."
            $inv.Invoke()
            break
        }
    }
}
