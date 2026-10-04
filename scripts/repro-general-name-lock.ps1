# Repro: Broadcast name focus swallows the next tap (menu / other page).
# Exit 1 (RED) when navigation fails after focusing the name field.
# Exit 0 (GREEN) when navigation succeeds.
param(
    [string]$Serial = ""
)
$ErrorActionPreference = "Stop"
$script:AdbPrefix = if ($Serial) { @("-s", $Serial) } else { @() }
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CmdArgs)
    & adb @script:AdbPrefix @CmdArgs
}
function Dump-Ui {
    Invoke-Adb shell uiautomator dump /sdcard/ui-repro.xml | Out-Null
    return (Invoke-Adb shell cat /sdcard/ui-repro.xml) -join "`n"
}
function Find-Center([string]$xml, [string]$pattern) {
    if ($xml -match $pattern) {
        $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        return @{ X = $x; Y = $y }
    }
    return $null
}
function Test-OnGeneral([string]$xml) {
    return ($xml -match 'class="android.widget.EditText"') -and ($xml -match 'text="General"')
}

Invoke-Adb shell am start -n me.trinitrix.mirax/.MainActivity | Out-Null
Start-Sleep -Milliseconds 800
$xml = Dump-Ui

$menu = Find-Center $xml 'content-desc="Menu"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
if (-not $menu) { Write-Host "FAIL: no Menu"; exit 2 }
Invoke-Adb shell input tap $menu.X $menu.Y
Start-Sleep -Milliseconds 700
$xml = Dump-Ui

$general = Find-Center $xml 'text="General"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
if (-not $general) { Write-Host "FAIL: no General in drawer (need English UI)"; exit 2 }
Invoke-Adb shell input tap $general.X $general.Y
Start-Sleep -Milliseconds 800
$xml = Dump-Ui
if (-not (Test-OnGeneral $xml)) { Write-Host "FAIL: did not enter General"; exit 2 }
Write-Host "on General"

$name = Find-Center $xml 'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
if (-not $name) { Write-Host "FAIL: no EditText"; exit 2 }
Invoke-Adb shell input tap $name.X $name.Y
Start-Sleep -Milliseconds 500
Invoke-Adb shell input text "LockTest"
Start-Sleep -Milliseconds 500
Write-Host "focused+typed Broadcast name"

$xml = Dump-Ui
$menu = Find-Center $xml 'content-desc="Menu"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
if (-not $menu) { Write-Host "RED: Menu missing after name focus"; exit 1 }
Invoke-Adb shell input tap $menu.X $menu.Y
Start-Sleep -Milliseconds 800
$xml = Dump-Ui
$dash = Find-Center $xml 'text="Dashboard"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
if (-not $dash) {
    Write-Host "RED: drawer did not open after name focus (Menu tap swallowed)"
    exit 1
}
Invoke-Adb shell input tap $dash.X $dash.Y
Start-Sleep -Milliseconds 800
$xml = Dump-Ui
if (Test-OnGeneral $xml) {
    Write-Host "RED: still on General after Dashboard tap"
    exit 1
}
Write-Host "GREEN: left General"
exit 0
