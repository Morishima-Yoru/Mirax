Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

$items = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
foreach ($it in $items) {
    if ($it.Current.Name -match "Renathan.*Z.*Fold5.*Category Cell phone") {
        $child = $it.FindFirst([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
        if ($child) {
            $ec = $child.GetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern) -as [System.Windows.Automation.ExpandCollapsePattern]
            if ($ec) {
                Write-Host "Current state: $($ec.Current.ExpandCollapseState)"
                Write-Host "Expanding..."
                $ec.Expand()
                Start-Sleep -Seconds 1
                
                # Check expanded contents
                $sub = $it.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
                foreach ($s in $sub) {
                    Write-Host "Sub: $($s.Current.Name) | $($s.Current.ControlType.ProgrammaticName)"
                    if ($s.Current.Name -match "Connect") {
                        $inv = $s.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern) -as [System.Windows.Automation.InvokePattern]
                        if ($inv) {
                            Write-Host "Found Connect button! Invoking..."
                            $inv.Invoke()
                        }
                    }
                }
            }
        }
        break
    }
}
