Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

$items = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
foreach ($it in $items) {
    if ($it.Current.Name -match "Renathan.*Z.*Fold5.*Category Cell phone") {
        Write-Host "Target: $($it.Current.Name)"
        try {
            $invoke = $it.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern) -as [System.Windows.Automation.InvokePattern]
            if ($invoke) {
                Write-Host "Invoking!"
                $invoke.Invoke()
                break
            } else {
                Write-Host "No InvokePattern"
            }
        } catch {
            Write-Host "Invoke failed: $_"
        }

        try {
            $select = $it.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern) -as [System.Windows.Automation.SelectionItemPattern]
            if ($select) {
                Write-Host "Selecting!"
                $select.Select()
                break
            } else {
                Write-Host "No SelectionItemPattern"
            }
        } catch {
            Write-Host "Select failed: $_"
        }

        # Check children for buttons like 'Connect'
        $children = $it.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
        Write-Host "Children count: $($children.Count)"
        foreach ($c in $children) {
            Write-Host "Child: $($c.Current.Name) | $($c.Current.ControlType.ProgrammaticName)"
            if ($c.Current.ControlType.ProgrammaticName -match "Button") {
                $btnInvoke = $c.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern) -as [System.Windows.Automation.InvokePattern]
                if ($btnInvoke) {
                    Write-Host "Invoking child button!"
                    $btnInvoke.Invoke()
                }
            }
        }
    }
}
