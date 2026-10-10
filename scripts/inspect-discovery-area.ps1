Add-Type -AssemblyName UIAutomationClient

$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Settings')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)

$prompt = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) |
    Where-Object { $_.Current.Name -match "Select a device below to connect" } | Select-Object -First 1

if ($prompt) {
    Write-Host "Found prompt. Inspecting parent/siblings..."
    $parent = [System.Windows.Automation.TreeWalker]::RawViewWalker.GetParent($prompt)
    if ($parent) {
        $c = $parent.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
        foreach ($el in $c) {
            Write-Host "Element: '$($el.Current.Name)' | $($el.Current.ClassName) | $($el.Current.ControlType.ProgrammaticName)"
        }
    }
}
