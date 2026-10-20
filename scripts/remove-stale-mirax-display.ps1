# Run after Windows Cast says disconnected and Mirax has ended the P2P session.
$active = Get-NetTCPConnection -State Established -ErrorAction Stop | Where-Object {
    $_.LocalPort -in 7236, 50000 -or $_.RemotePort -in 7236, 50000
}
if ($active) {
    throw 'Miracast TCP is still active; end the session on Android before removing its display.'
}

$monitors = @(Get-PnpDevice -Class Monitor -PresentOnly -ErrorAction Stop | Where-Object {
    $_.InstanceId -like 'DISPLAY\ZFD0001\*' -and $_.FriendlyName -like '*TrebleDroid*'
})
if ($monitors.Count -eq 0) {
    'No stale Mirax display is present.'
    return
}
if ($monitors.Count -ne 1) {
    throw "Expected one Mirax display, found $($monitors.Count); no devices changed."
}

$monitor = $monitors[0]
$parent = (Get-PnpDeviceProperty -InstanceId $monitor.InstanceId -KeyName DEVPKEY_Device_Parent -ErrorAction Stop).Data
if ($parent -notmatch '^SWD\\WiFiDirect\\.+#Miracast$') {
    throw "Unexpected monitor parent $parent; no devices changed."
}

& pnputil /remove-device $monitor.InstanceId
if ($LASTEXITCODE -ne 0) {
    throw "Could not remove stale Mirax display $($monitor.InstanceId)."
}
