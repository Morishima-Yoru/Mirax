# E2E decoder probe for Mirax stutter on a connected phone.
# Usage (while casting or right after a stuttery session):
#   .\scripts\probe-decoder-e2e.ps1
#   .\scripts\probe-decoder-e2e.ps1 -Serial HA1CSQTM -Seconds 20
param(
    [string]$Serial = "",
    [int]$Seconds = 15
)

$ErrorActionPreference = "Continue"
$adb = if ($Serial) { @("-s", $Serial) } else { @() }

function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & adb @adb @Args
}

Write-Host "=== devices ==="
Adb devices -l
Write-Host "=== clear + capture ${Seconds}s (cast now) ==="
Adb logcat -c | Out-Null
Start-Sleep -Seconds $Seconds
$log = Adb logcat -d -t 2000

Write-Host "=== MiraxH264 / codec selection ==="
$log | Select-String -Pattern "MiraxH264|Unable to instantiate codec|c2\.android\.avc|OMX\.MTK\.VIDEO\.DECODER|C2BqBuffer|hard backlog|sw codec input" |
    Select-Object -Last 80 |
    ForEach-Object { $_.Line }

Write-Host ""
Write-Host "=== verdict hints ==="
$mtkFail = ($log | Select-String -Pattern "Unable to instantiate codec 'OMX\.MTK\.VIDEO\.DECODER\.AVC'").Count
$sw = ($log | Select-String -Pattern "c2\.android\.avc\.decoder|software=true|sw=true").Count
$stall = ($log | Select-String -Pattern "C2BqBuffer: last successful dequeue").Count
$resync = ($log | Select-String -Pattern "hard backlog|sw codec input stalled").Count
Write-Host "mtk_instantiate_fail_lines=$mtkFail"
Write-Host "software_decoder_lines=$sw"
Write-Host "c2_dequeue_stall_lines=$stall"
Write-Host "resync_lines=$resync"
if ($mtkFail -gt 0 -and $sw -gt 0) {
    Write-Host "LIKELY: MTK HW decoder unavailable; software AVC cannot sustain the stream (device-specific)."
} elseif ($stall -gt 0) {
    Write-Host "LIKELY: decoder output stall (C2BqBuffer)."
} elseif ($resync -gt 0) {
    Write-Host "LIKELY: sink resync under backlog."
} else {
    Write-Host "No strong decoder signal in this window; cast longer and re-run."
}
