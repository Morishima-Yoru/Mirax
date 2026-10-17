Add-Type -AssemblyName System.Drawing
$screenshot = [System.Drawing.Bitmap]::new(864, 1692)
$g = [System.Drawing.Graphics]::FromImage($screenshot)
$g.CopyFromScreen(2016, 0, 0, 0, $screenshot.Size)
$screenshot.Save("C:/Users/Leviana/Documents/workspace/mirax-touch/scripts/after-chord.png")
$screenshot.Dispose()
"screenshot saved"