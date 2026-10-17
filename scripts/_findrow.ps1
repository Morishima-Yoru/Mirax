Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap("C:/Users/Leviana/Documents/workspace/mirax-touch/scripts/after-click.png")
# scan the row band y=812..828 for x extent of bright pixels
$minX = 9999; $maxX = -1
for ($y = 810; $y -le 830; $y++) {
  for ($x = 0; $x -lt $bmp.Width; $x++) {
    $p = $bmp.GetPixel($x, $y)
    if ($p.R -gt 120 -and $p.G -gt 120 -and $p.B -gt 120) {
      if ($x -lt $minX) { $minX = $x }
      if ($x -gt $maxX) { $maxX = $x }
    }
  }
}
Write-Host "row band x: $minX .. $maxX  center=$((($minX+$maxX)/2))"
# also the monitor icon: scan y=800..880 x=0..120
$ix = 0; $iy = 0
for ($y = 790; $y -le 890; $y++) {
  for ($x = 20; $x -lt 120; $x++) {
    $p = $bmp.GetPixel($x, $y)
    if ($p.R -gt 120 -and $p.G -gt 120 -and $p.B -gt 120) { $ix = $x; $iy = $y; break }
  }
}
Write-Host "icon approx: $ix,$iy"
$bmp.Dispose()