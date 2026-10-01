$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw "JAVA_HOME is not set. Set it to a complete JDK 17 installation."
}
$jdkBin = Join-Path $env:JAVA_HOME "bin"
$javaTools = @("javac.exe", "java.exe", "jar.exe", "keytool.exe")
$missingJavaTools = @($javaTools | Where-Object {
    -not (Test-Path -LiteralPath (Join-Path $jdkBin $_) -PathType Leaf)
})
if ($missingJavaTools.Count -gt 0) {
    throw "Missing required Java tool(s) under '$jdkBin': $($missingJavaTools -join ', '). Set JAVA_HOME to a complete JDK 17 installation."
}
$javac = Join-Path $jdkBin "javac.exe"
$java = Join-Path $jdkBin "java.exe"
$jar = Join-Path $jdkBin "jar.exe"
$keytool = Join-Path $jdkBin "keytool.exe"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$androidJar = Join-Path $sdk "platforms\android-36\android.jar"
$bt = Join-Path $sdk "build-tools\36.0.0"
$d8 = Join-Path $bt "d8.bat"
$aapt2 = Join-Path $bt "aapt2.exe"
$zipalign = Join-Path $bt "zipalign.exe"
$apksigner = Join-Path $bt "apksigner.bat"

Write-Host "protocol tests"
$out = Join-Path $root "out"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null
function Assert-Ok($step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed ($LASTEXITCODE)" }
}
& $javac -encoding UTF-8 -source 11 -target 11 -d $out `
    (Join-Path $root "src\com\secondscreen\wfd\Capabilities.java") `
    (Join-Path $root "src\com\secondscreen\wfd\VideoModes.java") `
    (Join-Path $root "src\com\secondscreen\wfd\Edid.java") `
    (Join-Path $root "src\com\secondscreen\wfd\SpsParser.java") `
    (Join-Path $root "src\com\secondscreen\wfd\RtspSession.java") `
    (Join-Path $root "src\com\secondscreen\wfd\MpegTsDepacketizer.java") `
    (Join-Path $root "test\com\secondscreen\wfd\ProtocolTests.java")
Assert-Ok "protocol javac"
& $java -cp $out com.secondscreen.wfd.ProtocolTests
Assert-Ok "protocol tests"

Write-Host "receiver dex"
$classes = Join-Path $root "classes"
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Path $classes | Out-Null
& $javac -encoding UTF-8 -source 11 -target 11 -classpath $androidJar -d $classes `
    (Join-Path $root "src\com\secondscreen\wfd\Capabilities.java") `
    (Join-Path $root "src\com\secondscreen\wfd\VideoModes.java") `
    (Join-Path $root "src\com\secondscreen\wfd\Edid.java") `
    (Join-Path $root "src\com\secondscreen\wfd\SpsParser.java") `
    (Join-Path $root "src\com\secondscreen\wfd\RtspSession.java") `
    (Join-Path $root "src\com\secondscreen\wfd\MpegTsDepacketizer.java") `
    (Join-Path $root "src\com\secondscreen\receiver\MiracastReceiver.java")
Assert-Ok "receiver javac"
$classFiles = Get-ChildItem -Recurse -Filter *.class $classes | ForEach-Object { $_.FullName }
& $d8 --min-api 26 --lib $androidJar --output (Join-Path $root "miracast.jar") $classFiles
Assert-Ok "receiver d8"

Write-Host "player apk"
$playerClasses = Join-Path $root "player-classes"
$playerDex = Join-Path $root "player-dex"
foreach ($dir in @($playerClasses, $playerDex)) {
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    New-Item -ItemType Directory -Path $dir | Out-Null
}
$playerSources = Get-ChildItem (Join-Path $root "player\*.java") | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 11 -target 11 -classpath $androidJar -d $playerClasses @playerSources
Assert-Ok "player javac"
$playerClassFiles = Get-ChildItem -Recurse -Filter *.class $playerClasses | ForEach-Object { $_.FullName }
& $d8 --min-api 26 --lib $androidJar --output $playerDex $playerClassFiles
Assert-Ok "player d8"
$unsigned = Join-Path $root "receiver-unsigned.apk"
$aligned = Join-Path $root "receiver-aligned.apk"
$apk = Join-Path $root "receiver.apk"
if (Test-Path $unsigned) { Remove-Item -Force $unsigned }
& $aapt2 link -o $unsigned -I $androidJar --manifest (Join-Path $root "player\AndroidManifest.xml") `
    --min-sdk-version 30 --target-sdk-version 34
Assert-Ok "aapt2"
$dexJar = Join-Path $playerDex "classes.dex"
if (-not (Test-Path $dexJar)) { throw "classes.dex missing" }
Push-Location $playerDex
try {
    & $jar -uf $unsigned classes.dex
} finally {
    Pop-Location
}
& $zipalign -f 4 $unsigned $aligned
Assert-Ok "zipalign"
$ks = Join-Path $root "debug.keystore"
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -keystore $ks -storepass android -keypass android -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
}
if (Test-Path $apk) { Remove-Item -Force $apk }
& $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $apk $aligned
Assert-Ok "apksigner"
Write-Host "built $apk and miracast.jar"
