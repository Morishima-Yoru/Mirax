<#
.SYNOPSIS
Red-capable loop for: P2P/helper up but MiraxSink never dials RTSP.

Asserts the handoff symptom:
  MiraxWfdBeacon reports source address / group up, yet MiraxSink stays silent
  (or PrivilegeProbe falsely reports hotspot and clears helperRunning).

Usage:
  .\scripts\diagnose-sink-null-command.ps1
  .\scripts\diagnose-sink-null-command.ps1 -Serial HA1CSQTM -SkipCastClick
#>
param(
    [string]$Serial = "HA1CSQTM",
    [string]$DeviceMatch = "Phh-Treble|Renathan.*Z\s*Fold\s*5|Fold",
    [int]$TimeoutSeconds = 45,
    [switch]$SkipCastClick
)

$ErrorActionPreference = "Stop"

function Write-Log([string]$Message) {
    $ts = Get-Date -Format "HH:mm:ss.fff"
    Write-Host "[$ts] $Message"
}

function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & adb -s $Serial @Args
}

Write-Log "=== diagnose sink null-command ==="
Write-Log "serial=$Serial"

function Ensure-StartBroadcast {
    Adb shell "am start -n me.trinitrix.mirax/.MainActivity" | Out-Null
    Start-Sleep -Seconds 2
    Adb shell "uiautomator dump /sdcard/dump.xml" | Out-Null
    $xml = Adb shell "cat /sdcard/dump.xml"
    if ($xml -match 'text="Stop broadcast"|text="停止廣播"') {
        Write-Log "Broadcast already on"
        return $true
    }
    $bounds = $null
    if ($xml -match 'text="Start broadcast"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        $bounds = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    } elseif ($xml -match 'text="開始廣播"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        $bounds = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    } elseif ($xml -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="Start broadcast"') {
        $bounds = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    } elseif ($xml -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="開始廣播"') {
        $bounds = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    }
    if (-not $bounds) {
        Write-Log "ERROR: Start broadcast button not found — open MainActivity dashboard first"
        return $false
    }
    $x = [int](($bounds[0] + $bounds[2]) / 2)
    $y = [int](($bounds[1] + $bounds[3]) / 2)
    Write-Log "Clicking Start broadcast at ($x,$y)"
    Adb shell "input tap $x $y"
    Start-Sleep -Seconds 2
    Adb shell "uiautomator dump /sdcard/dump.xml" | Out-Null
    $after = Adb shell "cat /sdcard/dump.xml"
    if ($after -match 'text="Stop broadcast"|text="停止廣播"') {
        Write-Log "Broadcast started"
        return $true
    }
    Write-Log "WARN: button clicked but Stop broadcast not visible yet"
    return $true
}

# Baseline: helper process + tether (P2P LocalHotspot vs user SoftAP)
$ps = Adb shell "ps -A"
$helperAlive = ($ps | Select-String -Pattern "app_process").Count -gt 0
$appAlive = ($ps | Select-String -Pattern "me\.trinitrix\.mirax").Count -gt 0
Write-Log "helper_app_process=$helperAlive app=$appAlive"

$tether = Adb shell "dumpsys tethering"
$p2pHotspot = [bool]($tether | Select-String -Pattern "p2p\d+\s+-\s+LocalHotspotState")
$userSoftAp = [bool]($tether | Select-String -Pattern "(wlan|softap|ap|swlan)\d*\s+-\s+(TetheredState|LocalHotspotState)")
Write-Log "p2p_local_hotspot=$p2pHotspot user_softap_tethered=$userSoftAp"

Adb logcat -c 2>$null | Out-Null
if (-not (Ensure-StartBroadcast)) {
    exit 3
}

if (-not $SkipCastClick) {
    Write-Log "Triggering cast click..."
    & "$PSScriptRoot\click-fold.ps1" -DeviceMatch $DeviceMatch -Serial $Serial
}

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$beaconGroup = $false
$beaconSource = $false
$sinkAny = $false
$hotspotSkip = $false
$privilegeLines = @()

Write-Log "Waiting up to ${TimeoutSeconds}s for beacon group vs sink..."
while ([DateTime]::UtcNow -lt $deadline) {
    $chunk = Adb logcat -d -t 80 -s MiraxWfdBeacon:V MiraxSink:V MiraxPrivilege:V MiraxHelper:V MiraxWfdBridge:V MiraxRoot:V
    if ($chunk -match "P2P group up|group up") { $beaconGroup = $true }
    if ($chunk -match "source address") { $beaconSource = $true }
    if ($chunk -match "MiraxSink") { $sinkAny = $true }
    if ($chunk -match "Hotspot active, skipping privilege probe") { $hotspotSkip = $true }
    if ($beaconSource -or ($beaconGroup -and ([DateTime]::UtcNow -gt $deadline.AddSeconds(-5)))) {
        break
    }
    Start-Sleep -Milliseconds 800
}

$final = Adb logcat -d -t 200 -s MiraxWfdBeacon:V MiraxSink:V MiraxPrivilege:V MiraxHelper:V MiraxWfdBridge:V MiraxRoot:V
Write-Log "--- recent logs ---"
Write-Host $final
Write-Log "--- end logs ---"

$beaconGroup = $beaconGroup -or ($final -match "P2P group up|group up")
$beaconSource = $beaconSource -or ($final -match "source address")
$sinkAny = $sinkAny -or ($final -match "MiraxSink")
$hotspotSkip = $hotspotSkip -or ($final -match "Hotspot active, skipping privilege probe")
$sinkDial = [bool]($final -match "RTP listening|playback started|watchGroup|dial|RTSP|serving")

Write-Log "RESULT beacon_group=$beaconGroup beacon_source=$beaconSource sink_any=$sinkAny sink_dial=$sinkDial hotspot_skip=$hotspotSkip"

# Red when privileged owner formed a group but app-process sink never ran.
if (($beaconSource -or $beaconGroup) -and -not $sinkAny) {
    Write-Log "RED: helper/beacon has P2P group but MiraxSink produced zero logs"
    exit 1
}

# Also red if probe falsely cleared privilege while P2P (not user SoftAP) is the only hotspot.
if ($hotspotSkip -and $p2pHotspot -and -not $userSoftAp -and -not $sinkAny) {
    Write-Log "RED: PrivilegeProbe hotspot short-circuit with P2P LocalHotspot only"
    exit 1
}

if (($beaconSource -or $beaconGroup) -and $sinkDial) {
    Write-Log "GREEN: beacon group + MiraxSink dial path observed"
    exit 0
}

Write-Log "AMBER: no clear group event in window (re-run without -SkipCastClick, or leave cast connected)"
exit 2
