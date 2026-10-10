Add-Type -AssemblyName UIAutomationClient

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

$list = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) |
    Where-Object { $_.Current.ClassName -eq "ListView" } | Select-Object -First 1

if ($list) {
    Write-Host "Found target ListView! Checking items..."
    $items = $list.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
    Write-Host "Item count: $($items.Count)"
    foreach ($it in $items) {
        Write-Host "ListView item: '$($it.Current.Name)' | $($it.Current.ControlType.ProgrammaticName)"
    }
} else {
    Write-Host "ListView not found"
}
