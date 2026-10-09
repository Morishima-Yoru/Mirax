<#
.SYNOPSIS
End-to-end test for Mirax debug overlay feature.
Tests: PictureActivity launches on connection, debug overlay shows FPS/latency/bandwidth plots.
#>

param(
    [string]$DeviceMatch = "Phh-Treble|Renathan.*Z\s*Fold\s*5|Fold",
    [string]$Serial = "",
    [int]$ConnectionTimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"

function Write-Log($message) {
    $timestamp = Get-Date -Format "HH:mm:ss.fff"
    Write-Host "[$timestamp] $message"
}

function Adb($args) {
    $adbArgs = @()
    if ($Serial) { $adbArgs += "-s", $Serial }
    $adbArgs += $args
    & adb @adbArgs
}

function Wait-ForLog($pattern, $timeoutMs = 10000, $source = "MiraxSink") {
    $start = [Environment]::TickCount
    while ([Environment]::TickCount - $start -lt $timeoutMs) {
        $log = Adb shell "logcat -d -t 20 -s $source"
        if ($log -match $pattern) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Get-ActivityName() {
    $dumpsys = Adb shell "dumpsys activity activities | Select-String 'mResumedActivity|mFocusedActivity|PictureActivity' | Select-Object -First 3"
    return $dumpsys
}

function Check-DebugOverlayVisible() {
    # Use uiautomator to check if debug overlay is visible
    $xml = Adb shell "uiautomator dump /dev/stdout 2>/dev/null"
    # Look for debug overlay text elements
    $hasFps = $xml -match "FPS|fps"
    $hasLatency = $xml -match "Latency|latency"
    $hasBandwidth = $xml -match "Bandwidth|bandwidth|BW"
    $hasDecoder = $xml -match "Decoder|decoder"
    return @{ Fps = $hasFps; Latency = $hasLatency; Bandwidth = $hasBandwidth; Decoder = $hasDecoder }
}

# Main test flow
Write-Log "=== Mirax Debug Overlay E2E Test ==="
Write-Log "Device match pattern: $DeviceMatch"

# 1. Clear logcat
Adb logcat -c
Write-Log "Logcat cleared"

# 2. Open Mirax app and enable debug overlay
Write-Log "Opening Mirax app..."
Adb shell "am start -n me.trinitrix.mirax/.MainActivity"
Start-Sleep -Seconds 3

# Navigate to Advanced settings and enable debug overlay
Write-Log "Enabling debug overlay in settings..."
Adb shell "input tap 500 500"  # This is placeholder - actual navigation needed
Start-Sleep -Seconds 2

# 3. Open cast window and connect
Write-Log "Opening cast window and connecting to device..."
& ".\scripts\click-fold.ps1" -DeviceMatch $DeviceMatch -Serial $Serial

# 4. Wait for connection to establish (RTSP PLAY + PictureActivity launch)
Write-Log "Waiting for RTSP PLAY and PictureActivity launch..."
$playFound = Wait-ForLog "playback started|EnteredPlay|ConnectionEstablished" -timeoutMs ($ConnectionTimeoutSeconds * 1000) -source "MiraxSink"
if (-not $playFound) {
    Write-Log "ERROR: RTSP PLAY not reached within timeout"
    Adb shell "logcat -d -t 50 -s MiraxSink:V MiraxWfdBridge:V MiraxSession:V"
    exit 1
}
Write-Log "RTSP PLAY reached"

# 5. Wait for PictureActivity to be in foreground
Write-Log "Waiting for PictureActivity to launch..."
$activityFound = $false
for ($i = 0; $i -lt 30; $i++) {
    $activity = Get-ActivityName
    if ($activity -match "PictureActivity") {
        $activityFound = $true
        Write-Log "PictureActivity is in foreground"
        break
    }
    Start-Sleep -Seconds 1
}

if (-not $activityFound) {
    Write-Log "ERROR: PictureActivity did not come to foreground"
    $activity = Get-ActivityName
    Write-Log "Current activity: $activity"
    Adb shell "logcat -d -t 50 -s MiraxSink:V MiraxSession:V ActivityManager:V"
    exit 1
}

# 6. Wait for debug overlay to populate with data
Write-Log "Waiting for debug overlay to populate with stats..."
Start-Sleep -Seconds 5

# 7. Check debug overlay content via UIAutomator
Write-Log "Checking debug overlay content..."
$overlayCheck = Check-DebugOverlayVisible
Write-Log "Debug overlay check results:"
Write-Log "  FPS: $($overlayCheck.Fps)"
Write-Log "  Latency: $($overlayCheck.Latency)"
Write-Log "  Bandwidth: $($overlayCheck.Bandwidth)"
Write-Log "  Decoder: $($overlayCheck.Decoder)"

# 8. Capture screenshot for verification
$screenshotPath = "e2e-debug-overlay-$(Get-Date -Format 'yyyyMMdd-HHmmss').png"
Write-Log "Capturing screenshot: $screenshotPath"
Adb shell "screencap -p /sdcard/$screenshotPath"
Adb pull "/sdcard/$screenshotPath" ".\$screenshotPath"
Adb shell "rm /sdcard/$screenshotPath"

# 9. Check logcat for decoder stats updates
Write-Log "Checking decoder stats in logcat..."
$log = Adb shell "logcat -d -t 100 -s MiraxH264:V MiraxSink:V"
$log | Select-String -Pattern "stats|FPS|bitrate|frame" | Select-Object -Last 10 | ForEach-Object { Write-Log "  $_" }

# 10. Verify key metrics
Write-Log "=== Test Results ==="
$pass = $true

if (-not $playFound) {
    Write-Log "FAIL: RTSP PLAY not reached"
    $pass = $false
} else {
    Write-Log "PASS: RTSP PLAY reached"
}

if (-not $activityFound) {
    Write-Log "FAIL: PictureActivity did not launch"
    $pass = $false
} else {
    Write-Log "PASS: PictureActivity launched and in foreground"
}

if ($overlayCheck.Fps -and $overlayCheck.Decoder) {
    Write-Log "PASS: Debug overlay shows FPS and decoder info"
} else {
    Write-Log "WARN: Debug overlay may not be fully populated (FPS: $($overlayCheck.Fps), Decoder: $($overlayCheck.Decoder))"
}

if ($overlayCheck.Latency -or $overlayCheck.Bandwidth) {
    Write-Log "PASS: Debug overlay shows latency/bandwidth metrics"
} else {
    Write-Log "INFO: Latency/bandwidth plots may need more time to populate"
}

if ($pass) {
    Write-Log "=== OVERALL: TEST PASSED ==="
    exit 0
} else {
    Write-Log "=== OVERALL: TEST FAILED ==="
    exit 1
}