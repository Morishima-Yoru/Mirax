Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.InteropServices

# Full screen capture to find the panel
$primary = [System.Drawing.Screen]::PrimaryScreen
$W = $primary.Bounds.Width
$H = $primary.Bounds.Height
Write-Host "primary screen: ${W}x${H} bounds=$($primary.Bounds)"

$shot = New-Object System.Drawing.Bitmap($W, $H)
$g = [System.Drawing.Graphics]::FromImage($shot)
$g.CopyFromScreen(0, 0, 0, 0, $shot.Size)
$shot.Save("C:/Users/Leviana/Documents/workspace/mirax-touch/scripts/full.png")
$shot.Dispose()
Write-Host "saved full.png"

# Find the dark panel: scan for the panel background color (~#2D2D2D)
$bmp = New-Object System.Drawing.Bitmap("C:/Users/Leviana/Documents/workspace/mirax-touch/scripts/full.png")
$minX = 99999; $maxX = -1; $minY = 99999; $maxY = -1
$panelColor = [System.Drawing.Color]::FromName("DimGray")
for ($y = 0; $y -lt $H; $y += 4) {
  for ($x = 0; $x -lt $W; $x += 4) {
    $p = $bmp.GetPixel($x, $y)
    # Panel background is dark gray ~ (45,45,45)
    if ($p.R -gt 35 -and $p.R -lt 60 -and $p.G -gt 35 -and $p.G -lt 60 -and $p.B -gt 35 -and $p.B -lt 60) {
      if ($x -lt $minX) { $minX = $x }
      if ($x -gt $maxX) { $maxX = $x }
      if ($y -lt $minY) { $minY = $y }
      if ($y -gt $maxY) { $maxY = $y }
    }
  }
}
Write-Host "PANEL: x=$minX..$maxX y=$minY..$maxY size=$((($maxX-$minX)+1))x(($maxY-$minY)+1)"

# Now find bright text bands within the panel (the device row)
$bands = @()
$inBand = $false; $start = 0
for ($y = $minY; $y -lt $maxY; $y++) {
  $bright = 0
  for ($x = $minX; $x -lt $maxX; $x += 2) {
    $p = $bmp.GetPixel($x, $y)
    if ($p.R -gt 120 -and $p.G -gt 120 -and $p.B -gt 120) { $bright++ }
  }
  if ($bright -gt 20) {
    if (-not $inBand) { $inBand = $true; $start = $y }
  } else {
    if ($inBand) {
      $inBand = $false
      if (($y - $start) -gt 3) { $bands += @{y1=$start; y2=$y; cy=[int](($start+$y)/2); h=($y-$start)} }
    }
  }
}
if ($inBand) { $bands += @{y1=$start; y2=$maxY; cy=[int](($start+$maxY)/2); h=($maxY-$start)} }

Write-Host "TEXT BANDS in panel:"
$bands | ForEach-Object {
  # find x extent
  $mX = 99999; $MX = -1
  for ($y = $_.y1; $y -lt $_.y2; $y++) {
    for ($x = $minX; $x -lt $maxX; $x++) {
      $p = $bmp.GetPixel($x, $y)
      if ($p.R -gt 120 -and $p.G -gt 120 -and $p.B -gt 120) {
        if ($x -lt $mX) { $mX = $x }
        if ($x -gt $MX) { $MX = $x }
      }
    }
  }
  Write-Host ("  y={0}-{1} (h={2}, cy={3}) x={4}-{5} (cx={6})" -f $_.y1, $_.y2, $_.h, $_.cy, $mX, $MX, [int](($mX+$MX)/2))
}

$bmp.Dispose()
