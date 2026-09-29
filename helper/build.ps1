# Build mirax-helper.jar for adb shell app_process.
# Requires Android SDK platform android-36 and build-tools 36.0.0.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin"
$javac = Join-Path $jdk "javac.exe"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$androidJar = Join-Path $sdk "platforms\android-36\android.jar"
$bt = Join-Path $sdk "build-tools\36.0.0"
$d8 = Join-Path $bt "d8.bat"

function Assert-Ok($step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed ($LASTEXITCODE)" }
}

Write-Host "helper dex"
$classes = Join-Path $root "classes"
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Path $classes | Out-Null

$sources = @(
    (Join-Path $root "src\me\trinitrix\mirax\wfd\SavedP2pGroups.java"),
    (Join-Path $root "src\me\trinitrix\mirax\wfd\PrimarySinkBeacon.java"),
    (Join-Path $root "src\me\trinitrix\mirax\helper\Helper.java")
)
& $javac -encoding UTF-8 -source 17 -target 17 -classpath $androidJar -d $classes @sources
Assert-Ok "helper javac"

$classFiles = Get-ChildItem -Recurse -Filter *.class $classes | ForEach-Object { $_.FullName }
$outJar = Join-Path $root "mirax-helper.jar"
if (Test-Path $outJar) { Remove-Item -Force $outJar }
& $d8 --min-api 30 --lib $androidJar --output $outJar @classFiles
Assert-Ok "helper d8"
Write-Host "built $outJar"
