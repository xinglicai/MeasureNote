# MeasureNote 构建脚本：aapt2 + javac + d8 + zipalign + apksigner，无需 Gradle
$ErrorActionPreference = "Continue"

$ROOT   = "C:\Users\Administrator\Downloads\pm_work\MeasureNote"
$SDK    = "C:\emu\android-sdk"
$BT     = "$SDK\build-tools\35.0.0"
$JAR    = "$SDK\platforms\android-34\android.jar"
$JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$JAVAC  = "$JAVA_HOME\bin\javac.exe"
$KEYSTORE = "C:\Users\Administrator\Downloads\pm_work\debug.keystore"

$BUILD   = "$ROOT\build"
$GEN     = "$BUILD\gen"
$CLASSES = "$BUILD\classes"
$DEXDIR  = "$BUILD\dex"

Write-Host "== 0 clean ==" -ForegroundColor Cyan
if (Test-Path $BUILD) { Remove-Item $BUILD -Recurse -Force }
New-Item -ItemType Directory -Force -Path $GEN, $CLASSES, $DEXDIR | Out-Null

Write-Host "== 1 aapt2 compile ==" -ForegroundColor Cyan
cmd /c "`"$BT\aapt2.exe`" compile --dir `"$ROOT\res`" -o `"$BUILD\res.zip`" 2>&1"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== 2 aapt2 link ==" -ForegroundColor Cyan
cmd /c "`"$BT\aapt2.exe`" link -o `"$BUILD\app-unsigned.apk`" -I `"$JAR`" --manifest `"$ROOT\AndroidManifest.xml`" -R `"$BUILD\res.zip`" --java `"$GEN`" --auto-add-overlay 2>&1"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== 3 javac ==" -ForegroundColor Cyan
$srcs = @(Get-ChildItem -Path "$ROOT\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$rsrc = @(Get-ChildItem -Path $GEN -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$javacArgs = @(
    "-encoding", "UTF-8",
    "--release", "8",
    "-nowarn",
    "-classpath", $JAR,
    "-d", $CLASSES
)
$javacArgs += $srcs
$javacArgs += $rsrc

$old = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
& $JAVAC @javacArgs 2>&1 | ForEach-Object { if ($_ -match 'error|错误') { Write-Host $_ } }
$code = $LASTEXITCODE
$ErrorActionPreference = $old
if ($code -ne 0) { throw "javac failed (exit $code)" }

Write-Host "== 4 d8 ==" -ForegroundColor Cyan
# 先把 class 打成 jar，避免命令行过长
Push-Location $CLASSES
& "$JAVA_HOME\bin\jar.exe" cf "$BUILD\classes.jar" .
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

$old = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
cmd /c "`"$BT\d8.bat`" --lib `"$JAR`" --min-api 21 --output `"$DEXDIR`" `"$BUILD\classes.jar`" 2>&1"
$code = $LASTEXITCODE
$ErrorActionPreference = $old
if ($code -ne 0) { throw "d8 failed (exit $code)" }

Write-Host "== 5 pack dex ==" -ForegroundColor Cyan
Copy-Item "$BUILD\app-unsigned.apk" "$BUILD\app-withdex.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$BUILD\app-withdex.apk", 'Update')
$entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
$es = $entry.Open()
$bytes = [System.IO.File]::ReadAllBytes("$DEXDIR\classes.dex")
$es.Write($bytes, 0, $bytes.Length)
$es.Close()
$zip.Dispose()

Write-Host "== 6 align + sign ==" -ForegroundColor Cyan
cmd /c "`"$BT\zipalign.exe`" -p -f 4 `"$BUILD\app-withdex.apk`" `"$BUILD\app-aligned.apk`" 2>&1"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
cmd /c "`"$BT\apksigner.bat`" sign --ks `"$KEYSTORE`" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey `"$BUILD\app-aligned.apk`" 2>&1"
if ($LASTEXITCODE -ne 0) { throw "sign failed" }

$out = "$ROOT\MeasureNote.apk"
Copy-Item "$BUILD\app-aligned.apk" $out -Force
Write-Host ""
Write-Host "BUILD OK: $out  ($([math]::Round((Get-Item $out).Length/1KB,1)) KB)" -ForegroundColor Green
