$ErrorActionPreference = "Continue"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

Write-Host "opening wireless display discovery"
Start-Process "explorer.exe" "ms-settings-connectabledevices:devicediscovery"
Start-Sleep -Seconds 4

function Dump-Named($root, $limit) {
    $all = $root.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition)
    $n = 0
    foreach ($el in $all) {
        $name = $el.Current.Name
        if (-not $name) { continue }
        if ($name -match "Fold|Renathan|Cast|Project|Connect|wireless|Wireless|Miracast|display|Display|Z Fold") {
            Write-Host ("HIT {0} | {1} | {2}" -f $el.Current.ControlType.ProgrammaticName, $el.Current.ClassName, $name)
            $n++
            if ($n -ge $limit) { break }
        }
    }
    Write-Host "scanned, hits=$n of $($all.Count)"
}

$root = [System.Windows.Automation.AutomationElement]::RootElement
Dump-Named $root 40

# Also list top-level window names that are new-looking
$wins = $root.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
foreach ($w in $wins) {
    $n = $w.Current.Name
    $c = $w.Current.ClassName
    if ($n -match "Settings|Connect|Cast|Project|Quick|System" -or $c -match "XamlExplorerHostIslandWindow|Popup|Flyout") {
        Write-Host "WIN $c :: $n"
    }
}
