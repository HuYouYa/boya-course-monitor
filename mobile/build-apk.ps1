param(
    [string]$SdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($SdkRoot)) { throw "Set ANDROID_SDK_ROOT first" }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw "Set JAVA_HOME (Java 17) first" }

$project = Split-Path -Parent $PSScriptRoot
$build = Join-Path $PSScriptRoot "build-manual"
$buildTools = Join-Path $SdkRoot "build-tools\35.0.0"
$androidJar = Join-Path $SdkRoot "platforms\android-35\android.jar"
$null = New-Item -ItemType Directory -Force (Join-Path $build "res-compiled"), (Join-Path $build "gen"), (Join-Path $build "classes"), (Join-Path $build "dex"), (Join-Path $PSScriptRoot "releases")

& (Join-Path $buildTools "aapt2.exe") compile --dir (Join-Path $PSScriptRoot "app\src\main\res") -o (Join-Path $build "res-compiled.zip")
& (Join-Path $buildTools "aapt2.exe") link -o (Join-Path $build "unsigned.apk") --manifest (Join-Path $PSScriptRoot "app\src\main\AndroidManifest.xml") -A (Join-Path $PSScriptRoot "app\src\main\assets") -I $androidJar --java (Join-Path $build "gen") --min-sdk-version 23 --target-sdk-version 35 --version-code 6 --version-name 1.1.5 (Join-Path $build "res-compiled.zip")
& (Join-Path $JavaHome "bin\javac.exe") -source 8 -target 8 -encoding UTF-8 -classpath $androidJar -d (Join-Path $build "classes") (Join-Path $build "gen\cn\boya\monitor\R.java") (Get-ChildItem (Join-Path $PSScriptRoot "app\src\main\java") -Filter *.java -Recurse | ForEach-Object { $_.FullName })
& (Join-Path $buildTools "d8.bat") --lib $androidJar --min-api 23 --output (Join-Path $build "dex") (Get-ChildItem (Join-Path $build "classes") -Filter *.class -Recurse | ForEach-Object { $_.FullName })
& (Join-Path $JavaHome "bin\jar.exe") uf (Join-Path $build "unsigned.apk") -C (Join-Path $build "dex") classes.dex

$keystore = Join-Path $build "debug.keystore"
if (-not (Test-Path $keystore)) {
    & (Join-Path $JavaHome "bin\keytool.exe") -genkeypair -alias androiddebugkey -keystore $keystore -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 -noprompt
}
& (Join-Path $buildTools "zipalign.exe") -f 4 (Join-Path $build "unsigned.apk") (Join-Path $build "aligned.apk")
$apk = Join-Path $PSScriptRoot "releases\boya-course-monitor.apk"
& (Join-Path $buildTools "apksigner.bat") sign --ks $keystore --ks-pass pass:android --key-pass pass:android --out $apk (Join-Path $build "aligned.apk")
& (Join-Path $buildTools "apksigner.bat") verify --verbose $apk
Write-Host "APK: $apk"
