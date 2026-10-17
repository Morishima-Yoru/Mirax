Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap("C:/Users/Leviana/Documents/workspace/mirax-touch/scripts/now.png")
$W = $bmp.Width; $H = $bmp.Height
Write-Host "image ${W}x${H}"
# Find all bright horizontal bands with wider x range
$bands = @()
$inBand = $false; $start = 0
for ($y = 0; $y -lt $H; $y++) {
  $bright = 0
  for ($x = 60; $x -lt 700; $x++) {
    $p = $bmp.GetPixel($x, $y)
    if ($p.R -gt 100 -and $p.G -gt 100 -and $p.B -gt 100) { $bright++ }
  }
  if ($bright -gt 30) {
    if (-not $inBand) { $inBand = $true; $start = $y }
  } else {
    if ($inBand) {
      $inBand = $false
      if (($y - $start) -gt 2) { $bands += @{y=$start; h=($y-$start); cy=[int](($start+$y)/2)} }
    }
  }
}
if ($inBand) { $bands += @{y=$start; h=($H-$start); cy=[int](($start+$H)/2)} }
$bands | ForEach-Object { Write-Host ("BAND y={0} h={1} center_y={2}" -f $_.y, $_.h, $_.cy) }
# For each band, find x extent
foreach ($b in $bands) {
  $minX = 9999; $maxX = -1
  for ($y = $b.y; $y -lt ($b.y + $b.h); $y++) {
    for ($x = 0; $x -lt $W; $x++) {
      $p = $bmp.GetPixel($x, $y)
      if ($p.R -gt 100 -and $p.G -gt 100 -and $p.B -gt 100) {
        if ($x -lt $minX) { $minX = $x }
        if ($x -gt $maxX) { $maxX = $x }
      }
    }
  }
  Write-Host ("  band y={0} x={1}..{2} center_x={3}" -f $b.cy, $minX, $maxX, [int](($minX+$maxX)/2))
}
$bmp.Dispose()