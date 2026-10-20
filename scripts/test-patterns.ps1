Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

$patterns = $win.GetSupportedPatterns()
Write-Host "Supported patterns on Settings: $($patterns.Length)"

$items = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
foreach ($it in $items) {
    if ($it.Current.Name -match "Renathan.*Z.*Fold5.*Category Cell phone") {
        $p = $it.GetSupportedPatterns()
        Write-Host "Item: $($it.Current.Name)"
        foreach ($pat in $p) {
            Write-Host " - Pattern: $($pat.ProgrammaticName)"
        }
        $child = $it.FindFirst([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
        if ($child) {
            Write-Host "Child: $($child.Current.Name)"
            foreach ($cpat in $child.GetSupportedPatterns()) {
                Write-Host "   - Child Pattern: $($cpat.ProgrammaticName)"
            }
        }
    }
}
