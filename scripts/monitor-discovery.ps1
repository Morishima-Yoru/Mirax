Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

Write-Host "Monitoring discovered devices..."
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 1
    $items = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
    foreach ($it in $items) {
        $n = $it.Current.Name
        if ($n -match "Treble|vanilla|Phh|Mirax|Fold") {
            Write-Host "Discovered item: $n | $($it.Current.ControlType.ProgrammaticName)"
            # Check if this item is clickable
            $pats = $it.GetSupportedPatterns()
            foreach ($p in $pats) {
                Write-Host " - Pattern: $($p.ProgrammaticName)"
                if ($p.ProgrammaticName -match "Invoke") {
                    $inv = $it.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern) -as [System.Windows.Automation.InvokePattern]
                    Write-Host "Triggering Invoke on discovered device!"
                    $inv.Invoke()
                    exit 0
                }
                if ($p.ProgrammaticName -match "SelectionItem") {
                    $sel = $it.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern) -as [System.Windows.Automation.SelectionItemPattern]
                    Write-Host "Triggering Select on discovered device!"
                    $sel.Select()
                    exit 0
                }
            }
        }
    }
}
