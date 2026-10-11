Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

if ($win) {
    Write-Host "Found Settings Window!"
    $items = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
    for ($i=0; $i -lt $items.Count; $i++) {
        $n = $items[$i].Current.Name
        if ($n -match 'Fold|Treble|vanilla|Phh|Cast|Connect|Display|Wireless') {
            Write-Host "$n | $($items[$i].Current.ControlType.ProgrammaticName)"
        }
    }
} else {
    Write-Host "Settings window not found"
}
